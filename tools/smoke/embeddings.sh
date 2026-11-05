#!/usr/bin/env bash
# Exercises POST /v1/embeddings over real HTTP against mock-provider (GW-05 section 8, the gateway-smoke skill).
# Needs gateway-app (profile `local`), mock-provider and compose Postgres already running; it starts neither app, and it
# resets the mock. It calls no real provider: the `local` profile points the adapter at the mock with a fake key.
# Usage, from anywhere: bash tools/smoke/embeddings.sh
# Optional environment:
#   GATEWAY, MOCK   base URLs, default http://127.0.0.1:8080 and http://127.0.0.1:8090
#   GATEWAY_LOG     the gateway's log file; turns on the canary check of scenario 8 (start the gateway with
#                   LOGGING_LEVEL_WEB=TRACE)
#   HANG=1          runs the hang fault; start the gateway with
#                   LLMGW_PROVIDERS_OPENAI_EMBEDDINGS_REQUEST_TIMEOUT=2s first
# Prints "PASS <check>", "SKIP <check>: <why>" or "FAIL <check>: <evidence>" per check, runs every check and exits 1
# if any failed. The OpenAI-disabled start, the closed port, the in-flight budget, a reply over max-response-size and
# the 8-call heap run need a start of their own, so they stay manual.
set -u

GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
MOCK="${MOCK:-http://127.0.0.1:8090}"
ROUTE="/v1/embeddings"
MODEL="mock-embed"
LOCAL_KEY="mock-local-key"
TMP="$(mktemp -d)"
HEADERS="$TMP/headers"
BODY="$TMP/body"
trap 'rm -rf "$TMP"' EXIT

# Windows has a `python3` stub that exits 49, so being on the PATH proves nothing. No interpreter stops the run: every
# byte comparison below needs one.
PYTHON=
for candidate in python3 python; do
    if "$candidate" -c 'pass' >/dev/null 2>&1; then
        PYTHON=$candidate
        break
    fi
done
if [ -z "$PYTHON" ]; then
    echo "FAIL python: neither python3 nor python runs"
    exit 1
fi

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

# Sets $STATUS and fills $HEADERS and $BODY. The request body is read from the file $1, as a batch is over argv's limit.
post_file() {
    local file=$1
    shift
    STATUS=$(curl -sS --max-time 120 -o "$BODY" -D "$HEADERS" -w '%{http_code}' \
        -H "@$TEAM_KEY_HEADER" -H 'Content-Type: application/json' "$@" --data-binary "@$file" "$GATEWAY$ROUTE")
}
post() {
    local body=$1
    shift
    printf '%s' "$body" >"$TMP/request"
    post_file "$TMP/request" "$@"
}
header() { tr -d '\r' <"$HEADERS" | sed -n "s/^$1: *//Ip" | tail -1; }
scenario() {
    curl -fsS --max-time 5 -X PUT -H 'Content-Type: application/json' --data "$1" "$MOCK/__mock/scenarios/$MODEL" \
        >/dev/null || fail "scenario" "the mock refused $1"
}
reset_mock() { curl -fsS --max-time 5 -X POST "$MOCK/__mock/reset" >/dev/null; }
last_seq() { curl -fsS --max-time 5 "$MOCK/__mock/stats" | grep -oE '"lastSeq":[0-9]+' | cut -d: -f2; }
journal() { curl -fsS --max-time 5 "$MOCK/__mock/requests?header=x-client-request-id:$1"; }
journal_since() { curl -fsS --max-time 5 "$MOCK/__mock/requests?since=$1"; }
entries() { grep -o '"seq":' "$1" | wc -l | tr -d ' '; }

# The same request straight at the mock, into the file $2.
direct() {
    printf '%s' "$1" >"$TMP/request"
    curl -fsS --max-time 120 -o "$2" -H "Authorization: Bearer $LOCAL_KEY" -H 'Content-Type: application/json' \
        --data-binary "@$TMP/request" "$MOCK$ROUTE"
}

# Evaluates the Python expression $1 over the JSON body in the file $2, bound to `b`. `tr`: print writes CRLF on Windows.
json_eval() {
    "$PYTHON" -c 'import json,sys
b=json.load(open(sys.argv[2],encoding="utf-8"))
print(eval(sys.argv[1]))' "$1" "$2" | tr -d '\r'
}

