#!/usr/bin/env bash
# Sourced by the smoke scripts: gets a team key through the admin API, never SQL (GW-04 section 8). Needs GATEWAY and
# TMP set by the caller, and a gateway already answering.
#   team_key_create        creates a uniquely named team and key; sets TEAM_ID, KEY_ID and TEAM_KEY_HEADER
#   team_key_add NAME [EXPIRES_AT]   one more key of that team; sets NEW_KEY_ID and NEW_KEY_HEADER
# A *_HEADER is a mode-600 file holding one `Authorization` line, used as `curl -H "@$TEAM_KEY_HEADER"`, so no key sits
# in argv (docs/knowledge/bugs/live-openai-puts-the-key-in-grep-argv.md). `printf` is a builtin and starts no process.
# Optional environment:
#   SMOKE_ADMIN_KEY   the admin key, default the `local` profile's public fake

SMOKE_ADMIN_KEY="${SMOKE_ADMIN_KEY:-local-profile-admin-key-not-a-secret}"

# Writes one `Authorization` line to the file $1, for the token in $2.
team_key_header_file() {
    (
        umask 077
        printf 'Authorization: Bearer %s\n' "$2" >"$1"
    )
}

ADMIN_HEADER="$TMP/admin-authorization"
team_key_header_file "$ADMIN_HEADER" "$SMOKE_ADMIN_KEY"

# POSTs the JSON in $2 to the admin path $1. The reply goes to a mode-600 file, because one of them holds a key.
team_key_admin_post() {
    local reply="$TMP/admin-reply" status
    (umask 077 && : >"$reply")
    status=$(curl -sS --max-time 10 -o "$reply" -w '%{http_code}' -H "@$ADMIN_HEADER" \
        -H 'Content-Type: application/json' --data "$2" "$GATEWAY$1")
    if [ "$status" != 201 ]; then
        echo "FAIL team key: POST $1 gave $status: $(cut -c1-300 "$reply")"
        exit 1
    fi
}

# The string member $1 of the last admin reply.
team_key_member() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" "$TMP/admin-reply"; }

team_key_create() {
    local suffix
    suffix="$(date +%s)-$$-$RANDOM"
    team_key_admin_post /admin/v1/teams "{\"name\":\"smoke-$suffix\"}"
    TEAM_ID="$(team_key_member id)"
    team_key_admin_post "/admin/v1/teams/$TEAM_ID/keys" '{"name":"smoke"}'
    KEY_ID="$(team_key_member id)"
    TEAM_KEY_HEADER="$TMP/team-authorization"
    team_key_header_file "$TEAM_KEY_HEADER" "$(team_key_member key)"
    : >"$TMP/admin-reply"
}

team_key_add() {
    local expiry=''
    [ -n "${2:-}" ] && expiry=",\"expires_at\":\"$2\""
    team_key_admin_post "/admin/v1/teams/$TEAM_ID/keys" "{\"name\":\"$1\"$expiry}"
    NEW_KEY_ID="$(team_key_member id)"
    NEW_KEY_HEADER="$TMP/$1-authorization"
    team_key_header_file "$NEW_KEY_HEADER" "$(team_key_member key)"
    : >"$TMP/admin-reply"
}
