#!/usr/bin/env bash
# Exercises stream: true on POST /v1/chat/completions over real HTTP against mock-provider (GW-02 section 8, the
# gateway-smoke skill). Needs gateway-app (profile `local`) and mock-provider already running; it never starts or stops
# either, and it resets the mock. It calls no real provider: the `local` profile points the adapter at the mock.
# Usage, from anywhere: bash tools/smoke/streaming.sh
# Optional environment:
#   GATEWAY, MOCK   base URLs, default http://127.0.0.1:8080 and http://127.0.0.1:8090
#   GATEWAY_LOG     the gateway's log file; turns on the log halves of scenarios 9 and 10
#   HANG=1          runs stall and hang; start the gateway with LLMGW_PROVIDERS_OPENAI_STREAM_IDLE_TIMEOUT=2s,
#                   LLMGW_PROVIDERS_OPENAI_STREAM_FIRST_BYTE_TIMEOUT=2s and LLMGW_SERVER_CLIENT_WRITE_TIMEOUT=1s first
# Prints "PASS <check>", "SKIP <check>: <why>", "INFO <check>: <value>" or "FAIL <check>: <evidence>" per check, runs
# every check and exits 1 if any failed. Scenario 12 (a client that stops reading) stays manual.
set -u

GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
MOCK="${MOCK:-http://127.0.0.1:8090}"
ROUTE="/v1/chat/completions"
# The extension member on an estimated usage chunk; ChatExtensions.MEMBER.
EXTENSION="llmgw"
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

stream_body() {
    printf '{"model":"mock-chat","messages":[{"role":"user","content":"%s"}],"stream":true%s}' "${1:-Hi}" "${2:-}"
}
# Sets $STATUS and $EXIT and fills $HEADERS and $BODY. Extra curl arguments follow the body.
post() {
    local body=$1
    shift
    STATUS=$(curl -sS -N --max-time 30 -o "$BODY" -D "$HEADERS" -w '%{http_code}' \
        -H 'Content-Type: application/json' "$@" --data "$body" "$GATEWAY$ROUTE")
    EXIT=$?
}
header() { tr -d '\r' <"$HEADERS" | sed -n "s/^$1: *//Ip" | tail -1; }
scenario() {
    curl -fsS --max-time 5 -X PUT -H 'Content-Type: application/json' --data "$1" "$MOCK/__mock/scenarios/mock-chat" \
        >/dev/null || fail "scenario" "the mock refused $1"
}
reset_mock() { curl -fsS --max-time 5 -X POST "$MOCK/__mock/reset" >/dev/null; }
last_seq() { curl -fsS --max-time 5 "$MOCK/__mock/stats" | grep -oE '"lastSeq":[0-9]+' | cut -d: -f2; }
journal() { curl -fsS --max-time 5 "$MOCK/__mock/requests?header=x-client-request-id:$1"; }
outcome() { journal "$1" | grep -oE '"outcome":\{"kind":"[a-z_]+"' | sed 's/.*"kind":"//; s/"$//'; }
await_outcome() {
    local id=$1 expected=$2 tries=40
    while [ "$tries" -gt 0 ]; do
        [ "$(outcome "$id")" = "$expected" ] && return 0
        sleep 0.05
        tries=$((tries - 1))
    done
    return 1
}
median_ms() { sort -n | awk '{ v[NR] = $1 } END { printf "%.2f", v[int((NR + 1) / 2)] * 1000 }'; }

curl -fsS --max-time 5 "$GATEWAY/actuator/health" >/dev/null || {
    echo "FAIL preflight: no gateway at $GATEWAY"
    exit 1
}
reset_mock || {
    echo "FAIL preflight: no mock at $MOCK"
    exit 1
}

