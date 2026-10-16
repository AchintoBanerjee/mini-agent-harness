# Mini agent harness: the design

I built an agent platform that thousands of customers use in production, and have run it since it went live. Everything on these pages is that experience, the platform I built and what it taught me, applied to a small harness.

One page that ties the pages together, in the order they were built: what was built live, what v2 adds, then the deep dives. Read this for the story, each page for the depth, `./demo.sh` for the proof (`docs/demo-output.txt` is its saved output). The brief is [PROBLEM.md](PROBLEM.md).

[1 · v1](#1--v1-what-i-built-live) · [2 · v2 HLD](#2--v2-the-harness-as-a-small-service) · [3 · Additional features](#3--v2-additional-features) · [4 · Durability](#4--durability) · [5 · Scaling](#5--scaling) · [6 · Performance](#6--performance) · [7 · Sub-agents](#7--sub-agents) · [8 · Measuring](#8--measuring-the-harness) · [9 · Routing across providers](#9--routing-across-providers) · [10 · Sandboxing tools](#10--sandboxing-tools) · [11 · Next](#11--what-i-would-build-next-in-order)

## 1 · v1: what I built live

![the whiteboard from the live round](docs/v1-whiteboard.png)

- The brief: `run(goal)`, a loop that hands the state to a model, runs the tool it asks for, feeds the result back, stops when the model says done; a stub model, no network. Then six follow-ups: durable runs, sub-agents, parallel tool calls, measuring the harness, routing across providers, sandboxing. Each of the six has a page below.
- What I drew and built in the hour: Orchestrator owns the loop; LLMProxy is the stub; ToolCaller and ToolRegistry run and hold the tools; Conversation, Turn, Span and Context are the state; a Summarizer relays the answer. One process, one question, one tool, done.
- What it could not do, and the follow-ups were about: a turn died with the process; nothing streamed; one tool at a time; no agent inside an agent; no way to say whether the loop did its job; the answer was the last tool's output.
- `v1/` is that code, frozen at tag `v1`, with its own [README](v1/README.md).

## 2 · v2: the harness as a small service

Page: [docs/V2_HLD.md](docs/V2_HLD.md)

![v2 HLD](docs/hld.svg)

- The same loop as v1, run as a small service: the API layer takes the question and answers with a job id at once; the loop runs the turn and streams the answer back; every step is a row in the store before the next one is taken.
- The loop's two seams are drawn as v1 drew them. Behind the LLM proxy sits the rule stub in this POC and a model on a GPU behind a gateway in production. Behind the tools sit lookups here and REST and MCP calls as the user in production.
- A question that needs several tool calls runs them one after the other when the second needs the first's result, and together when it does not; the model decides which from what it has read.

How to run it, the endpoints, the four tables and the proof under each claim are on [the HLD page](docs/V2_HLD.md).

## 3 · v2: additional features

Page: [docs/V2_FEATURES.md](docs/V2_FEATURES.md)

- What the loop already does beyond the picture, each with a run: the context is the memory, parallel tool calls, a clock on every tool call, the agent's tool list as the gate, the pass cap and the budget, chaining across passes, the answer.

## 4 · Durability

Page: [docs/DURABILITY.md](docs/DURABILITY.md)

- The split: the HTTP face and the loop become two processes with a queue between them, so a turn outlives the request that created it. A turn is a message; the visibility timeout is the lease; the worker extends it on every saved step and acks last.
- A dead worker's message comes back by itself; the next worker loads the saved rows and continues, mid-chain, without asking the model again for a decision it has.
- The contract: a saved step runs once; the one call in flight at the crash runs at least once; so a tool that writes is idempotent on the key the harness gives it.
- Temporal, a workflow engine, is the step up from the queue: the same contract, with the bookkeeping done for you. I never evaluated it. What is scoped here keeps the same contract, so it would carry over.
- What the POC runs: one build, two processes on one laptop, one SQLite file, the queue table standing in for SQS. Not containers.

## 5 · Scaling

Page: [docs/SCALING.md](docs/SCALING.md)

- The pods scale on load and on queue depth; nothing new there.
- The persistent connection: once the queue splits the API from the worker, the client's connection is on one pod and the turn runs on another. The loop produces the run's stream. A Redis Stream per job id carries it to whichever API pod holds the connection, with replay from the last id; when the stream is gone, the client reads the store instead.
- v2 has the same shape on one machine: the worker pushes every event to the API over HTTP, and the API holds the stream in memory as the stand-in for the Redis Stream.
- The GPU is the one thing that does not autoscale, so a governor sits in front of the shared model. On my platform we built three parts of it; my part was the direction, the business case and the batch admission side. The meter: saturation computed from calls and tokens, because the provider's own number comes too late. The fair share: each tenant gets a share of the GPU for its live calls. The batch queue: a batch run is admitted only into the room, the capacity that tenants are not using.
- The fourth part is design, not built: admitting a live call over its tenant's share into that same room, on the same saturation number. None of this is in v2, where the model is a stub.
- Tools and state: timeouts and limits on the downstream services the tools call; telemetry written to its own store, not into the state store. v2 has the timeout on every tool call; the limits and the separate telemetry store are design.

## 6 · Performance

Page: [docs/PERFORMANCE.md](docs/PERFORMANCE.md)

- Time and tokens, paid on every call. Built in v2: dedupe of tool results, and agent as a tool, the answer to multi-agent leakage.
- Design, from my platform: the prefix cache, which the order of the prompt decides; and tools in hundreds, with one search tool in the prompt and the catalog and the index behind it.
- The same leak inside a DAG (the pattern in [7 · Sub-agents](#7--sub-agents)) is on that page too.

## 7 · Sub-agents

Page: [docs/SUBAGENTS.md](docs/SUBAGENTS.md)

- Three patterns I have run at scale, one of them only as far as pre-production.
- Group chat, or a supervisor: every agent sees the whole transcript, and the calls double.
- Agent as a tool, built in v2: the child sees the goal and its own tools; the parent gets the answer.
- The DAG, for a sequence known up front: best on a workflow engine; design here, not built.

## 8 · Measuring the harness

Page: [docs/EVAL.md](docs/EVAL.md)

- Two questions, never one score: did the loop do its job, and was the answer right.
- Built: a golden set with the expected trace, run before ship. Designed: a sampled judge on live turns, with named rates per agent version.
- The harness scores are facts already in the rows, so code scores them exactly.

## 9 · Routing across providers

Page: [docs/ROUTING.md](docs/ROUTING.md)

- Design, from production. The router never chooses a model for an agent: the agent declares the models its golden set passed on, and the router only picks where to run one of them. Two factors with different jobs: distance sets the order in which regions are tried, from where the service runs; the governor's meter, under its threshold, decides whether a model in that region has room. A live conversation keeps its replica; the fallback continues the same walk.
- Which models an agent may declare is decided by evals, not by the router: the golden set against a second model, it joins the set when the scores hold, the live judge watches after.

## 10 · Sandboxing tools

Page: [docs/SANDBOX.md](docs/SANDBOX.md)

- The ask: a misbehaving tool must not reach the rest of the system, the worker's process, the store, the keys, the network.
- Our own tools need only the clock and the gate, and v2 had them. A tool that runs code it did not write gets the `container` type: each call in a new Docker container, no network, a read-only root, caps, no secrets, removed after, killed at the clock. One more branch in the loader, nothing else in the loop.
- The demo proves it: a tool looks for the secret and finds none, one calls out and has no network, one writes and the disk is read-only, one hangs and is killed. Skipped without Docker.
- Design, for production: the worker calls a runner service that owns the boxes, a pod per call, on nodes with no secrets; the tool acts as the user. What I ran: tools as the user in production, and a container runtime for a coding agent with the same walls, where the container is kept and reset between tasks.

## 11 · What I would build next, in order

1. The live loop of the eval: a sampled judge on a schedule, rates per agent version, a confirmed failure back into the golden set.
2. The Redis Stream per job id in place of the in-memory stream, and SQS with its dead-letter queue in place of the queue table.
3. The tool catalog, the index and the search tool.
4. The governor in front of the model.
5. The DAG on a workflow engine for the sequences that are known up front.
6. A real model behind the interface, with tokens and cached tokens as columns in the eval.
