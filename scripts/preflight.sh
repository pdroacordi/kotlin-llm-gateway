#!/usr/bin/env bash
# Session preflight: git state, hooks, toolchain, local stack, clean-room denylist, living docs.
# Usage: bash scripts/preflight.sh [--quiet]   (--quiet prints only problems). Always exits 0.
set -u

QUIET=0
[ "${1:-}" = "--quiet" ] && QUIET=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 0

info=()
problems=()
note() { info+=("$1"); }
warn() { problems+=("$1"); }

# Git: branch, upstream, rule files changed upstream, hooks path, dirty tree.
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    note "branch: $(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    if upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null); then
        timeout 8 git fetch --quiet 2>/dev/null || warn "git fetch failed or timed out: the upstream comparison may be stale"
        behind=$(git rev-list --count "HEAD..$upstream" 2>/dev/null || echo 0)
        ahead=$(git rev-list --count "$upstream..HEAD" 2>/dev/null || echo 0)
        note "upstream: $upstream (ahead $ahead, behind $behind)"
        if [ "$behind" -gt 0 ]; then
            warn "$behind commit(s) behind $upstream: stop and pull --rebase before working"
            rules=$(git diff --name-only "HEAD...$upstream" -- CLAUDE.md .claude/skills .claude/agents .claude/settings.json .githooks scripts docs/DECISIONS.md 2>/dev/null | tr '\n' ' ')
            [ -n "$rules" ] && warn "rule files changed upstream (re-read them after pulling): $rules"
        fi
    else
        note "upstream: none (local branch)"
    fi
    hooks=$(git config --get core.hooksPath || true)
    [ "$hooks" = ".githooks" ] || warn "core.hooksPath is '${hooks:-unset}', expected .githooks: git config core.hooksPath .githooks"
    dirty=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')
    [ "$dirty" -gt 0 ] && note "working tree: $dirty changed path(s); some may be another session's"
else
    warn "not a git repository"
fi

# Toolchain: JDK 21+.
if command -v java >/dev/null 2>&1; then
    jv=$(java -version 2>&1 | head -1)
    major=$(printf '%s' "$jv" | sed -nE 's/.*version "([0-9]+).*/\1/p')
    note "java: $jv"
    if [ -z "$major" ] || [ "$major" -lt 21 ]; then
        warn "JDK 21+ required (found: $jv)"
    fi
else
    warn "java not on PATH (JDK 21+ required)"
fi

# A stack port's value: exported variable, then deploy/.env (read as KEY=VALUE, never executed), then the default.
stack_port() {
    local value
    value=$(printenv "$1" || true)
    if [ -z "$value" ] && [ -f deploy/.env ]; then
        value=$(sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" deploy/.env | tail -1 | tr -d "\r\"'" |
            sed 's/[[:space:]]*$//')
    fi
    printf '%s' "${value:-$2}"
}

