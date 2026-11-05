#!/usr/bin/env bash
# Exercises authentication and the admin API over real HTTP (GW-04 section 8, the gateway-smoke skill).
# Needs gateway-app (profile `local`), mock-provider and compose Postgres already running; it never starts or stops
# either app, and it resets the mock. It calls no real provider: `local` points the adapters at the mock.
# Usage, from anywhere: bash tools/smoke/auth.sh
# Optional environment:
#   GATEWAY, MOCK   base URLs, default http://127.0.0.1:8080 and http://127.0.0.1:8090
#   GATEWAY_LOG     the gateway's log file, with LOGGING_LEVEL_WEB=debug on the gateway; turns on scenario 10
#   PG_FAULT=1      runs scenario 9: pauses compose Postgres, then restores the stack as found
#   SMOKE_ADMIN_KEY the admin key, default the `local` profile's fake
# Prints "PASS <check>", "SKIP <check>: <why>" or "FAIL <check>: <evidence>" per check, runs every check and exits 1
# if any failed. Scenarios 12 (a restart) and 13 (two gateways) start processes, so they stay manual.
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
GATEWAY="${GATEWAY:-http://127.0.0.1:8080}"
MOCK="${MOCK:-http://127.0.0.1:8090}"
ROUTE="/v1/chat/completions"
LOCAL_KEY="mock-local-key"
UNKNOWN_KEY="llmgw_smoke-unknown-key-0000000000000000000000000"
TMP="$(mktemp -d)"
HEADERS="$TMP/headers"
BODY="$TMP/body"
paused=""

restore() {
    [ -n "$paused" ] && docker compose -f "$ROOT/deploy/compose.yaml" unpause postgres >/dev/null
    rm -rf "$TMP"
}
trap restore EXIT

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

minimal() { printf '{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}'; }

# Sets $STATUS and fills $HEADERS and $BODY. The arguments are curl's, after the fixed ones.
post() {
    STATUS=$(curl -sS --max-time 30 -o "$BODY" -D "$HEADERS" -w '%{http_code}' \
        -H 'Content-Type: application/json' "$@" --data "$(minimal)" "$GATEWAY$ROUTE")
}
admin_get() {
    STATUS=$(curl -sS --max-time 10 -o "$BODY" -D "$HEADERS" -w '%{http_code}' -H "@$ADMIN_HEADER" "$GATEWAY$1")
}
header() { tr -d '\r' <"$HEADERS" | sed -n "s/^$1: *//Ip" | tail -1; }
code() { sed -n 's/.*"code":"\([^"]*\)".*/\1/p' "$BODY"; }
last_seq() { curl -fsS --max-time 5 "$MOCK/__mock/stats" | grep -oE '"lastSeq":[0-9]+' | cut -d: -f2; }
median_ms() { sort -n | awk '{ a[NR] = $1 } END { printf "%.2f", a[int((NR + 1) / 2)] * 1000 }'; }

curl -fsS --max-time 5 -X POST "$MOCK/__mock/reset" >/dev/null || {
    echo "FAIL preflight: the mock does not answer at $MOCK"
    exit 1
}
curl -fsS --max-time 5 "$GATEWAY/actuator/health" >/dev/null || {
    echo "FAIL preflight: the gateway does not answer at $GATEWAY"
    exit 1
}

# 1. No key.
before=$(last_seq)
post
check "1 no key status" "$STATUS" 401
check "1 no key code" "$(code)" missing_api_key
check "1 WWW-Authenticate" "$(header www-authenticate)" Bearer
[ -n "$(header x-request-id)" ] && pass "1 x-request-id" || fail "1 x-request-id" "absent"
check_same "1 no upstream call" "$(last_seq)" "$before"

# 2. Wrong, malformed and Basic credentials; a percent-encoded path.
post -H "Authorization: Bearer $UNKNOWN_KEY"
check "2 unknown status" "$STATUS" 401
check "2 unknown code" "$(code)" invalid_api_key
check "2 unknown WWW-Authenticate" "$(header www-authenticate)" 'Bearer error="invalid_token"'
cp "$BODY" "$TMP/unknown-body"
post -H 'Authorization: Bearer not-a-key'
check "2 malformed code" "$(code)" invalid_api_key
post -H 'Authorization: Basic dXNlcjpwYXNz'
check "2 Basic code" "$(code)" missing_api_key
STATUS=$(curl -sS --max-time 5 --path-as-is -o "$BODY" -w '%{http_code}' -H 'Content-Type: application/json' \
    --data "$(minimal)" "$GATEWAY/v%31/chat/completions")
check "2 encoded path status" "$STATUS" 401

# 3. Team and key; a repeated name; the list.
team_key_create
pass "3 team and key created"
STATUS=$(curl -sS --max-time 5 -o "$BODY" -w '%{http_code}' -H "@$ADMIN_HEADER" \
    -H 'Content-Type: application/json' --data '{"name":"smoke"}' "$GATEWAY/admin/v1/teams/$TEAM_ID/keys")
check "3 repeated name status" "$STATUS" 409
check "3 repeated name code" "$(code)" key_name_taken
admin_get "/admin/v1/teams/$TEAM_ID/keys"
check "3 list status" "$STATUS" 200
check_has "3 list has the hint" "$BODY" '"key_hint":"llmgw_'
check_lacks "3 list has no key" "$BODY" '"key":'
check_lacks "3 list has no digest" "$BODY" 'digest'
check "3 no-store" "$(header cache-control)" no-store

