#!/usr/bin/env bash
# Checks a running local stack (FND-02, docs/knowledge/systems/local-stack.md): services, health, loopback ports,
# OTLP traces to Jaeger, OTLP metrics to Prometheus, Prometheus targets, Grafana datasources and Host enforcement.
# Usage, from anywhere: bash tools/smoke/stack-check.sh
# Prints "PASS <check>", "SKIP <check>: <why>" or "FAIL <check>: <evidence>" per check, runs every check and exits 1
# if any failed. It never starts or stops a service and writes nothing to the repository. The only data it leaves
# is one synthetic span (service llmgw-stack-check) in Jaeger and one gauge (stack_check_heartbeat) in Prometheus.
# Debugging: plain `bash -x` makes checks FAIL, because they capture 2>&1 and with it the trace; send the trace to
# another fd: BASH_XTRACEFD=7 bash -x tools/smoke/stack-check.sh 7>trace.log
# (docs/knowledge/gotchas/bash-xtrace-pollutes-captured-stderr.md).
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1
COMPOSE_FILE="deploy/compose.yaml"

# Same precedence as Compose: an exported variable wins over deploy/.env, which wins over the default.
# deploy/.env is read as KEY=VALUE lines, never executed.
env_file_value() {
    [ -f deploy/.env ] || return 0
    sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" deploy/.env | tail -1 | tr -d '\r' | sed -e 's/[[:space:]]*$//' \
        -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}
setting() {
    local name=$1 default=$2 value
    value=$(printenv "$name" || true)
    [ -n "$value" ] || value=$(env_file_value "$name")
    printf '%s' "${value:-$default}"
}

POSTGRES_PORT=$(setting LLMGW_POSTGRES_PORT 5440)
REDIS_PORT=$(setting LLMGW_REDIS_PORT 6390)
KAFKA_PORT=$(setting LLMGW_KAFKA_PORT 19092)
OTLP_HTTP_PORT=$(setting LLMGW_OTLP_HTTP_PORT 4318)
JAEGER_PORT=$(setting LLMGW_JAEGER_UI_PORT 16686)
PROMETHEUS_PORT=$(setting LLMGW_PROMETHEUS_PORT 9090)
GRAFANA_PORT=$(setting LLMGW_GRAFANA_PORT 3301)

failed=0
pass() { echo "PASS $1"; }
skip() { echo "SKIP $1: $2"; }
fail() {
    echo "FAIL $1: $2"
    failed=1
}

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }
http() { curl -fsS --max-time 5 "$@"; }
one_line() { tr -d '\r' | tr '\n' ' ' | cut -c1-300; }
hex() { od -An -tx1 -N"$1" /dev/urandom | tr -d ' \n'; }

# Host TCP through Git Bash's or Linux bash's /dev/tcp. Returns 2 when this bash has no /dev/tcp.
host_tcp_open() {
    timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null && return 0
    timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/1' 2>&1 | grep -q 'No such file or directory' && return 2
    return 1
}

# 1. Every service in the compose file is running. Each command's own exit status counts: a broken compose file
# makes both print the same error, which would otherwise compare equal.
check_services() {
    local expected running expected_status running_status
    expected=$(compose config --services 2>&1)
    expected_status=$?
    running=$(compose ps --services --status running 2>&1)
    running_status=$?
    expected=$(echo "$expected" | tr -d '\r' | sort)
    running=$(echo "$running" | tr -d '\r' | sort)
    if [ "$expected_status" -ne 0 ]; then
        fail services "compose config --services exited $expected_status: $(echo "$expected" | one_line)"
    elif [ "$running_status" -ne 0 ]; then
        fail services "compose ps --services exited $running_status: $(echo "$running" | one_line)"
    elif [ -n "$expected" ] && [ "$expected" = "$running" ]; then
        pass services
    else
        fail services "expected [$(echo "$expected" | one_line)], running [$(echo "$running" | one_line)]"
    fi
}

# 2. Services with a healthcheck report healthy.
check_health() {
    local states bad="" service state
    states=$(compose ps --format '{{.Service}} {{.Health}}' 2>&1 | tr -d '\r')
    for service in postgres redis redpanda prometheus grafana; do
        state=$(echo "$states" | sed -n "s/^$service //p")
        [ "$state" = "healthy" ] || bad="$bad $service=${state:-absent}"
    done
    if [ -z "$bad" ]; then pass health; else fail health "not healthy:$bad"; fi
}

# 3. No published port on all interfaces.
check_loopback() {
    local ports
    ports=$(compose ps --format '{{.Service}} {{.Ports}}' 2>&1 | tr -d '\r')
    if echo "$ports" | grep -qE '0\.0\.0\.0:|\[::\]:|:::[0-9]'; then
        fail loopback "$(echo "$ports" | grep -E '0\.0\.0\.0:|\[::\]:|:::[0-9]' | one_line)"
    elif ! echo "$ports" | grep -q '127\.0\.0\.1:'; then
        fail loopback "no port bound to 127.0.0.1: $(echo "$ports" | one_line)"
    else
        pass loopback
    fi
}

