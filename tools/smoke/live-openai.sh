#!/usr/bin/env bash
# Paid check against the real OpenAI API: TLS, real bodies and real error shapes, which the mock cannot prove.
# Costs under one US cent a run (two short completions; the error checks are refused before any tokens). Never in CI;
# run it only with the user's go-ahead (CLAUDE.md, Testing). It starts its own gateway-app and stops it.
# Usage, from anywhere: bash tools/smoke/live-openai.sh
# The key comes from .env at the repo root (gitignored, see .env.example). The file is read as KEY=VALUE, never
# executed, and the key reaches only the gateway's environment: it is never printed or passed as an argument.
# Optional environment:
#   LIVE_KEY_FILE   the file to read instead of .env
#   LIVE_BASE_URL   default https://api.openai.com/v1; a mock URL proves the script itself without spending
#   LIVE_PORT       default 18080
# Prints "PASS <check>", "INFO <check>: <value>" or "FAIL <check>: <evidence>" per check; exits 1 if any failed.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KEY_FILE="${LIVE_KEY_FILE:-$ROOT/.env}"
BASE_URL="${LIVE_BASE_URL:-https://api.openai.com/v1}"
PORT="${LIVE_PORT:-18080}"
GATEWAY="http://127.0.0.1:$PORT"
ROUTE="$GATEWAY/v1/chat/completions"
CANARY="llmgw-live-canary-prompt"
TMP="$(mktemp -d)"
HEADERS="$TMP/headers"
BODY="$TMP/body"
gateway_pid=""

stop_gateway() {
    [ -n "$gateway_pid" ] && kill "$gateway_pid" 2>/dev/null
    gateway_pid=""
}
trap 'stop_gateway; rm -rf "$TMP"' EXIT

failed=0
pass() { echo "PASS $1"; }
info() { echo "INFO $1: $2"; }
fail() {
    echo "FAIL $1: $2"
    failed=1
}

# The last value of a KEY=VALUE line, without quotes or a Windows line ending.
file_value() {
    sed -n "s/^[^A-Za-z]*$1[[:space:]]*=[[:space:]]*//p" "$KEY_FILE" | tail -1 | tr -d "\r\"'" | sed 's/[[:space:]]*$//'
}

[ -f "$KEY_FILE" ] || {
    echo "No key file at $KEY_FILE: copy .env.example to .env and set OPENAI_API_KEY."
    exit 2
}
KEY="$(file_value OPENAI_API_KEY)"
[ -n "$KEY" ] || {
    echo "OPENAI_API_KEY is empty in $KEY_FILE (a UTF-16 file reads as empty: save it as UTF-8)."
    exit 2
}
MODEL="$(file_value LLMGW_LIVE_MODEL)"
MODEL="${MODEL:-gpt-4o-mini}"

java_25() {
    local candidate
    for candidate in "${JAVA_25:-}" "$HOME"/.gradle/jdks/*25*/bin/java{.exe,} "$HOME"/.gradle/jdks/*25*/*/bin/java{.exe,}; do
        [ -f "$candidate" ] && [ -x "$candidate" ] && {
            printf '%s' "$candidate"
            return 0
        }
    done
    return 1
}

JAVA="$(java_25)" || {
    echo "No JDK 25 found: set JAVA_25, or run ./gradlew build once so Gradle provisions it."
    exit 2
}
(cd "$ROOT" && ./gradlew -q :gateway-app:bootJar --console=plain) || {
    echo "bootJar failed"
    exit 2
}

# $1: the key, $2: the log file.
start_gateway() {
    OPENAI_API_KEY="$1" LLMGW_PROVIDERS_OPENAI_BASE_URL="$BASE_URL" SERVER_PORT="$PORT" \
        "$JAVA" -jar "$ROOT/gateway-app/build/libs/gateway-app.jar" > "$2" 2>&1 &
    gateway_pid=$!
    local tries=0
    until curl -fs -o /dev/null --max-time 2 "$GATEWAY/actuator/health"; do
        tries=$((tries + 1))
        [ "$tries" -lt 60 ] || {
            echo "gateway did not start; last lines of its log:"
            tail -5 "$2" | cut -c1-160
            exit 2
        }
        sleep 1
    done
}

# $1: JSON body. Sets status; the headers and body land in $HEADERS and $BODY.
post() {
    status=$(curl -s --max-time 120 -D "$HEADERS" -o "$BODY" -w '%{http_code}' -H 'Content-Type: application/json' \
        -d "$1" "$ROUTE")
}

header() { tr -d '\r' < "$HEADERS" | sed -n "s/^$1: *//Ip" | tail -1; }
member() { grep -o "\"$1\":[^,}]*" "$BODY" | tail -1 | cut -d: -f2- | tr -d '"'; }

