#!/usr/bin/env bash
# Exercises POST /v1/chat/completions over real HTTP against mock-provider (GW-01 section 8, the gateway-smoke skill).
# Needs gateway-app (profile `local`) and mock-provider already running; it never starts or stops either, and it
# resets the mock. It calls no real provider: the `local` profile points the adapter at the mock with a fake key.
# Usage, from anywhere: bash tools/smoke/chat-completions.sh
# Optional environment:
#   GATEWAY, MOCK   base URLs, default http://127.0.0.1:8080 and http://127.0.0.1:8090
#   GATEWAY_LOG     the gateway's log file; turns on the log half of scenario 9
#   HANG=1          runs the hang fault; start the gateway with LLMGW_PROVIDERS_OPENAI_REQUEST_TIMEOUT=2s first
# Prints "PASS <check>", "SKIP <check>: <why>" or "FAIL <check>: <evidence>" per check, runs every check and exits 1
# if any failed. Scenario 7 (stopped mock) and 12 (non-loopback start) stop or start a process, so they stay manual.
set -u

GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
MOCK="${MOCK:-http://127.0.0.1:8090}"
ROUTE="/v1/chat/completions"
LOCAL_KEY="mock-local-key"
TMP="$(mktemp -d)"
HEADERS="$TMP/headers"
BODY="$TMP/body"
trap 'rm -rf "$TMP"' EXIT

failed=0
pass() { echo "PASS $1"; }
skip() { echo "SKIP $1: $2"; }
fail() {
    echo "FAIL $1: $2"
    failed=1
}
check() {
    local name=$1 actual=$2 expected=$3
    if [ "$actual" = "$expected" ]; then pass "$name"; else fail "$name" "expected '$expected', got '$actual'"; fi
}
check_has() {
    local name=$1 file=$2 needle=$3
    if grep -qF -- "$needle" "$file"; then pass "$name"; else fail "$name" "no '$needle' in: $(cut -c1-300 "$file")"; fi
}
check_lacks() {
    local name=$1 file=$2 needle=$3
    if grep -qF -- "$needle" "$file"; then fail "$name" "'$needle' found"; else pass "$name"; fi
}

# Sets $STATUS and fills $HEADERS and $BODY. Extra curl arguments follow the body.
post() {
    local body=$1
    shift
    STATUS=$(curl -sS --max-time 30 -o "$BODY" -D "$HEADERS" -w '%{http_code}' \
        -H 'Content-Type: application/json' "$@" --data "$body" "$GATEWAY$ROUTE")
}
header() { tr -d '\r' <"$HEADERS" | sed -n "s/^$1: *//Ip" | tail -1; }
minimal() { printf '{"model":"%s","messages":[{"role":"user","content":"%s"}]}' "$1" "${2:-Hi}"; }
scenario() {
    curl -fsS --max-time 5 -X PUT -H 'Content-Type: application/json' --data "$2" "$MOCK/__mock/scenarios/$1" >/dev/null ||
        fail "scenario $1" "the mock refused it"
}
last_seq() { curl -fsS --max-time 5 "$MOCK/__mock/stats" | grep -oE '"lastSeq":[0-9]+' | cut -d: -f2; }
journal() { curl -fsS --max-time 5 "$MOCK/__mock/requests?header=x-client-request-id:$1"; }
median_ms() {
    sort -n | awk '{ v[NR] = $1 } END { printf "%.2f", v[int((NR + 1) / 2)] * 1000 }'
}

curl -fsS --max-time 5 -X POST "$MOCK/__mock/reset" >/dev/null || {
    echo "FAIL mock: not reachable at $MOCK"
    exit 1
}

# 1. Health and bind.
health=$(curl -sS --max-time 5 "$GATEWAY/actuator/health")
case "$health" in *'"status":"UP"'*) pass "1 health" ;; *) fail "1 health" "$health" ;; esac
port="${GATEWAY##*:}"
listeners=$( (ss -ltn 2>/dev/null || netstat -an) | grep -E "[:.]$port[[:space:]].*LISTEN|LISTEN.*[:.]$port[[:space:]]")
if [ -z "$listeners" ]; then
    skip "1 bind" "no listener list on this host"
elif printf '%s\n' "$listeners" | grep -qvE "127\.0\.0\.1[:.]$port"; then
    fail "1 bind" "$listeners"
else
    pass "1 bind"
fi

# 2. Completion.
post "$(minimal mock-chat)" -H 'x-client-request-id: smoke-1' -H 'Authorization: Bearer client-key'
check "2 status" "$STATUS" 200
check "2 deployment" "$(header x-gateway-deployment)" openai
check "2 client id echoed" "$(header x-client-request-id)" smoke-1
request_id=$(header x-request-id)
if printf '%s' "$request_id" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'; then
    pass "2 x-request-id is a UUIDv7"
else
    fail "2 x-request-id is a UUIDv7" "$request_id"
fi
for member in '"object":"chat.completion"' '"choices":[' '"usage":{"prompt_tokens":' '"system_fingerprint":"fp_mock"'; do
    check_has "2 body $member" "$BODY" "$member"
done

# 3. Correlation: one entry, under the gateway's ID, with the configured key's fingerprint.
journal "$request_id" >"$TMP/journal"
check "3 one journal entry" "$(grep -o '"seq":' "$TMP/journal" | wc -l | tr -d ' ')" 1
fingerprint=$(printf '%s' "$LOCAL_KEY" | sha256sum | cut -c1-8)
check_has "3 fingerprint of the configured key" "$TMP/journal" "\"fingerprint\":\"$fingerprint\""

