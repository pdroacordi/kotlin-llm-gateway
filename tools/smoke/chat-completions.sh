#!/usr/bin/env bash
# Exercises POST /v1/chat/completions over real HTTP against mock-provider (GW-01 section 8, the gateway-smoke skill).
# Needs gateway-app (profile `local`), mock-provider and compose Postgres already running; it starts neither app, and it
# resets the mock. It calls no real provider: the `local` profile points the adapter at the mock with a fake key.
# Usage, from anywhere: bash tools/smoke/chat-completions.sh
# Optional environment:
#   GATEWAY, MOCK   base URLs, default http://127.0.0.1:8080 and http://127.0.0.1:8090
#   GATEWAY_LOG     the gateway's log file; turns on the log half of scenario 9
#   HANG=1          runs the hang fault; start the gateway with LLMGW_PROVIDERS_OPENAI_REQUEST_TIMEOUT=2s first
# Prints "PASS <check>", "SKIP <check>: <why>" or "FAIL <check>: <evidence>" per check, runs every check and exits 1
# if any failed. Scenario 7 (stopped mock) stops a process, so it stays manual.
set -u

GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
MOCK="${MOCK:-http://127.0.0.1:8090}"
ROUTE="/v1/chat/completions"
LOCAL_KEY="mock-local-key"
TMP="$(mktemp -d)"
HEADERS="$TMP/headers"
BODY="$TMP/body"
trap 'rm -rf "$TMP"' EXIT

# shellcheck source=tools/smoke/team-key.sh
. "$(dirname "$0")/team-key.sh"

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
# Two computed values: both empty proves nothing, as when the mock stopped answering.
check_same() {
    local name=$1 actual=$2 expected=$3
    if [ -z "$actual" ]; then fail "$name" "nothing to compare"; else check "$name" "$actual" "$expected"; fi
}
check_has() {
    local name=$1 file=$2 needle=$3
    if grep -qF -- "$needle" "$file"; then pass "$name"; else fail "$name" "no '$needle' in: $(cut -c1-300 "$file")"; fi
}
check_lacks() {
    local name=$1 file=$2 needle=$3
    if [ ! -s "$file" ]; then
        fail "$name" "nothing to read"
    elif grep -qF -- "$needle" "$file"; then
        fail "$name" "'$needle' found"
    else
        pass "$name"
    fi
}