# Prints the count of `embedding` values in the file $1 and a SHA-256 of their raw bytes, read from the body and not
# through a JSON parser, so two literals of one number differ.
raw_vectors() {
    "$PYTHON" -c 'import hashlib,re,sys
found=re.findall(rb"\"embedding\":\s*(\[[^\]]*\]|\"[^\"]*\")",open(sys.argv[1],"rb").read())
print(len(found),hashlib.sha256(b"\n".join(found)).hexdigest())' "$1" | tr -d '\r'
}

# As check, but no vectors on both sides is a failure, not an equality.
check_vectors() {
    local name=$1 actual=$2 expected=$3
    case "$actual" in
    '' | '0 '*) fail "$name" "no vectors: '$actual'" ;;
    *) check "$name" "$actual" "$expected" ;;
    esac
}

team_key_create
reset_mock

# 1. Four forms, both formats; base64 decoded as little-endian float32 equals the float reply.
while read -r form indices tokens; do
    post "{\"model\":\"$MODEL\",\"input\":$form}"
    check "form $form status" "$STATUS" 200
    check "form $form deployment" "$(header x-gateway-deployment)" openai
    request_id=$(header x-request-id)
    if printf '%s' "$request_id" | grep -qE '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'; then
        pass "form $form x-request-id is a UUIDv7"
    else
        fail "form $form x-request-id is a UUIDv7" "$request_id"
    fi
    check_has "form $form object" "$BODY" '"object":"list"'
    check "form $form one item per input" "$(json_eval '",".join(str(d["index"]) for d in b["data"])' "$BODY")" "$indices"
    check "form $form item object" "$(json_eval '{d["object"] for d in b["data"]}' "$BODY")" "{'embedding'}"
    check "form $form model" "$(json_eval 'b["model"]' "$BODY")" "$MODEL"
    check "form $form usage" "$(json_eval 'b["usage"]' "$BODY")" "{'prompt_tokens': $tokens, 'total_tokens': $tokens}"
    check_lacks "form $form no completion_tokens" "$BODY" completion_tokens
    check_lacks "form $form no llmgw" "$BODY" llmgw
    check "form $form no estimated header" "$(header x-gateway-usage-estimated)" ''
