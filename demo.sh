#!/usr/bin/env bash
# demo.sh: compiles v2, then runs every command of the manual in order, one header per section.
#   ./demo.sh                                  watch it
#   ./demo.sh > docs/demo-output.txt 2>&1      save the proof (the committed copy was made this way)
# Needs Java 21+ and Maven on the PATH. Everything runs from v2/, where agents/, tools/ and runs/ live.
set -u
cd "$(dirname "$0")/v2" || exit 1

CP='target/classes:target/lib/*'

header() { printf '\n=== %s ===\n' "$1"; }

# Prints the command the way a reader would type it, then runs it.
show() {
    printf '$ java -cp "%s" harness.cli.Main' "$CP"
    for arg in "$@"; do
        case "$arg" in
            *' '*) printf ' "%s"' "$arg" ;;
            *) printf ' %s' "$arg" ;;
        esac
    done
    printf '\n'
}
harness() { show "$@"; java -cp "$CP" harness.cli.Main "$@"; }
must() { harness "$@" || { echo "FAILED (exit $?): $*"; exit 1; }; }

printf 'mini agent harness demo · %s · %s\n' "$(date -u +%Y-%m-%dT%H:%MZ)" "$(java -version 2>&1 | head -1)"

header "compile v2"
echo '$ mvn -q compile      (classes to target/classes, Jackson to target/lib)'
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

header "a plain run: LLM row, TOOL row, LLM row, answer"
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
echo "(process exited with code $status; the log runs/$run_id.jsonl survived, so:)"
must resume "$run_id"

header "the other crash window: the tool ran, the process died before its row; resume runs it again"
crash_out=$(harness run --agent support "Where is my shipment SHP-1?" --crash-before-persist)
status=$?
printf '%s\n' "$crash_out"
if [ "$status" -ne 3 ]; then echo "expected exit 3 from the simulated crash, got $status"; exit 1; fi
run_id=$(printf '%s\n' "$crash_out" | sed -n 's/.*run \(r-[0-9a-f]*\).*/\1/p' | head -1)
if [ -z "$run_id" ]; then run_id=$(ls -t runs | head -1 | sed 's/\.jsonl$//'); fi
if [ -z "$run_id" ]; then echo "no run id found"; exit 1; fi
echo "(the handler ran once in that process and the TOOL row never landed; runs/$run_id.jsonl holds only the LLM row, so resume runs the decided call again:)"
must resume "$run_id"

header "the hang on chaos: hang_forever is cut off at the agent's toolTimeoutMs"
must run --agent chaos "Please hang while checking ORD-1"

header "the dedupe run: the same question twice in one conversation runs the handler once"
must run --agent support "status of order ORD-1" "status of order ORD-1 again"

printf '\ndemo complete\n'
