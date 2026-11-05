#!/usr/bin/env bash
# Exercises the Anthropic adapter over real HTTP against mock-provider (GW-03 section 8, the gateway-smoke skill). Needs
# gateway-app (profile `local`), mock-provider and compose Postgres already running; it never starts or stops either, and it resets the
# mock. It calls no real provider: the `local` profile points both adapters at the mock.
# Usage, from anywhere: bash tools/smoke/anthropic.sh
# Optional environment:
#   GATEWAY, MOCK   base URLs, default http://127.0.0.1:8080 and http://127.0.0.1:8090
#   HANG=1          runs scenario 10; start the gateway with LLMGW_PROVIDERS_ANTHROPIC_STREAM_FIRST_BYTE_TIMEOUT=2s,
#                   LLMGW_PROVIDERS_ANTHROPIC_STREAM_IDLE_TIMEOUT=2s and LLMGW_SERVER_CLIENT_WRITE_TIMEOUT=1s first
# Scenario 9 (an OpenAI-only start) needs a start of its own: see the SKIP line. Scenario 10 runs two of the three
# deadlines; total-timeout needs a start of its own. Scenario 11 is chat-completions.sh and streaming.sh. Prints "PASS <check>", "SKIP <check>: <why>" or "FAIL <check>: <evidence>" per check, runs every check
# and exits 1 if any failed.
set -u

GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
MOCK="${MOCK:-http://127.0.0.1:8090}"
ROUTE="/v1/chat/completions"
MODEL="mock-claude"
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

# Sets $STATUS and fills $HEADERS and $BODY.
post() {
    STATUS=$(curl -sS -N --max-time 30 -o "$BODY" -D "$HEADERS" -w '%{http_code}' \
        -H "@$TEAM_KEY_HEADER" -H 'Content-Type: application/json' --data "$1" "$GATEWAY$ROUTE")
}
header() { tr -d '\r' <"$HEADERS" | sed -n "s/^$1: *//Ip" | tail -1; }
scenario() {
    curl -fsS --max-time 5 -X PUT -H 'Content-Type: application/json' --data "$1" "$MOCK/__mock/scenarios/$MODEL" \
        >/dev/null || fail "scenario" "the mock refused $1"
}
reset_mock() { curl -fsS --max-time 5 -X POST "$MOCK/__mock/reset" >/dev/null; }
last_seq() { curl -fsS --max-time 5 "$MOCK/__mock/stats" | grep -oE '"lastSeq":[0-9]+' | cut -d: -f2; }
journal() { curl -fsS --max-time 5 "$MOCK/__mock/requests?header=x-client-request-id:$1"; }
await_outcome() {
    local id=$1 expected=$2 tries=40
    while [ "$tries" -gt 0 ]; do
        journal "$id" | grep -qF "\"kind\":\"$expected\"" && return 0
        sleep 0.05
        tries=$((tries - 1))
    done
    return 1
}
body() { printf '{"model":"%s","messages":[{"role":"user","content":"Hi"}]%s}' "$MODEL" "${1:-}"; }

curl -fsS --max-time 5 "$GATEWAY/actuator/health" >/dev/null || {
    echo "FAIL preflight: no gateway at $GATEWAY"
    exit 1
}
reset_mock || {
    echo "FAIL preflight: no mock at $MOCK"
    exit 1
}
team_key_create

