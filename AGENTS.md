# AGENTS.md

Mini agent harness: a small Java 21 agent loop (model stub -> tool call -> result -> model again), built for a take-home whose brief is `PROBLEM.md`.
`v1/` is the harness exactly as designed and built in the live round (tag `v1`) and is frozen: never edit it. All work is in `v2/`.

- Build and test: `cd v2 && mvn -q test` (76 tests, about 4 s). Compile only: `mvn -q compile` (classes to `target/classes`, Jackson to `target/lib`).
- Run, from `v2/`: `java -cp "target/classes:target/lib/*" harness.cli.Main <command>` with `agents`, `tools`, `run --agent <name> "question" [...] [--slow] [--crash-after N] [--crash-before-persist]`, `resume <runId>`, `replay <runId> [fromStep]`, `eval [evals/golden.json]`.
- Proof: `./demo.sh` from the repo root compiles v2 and runs every command in order; `docs/demo-output.txt` is its saved output. The manual with each command's real output is `v2/README.md`.
- Design doc: `DESIGN.md` at the repo root (the write-up; it lands after the code steps). The live round's whiteboard is `docs/v1-whiteboard.png`.
- Data: agents are `v2/agents/*.json`, tools are `v2/tools/*.json` (`type` picks the adapter: `lookup`, `sleep`, `fail`), the golden set is `v2/evals/golden.json`, runs are logged to `v2/runs/<id>.jsonl` (gitignored; tests use the in-memory store or a temp dir).
- Rules: the model stays a stub (`RuleBasedLLM`, no network, no keys); the only dependency beyond JUnit is jackson-databind; plain Java, no frameworks, no preview APIs; the suite stays green and under about 5 s; names and strings stay honest (`TIMEOUT`, `FileRunStore`, "(latency simulated)").
- Git: commit from the repo root with a one-line message `v2 step N: <what>`; never push, never `git reset --hard`.
