#!/usr/bin/env bash
# demo.sh: compiles v2, then runs the manual's commands in order, the HTTP face first, one header per section.
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

server_pid=""
worker_pid=""
trap 'kill $server_pid $worker_pid 2>/dev/null' EXIT

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
# The turn id out of a POST /runs reply.
turn_id_of() { printf '%s\n' "$1" | sed -n 's/.*"turnId" *: *"\(r-[0-9a-f]*\)".*/\1/p'; }

printf 'mini agent harness demo · %s · %s\n' "$(date -u +%Y-%m-%dT%H:%MZ)" "$(java -version 2>&1 | head -1)"

header "compile v2"
echo '$ mvn -q compile      (classes to target/classes, Jackson and sqlite-jdbc to target/lib)'
# On JDK 24+ two flags quiet Maven's own warnings (jansi, guava); older JDKs do not know them, so they are only set there.
major=$(mvn -version 2>/dev/null | sed -nE 's/^Java version: ([0-9]+).*/\1/p')
if [ "${major:-0}" -ge 24 ]; then export MAVEN_OPTS="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"; fi
mvn -q compile || exit 1
echo "compiled"
echo '$ rm -f harness.db harness.db-wal harness.db-shm      (the demo starts from an empty store)'
rm -f harness.db harness.db-wal harness.db-shm

header "the HTTP face: serve, and one worker, in the background"
server_log=$(mktemp -t harness-serve)
show serve --port 0
$JAVA -cp "$CP" harness.cli.Main serve --port 0 > "$server_log" 2>&1 &
server_pid=$!
for _ in $(seq 1 50); do grep -q 'listening on' "$server_log" && break; sleep 0.1; done
base=$(sed -n 's/^listening on \(http:[^ ]*\).*/\1/p' "$server_log")
if [ -z "$base" ]; then echo "the server did not start:"; cat "$server_log"; exit 1; fi
grep '^listening on' "$server_log"
echo "(the server stays in the background; --port 0 picked a free port, the manual uses 8080)"
worker_log=$(mktemp -t harness-worker)
show worker
$JAVA -cp "$CP" harness.cli.Main worker > "$worker_log" 2>&1 &
worker_pid=$!
for _ in $(seq 1 50); do grep -q '^worker pid' "$worker_log" && break; sleep 0.1; done
grep '^worker pid' "$worker_log"
echo "(the worker stays in the background too: the server only queues turns, this process runs them)"

header "agents and tools: metadata files, read over GET /agents and GET /tools"
curl_show -s "$base/agents"
curl_show -s "$base/tools"

header "a turn: POST /runs answers with the turn id at once; GET /turns/{id} once the worker is done"
ticket=$(curl_show -s -X POST "$base/runs" -d '{"agent":"support","question":"What is the status of order ORD-1?"}')
printf '%s\n' "$ticket"
turn_id=$(turn_id_of "$ticket")
if [ -z "$turn_id" ]; then echo "no turn id in the POST reply"; exit 1; fi
conversation_id=$(printf '%s\n' "$ticket" | sed -n 's/.*"conversationId" *: *"\(c-[0-9a-f]*\)".*/\1/p')
echo "(202 came back at once with the turn QUEUED; the worker took it, and one second later the turn row says DONE, who ran it, the answer, and the spans:)"
sleep 1
curl_show -s "$base/turns/$turn_id"

header "the stream: GET /turns/{id}/events sends every span with its number as the event id, then done; a reconnect with Last-Event-ID gets only the tail"
curl_show -sN --max-time 5 "$base/turns/$turn_id/events"
echo "(a client that reconnects sends the last id it saw:)"
curl_show -sN --max-time 5 -H 'Last-Event-ID: 2' "$base/turns/$turn_id/events"