# Docker Desktop publishes 127.0.0.1:<port> even when a Windows process already listens on that port, and `up`
# succeeds while the port serves the other program
# (docs/knowledge/gotchas/docker-desktop-publishes-ports-held-by-windows-processes.md). So, while the stack is down,
# warn about every stack port something already listens on. Prints "pid <pid> on <address>" lines per port.
listeners_on() {
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*)
            # A listening row has no remote end (0.0.0.0:0 or [::]:0); unlike the state column, that is not localized.
            printf '%s\n' "$host_listeners" | tr -d '\r' | awk -v p="$1" '$1 == "TCP" &&
                ($3 == "0.0.0.0:0" || $3 == "[::]:0") &&
                ($2 == "0.0.0.0:" p || $2 == "127.0.0.1:" p || $2 == "[::]:" p || $2 == "[::1]:" p) {
                print "pid " $5 " on " $2 }'
            ;;
        *)
            printf '%s\n' "$host_listeners" | awk -v p="$1" '{ n = split($4, a, ":"); if (a[n] == p) {
                pid = "?"; if (match($0, /pid=[0-9]+/)) pid = substr($0, RSTART + 4, RLENGTH - 4)
                print "pid " pid " on " $4 } }'
            ;;
    esac
}
check_stack_ports() {
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*) host_listeners=$(timeout 5 netstat -ano -p TCP 2>/dev/null; timeout 5 netstat -ano -p TCPv6 2>/dev/null) ;;
        *) host_listeners=$(timeout 5 ss -ltnpH 2>/dev/null || true) ;;
    esac
    [ -n "$host_listeners" ] || return 0
    local entry name default port found
    for entry in LLMGW_POSTGRES_PORT:5440 LLMGW_REDIS_PORT:6390 LLMGW_KAFKA_PORT:19092 LLMGW_OTLP_GRPC_PORT:4317 \
        LLMGW_OTLP_HTTP_PORT:4318 LLMGW_JAEGER_UI_PORT:16686 LLMGW_PROMETHEUS_PORT:9090 LLMGW_GRAFANA_PORT:3301; do
        name=${entry%%:*}
        default=${entry#*:}
        port=$(stack_port "$name" "$default")
        found=$(listeners_on "$port" | sort -u | tr '\n' ';' | sed 's/;$//; s/;/, /g')
        [ -n "$found" ] && warn "port $port ($name) is already in use ($found): the stack would start but that port would reach the other program; set $name in deploy/.env"
    done
    return 0
}

# Docker and the local stack (deploy/compose.yaml lands with FND-02).
if command -v docker >/dev/null 2>&1; then
    if timeout 5 docker info >/dev/null 2>&1; then
        if [ -f deploy/compose.yaml ]; then
            running=$(timeout 8 docker compose -f deploy/compose.yaml ps --services --filter status=running 2>/dev/null | tr -d '\r' | tr '\n' ' ' | sed 's/ *$//')
            note "compose running: ${running:-none}"
            # A linked worktree has its own git dir; without deploy/.env it shares the default project and ports.
            git_dir=$(git rev-parse --git-dir 2>/dev/null || true)
            common_dir=$(git rev-parse --git-common-dir 2>/dev/null || true)
            if [ -n "$git_dir" ] && [ "$git_dir" != "$common_dir" ] && [ ! -f deploy/.env ]; then
                note "worktree without deploy/.env: compose commands here act on the shared llmgw stack"
            fi
            [ -z "$running" ] && check_stack_ports
        fi
    else
        warn "docker daemon not reachable"
    fi
else
    warn "docker not on PATH"
fi

# Clean-room denylist (CLAUDE.md rule 1).
if [ ! -s .clean-room-denylist ] || ! grep -qvE '^[[:space:]]*(#|$)' .clean-room-denylist; then
    warn ".clean-room-denylist missing or empty: the pre-commit scan cannot catch employer terms (CLAUDE.md rule 1)"
fi

# Living docs.
if ! docs=$(bash scripts/docs-check.sh 2>&1); then
    warn "docs-check failed: $(printf '%s' "$docs" | head -3 | tr '\n' ' ')"
fi

# Roadmap items in progress.
if [ -f docs/roadmap.md ]; then
    wip=$(grep -E '^\| [A-Z]+-[0-9]+ ' docs/roadmap.md | grep -F '`[~]`' | cut -d'|' -f2 | tr -d ' ' | tr '\n' ' ')
    [ -n "$wip" ] && note "in progress (continue first): $wip"
fi

if [ "$QUIET" -eq 1 ]; then
    [ ${#problems[@]} -eq 0 ] && exit 0
    echo "PREFLIGHT found problems (run the preflight skill):"
    printf '  - %s\n' "${problems[@]}"
    exit 0
fi

echo "preflight"
printf '  %s\n' "${info[@]}"
if [ ${#problems[@]} -eq 0 ]; then
    echo "  no problems"
else
    echo "problems"
    printf '  - %s\n' "${problems[@]}"
fi
exit 0
