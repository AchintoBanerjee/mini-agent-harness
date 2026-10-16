# Mini agent harness

A small agent harness in plain Java 21: a loop that hands the state to a model, runs the tool the model asks for, feeds the result back, and stops when the model says it is done. The model is a stub; there is no network and no key. Built for the brief in [PROBLEM.md](PROBLEM.md).

I built an agent platform that thousands of customers use in production, and have run it since it went live. Everything on these pages is that experience, the platform I built and what it taught me, applied to a small harness.

- `v1/` is the harness exactly as designed and built in the live round (git tag `v1`). It is frozen.
- `v2/` extends it into a small service. HTTP is the face. Each question is a turn, run as a job, and its answer streams back. A queue with a lease lets the next worker resume a dead worker's turn. It also adds an agent as a tool, parallel and chained tool calls, dedupe, a golden set that scores the harness itself, and a Docker sandbox for tools that run code the harness did not write.
- [DESIGN.md](DESIGN.md) is the write-up. It covers v1 and its gaps, then what v2 adds. Durability, scaling, performance, sub-agents, measurement, routing and sandboxing each have a page of their own: the design, the choices, what I ran in production, and the proof where v2 builds it.

![v2 HLD](docs/hld.svg)

## Try it in two minutes

What you need:

- Java 21 or newer. On JDK 24 or newer, `--enable-native-access=ALL-UNNAMED` goes right after `java`, or the SQLite driver prints a warning; the demo adds it for you.
- Maven 3.6 or newer. The first build downloads the two dependencies, Jackson and the SQLite driver.
- `curl` and bash: macOS or Linux as they come, WSL on Windows.
- Nothing for SQLite: the driver carries the library, and `v2/harness.db` is created on first use. `sqlite3` only to run the two queries on [the HLD page](docs/V2_HLD.md) yourself.
- Docker, only for the sandbox section: the sandbox agent's tools run in Docker containers. Without it the demo skips that section, six tests are skipped, and [docs/demo-output.txt](docs/demo-output.txt) has the section recorded. The first run with Docker pulls `alpine:3.20` once.
- Ports: the demo picks a free one. By hand the server is `localhost:8080` (`--port`), and the worker pushes to it (`--api`).

```bash
./demo.sh
```

About a minute for the first build, under a minute for the demo. The tests: `cd v2 && mvn test`, ten seconds without Docker, fifteen with it; it ends with `Tests run: 135, Failures: 0`, with `Skipped: 6` without Docker. The `crash: exit 3` lines before it are one test's own worker log. On JDK 24 or newer Maven prints a few native-access warnings; harmless.

`demo.sh` compiles v2 and runs every command of the manual, [v2/README.md](v2/README.md), in order:

- a server and a worker, then the agents and tools
- a turn that chains two lookups, and its stream
- a hanging tool cut off
- a repeated call deduped
- two tools in one pass, the answer arriving word by word
- the concierge handing a question to the logistics agent, then the depth cap
- the sandbox agent: three tools try to reach out from inside Docker containers and get nothing, one hangs and is killed (skipped without Docker)
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
