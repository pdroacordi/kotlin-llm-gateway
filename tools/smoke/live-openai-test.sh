#!/usr/bin/env bash
# Proves the parsing and the log checks of live-openai.sh on fake files: no gateway, no network, no key, no cost. It
# loads the functions from the script and runs nothing else of it; it never opens the real .env.
# Usage, from anywhere: bash tools/smoke/live-openai-test.sh
# Prints "PASS <check>" or "FAIL <check>: <evidence>" per check; exits 1 if any failed.
set -u

SCRIPT="$(dirname "$0")/live-openai.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
KEY_FILE="$TMP/fake.env"
LOG="$TMP/gateway.log"
FAKE_KEY="sk-llmgw-test-fake*[key]?"

eval "$(sed -n '/^file_value()/,/^}/p; /^lacks()/,/^}/p; /^check_log_lacks()/,/^}/p' "$SCRIPT")"

failures=0
verdict=""
# The script's own reporters, which a check under test calls.
pass() { verdict=PASS; }
fail() { verdict=FAIL; }

expect() {
    local name=$1 actual=$2 expected=$3
    if [ "$actual" = "$expected" ]; then
        echo "PASS $name"
    else
        echo "FAIL $name: expected '$expected', got '$actual'"
        failures=1
    fi
}

# $1: check name, $2: the file's bytes in printf format, $3: the name to read, $4: the value expected.
expect_value() {
    # shellcheck disable=SC2059 # the format carries the fixture's escapes
    printf "$2" > "$KEY_FILE"
    expect "$1" "$(file_value "$3")" "$4"
}

expect_value "plain" 'OPENAI_API_KEY=sk-plain\n' OPENAI_API_KEY sk-plain
expect_value "last line wins" 'OPENAI_API_KEY=sk-a\nOPENAI_API_KEY=sk-b\n' OPENAI_API_KEY sk-b
expect_value "commented line after the real one" 'OPENAI_API_KEY=sk-now\n# OPENAI_API_KEY=old\n' OPENAI_API_KEY sk-now
expect_value "commented line with no space" 'OPENAI_API_KEY=sk-now\n#OPENAI_API_KEY=old\n' OPENAI_API_KEY sk-now
expect_value "indented comment" 'OPENAI_API_KEY=sk-now\n  # OPENAI_API_KEY=old\n' OPENAI_API_KEY sk-now
expect_value "only a comment" '# LLMGW_LIVE_MODEL=commented-model\n' LLMGW_LIVE_MODEL ''
expect_value "export" 'export OPENAI_API_KEY=x\n' OPENAI_API_KEY x
expect_value "export with a tab and blanks" 'export \t OPENAI_API_KEY=x\n' OPENAI_API_KEY x
expect_value "commented export" 'OPENAI_API_KEY=sk-now\n# export OPENAI_API_KEY=old\n' OPENAI_API_KEY sk-now
expect_value "CRLF" 'OPENAI_API_KEY=sk-crlf\r\n' OPENAI_API_KEY sk-crlf
expect_value "double quotes" 'OPENAI_API_KEY="sk-quoted"\n' OPENAI_API_KEY sk-quoted
expect_value "single quotes" "OPENAI_API_KEY='sk-quoted'\n" OPENAI_API_KEY sk-quoted
expect_value "quotes and CRLF" 'OPENAI_API_KEY="sk-quoted"\r\n' OPENAI_API_KEY sk-quoted
expect_value "byte order mark" '\357\273\277OPENAI_API_KEY=sk-bom\n' OPENAI_API_KEY sk-bom
expect_value "byte order mark, then export" '\357\273\277export OPENAI_API_KEY=sk-bom\n' OPENAI_API_KEY sk-bom
expect_value "blanks around the sign" '  OPENAI_API_KEY = sk-blanks  \n' OPENAI_API_KEY sk-blanks
expect_value "a longer name is another key" 'MY_OPENAI_API_KEY=x\nOPENAI_API_KEY_OLD=y\n_OPENAI_API_KEY=z\n' OPENAI_API_KEY ''

# $1: check name, $2: the verdict expected.
expect_log_verdict() {
    verdict=""
    # An empty PATH: the check must run no process, so the key can be no process's argument.
    PATH="" check_log_lacks "$1" "$FAKE_KEY"
    expect "$1" "$verdict" "$2"
}

printf 'a line\n' > "$LOG"
expect_log_verdict "log without the key" PASS
printf 'a line\nAuthorization: Bearer %s\n' "$FAKE_KEY" > "$LOG"
expect_log_verdict "log with the key" FAIL
printf 'sk-llmgw-test-fake-Xkey-\n' > "$LOG"
expect_log_verdict "the key is text, not a pattern" PASS
: > "$LOG"
expect_log_verdict "empty log" FAIL
rm -f "$LOG"
expect_log_verdict "missing log" FAIL

BODY="$TMP/body"
printf 'data: {"a":1}\n' > "$BODY"
lacks '"usage"' "$BODY" && absent=yes || absent=no
expect "lacks: text absent" "$absent" yes
lacks '"a"' "$BODY" && absent=yes || absent=no
expect "lacks: text present" "$absent" no
: > "$BODY"
lacks '"usage"' "$BODY" && absent=yes || absent=no
expect "lacks: empty file proves nothing" "$absent" no
lacks '"usage"' "$TMP/no-such-file" && absent=yes || absent=no
expect "lacks: missing file proves nothing" "$absent" no
printf 'text\n' > "$BODY"
lacks '[' "$BODY" 2>/dev/null && absent=yes || absent=no
expect "lacks: a grep error proves nothing" "$absent" no

exit "$failures"
