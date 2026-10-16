# Mini agent harness

A small agent harness in plain Java 21: a loop that hands the state to a model, runs the tool the model asks for, feeds the result back, and stops when the model says it is done. The model is a stub; there is no network and no key. Built for the brief in [PROBLEM.md](PROBLEM.md).

I built an agent platform that thousands of customers use in production, and have run it since it went live. Everything on these pages is that experience, the platform I built and what it taught me, applied to a small harness.

- `v1/` is the harness exactly as designed and built in the live round (git tag `v1`). It is frozen.
- `v2/` extends it into a small service. HTTP is the face. Each question is a turn, run as a job, and its answer streams back. A queue with a lease lets the next worker resume a dead worker's turn. It also adds an agent as a tool, parallel and chained tool calls, dedupe, a golden set that scores the harness itself, and a container sandbox for tools that run code the harness did not write.
- [DESIGN.md](DESIGN.md) is the write-up. It covers v1 and its gaps, then what v2 adds. Durability, scaling, performance, sub-agents, measurement, routing and sandboxing each have a page of their own: the design, the choices, what I ran in production, and the proof where v2 builds it.

![v2 HLD](docs/hld.svg)

## Try it in two minutes

What you need:

- Java 21 or newer (`java -version`). On JDK 24 or newer, `--enable-native-access=ALL-UNNAMED` must come right after `java`, or the SQLite driver prints a warning. The demo adds it for you; when you run commands by hand, add it yourself.
- Maven 3.6 or newer (`mvn -version`). The first build downloads the two dependencies, Jackson and the SQLite driver, so the network is needed once.
- `curl` and a bash shell: macOS or Linux as they come; on Windows, WSL.
- Nothing for SQLite: the driver carries the library. `v2/harness.db` is created on first use, and the demo starts from an empty one. The `sqlite3` command is needed only to run the two queries in [docs/V2_HLD.md](docs/V2_HLD.md) yourself.
- Docker, only for the sandbox section: any `docker` command with a running daemon (Docker Desktop, Rancher Desktop). Without it the demo says it skipped that section and runs everything else, and six tests are skipped; [docs/demo-output.txt](docs/demo-output.txt) has the section recorded. With Docker, the first run pulls `alpine:3.20`, a few megabytes, once, quietly: the demo before its sandbox section, `mvn test` before the six container tests, so that first run pauses there.
- Ports: the demo picks a free port. By hand, the server listens on `localhost:8080` (`--port` to change), and the worker pushes its events to the server at that address (`--api` to change).

```bash
./demo.sh
```

About a minute for the first build, under a minute for the demo. The tests are separate: `cd v2 && mvn test`, about ten seconds without Docker and about fifteen with it; it ends with `Tests run: 135, Failures: 0, Errors: 0, Skipped: 0` with Docker running, `Skipped: 6` without it. The lines before that, `crash: exit 3` among them, are the tests' own worker logs: the simulated crash is one of the tests. On JDK 24 or newer Maven prints a few native-access warnings of its own; they are harmless.

`demo.sh` compiles v2 and runs every command of the manual, [v2/README.md](v2/README.md), in order:

- a server and a worker, then the agents and tools
- a turn that chains two lookups, and its stream
- a hanging tool cut off
- a repeated call deduped
- two tools in one pass, the answer arriving word by word
- the concierge handing a question to the logistics agent, then the depth cap
- the sandbox agent: three tools that try to reach out get nothing from inside their containers, one hangs and is killed at the clock (skipped without Docker)
- a worker killed after its second row, and the next one continuing the chain
- the eval table, with two failures left in on purpose
- the rest of the CLI, `replay` and `eval`

[docs/demo-output.txt](docs/demo-output.txt) is the saved output of that script, so the whole demo can be read without running it.

To run commands by hand, see [v2/README.md](v2/README.md). Everything runs from `v2/` as `java -cp "target/classes:target/lib/*" harness.cli.Main <command>`.

## Layout

```
PROBLEM.md          the brief, verbatim
DESIGN.md           the write-up that ties the pages together
docs/               the pages, the diagrams, the saved demo output, the live round's whiteboard
demo.sh             compiles v2 and runs the whole story
AGENTS.md           how to build, test and run, for a coding assistant
v1/                 the live-round harness, frozen (tag v1)
v2/                 the harness as a service: agents/, tools/, evals/, src/, its own README
```