ask='"messages":[{"role":"user","content":"'"$CANARY"': reply with the single word OK."}]'
limit='"max_completion_tokens":16'

echo "Base URL $BASE_URL, model $MODEL"
LOG="$TMP/gateway.log"
start_gateway "$KEY" "$LOG"

# 1. A real completion over real TLS.
post "{\"model\":\"$MODEL\",$ask,$limit}"
if [ "$status" = 200 ] && grep -q '"object":"chat.completion"' "$BODY"; then
    pass "non-stream 200"
else
    fail "non-stream 200" "status $status, code $(member code)"
fi
tokens=$(member total_tokens)
if [ -n "$tokens" ] && [ "$tokens" -gt 0 ] 2>/dev/null; then pass "provider usage ($tokens tokens)"; else fail "provider usage" "total_tokens '$tokens'"; fi
[ -n "$(header x-request-id)" ] && pass "x-request-id" || fail "x-request-id" "missing"
[ "$(header x-gateway-deployment)" = openai ] && pass "x-gateway-deployment" || fail "x-gateway-deployment" "'$(header x-gateway-deployment)'"
[ -z "$(header x-gateway-usage-estimated)" ] && pass "usage not estimated" || fail "usage not estimated" "estimated"

# 2. A real stream, with the client asking for usage.
timing=$(curl -s -N --max-time 120 -D "$HEADERS" -o "$BODY" -w '%{http_code} %{time_starttransfer} %{time_total}' \
    -H 'Content-Type: application/json' \
    -d "{\"model\":\"$MODEL\",$ask,$limit,\"stream\":true,\"stream_options\":{\"include_usage\":true}}" "$ROUTE")
set -- $timing
if [ "$1" = 200 ] && grep -q '^data: \[DONE\]' "$BODY"; then pass "stream ends in [DONE]"; else fail "stream ends in [DONE]" "status $1, error $(member code)"; fi
case "$(header content-type)" in text/event-stream*) pass "stream content type" ;; *) fail "stream content type" "$(header content-type)" ;; esac
if grep -q '"usage":{"prompt_tokens"' "$BODY"; then pass "stream usage chunk"; else fail "stream usage chunk" "absent"; fi
if grep -q '"llmgw"' "$BODY"; then fail "stream usage is the provider's" "flagged as estimated"; else pass "stream usage is the provider's"; fi
info "stream first byte, total" "$2 s, $3 s"
info "stream events" "$(grep -c '^data: ' "$BODY")"

# 3. The same stream without usage: asked upstream anyway, not shown.
curl -s -N --max-time 120 -o "$BODY" -H 'Content-Type: application/json' \
    -d "{\"model\":\"$MODEL\",$ask,$limit,\"stream\":true}" "$ROUTE"
if grep -q '"usage":{"prompt_tokens"' "$BODY"; then fail "usage stripped" "a usage chunk reached the client"; else pass "usage stripped"; fi
grep -q '^data: \[DONE\]' "$BODY" && pass "stripped stream ends in [DONE]" || fail "stripped stream ends in [DONE]" "$(member code)"

# 4. Real error bodies: refused by OpenAI before any tokens are spent.
post "{\"model\":\"llmgw-no-such-model\",$ask,$limit}"
if [ "$status" = 404 ]; then pass "unknown model 404 ($(member code))"; else fail "unknown model 404" "status $status, code $(member code)"; fi
post "{\"model\":\"$MODEL\",$ask,\"temperature\":99}"
if [ "$status" = 400 ]; then pass "bad parameter 400 (code $(member code), param $(member param))"; else fail "bad parameter 400" "status $status, code $(member code)"; fi
if grep -q 'Invalid\|invalid value\|temperature.*99' "$BODY"; then fail "provider message not relayed" "provider text in the body"; else pass "provider message not relayed"; fi

# 5. Nothing secret in the log.
if grep -qF -- "$KEY" "$LOG"; then fail "key not in the log" "found"; else pass "key not in the log"; fi
if grep -qF -- "$CANARY" "$LOG"; then fail "prompt not in the log" "found"; else pass "prompt not in the log"; fi
stop_gateway
sleep 2

# 6. A key OpenAI refuses: a real 401 maps to 502, with no retry hint.
BAD_LOG="$TMP/gateway-bad-key.log"
start_gateway "sk-llmgw-not-a-real-key" "$BAD_LOG"
post "{\"model\":\"$MODEL\",$ask,$limit}"
if [ "$status" = 502 ] && [ "$(member code)" = upstream_auth_failed ]; then
    pass "refused key 502 upstream_auth_failed"
else
    fail "refused key 502 upstream_auth_failed" "status $status, code $(member code)"
fi
[ "$(header x-should-retry)" = false ] && pass "refused key x-should-retry" || fail "refused key x-should-retry" "'$(header x-should-retry)'"
stop_gateway

exit "$failed"