# 1. Headers.
scenario '{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}'
post "$(stream_body)"
check "1 status" "$STATUS" 200
check "1 content-type" "$(header content-type)" "text/event-stream;charset=UTF-8"
check "1 cache-control" "$(header cache-control)" "no-cache"
check "1 x-accel-buffering" "$(header x-accel-buffering)" "no"
check "1 x-gateway-deployment" "$(header x-gateway-deployment)" "openai"
check "1 transfer-encoding" "$(header transfer-encoding)" "chunked"
check "1 no content-length" "$(header content-length)" ""
check "1 no usage header" "$(header x-gateway-usage-estimated)" ""
ID=$(header x-request-id)
if echo "$ID" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'; then
    pass "1 UUIDv7 request ID"
else
    fail "1 UUIDv7 request ID" "$ID"
fi

# 2. Frames.
check_has "2 chunks" "$BODY" 'data: {"id":"'
check "2 last frame" "$(grep -v '^$' "$BODY" | tail -1)" "data: [DONE]"

# 3. Correlation.
ENTRY=$(journal "$ID")
check "3 one journal entry" "$(echo "$ENTRY" | grep -oE '"seq":[0-9]+' | wc -l | tr -d ' ')" 1
if echo "$ENTRY" | grep -qE 'include_usage\\":true'; then pass "3 include_usage upstream"; else
    fail "3 include_usage upstream" "$(echo "$ENTRY" | cut -c1-300)"
fi

# 4. Usage.
post "$(stream_body Hi ',"stream_options":{"include_usage":true}')"
check_has "4 asked: usage chunk" "$BODY" '"choices":[],"usage":{"prompt_tokens"'
check_lacks "4 asked: no extension" "$BODY" "$EXTENSION"
post "$(stream_body)"
check_lacks "4 silent: no usage" "$BODY" '"usage"'
post "$(stream_body Hi ',"stream_options":{"include_usage":null}')"
check "4 null counts as absent: status" "$STATUS" 200
check_lacks "4 null counts as absent: no usage" "$BODY" '"usage"'
scenario '{"otherwise":{"usage":{"omit":true}}}'
post "$(stream_body Hi ',"stream_options":{"include_usage":true}')"
check_has "4 omitted: flagged" "$BODY" "\"$EXTENSION\":{\"usage_estimated\":true}"
post "$(stream_body)"
check_lacks "4 omitted, silent: no chunk" "$BODY" "\"$EXTENSION\""