# 1. Text, both forms.
scenario '{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}'
post "$(body)"
check "1 status" "$STATUS" 200
check "1 deployment" "$(header x-gateway-deployment)" "anthropic"
check_has "1 content" "$BODY" '"content":"Hi there"'
check_has "1 object" "$BODY" '"object":"chat.completion"'
ENTRY=$(journal "$(header x-request-id)")
echo "$ENTRY" >"$TMP/entry"
check_has "1 journal route" "$TMP/entry" '"route":"anthropic.messages"'
check_has "1 journal version" "$TMP/entry" '2023-06-01'
check_has "1 journal credential" "$TMP/entry" '"scheme":"x-api-key"'
check_has "1 journal max_tokens" "$TMP/entry" '\"max_tokens\":4096'
post "$(body ',"stream":true')"
check "1 stream status" "$STATUS" 200
check_has "1 role on the first delta" "$BODY" '"role":"assistant"'
check "1 last frame" "$(grep -v '^$' "$BODY" | tail -1)" "data: [DONE]"
scenario '{"otherwise":{"latency":{"perTokenMs":100},"content":{"mode":"fixed","text":"a b c d e f g h i j"}}}'
TIMES=$(curl -sS -N --max-time 30 -o /dev/null -w '%{time_starttransfer} %{time_total}' \
    -H "@$TEAM_KEY_HEADER" -H 'Content-Type: application/json' --data "$(body ',"stream":true')" "$GATEWAY$ROUTE")
if echo "$TIMES" | awk '{ exit !($1 < $2 / 2) }'; then
    pass "1 frames spread"
else
    fail "1 frames spread" "first byte and total, in s: $TIMES"
fi

# 2. A tool call, then the result, on both forms.
TOOLS='"tools":[{"type":"function","function":{"name":"get_weather"}}]'
CALL='{"id":"toolu_1","name":"get_weather","input":{"city":"Paris"},"chunkChars":5}'
SUNNY='{"content":{"mode":"fixed","text":"Sunny.","toolCalls":[]}}'
ASSISTANT='{"role":"assistant","content":"Checking.","tool_calls":[{"id":"toolu_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"Paris\"}"}}]}'
RESULT='{"role":"tool","tool_call_id":"toolu_1","content":"18C"}'
SECOND="{\"model\":\"$MODEL\",$TOOLS,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"},$ASSISTANT,$RESULT]"
for form in complete stream; do
    reset_mock
    scenario "{\"steps\":[{\"content\":{\"mode\":\"fixed\",\"text\":\"Checking.\",\"toolCalls\":[$CALL]}}],\"otherwise\":$SUNNY}"
    if [ "$form" = complete ]; then EXTRA=""; else EXTRA=',"stream":true'; fi
    post "$(body ",$TOOLS$EXTRA")"
    check "2 $form status" "$STATUS" 200
    check_has "2 $form finish" "$BODY" '"finish_reason":"tool_calls"'
    check_has "2 $form tool id" "$BODY" '"id":"toolu_1"'
    post "$SECOND$EXTRA}"
    check "2 $form second status" "$STATUS" 200
    journal "$(header x-request-id)" >"$TMP/entry"
    check_has "2 $form tool_use upstream" "$TMP/entry" \
        '{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Checking.\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\",\"input\":{\"city\":\"Paris\"}}]}'
    check_has "2 $form tool_result first upstream" "$TMP/entry" \
        '{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"18C\"}]}'
done

# 3. Parallel tools with text in between: tool indexes from 0.
reset_mock
scenario "{\"otherwise\":{\"content\":{\"mode\":\"fixed\",\"text\":\"Checking.\",\"toolCalls\":[$CALL,{\"name\":\"get_time\",\"input\":{}}]}}}"
post "$(body ",$TOOLS,\"stream\":true")"
check_has "3 first tool index" "$BODY" '"index":0,"id":"toolu_1"'
check_has "3 second tool index" "$BODY" '"tool_calls":[{"index":1,'
check_has "3 empty input" "$BODY" '"arguments":"{}"'

# 4. Reject rows: 400 and no upstream call.
reset_mock
BEFORE=$(last_seq)
while read -r member code; do
    post "$(body ",$member")"
    check "4 $member status" "$STATUS" 400
    check_has "4 $member code" "$BODY" "\"code\":\"$code\""
