# Mini agent harness

A small agent harness in plain Java 21: a loop that hands the state to a model, runs the tool the model asks for, feeds the result back, and stops when the model says it is done. The model is a stub; there is no network and no key. Built for the brief in [PROBLEM.md](PROBLEM.md).

- `v1/` is the harness exactly as designed and built in the live round (git tag `v1`). It is frozen.
- `v2/` extends it into a small service: HTTP as the face, a turn as a job whose answer streams back, a queue with a lease so a dead worker's turn is resumed by the next one, an agent as a tool, parallel and chained tool calls, dedupe, a golden set that scores the harness itself.
- [DESIGN.md](DESIGN.md) is the write-up: v1 and its gaps, what v2 adds, then durability, scaling, performance, sub-agents and measurement, each on its own page with the design, the choices, what I ran in production, and the proof.

![v2 HLD](docs/hld.svg)

## Try it in two minutes

What you need:

- Java 21 or newer (`java -version`). On JDK 24 or newer the demo adds `--enable-native-access=ALL-UNNAMED` for you; by hand, put it right after `java` or the SQLite driver prints a warning.
- Maven 3.6 or newer (`mvn -version`). The first build downloads the two dependencies, Jackson and the SQLite driver, so the network is needed once.
- `curl` and a bash shell: macOS or Linux as they come; on Windows, WSL.
- Nothing for SQLite. The driver carries the library; `v2/harness.db` is created on first use and the demo starts from an empty one. The `sqlite3` command is only needed if you want to run the one query on the HLD page yourself.
- Ports: the demo picks a free port. By hand, the server listens on `localhost:8080` (`--port` to change) and the worker pushes to it (`--api`).

```bash
./demo.sh
```

About a minute for the first build, ten seconds of tests, under a minute for the demo.

It compiles v2 and runs every command of the manual in order: a server and a worker, the agents and tools, a turn that chains two lookups and its stream, a hanging tool cut off, a repeated call deduped, two tools in one pass with the answer arriving word by word, the concierge handing a question to the logistics agent and the depth cap, a worker killed after its second row and the next one continuing the chain, the eval table with two failures left in on purpose, then the CLI that remains. [docs/demo-output.txt](docs/demo-output.txt) is the saved output of that script, so the whole demo can be read without running it.

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