# 4. Postgres answers inside the container and on the host port.
check_postgres() {
    local out tcp
    if ! out=$(compose exec -T postgres pg_isready -h 127.0.0.1 -U llmgw -d llmgw 2>&1); then
        fail postgres "pg_isready: $(echo "$out" | one_line)"
        return
    fi
    host_tcp_open "$POSTGRES_PORT"
    tcp=$?
    case $tcp in
        0) pass postgres ;;
        2)
            pass postgres
            skip postgres-host-tcp "this bash has no /dev/tcp"
            ;;
        *) fail postgres "no TCP connection to 127.0.0.1:$POSTGRES_PORT" ;;
    esac
}

# 5. Redis answers PING inside the container and on the host port.
check_redis() {
    local out reply
    out=$(compose exec -T redis redis-cli ping 2>&1 | tr -d '\r')
    if [ "$out" != "PONG" ]; then
        fail redis "redis-cli ping: $(echo "$out" | one_line)"
        return
    fi
    host_tcp_open "$REDIS_PORT"
    case $? in
        0)
            reply=$(timeout 3 bash -c "exec 3<>/dev/tcp/127.0.0.1/$REDIS_PORT; printf 'PING\r\n' >&3; head -c 7 <&3" \
                2>/dev/null | tr -d '\r\n')
            if [ "$reply" = "+PONG" ]; then pass redis; else fail redis "host PING got '$reply'"; fi
            ;;
        2)
            pass redis
            skip redis-host-tcp "this bash has no /dev/tcp"
            ;;
        *) fail redis "no TCP connection to 127.0.0.1:$REDIS_PORT" ;;
    esac
}

# 6. Redpanda is healthy and advertises the host address clients must use.
check_redpanda() {
    local out
    if ! out=$(compose exec -T redpanda rpk cluster health 2>&1); then
        fail redpanda "rpk cluster health: $(echo "$out" | one_line)"
        return
    fi
    if ! out=$(compose exec -T redpanda rpk cluster info -X brokers=127.0.0.1:19092 2>&1); then
        fail redpanda "rpk cluster info: $(echo "$out" | one_line)"
    elif echo "$out" | grep -qE "127\.0\.0\.1[[:space:]]+$KAFKA_PORT([^0-9]|$)|127\.0\.0\.1:$KAFKA_PORT([^0-9]|$)"; then
        pass redpanda
    else
        fail redpanda "advertised address is not 127.0.0.1:$KAFKA_PORT: $(echo "$out" | one_line)"
    fi
}

# 7. Jaeger UI answers.
check_jaeger_ui() {
    local out
    if out=$(http -o /dev/null "http://127.0.0.1:$JAEGER_PORT/" 2>&1); then
        pass jaeger-ui
    else
        fail jaeger-ui "$(echo "$out" | one_line)"
    fi
}

# 8. A span sent over OTLP/HTTP reaches Jaeger through the collector.
check_traces() {
    local trace_id span_id now body out i
    trace_id=$(hex 16)
    span_id=$(hex 8)
    now=$(date +%s%N)
    body='{"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"llmgw-stack-check"}}]},'
    body+='"scopeSpans":[{"scope":{"name":"stack-check"},"spans":[{"traceId":"'$trace_id'","spanId":"'$span_id'",'
    body+='"name":"stack-check","kind":1,"startTimeUnixNano":"'$((now - 1000000))'","endTimeUnixNano":"'$now'"}]}]}]}'
    if ! out=$(http -H 'Content-Type: application/json' -d "$body" "http://127.0.0.1:$OTLP_HTTP_PORT/v1/traces" 2>&1); then
        fail traces "OTLP POST: $(echo "$out" | one_line)"
        return
    fi
    for i in $(seq 1 15); do
        if out=$(http "http://127.0.0.1:$JAEGER_PORT/api/traces/$trace_id" 2>/dev/null) && echo "$out" | grep -q "$trace_id"; then
            pass traces
            return
        fi
        sleep 2
    done
    fail traces "trace $trace_id not in Jaeger after 30 s"
}