done <<'ROWS'
"seed":1 unsupported_parameter
"response_format":{"type":"json_object"} unsupported_value
"n":2 unsupported_value
"logprobs":true unsupported_value
"stop":7 unsupported_value
"tool_choice":{"type":"custom"} unsupported_value
ROWS
post "$(body ",\"parallel_tool_calls\":\"x\",$TOOLS")"
check "4 parallel_tool_calls not a boolean status" "$STATUS" 400
check_has "4 parallel_tool_calls not a boolean code" "$BODY" '"code":"unsupported_value"'
check_has "4 parallel_tool_calls not a boolean param" "$BODY" '"param":"parallel_tool_calls"'
post "$(body ',"response_format":{"type":"json_object"}')"
check_has "4 json_object param" "$BODY" '"param":"response_format.type"'
while read -r format code param; do
    post "$(body ",\"response_format\":$format")"
    check "4 $format status" "$STATUS" 400
    check_has "4 $format code" "$BODY" "\"code\":\"$code\""
    check_has "4 $format param" "$BODY" "\"param\":\"$param\""
done <<'ROWS'
{"type":"json_schema"} invalid_request response_format.json_schema
{"type":"json_schema","json_schema":{"name":"n"}} invalid_request response_format.json_schema.schema
{"type":"xml"} unsupported_value response_format.type
"text" unsupported_value response_format
{"type":"text","extra":1} unsupported_parameter response_format.extra
ROWS
post "$(body ',"output_config":{"format":{"type":"json_schema","schema":{}}}')"
check "4 client output_config status" "$STATUS" 400
check_has "4 client output_config code" "$BODY" '"code":"unsupported_parameter"'
check_same "4 no upstream call" "$(last_seq)" "$BEFORE"

# 4b. response_format that goes through, on both forms: json_schema as output_config, text as nothing.
reset_mock
SCHEMA='{"type":"object","properties":{"city":{"type":"string"}},"additionalProperties":false}'
FORMAT="\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{\"name\":\"r\",\"description\":\"d\",\"strict\":true,\"schema\":$SCHEMA}}"
ESCAPED=$(printf '%s' "$SCHEMA" | sed 's/"/\\"/g')
for extra in "" ',"stream":true'; do
    post "$(body ",$FORMAT$extra")"
    check "4b json_schema$extra status" "$STATUS" 200
    journal "$(header x-request-id)" >"$TMP/entry"
    check_has "4b json_schema$extra output_config" "$TMP/entry" \
        "\\\"output_config\\\":{\\\"format\\\":{\\\"type\\\":\\\"json_schema\\\",\\\"schema\\\":$ESCAPED}}"
    check_lacks "4b json_schema$extra no strict upstream" "$TMP/entry" 'strict'
    check_lacks "4b json_schema$extra no name upstream" "$TMP/entry" '\"name\"'
    check_lacks "4b json_schema$extra no description upstream" "$TMP/entry" '\"description\"'
    post "$(body ",\"response_format\":{\"type\":\"text\"}$extra")"
    check "4b text$extra status" "$STATUS" 200
    journal "$(header x-request-id)" >"$TMP/entry"
    # An unmatched journal read is {"data":[]}: not empty, so check_lacks alone would pass on it.
    check_has "4b text$extra reached the mock" "$TMP/entry" '"route":"anthropic.messages"'
    check_lacks "4b text$extra no output_config" "$TMP/entry" 'output_config'
done

# 4c. A tool-only reply with usage omitted: name and arguments, 27 characters, 7 tokens, on both forms.
reset_mock
scenario "{\"otherwise\":{\"usage\":{\"omit\":true},\"content\":{\"mode\":\"fixed\",\"text\":\"\",\"toolCalls\":[$CALL]}}}"
post "$(body)"
check "4c estimated header" "$(header x-gateway-usage-estimated)" true
check_has "4c completion_tokens" "$BODY" '"completion_tokens":7,'
post "$(body ',"stream":true,"stream_options":{"include_usage":true}')"
check_has "4c stream completion_tokens" "$BODY" '"completion_tokens":7,'
check_has "4c stream flag" "$BODY" '"usage_estimated":true'