# 5. Spread: at least 600 ms between the first and the last piece.
scenario '{"otherwise":{"latency":{"perTokenMs":200},"content":{"mode":"fixed","text":"one two three four five six"}}}'
curl -sS -N --max-time 10 -H 'Content-Type: application/json' --data "$(stream_body)" "$GATEWAY$ROUTE" |
    while IFS= read -r line; do
        case "$line" in *'"content":"'[!\"]*) date +%s%3N ;; esac
    done >"$TMP/arrivals"
SPREAD=$(($(tail -1 "$TMP/arrivals") - $(head -1 "$TMP/arrivals")))
if [ "$SPREAD" -ge 600 ]; then pass "5 spread ${SPREAD} ms"; else fail "5 spread" "$SPREAD ms"; fi

# 6. Errors before commit.
for row in 400:400 401:502 429:429 500:502 503:503 504:504; do
    upstream=${row%%:*}
    expected=${row##*:}
    scenario "{\"otherwise\":{\"error\":{\"status\":$upstream}}}"
    post "$(stream_body)"
    check "6 upstream $upstream status" "$STATUS" "$expected"
    check "6 upstream $upstream type" "$(header content-type)" "application/json"
    check "6 upstream $upstream no stream header" "$(header x-accel-buffering)" ""
done

# 7. Faults after commit: an error frame, no [DONE], and curl exits 0 on a clean end.
for fault in '{"fault":{"kind":"disconnect","afterPieces":2}}' \
    '{"fault":{"kind":"malformed","afterPieces":2}}' \
    '{"error":{"status":500},"fault":{"kind":"stream_error","afterPieces":2}}'; do
    name=$(echo "$fault" | grep -oE 'disconnect|malformed|stream_error')
    scenario "{\"otherwise\":$fault}"
    post "$(stream_body)"
    check "7 $name status" "$STATUS" 200
    check "7 $name curl exit" "$EXIT" 0
    check_has "7 $name error frame" "$BODY" '"code":"upstream_error"'
    check_lacks "7 $name no DONE" "$BODY" '[DONE]'
done

# 8. Deadlines, with the gateway's timeouts lowered.
if [ "${HANG:-0}" = 1 ]; then
    scenario '{"otherwise":{"fault":{"kind":"stall","afterPieces":2}}}'
    post "$(stream_body)"
    check "8 stall status" "$STATUS" 200
    check_has "8 stall frame" "$BODY" '"code":"upstream_timeout"'
    scenario '{"otherwise":{"fault":{"kind":"hang"}}}'
    post "$(stream_body)"
    check "8 hang status" "$STATUS" 504
    if await_outcome "$(header x-request-id)" client_cancelled; then pass "8 hang cancelled upstream"; else
        fail "8 hang cancelled upstream" "$(outcome "$(header x-request-id)")"
    fi
else
    skip "8 stall and hang" "set HANG=1 and start the gateway with the timeouts above"
fi

# 9. Disconnect.
scenario "{\"otherwise\":{\"latency\":{\"perTokenMs\":100},\"content\":{\"mode\":\"fixed\",\"text\":\"$(printf 'word %.0s' {1..50})\"}}}"
curl -sS -N --max-time 1 -D "$HEADERS" -o "$BODY" -H 'Content-Type: application/json' --data "$(stream_body)" \
    "$GATEWAY$ROUTE"
ID=$(header x-request-id)
if await_outcome "$ID" client_cancelled; then pass "9 client_cancelled"; else fail "9 client_cancelled" "$(outcome "$ID")"; fi
if [ -n "${GATEWAY_LOG:-}" ]; then
    sleep 0.2
    check "9 one Stream cancelled line" "$(grep -F "$ID" "$GATEWAY_LOG" | grep -cF 'Stream cancelled')" 1
else
    skip "9 log line" "set GATEWAY_LOG"
fi

# 10. No leak.
scenario '{"otherwise":{"error":{"status":500,"message":"canary-provider-message"},"content":{"mode":"fixed","text":"canarycompletion one"},"fault":{"kind":"stream_error","afterPieces":1}}}'
post "$(stream_body canary-prompt)"
check_lacks "10 no provider message in the response" "$BODY" 'canary-provider-message'
if [ -n "${GATEWAY_LOG:-}" ]; then
    sleep 0.2
    for canary in canary-provider-message canary-prompt canarycompletion; do
        check_lacks "10 no $canary in the log" "$GATEWAY_LOG" "$canary"
    done
else
    skip "10 log" "set GATEWAY_LOG"
fi

# 11. Rejections: none reaches the mock.
before=$(last_seq)
post "$(stream_body Hi ',"stream_options":5')"
check "11 not an object" "$STATUS" 400
check_has "11 not an object param" "$BODY" '"param":"stream_options"'
post "$(stream_body Hi ',"stream_options":{"include_usage":"x"}')"
check "11 include_usage" "$STATUS" 400
check_has "11 include_usage param" "$BODY" '"param":"stream_options.include_usage"'
post '{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream_options":{}}'
check "11 without stream" "$STATUS" 400
check_has "11 without stream message" "$BODY" "only allowed when 'stream' is true"
check "11 lastSeq unchanged" "$(last_seq)" "$before"

# 13. Time to first chunk, informational: no threshold.
reset_mock
scenario '{"otherwise":{"content":{"mode":"fixed","text":"Hi"}}}'
first_byte() {
    local url=$1 auth=$2
    for _ in $(seq 50); do
        curl -sS -o /dev/null -w '%{time_starttransfer}\n' --max-time 5 -H 'Content-Type: application/json' \
            -H "Authorization: Bearer $auth" --data "$(stream_body)" "$url"
    done
}
VIA=$(first_byte "$GATEWAY$ROUTE" smoke | median_ms)
DIRECT=$(first_byte "$MOCK$ROUTE" smoke | median_ms)
echo "INFO 13 time to first chunk: gateway $VIA ms, direct $DIRECT ms (medians of 50)"

reset_mock
exit "$failed"