# 9. A gauge sent over OTLP/HTTP is scraped by Prometheus. The run_id label keeps an earlier run's sample from
# passing this one.
check_metrics() {
    local run_id now body out query i
    run_id=$(hex 8)
    now=$(date +%s%N)
    body='{"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"llmgw-stack-check"}}]},'
    body+='"scopeMetrics":[{"scope":{"name":"stack-check"},"metrics":[{"name":"stack.check.heartbeat","gauge":{"dataPoints":'
    body+='[{"asInt":"1","timeUnixNano":"'$now'","attributes":[{"key":"run_id","value":{"stringValue":"'$run_id'"}}]}]}}]}]}]}'
    if ! out=$(http -H 'Content-Type: application/json' -d "$body" "http://127.0.0.1:$OTLP_HTTP_PORT/v1/metrics" 2>&1); then
        fail metrics "OTLP POST: $(echo "$out" | one_line)"
        return
    fi
    query="{__name__=~\"stack_check_heartbeat.*\",run_id=\"$run_id\"}"
    for i in $(seq 1 15); do
        if out=$(http -G --data-urlencode "query=$query" "http://127.0.0.1:$PROMETHEUS_PORT/api/v1/query" 2>/dev/null) &&
            echo "$out" | grep -q '"result":\[{'; then
            pass metrics
            return
        fi
        sleep 3
    done
    fail metrics "no sample for $query after 45 s: $(echo "${out:-}" | one_line)"
}

# 10. Prometheus scrapes itself and both collector endpoints.
check_targets() {
    local out pools bad job i
    for i in $(seq 1 10); do
        bad=""
        if out=$(http "http://127.0.0.1:$PROMETHEUS_PORT/api/v1/targets?state=active" 2>&1); then
            pools=$(echo "$out" | grep -oE '"scrapePool":"[^"]*"[^{}]*"health":"[a-z]+"' |
                sed -E 's/^"scrapePool":"([^"]*)".*"health":"([a-z]+)"$/\1=\2/')
            for job in prometheus otel-collector otel-collector-exporter; do
                echo "$pools" | grep -qx "$job=up" || bad="$bad $job=$(echo "$pools" | sed -n "s/^$job=//p" | head -1)"
            done
        else
            bad=" $(echo "$out" | one_line)"
        fi
        [ -z "$bad" ] && break
        sleep 3
    done
    if [ -z "$bad" ]; then pass targets; else fail targets "not up:$bad"; fi
}

# 11. Grafana is up, both provisioned datasources exist, and the Prometheus one answers its health check.
# The Jaeger one is checked for existence only (spec FND-02 U6 fallback): Grafana 13.3.0's Jaeger health check
# calls /api/services, which Jaeger 2.22.0 answers with 404 (docs/knowledge/systems/local-stack.md, Gotchas).
check_grafana() {
    local out uid problems=""
    if ! out=$(http "http://127.0.0.1:$GRAFANA_PORT/api/health" 2>&1) || ! echo "$out" | grep -qE '"database": *"ok"'; then
        fail grafana "api/health: $(echo "$out" | one_line)"
        return
    fi
    for uid in prometheus jaeger; do
        if ! out=$(http "http://127.0.0.1:$GRAFANA_PORT/api/datasources/uid/$uid" 2>&1); then
            problems="$problems datasource $uid: $(echo "$out" | one_line);"
        fi
    done
    uid=prometheus
    if ! out=$(http "http://127.0.0.1:$GRAFANA_PORT/api/datasources/uid/$uid/health" 2>&1) ||
        ! echo "$out" | grep -qE '"status": *"OK"'; then
        problems="$problems datasource $uid health: $(echo "$out" | one_line);"
    fi
    if [ -z "$problems" ]; then pass grafana; else fail grafana "$problems"; fi
}

# 12. Grafana refuses a write that names another host, as a DNS-rebinding page would: anonymous Admin must not be
# reachable through any Host but 127.0.0.1 (docs/knowledge/bugs/grafana-anonymous-admin-reachable-through-dns-rebinding.md).
# Expected: a redirect to 127.0.0.1 and no datasource created. A datasource it did create is removed again.
check_grafana_host() {
    local name code found
    name="llmgw-stack-check-rebind-$(hex 4)"
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 -X POST \
        -H 'Host: rebind.example' -H 'Origin: http://rebind.example' -H 'Content-Type: application/json' \
        -d '{"name":"'"$name"'","type":"prometheus","url":"http://prometheus:9090","access":"proxy"}' \
        "http://127.0.0.1:$GRAFANA_PORT/api/datasources")
    found=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$GRAFANA_PORT/api/datasources/name/$name")
    if [ "$found" = "200" ]; then
        curl -s -o /dev/null --max-time 5 -X DELETE "http://127.0.0.1:$GRAFANA_PORT/api/datasources/name/$name"
        fail grafana-host "POST /api/datasources with Host: rebind.example got $code and created datasource $name"
    elif [ "$found" != "404" ]; then
        fail grafana-host "could not tell whether $name exists: GET by name got $found (POST got $code)"
    elif [ "${code:0:1}" = "2" ] || [ "$code" = "000" ]; then
        fail grafana-host "POST /api/datasources with Host: rebind.example got $code"
    else
        pass grafana-host
    fi
}

check_services
check_health
check_loopback
check_postgres
check_redis
check_redpanda
check_jaeger_ui
check_traces
check_metrics
check_targets
check_grafana
check_grafana_host

exit $failed
