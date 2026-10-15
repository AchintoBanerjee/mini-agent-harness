# Mini agent harness

A small agent harness in plain Java 21: a loop that hands the state to a model, runs the tool the model asks for, feeds the result back, and stops when the model says it is done. The model is a stub; there is no network and no key. It was built for the brief in `PROBLEM.md`.

- `v1/` is the harness exactly as designed and built in the live round (git tag `v1`). It is frozen.
- `v2/` extends it: agents and tools as files, a step log written before each step is acted on, a run that can be killed and resumed from its log, a runaway tool cut off at a timeout, independent tool calls run together, repeated calls deduped inside a conversation, a golden set that scores the harness itself, HTTP as the face with the answer streamed back, a queue with a lease so a dead worker's turn is resumed by the next one, and an agent as a tool: a child loop inside a tool call, its rows nested in the same turn (`docs/SUBAGENTS.md`).
- `DESIGN.md` is the write-up: v1 and its issues, what v2 fixes and what it designs, and what broke at scale on the platform I ran. Fifteen minutes.

![v2 at a glance](docs/v2-at-a-glance.svg)

## Try it in two minutes

Needs Java 21 or newer and Maven on the path.

```bash
./demo.sh
```

It compiles v2 and runs every command of the manual in order, HTTP first: a server and a worker, the agents and tools, a turn and its stream, a hanging tool cut off, a repeated call deduped, two tools in one pass, the concierge handing a question to the logistics agent and the depth cap, a worker killed after its second row and the next one resuming the turn, the eval table with two failures left in on purpose, then the CLI that remains. `docs/demo-output.txt` is the saved output of that script, so the whole demo can be read without running it.

To run commands by hand, see `v2/README.md`. Everything runs from `v2/` as `java -cp "target/classes:target/lib/*" harness.cli.Main <command>`.

## Layout

```
PROBLEM.md          the brief, verbatim
DESIGN.md           the design write-up
demo.sh             compiles v2 and runs the whole story; docs/demo-output.txt is its saved output
AGENTS.md           how to build, test and run, for a coding assistant
docs/               the diagrams and the live round's whiteboard
v1/                 the live-round harness, frozen (tag v1)
v2/                 the harness extended: agents/, tools/, evals/, src/, its own README
```