# 5. The status table: a stream fails the same way before its first frame.
while read -r injected status code; do
    reset_mock
    scenario "{\"otherwise\":{\"error\":$injected}}"
    post "$(body)"
    check "5 $injected status" "$STATUS" "$status"
    check_has "5 $injected code" "$BODY" "\"code\":\"$code\""
    post "$(body ',"stream":true')"
    check "5 $injected stream status" "$STATUS" "$status"
    check_has "5 $injected stream code" "$BODY" "\"code\":\"$code\""
done <<'ROWS'
{"status":400} 400 upstream_rejected_request
{"status":401} 502 upstream_auth_failed
{"status":402} 503 upstream_quota_exhausted
{"status":403} 502 upstream_auth_failed
{"status":404} 404 model_not_found
{"status":404,"type":"other"} 502 upstream_error
{"status":408} 504 upstream_timeout
{"status":409} 502 upstream_error
{"status":413} 413 upstream_rejected_request
{"status":422} 422 upstream_rejected_request
{"status":429} 429 upstream_rate_limited
{"status":429,"code":"enforced_spend_limit_reached"} 503 upstream_quota_exhausted
{"status":500} 502 upstream_error
{"status":500,"type":"timeout_error"} 504 upstream_timeout
{"status":503} 503 provider_unavailable
{"status":504} 504 upstream_timeout
{"status":529} 503 provider_unavailable
ROWS
reset_mock
scenario '{"otherwise":{"error":{"status":429}}}'
post "$(body)"
check "5 429 status" "$STATUS" 429
check "5 429 no retry-after" "$(header retry-after)" ""
scenario '{"otherwise":{"error":{"status":429,"retryAfterSeconds":7}}}'
post "$(body)"
check "5 429 retry-after" "$(header retry-after)" "7"

# 5. The in-band table: error.type decides. Before the first chunk the row's status, after it a frame.
while read -r injected status code; do
    reset_mock
    scenario "{\"otherwise\":{\"content\":{\"mode\":\"fixed\",\"text\":\"a b c d\"},\"error\":$injected,\"fault\":{\"kind\":\"stream_error\",\"afterPieces\":0}}}"
    post "$(body ',"stream":true')"
    check "5 in-band $injected status" "$STATUS" "$status"
    check_has "5 in-band $injected code" "$BODY" "\"code\":\"$code\""
    reset_mock
    scenario "{\"otherwise\":{\"content\":{\"mode\":\"fixed\",\"text\":\"a b c d\"},\"error\":$injected,\"fault\":{\"kind\":\"stream_error\",\"afterPieces\":2}}}"
    post "$(body ',"stream":true')"
    check "5 in-band $injected after commit status" "$STATUS" 200
    check_has "5 in-band $injected frame" "$BODY" "\"code\":\"$code\""
    check_lacks "5 in-band $injected no DONE" "$BODY" '[DONE]'
done <<'ROWS'
{"status":500,"type":"overloaded_error"} 503 provider_unavailable
{"status":500,"type":"rate_limit_error"} 429 upstream_rate_limited
{"status":500,"type":"rate_limit_error","code":"enforced_spend_limit_reached"} 503 upstream_quota_exhausted
{"status":500,"type":"billing_error"} 503 upstream_quota_exhausted
{"status":500,"type":"authentication_error"} 502 upstream_auth_failed
{"status":500,"type":"permission_error"} 502 upstream_auth_failed
{"status":500,"type":"invalid_request_error"} 400 upstream_rejected_request
{"status":500,"type":"request_too_large"} 413 upstream_rejected_request
{"status":500,"type":"not_found_error"} 404 model_not_found
{"status":500,"type":"timeout_error"} 504 upstream_timeout
{"status":500,"type":"api_error"} 502 upstream_error
{"status":500,"type":"conflict_error"} 502 upstream_error
{"status":500,"type":"another_type"} 502 upstream_error
ROWS