# 4. Pass-through.
post '{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"x_future":{"a":[1,null]},"seed":9007199254740993,"temperature":0.7}'
journal "$(header x-request-id)" >"$TMP/journal"
check_has "4 unknown member" "$TMP/journal" '\"x_future\":{\"a\":[1,null]}'
check_has "4 big integer" "$TMP/journal" '\"seed\":9007199254740993'
check_has "4 float" "$TMP/journal" '\"temperature\":0.7'

# 5. Mapped errors: name, injected error, status, code.
while read -r name error status code; do
    scenario "smoke-$name" "{\"otherwise\":{\"error\":$error}}"
    post "$(minimal "smoke-$name")"
    check "5 $name status" "$STATUS" "$status"
    check_has "5 $name code" "$BODY" "\"code\":\"$code\""
    [ -n "$(header x-request-id)" ] && pass "5 $name x-request-id" || fail "5 $name x-request-id" "absent"
done <<'ROWS'
400 {"status":400} 400 upstream_rejected_request
401 {"status":401} 502 upstream_auth_failed
404 {"status":404} 404 model_not_found
429 {"status":429,"retryAfterSeconds":7} 429 upstream_rate_limited
quota {"status":429,"code":"insufficient_quota"} 503 upstream_quota_exhausted
500 {"status":500} 502 upstream_error
503 {"status":503} 503 provider_unavailable
504 {"status":504} 504 upstream_timeout
ROWS
post "$(minimal smoke-429)"
check "5 retry-after" "$(header retry-after)" 7
check "5 retry-after-ms" "$(header retry-after-ms)" 7000

# 6. Faults.
for fault in malformed disconnect; do
    scenario "smoke-$fault" "{\"otherwise\":{\"fault\":{\"kind\":\"$fault\"}}}"
    post "$(minimal "smoke-$fault")"
    check "6 $fault status" "$STATUS" 502
    check_has "6 $fault code" "$BODY" '"code":"upstream_error"'
done
if [ "${HANG:-0}" = 1 ]; then
    scenario smoke-hang '{"otherwise":{"fault":{"kind":"hang"}}}'
    post "$(minimal smoke-hang)"
    check "6 hang status" "$STATUS" 504
    check_has "6 hang code" "$BODY" '"code":"upstream_timeout"'
else
    skip "6 hang" "set HANG=1 and start the gateway with LLMGW_PROVIDERS_OPENAI_REQUEST_TIMEOUT=2s"
fi

skip "7 stopped mock" "manual: stop the mock, expect 503 provider_unavailable, restart it, expect 200"

# 8. Estimate.
scenario smoke-omit '{"otherwise":{"usage":{"omit":true}}}'
post "$(minimal smoke-omit)"
check "8 status" "$STATUS" 200
check "8 estimated header" "$(header x-gateway-usage-estimated)" true
check_has "8 usage present" "$BODY" '"usage":{"prompt_tokens":'
post "$(minimal mock-chat)"
check "8 no header with provider usage" "$(header x-gateway-usage-estimated)" ""

# 9. No leak.
scenario smoke-leak '{"otherwise":{"error":{"status":429,"message":"canary-provider-message org-canary"}}}'
post "$(minimal smoke-leak canary-prompt)"
check_lacks "9 provider message in the response" "$BODY" canary-provider-message
check_lacks "9 organization in the response" "$BODY" org-canary
check_lacks "9 prompt in the response" "$BODY" canary-prompt
if [ -n "${GATEWAY_LOG:-}" ]; then
    for canary in canary-provider-message org-canary canary-prompt "$LOCAL_KEY"; do
        check_lacks "9 $canary in the log" "$GATEWAY_LOG" "$canary"
    done
else
    skip "9 log" "set GATEWAY_LOG to the gateway's log file"
fi

# 10. Rejections: none reaches the mock.
before=$(last_seq)
post "$(minimal mock-chat)" -H 'Host: evil.example'
check "10 foreign Host status" "$STATUS" 403
STATUS=$(curl -sS --max-time 5 -o "$BODY" -w '%{http_code}' -H 'Content-Type: text/plain' --data 'Hi' "$GATEWAY$ROUTE")
check "10 text/plain status" "$STATUS" 415
check "10 no upstream call" "$(last_seq)" "$before"

# 11. Overhead, informational: the median of 100 calls through the gateway minus direct to the mock.
direct=$(for _ in $(seq 100); do
    curl -sS -o /dev/null -w '%{time_total}\n' -H "Authorization: Bearer $LOCAL_KEY" \
        -H 'Content-Type: application/json' --data "$(minimal mock-chat)" "$MOCK$ROUTE"
done | median_ms)
through=$(for _ in $(seq 100); do
    curl -sS -o /dev/null -w '%{time_total}\n' -H 'Content-Type: application/json' \
        --data "$(minimal mock-chat)" "$GATEWAY$ROUTE"
done | median_ms)
echo "INFO 11 median ms: direct $direct, through the gateway $through"

skip "12 non-loopback start" "manual: LLMGW_BIND_ADDRESS=0.0.0.0 at startup exits non-zero naming the property"

exit "$failed"