# Sets $STATUS and fills $HEADERS and $BODY. Extra curl arguments follow the body.
post() {
    local body=$1
    shift
    STATUS=$(curl -sS --max-time 30 -o "$BODY" -D "$HEADERS" -w '%{http_code}' \
        -H "@$TEAM_KEY_HEADER" -H 'Content-Type: application/json' "$@" --data "$body" "$GATEWAY$ROUTE")
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
team_key_create
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
post "$(minimal mock-chat)" -H 'x-client-request-id: smoke-1'
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

# 4b. Tools and response_format pass through, on both forms (GW-06 section 8).
TOOLS='"tools":[{"type":"function","function":{"name":"get_weather","parameters":{"type":"object"}}}],"tool_choice":"auto","parallel_tool_calls":false'
CALL='{"id":"call_1","name":"get_weather","input":{"city":"Paris"},"chunkChars":3}'
SECOND_CALL='{"id":"call_2","name":"get_time","input":{"zone":"CET"},"chunkChars":3}'
SUNNY='{"content":{"mode":"fixed","text":"Sunny.","toolCalls":[]}}'
ASSISTANT='{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]}'
RESULT='{"role":"tool","content":"18C","tool_call_id":"call_1"}'
USER='{"role":"user","content":"Hi"}'
for form in complete stream; do
    if [ "$form" = complete ]; then extra=""; else extra=',"stream":true'; fi
    scenario smoke-tools "{\"steps\":[{\"content\":{\"mode\":\"fixed\",\"text\":\"\",\"toolCalls\":[$CALL]}}],\"otherwise\":$SUNNY}"
    post "{\"model\":\"smoke-tools\",$TOOLS$extra,\"messages\":[$USER]}"
    check "4b $form tools status" "$STATUS" 200
    check_has "4b $form finish" "$BODY" '"finish_reason":"tool_calls"'
    check_has "4b $form call id" "$BODY" '"id":"call_1"'
    journal "$(header x-request-id)" >"$TMP/journal"
    check_has "4b $form tools upstream" "$TMP/journal" '\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"'
    check_has "4b $form tool_choice upstream" "$TMP/journal" '\"tool_choice\":\"auto\"'
    check_has "4b $form parallel_tool_calls upstream" "$TMP/journal" '\"parallel_tool_calls\":false'
    post "{\"model\":\"smoke-tools\",$TOOLS$extra,\"messages\":[$USER,$ASSISTANT,$RESULT]}"
    check "4b $form second turn status" "$STATUS" 200
    journal "$(header x-request-id)" >"$TMP/journal"
    check_has "4b $form echoed call upstream" "$TMP/journal" '\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\"'
    check_has "4b $form tool message upstream" "$TMP/journal" '\"tool_call_id\":\"call_1\"'
done
post "{\"model\":\"smoke-tools\",\"messages\":[$USER,$ASSISTANT,{\"role\":\"tool\",\"content\":\"x\",\"tool_call_id\":\"other\"}]}"
check "4b unknown tool_call_id status" "$STATUS" 400
check_has "4b unknown tool_call_id param" "$BODY" '"param":"messages[2]"'

# 4c. Parallel calls, arguments split across chunks: each call in more than one delta, and whole when joined by index.
scenario smoke-parallel "{\"otherwise\":{\"content\":{\"mode\":\"fixed\",\"text\":\"\",\"toolCalls\":[$CALL,$SECOND_CALL]}}}"
post '{"model":"smoke-parallel","stream":true,"messages":[{"role":"user","content":"Hi"}]}'
check_has "4c first call" "$BODY" '"tool_calls":[{"index":0,'
check_has "4c second call" "$BODY" '"tool_calls":[{"index":1,'
# The non-empty argument fragments of the call at index $1, one per line, still JSON-escaped.
fragments() { sed -n "s/.*\"tool_calls\":\[{\"index\":$1,.*\"arguments\":\"\(..*\)\"}}\]}.*/\1/p" "$BODY"; }
for row in '0 {\"city\":\"Paris\"}' '1 {\"zone\":\"CET\"}'; do
    index=${row%% *}
    if [ "$(fragments "$index" | wc -l)" -gt 1 ]; then
        pass "4c call $index arguments split"
    else
        fail "4c call $index arguments split" "$(cut -c1-300 "$BODY")"
    fi
    check "4c call $index arguments joined" "$(fragments "$index" | tr -d '\r\n')" "${row#* }"
done

# 4d. response_format forms reach the provider as sent.
while read -r format; do
    escaped=$(printf '%s' "$format" | sed 's/"/\\"/g')
    for extra in "" ',"stream":true'; do
        post "{\"model\":\"mock-chat\",\"response_format\":$format$extra,\"messages\":[$USER]}"
        check "4d $format$extra status" "$STATUS" 200
        journal "$(header x-request-id)" >"$TMP/journal"
        check_has "4d $format$extra upstream" "$TMP/journal" "\\\"response_format\\\":$escaped"
    done
done <<'ROWS'
{"type":"text"}
{"type":"json_object"}
{"type":"json_schema","json_schema":{"name":"r","strict":true,"schema":{"type":"object","additionalProperties":false}}}
ROWS
scenario smoke-format '{"otherwise":{"error":{"status":400,"code":"invalid_json_schema","param":"response_format.json_schema.schema"}}}'
post '{"model":"smoke-format","response_format":{"type":"json_schema"},"messages":[{"role":"user","content":"Hi"}]}'
check "4d provider 400 status" "$STATUS" 400
check_has "4d provider 400 message" "$BODY" 'The provider rejected the request; see the code and param members.'
check_has "4d provider 400 code" "$BODY" '"code":"invalid_json_schema"'
check_has "4d provider 400 param" "$BODY" '"param":"response_format.json_schema.schema"'

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
check "8 provider usage status" "$STATUS" 200
check "8 no header with provider usage" "$(header x-gateway-usage-estimated)" ""
# get_weather and {"city":"Paris"}: 27 characters, 7 tokens, on both forms.
scenario smoke-omit-tool "{\"otherwise\":{\"usage\":{\"omit\":true},\"content\":{\"mode\":\"fixed\",\"text\":\"\",\"toolCalls\":[$CALL]}}}"
post "$(minimal smoke-omit-tool)"
check "8 tool-only estimated header" "$(header x-gateway-usage-estimated)" true
check_has "8 tool-only completion_tokens" "$BODY" '"completion_tokens":7,'
post '{"model":"smoke-omit-tool","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"Hi"}]}'
check_has "8 tool-only stream completion_tokens" "$BODY" '"completion_tokens":7,'
check_has "8 tool-only stream flag" "$BODY" '"usage_estimated":true'

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
STATUS=$(curl -sS --max-time 5 -o "$BODY" -w '%{http_code}' -H "@$TEAM_KEY_HEADER" -H 'Content-Type: text/plain' \
    --data 'Hi' "$GATEWAY$ROUTE")
check "10 text/plain status" "$STATUS" 415
check_same "10 no upstream call" "$(last_seq)" "$before"

# 11. Overhead, informational: the median of 100 calls through the gateway minus direct to the mock.
direct=$(for _ in $(seq 100); do
    curl -sS -o /dev/null -w '%{time_total}\n' -H "Authorization: Bearer $LOCAL_KEY" \
        -H 'Content-Type: application/json' --data "$(minimal mock-chat)" "$MOCK$ROUTE"
done | median_ms)
through=$(for _ in $(seq 100); do
    curl -sS -o /dev/null -w '%{time_total}\n' -H "@$TEAM_KEY_HEADER" -H 'Content-Type: application/json' \
        --data "$(minimal mock-chat)" "$GATEWAY$ROUTE"
done | median_ms)
echo "INFO 11 median ms: direct $direct, through the gateway $through"

exit "$failed"