header "a live stream on chaos: opened right after the POST, it waits for each row; the hang is cut off at the agent's toolTimeoutMs"
ticket=$(curl_show -s -X POST "$base/runs" -d '{"agent":"chaos","question":"Please hang while checking ORD-1"}')
printf '%s\n' "$ticket"
live_id=$(turn_id_of "$ticket")
curl_show -sN --max-time 5 "$base/turns/$live_id/events"

header "a follow-up turn in the same conversation: the model sees the earlier turn, the repeated call is not run again"
ticket=$(curl_show -s -X POST "$base/runs" -d '{"conversationId":"'"$conversation_id"'","question":"What is the status of order ORD-1, again?"}')
printf '%s\n' "$ticket"
again_id=$(turn_id_of "$ticket")
if [ -z "$again_id" ]; then echo "no turn id in the POST reply"; exit 1; fi
sleep 1
echo "(the spans are numbered on from the first turn; the TOOL span is DEDUPED and points at span 2 of the first turn:)"
curl_show -s "$base/turns/$again_id"

echo
echo "(the worker's own log: it received the three turns, ran them, acked them; the server ran no loop)"
kill "$worker_pid"
wait "$worker_pid" 2>/dev/null
worker_pid=""
sed -n '/^worker pid/,$p' "$worker_log"
rm -f "$worker_log"
echo "(worker stopped; the server stays up, so the next turns wait on the queue until a worker is started by hand)"

header "two tools in one pass run together: a worker started with --slow shows wall < work"
ticket=$(curl_show -s -X POST "$base/runs" -d '{"agent":"support","question":"Status of order ORD-2 and is product PRD-1 available?"}')
printf '%s\n' "$ticket"
parallel_id=$(turn_id_of "$ticket")
if [ -z "$parallel_id" ]; then echo "no turn id in the POST reply"; exit 1; fi
echo "(--slow gives every lookup 300 ms of simulated latency; two lookups in one pass take one sleep of wall time, not two:)"
must worker --once --slow
curl_show -s "$base/turns/$parallel_id"

header "durability: the queue, the lease, the dead worker"
echo "(submit writes the turn row QUEUED and its queue message without HTTP, and comes back with the id:)"
sub_out=$(harness submit --agent support "What is the status of order ORD-1?")
printf '%s\n' "$sub_out"
dur_id=$(printf '%s\n' "$sub_out" | sed -n 's/^submitted \(r-[0-9a-f]*\) .*/\1/p')
if [ -z "$dur_id" ]; then echo "no turn id in the submit output"; exit 1; fi
curl_show -s "$base/turns/$dur_id"
echo "(a worker takes it with a 2000 ms lease and dies right after its 2nd row is persisted: exit 3, no ack)"
crash_out=$(harness worker --once --crash-after 2 --visibility-ms 2000)
status=$?
printf '%s\n' "$crash_out"
if [ "$status" -ne 3 ]; then echo "expected exit 3 from the simulated crash, got $status"; exit 1; fi
echo "(process exited with code $status; the turn row says RUNNING, the message is still queued but hidden until the lease runs out)"
echo '$ sleep 2'
sleep 2
echo "(the lease has run out, so the next worker receives the same message again and resumes from the last saved step:)"
must worker --once
echo "(the API reads the same rows, whoever wrote them: DONE, attempt 2, claimed by the second worker's pid, all four spans)"
curl_show -s "$base/turns/$dur_id"

header "eval over HTTP: POST /evals scores the golden set in the server; two FAILs left in on purpose"
curl_show -s -X POST "$base/evals"

echo
echo "(the server's own log, one line per request:)"
kill "$server_pid"
wait "$server_pid" 2>/dev/null
server_pid=""
sed -n '/^listening on/,$p' "$server_log"
rm -f "$server_log"
echo "(server stopped)"

header "the CLI that remains: serve, worker, submit, eval, replay"
echo "(serve, worker and submit ran above; replay prints a turn's stored rows; eval prints the table POST /evals returns)"
must replay "$dur_id"
must eval

printf '\ndemo complete\n'