# 4. Chat with the key: the provider sees the configured key, not this one.
post -H "@$TEAM_KEY_HEADER"
check "4 chat status" "$STATUS" 200
fingerprint=$(printf '%s' "$LOCAL_KEY" | sha256sum | cut -c1-8)
journal=$(curl -fsS --max-time 5 "$MOCK/__mock/requests")
case "$journal" in
    *"\"fingerprint\":\"$fingerprint\""*) pass "4 journal fingerprint" ;;
    *) fail "4 journal fingerprint" "no $fingerprint in the journal" ;;
esac

# 5. Stream with the key.
STATUS=$(curl -sS -N --max-time 30 -o "$BODY" -w '%{http_code}' -H "@$TEAM_KEY_HEADER" \
    -H 'Content-Type: application/json' \
    --data '{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}' "$GATEWAY$ROUTE")
check "5 stream status" "$STATUS" 200
check_has "5 frames" "$BODY" 'data: {'
check_has "5 done" "$BODY" 'data: [DONE]'

# 6. Revoke: the next call is 401, and its body is an unknown key's.
team_key_add revoked
STATUS=$(curl -sS --max-time 5 -o "$BODY" -w '%{http_code}' -H "@$ADMIN_HEADER" -X POST \
    "$GATEWAY/admin/v1/teams/$TEAM_ID/keys/$NEW_KEY_ID/revoke")
check "6 revoke status" "$STATUS" 200
post -H "@$NEW_KEY_HEADER"
check "6 revoked status" "$STATUS" 401
if cmp -s "$BODY" "$TMP/unknown-body"; then pass "6 body equals an unknown key's"; else
    fail "6 body equals an unknown key's" "$(cut -c1-300 "$BODY")"
fi

# 7. A key that expires while it is cached.
team_key_add expiring "$(date -u -d '+2 seconds' +%Y-%m-%dT%H:%M:%SZ)"
post -H "@$NEW_KEY_HEADER"
check "7 before expiry" "$STATUS" 200
sleep 3
post -H "@$NEW_KEY_HEADER"
check "7 after expiry" "$STATUS" 401

# 8. Each credential only where it belongs.
post -H "@$ADMIN_HEADER"
check "8 admin key on v1 status" "$STATUS" 401
check "8 admin key on v1 code" "$(code)" invalid_api_key
STATUS=$(curl -sS --max-time 5 -o "$BODY" -w '%{http_code}' -H "@$TEAM_KEY_HEADER" "$GATEWAY/admin/v1/teams")
check "8 team key on admin status" "$STATUS" 401
check "8 team key on admin code" "$(code)" invalid_api_key

# 9. Postgres hung: cached keys keep working, and any other fails fast and closed.
if [ "${PG_FAULT:-}" = 1 ]; then
    team_key_add uncached
    post -H "@$TEAM_KEY_HEADER"
    docker compose -f "$ROOT/deploy/compose.yaml" pause postgres >/dev/null && paused=1
    post -H "@$TEAM_KEY_HEADER"
    check "9 cached key status" "$STATUS" 200
    timing=$(curl -sS --max-time 5 -o "$BODY" -w '%{http_code} %{time_total}' -H "@$NEW_KEY_HEADER" \
        -H 'Content-Type: application/json' --data "$(minimal)" "$GATEWAY$ROUTE")
    check "9 uncached key status" "${timing%% *}" 503
    check "9 uncached key code" "$(code)" team_store_unavailable
    if awk -v t="${timing##* }" 'BEGIN { exit !(t <= 1.5) }'; then pass "9 within 1.5 s"; else
        fail "9 within 1.5 s" "${timing##* }"
    fi
    STATUS=$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' "$GATEWAY/actuator/health")
    check "9 health" "$STATUS" 200
    docker compose -f "$ROOT/deploy/compose.yaml" unpause postgres >/dev/null && paused=""
    recovered=""
    for _ in $(seq 40); do
        post -H "@$NEW_KEY_HEADER"
        if [ "$STATUS" = 200 ]; then
            recovered=1
            break
        fi
        sleep 0.25
    done
    [ -n "$recovered" ] && pass "9 recovery" || fail "9 recovery" "still $STATUS"
else
    skip "9 Postgres hung" "set PG_FAULT=1"
fi

# 10. No key in the log.
if [ -n "${GATEWAY_LOG:-}" ]; then
    if [ ! -s "$GATEWAY_LOG" ]; then
        fail "10 log" "nothing to read at $GATEWAY_LOG"
    elif grep -qE 'llmgw_[A-Za-z0-9_-]{5,}' "$GATEWAY_LOG"; then
        fail "10 log" "a token beyond a hint is in the log"
    else
        pass "10 no key in the log"
    fi
else
    skip "10 log" "set GATEWAY_LOG to the gateway's log file"
fi

# 11. Overhead, informational: the GW-03 baseline is 0.90 ms over the mock, and the target under 0.1 ms on top of it.
post -H "@$TEAM_KEY_HEADER"
direct=$(for _ in $(seq 100); do
    curl -sS -o /dev/null -w '%{time_total}\n' -H "Authorization: Bearer $LOCAL_KEY" \
        -H 'Content-Type: application/json' --data "$(minimal)" "$MOCK$ROUTE"
done | median_ms)
through=$(for _ in $(seq 100); do
    curl -sS -o /dev/null -w '%{time_total}\n' -H "@$TEAM_KEY_HEADER" \
        -H 'Content-Type: application/json' --data "$(minimal)" "$GATEWAY$ROUTE"
done | median_ms)
echo "INFO 11 median ms, cached key: direct $direct, through the gateway $through"

skip "12 restart" "manual: a key created before a gateway restart answers 200 after it"
skip "13 two gateways" "manual: with LLMGW_AUTH_CACHE_TTL=3s, a revoke on one is refused on the other within 3 s"

exit "$failed"
