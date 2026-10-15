# AGENTS.md

Mini agent harness: a small Java 21 agent loop (model stub -> tool call -> result -> model again), built for a take-home whose brief is `PROBLEM.md`.
`v1/` is the harness exactly as designed and built in the live round (tag `v1`) and is frozen: never edit it. All work is in `v2/`.

- Build and test: `cd v2 && mvn -q test` (84 tests, about 7 s). Compile only: `mvn -q compile` (classes to `target/classes`, Jackson and sqlite-jdbc to `target/lib`).
- Run, from `v2/`: `java -cp "target/classes:target/lib/*" harness.cli.Main <command>` with `agents`, `tools`, `run --agent <name> "question" [...] [--slow] [--crash-after N] [--crash-before-persist]`, `resume <runId>`, `replay <runId> [fromStep]`, `eval [evals/golden.json]`, `serve [--port 8080]`. On JDK 24+ put `--enable-native-access=ALL-UNNAMED` after `java`, or the SQLite driver's native load prints a warning.
- HTTP, once `serve` runs: `GET /agents`, `GET /agents/{name}`, `PUT /agents/{name}` (writes `agents/{name}.json`, 400 for an unknown tool), `GET /tools`, `POST /runs` (`{"agent","question"}` -> `202 {"jobId","status":"RUNNING"}`), `GET /runs/{id}` (job row, steps, answer when DONE), `GET /runs/{id}/events` (SSE: stored steps from `Last-Event-ID` or `?after=N`, then live, then `done`).
- Proof: `./demo.sh` from the repo root compiles v2 and runs every command in order, the HTTP section included; `docs/demo-output.txt` is its saved output. The manual with each command's real output is `v2/README.md`; the high-level design of the service is `docs/V2_HLD.md`.
- Design doc: `DESIGN.md` at the repo root. The live round's whiteboard is `docs/v1-whiteboard.png`.
- Data: agents are `v2/agents/*.json`, tools are `v2/tools/*.json` (`type` picks the adapter: `lookup`, `sleep`, `fail`), the golden set is `v2/evals/golden.json`. Runs live in `v2/harness.db` (SQLite, gitignored, tables `job` and `step`, created on first use); tests use the in-memory store or a temp file, never that one.
- Rules: the model stays a stub (`RuleBasedLLM`, no network, no keys); the dependencies beyond JUnit are jackson-databind and sqlite-jdbc; the HTTP server is the JDK's `com.sun.net.httpserver.HttpServer`; plain Java, no frameworks, no preview APIs; the suite stays green and under about 8 s; names and strings stay honest (`TIMEOUT`, `SqliteRunStore`, "(latency simulated)").
- Git: commit from the repo root with a one-line message: `v2 step N: <what>` for the step builds, `v2 hld: <what>` and `v2 durability: <what>` for the two document builds; never push, never `git reset --hard`.