done <<'ROWS'
"a" 0 1
["a","b"] 0,1 2
[1,2] 0 2
[[1],[2,3]] 0,1 3
ROWS
TEXT='alpha beta gamma delta epsilon zeta'
FLOATS="{\"model\":\"$MODEL\",\"input\":\"$TEXT\",\"dimensions\":8,\"encoding_format\":\"float\"}"
BASE64="{\"model\":\"$MODEL\",\"input\":\"$TEXT\",\"dimensions\":8,\"encoding_format\":\"base64\"}"
DEFAULT="{\"model\":\"$MODEL\",\"input\":\"$TEXT\",\"dimensions\":8}"
post "$FLOATS"
cp "$BODY" "$TMP/float"
check "dimensions float length" "$(json_eval 'len(b["data"][0]["embedding"])' "$TMP/float")" 8
direct "$FLOATS" "$TMP/direct-float"
check_vectors "float byte-identical to the mock" "$(raw_vectors "$TMP/float")" "$(raw_vectors "$TMP/direct-float")"
post "$BASE64"
cp "$BODY" "$TMP/base64"
check "base64 is a string" "$(json_eval 'type(b["data"][0]["embedding"]).__name__' "$TMP/base64")" str
direct "$BASE64" "$TMP/direct"
check_vectors "base64 byte-identical to the mock" "$(raw_vectors "$TMP/base64")" "$(raw_vectors "$TMP/direct")"
check "base64 decodes to the float reply" "$("$PYTHON" -c 'import base64,json,struct,sys
a=json.load(open(sys.argv[1]))["data"][0]["embedding"]
b=base64.b64decode(json.load(open(sys.argv[2]))["data"][0]["embedding"])
print(len(a)>0 and list(struct.unpack("<%df" % (len(b)//4),b))==[struct.unpack("<f",struct.pack("<f",x))[0] for x in a])' \
    "$TMP/float" "$TMP/base64" | tr -d '\r')" True

# Absent stays absent, what a client sets arrives verbatim, and llmgw stops at the gateway.
post "$DEFAULT"
check "no format status" "$STATUS" 200
check "no format is a float reply" "$(json_eval 'type(b["data"][0]["embedding"]).__name__' "$BODY")" list
journal "$(header x-request-id)" >"$TMP/journal"
check "no format one journal entry" "$(entries "$TMP/journal")" 1
check_lacks "no format stays absent upstream" "$TMP/journal" encoding_format
post "{\"model\":\"$MODEL\",\"input\":\"a\",\"dimensions\":8,\"encoding_format\":\"base64\",\"user\":\"u-1\",\"x_future\":{\"a\":[1,null]},\"llmgw\":{\"x\":1}}"
check "forwarded members status" "$STATUS" 200
journal "$(header x-request-id)" >"$TMP/journal"
for member in '\"dimensions\":8' '\"encoding_format\":\"base64\"' '\"user\":\"u-1\"' '\"x_future\":{\"a\":[1,null]}'; do
    check_has "forwarded $member" "$TMP/journal" "$member"
done
check_lacks "llmgw stays at the gateway" "$TMP/journal" 'llmgw'
post "{\"model\":\"$MODEL\",\"input\":\"a\"}" -H 'Accept: text/plain'
check "Accept is ignored" "$STATUS" 200

# 2. Section 4: gateway rows make no upstream call, provider rows are relayed.
while read -r param body; do
    before=$(last_seq)
    post "$body"
    check "gateway row $body status" "$STATUS" 400
    check_has "gateway row $body code" "$BODY" '"code":"invalid_request"'
    check_has "gateway row $body param" "$BODY" "\"param\":\"$param\""
    check_same "gateway row $body no upstream call" "$(last_seq)" "$before"
done <<ROWS
model {"input":"a"}
model {"model":"","input":"a"}
model {"model":"  ","input":"a"}
model {"model":5,"input":"a"}
input {"model":"$MODEL"}
input {"model":"$MODEL","input":null}
input {"model":"$MODEL","input":5}
input {"model":"$MODEL","input":true}
input {"model":"$MODEL","input":{}}
input {"model":"$MODEL","input":["a",1]}
input {"model":"$MODEL","input":[[1],"a"]}
input {"model":"$MODEL","input":[1.5]}
input {"model":"$MODEL","input":[9223372036854775808]}
ROWS
for members in '"input":""' '"input":[]' '"input":[""]' '"input":[[]]' '"input":"a","dimensions":0' \
    '"input":"a","dimensions":"x"' '"input":"a","encoding_format":"x"'; do
    before=$(last_seq)
    post "{\"model\":\"$MODEL\",$members}"
    check "provider row $members status" "$STATUS" 400
    check "provider row $members one upstream call" "$(last_seq)" "$((before + 1))"
    check_has "provider row $members fixed message" "$BODY" 'The provider rejected the request'
done
"$PYTHON" -c 'import sys
print("{\"model\":\"%s\",\"input\":[%s]}" % (sys.argv[1], ",".join(["\"a\""] * 2049)))' "$MODEL" >"$TMP/over"
before=$(last_seq)
post_file "$TMP/over"
check "provider row 2049 items status" "$STATUS" 400
check_has "provider row 2049 items param" "$BODY" '"param":"input"'
check "provider row 2049 items one upstream call" "$(last_seq)" "$((before + 1))"

# The route's own edges: none reaches the provider.
before=$(last_seq)
STATUS=$(curl -sS --max-time 10 -o "$BODY" -D "$HEADERS" -w '%{http_code}' -H "@$TEAM_KEY_HEADER" \
    -H 'Content-Type: text/plain' --data 'x' "$GATEWAY$ROUTE")
check "415 status" "$STATUS" 415
STATUS=$(curl -sS --max-time 10 -o "$BODY" -D "$HEADERS" -w '%{http_code}' -H "@$TEAM_KEY_HEADER" "$GATEWAY$ROUTE")
check "405 status" "$STATUS" 405
check "405 allow" "$(header allow)" POST
head -c 10485761 /dev/zero | tr '\0' ' ' >"$TMP/large"
post_file "$TMP/large"
check "413 status" "$STATUS" 413
check_same "edges no upstream call" "$(last_seq)" "$before"

# 3. A model mapped to Anthropic: a 400 with no upstream call.
before=$(last_seq)
post '{"model":"mock-claude","input":"a"}'
check "anthropic model status" "$STATUS" 400
check_has "anthropic model code" "$BODY" '"code":"unsupported_value"'
check_has "anthropic model param" "$BODY" '"param":"model"'
check_has "anthropic model type" "$BODY" '"type":"invalid_request_error"'
check_same "anthropic model no upstream call" "$(last_seq)" "$before"

# 4. Omitted usage. "12345" is ceil(5 / 4) = 2 tokens by the gateway's estimate.
scenario '{"otherwise":{"usage":{"omit":true}}}'
post "{\"model\":\"$MODEL\",\"input\":\"12345\"}"
check "omitted usage status" "$STATUS" 200
check "omitted usage header" "$(header x-gateway-usage-estimated)" true
check_has "omitted usage body" "$BODY" '"llmgw":{"usage_estimated":true}'
check "omitted usage counts" "$(json_eval 'b["usage"]' "$BODY")" "{'prompt_tokens': 2, 'total_tokens': 2}"
reset_mock

# 5. 2048 by 3072 in both formats, with time reported; the reply must equal the mock's, not only be a 200.
batch() {
    printf '{"model":"%s","dimensions":3072,"encoding_format":"%s","input":[' "$MODEL" "$1"
    for ((i = 1; i < 2048; i++)); do printf '[%d],' "$i"; done
    printf '[2048]]}'
}
for format in base64 float; do
    batch "$format" >"$TMP/batch"
    started=$(date +%s%N)
    post_file "$TMP/batch"
    finished=$(date +%s%N)
    check "full batch $format status" "$STATUS" 200
    check "full batch $format items" "$(json_eval 'len(b["data"])' "$BODY")" 2048
    curl -fsS --max-time 120 -o "$TMP/direct" -H "Authorization: Bearer $LOCAL_KEY" \
        -H 'Content-Type: application/json' --data-binary "@$TMP/batch" "$MOCK$ROUTE"
    check_vectors "full batch $format byte-identical" "$(raw_vectors "$BODY")" "$(raw_vectors "$TMP/direct")"
    echo "INFO full batch $format: $(wc -c <"$BODY") bytes in $(((finished - started) / 1000000)) ms"
done

# 7. Faults. A closed port stops the mock, so it stays manual.
while read -r name error status code; do
    scenario "{\"otherwise\":{\"error\":$error}}"
    before=$(last_seq)
    post "{\"model\":\"$MODEL\",\"input\":\"a\"}"
    check "status row $name status" "$STATUS" "$status"
    check_has "status row $name code" "$BODY" "\"code\":\"$code\""
    check "status row $name one upstream call" "$(last_seq)" "$((before + 1))"
    [ -n "$(header x-request-id)" ] && pass "status row $name x-request-id" || fail "status row $name x-request-id" "absent"
done <<'ROWS'
400 {"status":400} 400 upstream_rejected_request
400-fields {"status":400,"code":"bad_thing","param":"dimensions"} 400 bad_thing
401 {"status":401} 502 upstream_auth_failed
403 {"status":403} 502 upstream_auth_failed
404 {"status":404} 404 model_not_found
404-other {"status":404,"code":"other"} 502 upstream_error
408 {"status":408} 504 upstream_timeout
409 {"status":409} 502 upstream_error
413 {"status":413} 413 upstream_rejected_request
422 {"status":422} 422 upstream_rejected_request
429 {"status":429,"retryAfterSeconds":7} 429 upstream_rate_limited
quota {"status":429,"code":"insufficient_quota"} 503 upstream_quota_exhausted
500 {"status":500} 502 upstream_error
502 {"status":502} 502 upstream_error
503 {"status":503,"retryAfterSeconds":3} 503 provider_unavailable
504 {"status":504} 504 upstream_timeout
ROWS
scenario '{"otherwise":{"error":{"status":429,"retryAfterSeconds":7}}}'
post "{\"model\":\"$MODEL\",\"input\":\"a\"}"
check "status row 429 retry-after" "$(header retry-after)" 7
scenario '{"otherwise":{"error":{"status":400,"code":"bad_thing","param":"dimensions"}}}'
post "{\"model\":\"$MODEL\",\"input\":\"a\"}"
check_has "status row 400 keeps param" "$BODY" '"param":"dimensions"'
check_has "status row 400 fixed message" "$BODY" 'The provider rejected the request'
scenario '{"otherwise":{"fault":{"kind":"malformed"}}}'
before=$(last_seq)
post "{\"model\":\"$MODEL\",\"input\":\"a\"}"
check "malformed reply status" "$STATUS" 502
check_has "malformed reply code" "$BODY" '"code":"upstream_error"'
check "malformed reply one upstream call" "$(last_seq)" "$((before + 1))"
scenario '{"otherwise":{"fault":{"kind":"disconnect"}}}'
before=$(last_seq)
post "{\"model\":\"$MODEL\",\"input\":\"a\"}"
check "provider disconnect status" "$STATUS" 502
check_has "provider disconnect code" "$BODY" '"code":"upstream_error"'
check "provider disconnect one upstream call" "$(last_seq)" "$((before + 1))"
reset_mock

if [ "${HANG:-}" = 1 ]; then
    scenario '{"otherwise":{"fault":{"kind":"hang"}}}'
    before=$(last_seq)
    post "{\"model\":\"$MODEL\",\"input\":\"a\"}"
    check "hang status" "$STATUS" 504
    check_has "hang code" "$BODY" '"code":"upstream_timeout"'
    check "hang one upstream call" "$(last_seq)" "$((before + 1))"
    reset_mock
else
    skip "hang" "set HANG=1"
fi
scenario '{"otherwise":{"fault":{"kind":"hang"}}}'
before=$(last_seq)
curl -sS --max-time 1 -o /dev/null -H "@$TEAM_KEY_HEADER" -H 'Content-Type: application/json' \
    --data "{\"model\":\"$MODEL\",\"input\":\"a\"}" "$GATEWAY$ROUTE" 2>/dev/null
sleep 1
journal_since "$before" >"$TMP/journal"
check "disconnect one journal entry" "$(entries "$TMP/journal")" 1
check_has "disconnect cancels the upstream call" "$TMP/journal" '"kind":"client_cancelled"'
reset_mock

# 8. Canaries, in the input and in the reply. An empty needle would match every line, so it fails on its own.
CANARY="canary-embedding-input-$$"
post "{\"model\":\"$MODEL\",\"input\":\"$CANARY\",\"dimensions\":8,\"encoding_format\":\"base64\"}"
vector=$(json_eval 'b["data"][0]["embedding"]' "$BODY")
if [ -z "$vector" ]; then
    fail "canary vector" "no embedding in: $(cut -c1-300 "$BODY")"
elif [ -n "${GATEWAY_LOG:-}" ]; then
    check_lacks "input not in the log" "$GATEWAY_LOG" "$CANARY"
    check_lacks "vector not in the log" "$GATEWAY_LOG" "$vector"
else
    skip "canaries in the log" "set GATEWAY_LOG"
fi

# 9. No key, a key that is not one, a revoked key; none reaches the provider.
before=$(last_seq)
STATUS=$(curl -sS --max-time 10 -o "$BODY" -w '%{http_code}' -H 'Content-Type: application/json' \
    --data "{\"model\":\"$MODEL\",\"input\":\"a\"}" "$GATEWAY$ROUTE")
check "no key" "$STATUS" 401
check_has "no key code" "$BODY" '"code":"missing_api_key"'
STATUS=$(curl -sS --max-time 10 -o "$BODY" -w '%{http_code}' -H 'Authorization: Bearer llmgw_wrong' \
    -H 'Content-Type: application/json' --data "{\"model\":\"$MODEL\",\"input\":\"a\"}" "$GATEWAY$ROUTE")
check "wrong key" "$STATUS" 401
check_has "wrong key code" "$BODY" '"code":"invalid_api_key"'
team_key_add revoked
STATUS=$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' -X POST -H "@$ADMIN_HEADER" \
    "$GATEWAY/admin/v1/teams/$TEAM_ID/keys/$NEW_KEY_ID/revoke")
check "revoke status" "$STATUS" 200
STATUS=$(curl -sS --max-time 10 -o "$BODY" -w '%{http_code}' -H "@$NEW_KEY_HEADER" \
    -H 'Content-Type: application/json' --data "{\"model\":\"$MODEL\",\"input\":\"a\"}" "$GATEWAY$ROUTE")
check "revoked key" "$STATUS" 401
check_same "no upstream call without a key" "$(last_seq)" "$before"

exit "$failed"
