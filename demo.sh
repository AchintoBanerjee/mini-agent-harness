#!/usr/bin/env bash
# demo.sh: compiles v2, then runs every command of the manual in order, one header per section.
#   ./demo.sh                                  watch it
#   ./demo.sh > docs/demo-output.txt 2>&1      save the proof (the committed copy was made this way)
# Needs Java 21+, Maven and curl on the PATH. Everything runs from v2/, where agents/, tools/ and harness.db live.
set -u
cd "$(dirname "$0")/v2" || exit 1

CP='target/classes:target/lib/*'

# On JDK 24+ the SQLite driver's native load prints a "restricted method" warning unless native access is
# enabled; the flag quiets it and is printed with every command, because it was part of it. JDK 21 needs nothing.
java_major=$(java -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p')
JAVA="java"
if [ "${java_major:-0}" -ge 24 ]; then JAVA="java --enable-native-access=ALL-UNNAMED"; fi

header() { printf '\n=== %s ===\n' "$1"; }

# Prints the command the way a reader would type it, then runs it.
show() {
    printf '$ %s -cp "%s" harness.cli.Main' "$JAVA" "$CP"
    for arg in "$@"; do
        case "$arg" in
            *' '*) printf ' "%s"' "$arg" ;;
            *) printf ' %s' "$arg" ;;
        esac
    done
    printf '\n'
}
harness() { show "$@"; $JAVA -cp "$CP" harness.cli.Main "$@"; }
must() { harness "$@" || { echo "FAILED (exit $?): $*"; exit 1; }; }
# Prints a curl command the way a reader would type it (JSON and headers in single quotes), then runs it.
curl_show() {
    printf '$ curl'
    for arg in "$@"; do
        case "$arg" in
            *[\ \{\"]*) printf " '%s'" "$arg" ;;
            *) printf ' %s' "$arg" ;;
        esac
    done
    printf '\n'
    curl "$@"
}
# PUT with the body on stdin, as a reader would type it with a here-string.
curl_put() {
    printf "\$ curl -s -X PUT %s -d @- <<< '%s'\n" "$1" "$2"
    curl -s -X PUT "$1" -d @- <<< "$2"
}

printf 'mini agent harness demo · %s · %s\n' "$(date -u +%Y-%m-%dT%H:%MZ)" "$(java -version 2>&1 | head -1)"

header "compile v2"
echo '$ mvn -q compile      (classes to target/classes, Jackson and sqlite-jdbc to target/lib)'
# On JDK 24+ two flags quiet Maven's own warnings (jansi, guava); older JDKs do not know them, so they are only set there.
major=$(mvn -version 2>/dev/null | sed -nE 's/^Java version: ([0-9]+).*/\1/p')
if [ "${major:-0}" -ge 24 ]; then export MAVEN_OPTS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"; fi
mvn -q compile || exit 1
echo "compiled"

header "agents: an agent is a file in agents/"
must agents

header "tools: a tool is a file in tools/, its type picks the adapter"
must tools

header "eval: the golden set, two FAILs left in on purpose"
must eval

header "a plain run: LLM row, TOOL row, LLM row, answer; every row lands in harness.db"
must run --agent support "What is the status of order ORD-1?"

header "the --slow parallel run: two tools in one pass, wall < work"
must run --agent support "Status of order ORD-2 and is product PRD-1 available?" --slow

header "the crash and the resume: exit 3 after row 1 persists, resume runs the decided call"
crash_out=$(harness run --agent support "What is the status of order ORD-1?" --crash-after 1)
status=$?
printf '%s\n' "$crash_out"
if [ "$status" -ne 3 ]; then echo "expected exit 3 from the simulated crash, got $status"; exit 1; fi
run_id=$(printf '%s\n' "$crash_out" | sed -n 's/.* of run \(r-[0-9a-f]*\) .*/\1/p')
if [ -z "$run_id" ]; then echo "no run id in the crash output"; exit 1; fi
echo "(process exited with code $status; the rows of $run_id survived in harness.db and its job row still says RUNNING, so:)"
must resume "$run_id"

header "the other crash window: the tool ran, the process died before its row; resume runs it again"
crash_out=$(harness run --agent support "Where is my shipment SHP-1?" --crash-before-persist)
status=$?
printf '%s\n' "$crash_out"
if [ "$status" -ne 3 ]; then echo "expected exit 3 from the simulated crash, got $status"; exit 1; fi
run_id=$(printf '%s\n' "$crash_out" | sed -n 's/.* of run \(r-[0-9a-f]*\) .*/\1/p')
if [ -z "$run_id" ]; then echo "no run id in the crash output"; exit 1; fi
echo "(the handler ran once in that process and the TOOL row never landed; harness.db holds only the LLM row of $run_id, so resume runs the decided call again:)"
must resume "$run_id"

header "the hang on chaos: hang_forever is cut off at the agent's toolTimeoutMs"
must run --agent chaos "Please hang while checking ORD-1"

header "the dedupe run: the same question twice in one conversation runs the handler once"
must run --agent support "status of order ORD-1" "status of order ORD-1 again"

header "the HTTP API: serve, POST /runs, GET /runs/{id}, the SSE stream, PUT /agents/{name}"
server_log=$(mktemp -t harness-serve)
show serve --port 0
$JAVA -cp "$CP" harness.cli.Main serve --port 0 > "$server_log" 2>&1 &
server_pid=$!
for _ in $(seq 1 50); do grep -q 'listening on' "$server_log" && break; sleep 0.1; done
base=$(sed -n 's/^listening on \(http:[^ ]*\).*/\1/p' "$server_log")
if [ -z "$base" ]; then echo "the server did not start:"; cat "$server_log"; kill "$server_pid" 2>/dev/null; exit 1; fi
grep '^listening on' "$server_log"
echo "(the server stays in the background; --port 0 picked a free port, the manual uses 8080)"

echo
ticket=$(curl_show -s -X POST "$base/runs" -d '{"agent":"support","question":"What is the status of order ORD-1?"}')
printf '%s\n' "$ticket"
job_id=$(printf '%s\n' "$ticket" | sed -n 's/.*"jobId" *: *"\(r-[0-9a-f]*\)".*/\1/p')
if [ -z "$job_id" ]; then echo "no job id in the POST reply"; kill "$server_pid"; exit 1; fi
echo "(202 came back before the loop ran; one second later the job row says DONE and the steps are there:)"
sleep 1
curl_show -s "$base/runs/$job_id"

echo
echo "(the event stream: every stored step with its step number as the event id, then done with the answer)"
curl_show -sN --max-time 5 "$base/runs/$job_id/events"
echo "(a client that reconnects sends the last id it saw and gets only the tail)"
curl_show -sN --max-time 5 -H 'Last-Event-ID: 2' "$base/runs/$job_id/events"

echo
echo "(a live one: the stream is opened right after the POST and waits for each row; the hang takes 1000 ms to be cut off)"
ticket=$(curl_show -s -X POST "$base/runs" -d '{"agent":"chaos","question":"Please hang while checking ORD-1"}')
printf '%s\n' "$ticket"
live_id=$(printf '%s\n' "$ticket" | sed -n 's/.*"jobId" *: *"\(r-[0-9a-f]*\)".*/\1/p')
curl_show -sN --max-time 5 "$base/runs/$live_id/events"

echo
echo "(the builder experience: an agent is a file, PUT writes it; a tool that does not exist is refused first)"
curl_put "$base/agents/returns" '{"name":"returns","description":"Refunds an order, by order id","tools":["refund_orders"],"maxPasses":3,"toolTimeoutMs":2000}'
curl_put "$base/agents/returns" '{"name":"returns","description":"Refunds an order, by order id","tools":["refund_order"],"maxPasses":3,"toolTimeoutMs":2000}'
curl_show -s "$base/agents/returns"
echo '$ cat agents/returns.json'
cat agents/returns.json

echo
echo "(the server's own log, one line per request:)"
kill "$server_pid"
wait "$server_pid" 2>/dev/null
sed -n '/^listening on/,$p' "$server_log"
rm -f "$server_log" agents/returns.json
echo "(server stopped; agents/returns.json removed again so the agent list is back to three)"

printf '\ndemo complete\n'