# 6. A thinking prefix, then an error: a status, not a frame.
reset_mock
scenario '{"otherwise":{"content":{"mode":"fixed","text":"Hi","thinking":[{"signature":"sig-1"}]},"error":{"status":529},"fault":{"kind":"stream_error","afterPieces":0}}}'
post "$(body ',"stream":true')"
check "6 status" "$STATUS" 503
check_has "6 code" "$BODY" '"code":"provider_unavailable"'

# 7. Thinking replay, by echoing llmgw.
reset_mock
scenario '{"otherwise":{"content":{"mode":"fixed","text":"Hi","thinking":[{"signature":"sig-1"},{"redacted":"opaque-1"}]}}}'
post "$(body)"
check_has "7 blocks on the reply" "$BODY" '"thinking_blocks":[{"type":"thinking"'
check_has "7 content is the text alone" "$BODY" '"content":"Hi"'
BLOCKS='{"thinking_blocks":[{"type":"thinking","thinking":"","signature":"sig-1"},{"type":"redacted_thinking","data":"opaque-1"}]}'
post "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\",\"llmgw\":$BLOCKS},{\"role\":\"user\",\"content\":\"c\"}]}"
journal "$(header x-request-id)" >"$TMP/entry"
check_has "7 blocks first upstream" "$TMP/entry" \
    '{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"sig-1\"},{\"type\":\"redacted_thinking\",\"data\":\"opaque-1\"},{\"type\":\"text\",\"text\":\"b\"}]}'
check_lacks "7 no llmgw upstream" "$TMP/entry" 'llmgw'
upstream_body() { journal "$(header x-request-id)" | grep -oE '"body":"([^"\\]|\\.)*"'; }
post "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\"},{\"role\":\"user\",\"content\":\"c\"}]}"
upstream_body >"$TMP/plain"
post "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"a\"},{\"role\":\"assistant\",\"content\":\"b\",\"llmgw\":{\"thinking_blocks\":null}},{\"role\":\"user\",\"content\":\"c\"}]}"
check "7 null blocks status" "$STATUS" 200
upstream_body >"$TMP/nulled"
check_has "7 null blocks reached the mock" "$TMP/nulled" '\"role\":\"assistant\"'
check_same "7 null blocks count as absent" "$(cat "$TMP/nulled")" "$(cat "$TMP/plain")"

# 8. Disconnect mid-stream.
reset_mock
WORDS=$(printf 'word %.0s' $(seq 1 200))
scenario "{\"otherwise\":{\"latency\":{\"perTokenMs\":50},\"content\":{\"mode\":\"fixed\",\"text\":\"$WORDS\"}}}"
curl -s -N --max-time 1 -o /dev/null -D "$HEADERS" -H "@$TEAM_KEY_HEADER" -H 'Content-Type: application/json' \
    --data "$(body ',"stream":true')" "$GATEWAY$ROUTE"
if await_outcome "$(header x-request-id)" client_cancelled; then
    pass "8 client_cancelled"
else
    fail "8 client_cancelled" "$(journal "$(header x-request-id)" | cut -c1-300)"
fi

# 9. An OpenAI-only start.
skip "9 OpenAI-only start" "start with LLMGW_PROVIDERS_ANTHROPIC_ENABLED=false, then POST model claude-x: journal route openai.chat"

# 10. First byte and idle; total-timeout needs a start of its own.
if [ "${HANG:-}" = 1 ]; then
    reset_mock
    scenario '{"otherwise":{"fault":{"kind":"hang"}}}'
    post "$(body ',"stream":true')"
    check "10 first-byte status" "$STATUS" 504
    reset_mock
    scenario '{"otherwise":{"fault":{"kind":"stall","afterPieces":1}}}'
    post "$(body ',"stream":true')"
    check_has "10 idle frame" "$BODY" '"code":"upstream_timeout"'
else
    skip "10 deadlines" "HANG=1 and the timeouts in this file's header"
fi

reset_mock
exit "$failed"
