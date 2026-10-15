# Mini agent harness: the design

One page that ties the pages together, in the order they were built: what was built live, what v2 adds, then the deep dives. Read this for the story, each page for the depth, `./demo.sh` for the proof (`docs/demo-output.txt` is its saved output). The brief is [PROBLEM.md](PROBLEM.md).

[1 · v1](#1--v1-what-i-built-live) · [2 · v2 HLD](#2--v2-the-harness-as-a-small-service) · [3 · Additional features](#3--v2-additional-features) · [4 · Durability](#4--durability) · [5 · Scaling](#5--scaling) · [6 · Performance](#6--performance) · [7 · Sub-agents](#7--sub-agents) · [8 · Measuring](#8--measuring-the-harness) · [9 · Routing across providers](#9--routing-across-providers) · [10 · Sandboxing tools](#10--sandboxing-tools) · [11 · The picture at the end](#11--the-picture-at-the-end) · [12 · Next](#12--what-i-would-build-next-in-order)

## 1 · v1: what I built live

![the whiteboard from the live round](docs/v1-whiteboard.png)

- The brief: `run(goal)`, a loop that hands the state to a model, runs the tool it asks for, feeds the result back, stops when the model says done; a stub model, no network. Then six follow-ups: durable runs, sub-agents, parallel tool calls, measuring the harness, routing across providers, sandboxing.
- What I drew and built in the hour: Orchestrator owns the loop; LLMProxy is the stub; ToolCaller and ToolRegistry run and hold the tools; Conversation, Turn, Span and Context are the state; a Summarizer relays the answer. One process, one question, one tool, done.
- What it could not do, and the follow-ups were about: a turn died with the process; nothing streamed; one tool at a time; no agent inside an agent; no way to say whether the loop did its job; the answer was the last tool's output.
- `v1/` is that code, frozen at tag `v1`, with its own [README](v1/README.md).

## 2 · v2: the harness as a small service

Page: [docs/V2_HLD.md](docs/V2_HLD.md)

- One service, two layers: the HTTP face and the loop. A turn is a job: `POST /runs` writes the turn row and answers with its id at once; the answer streams back over SSE as the steps and the tokens happen; `GET /turns/{id}` reads it after.
- Three tables, from the whiteboard: a conversation has turns, a turn is one job, a turn has spans. Every step is written to the store before the next one is taken.
- Agents and tools are files; the file's type picks the adapter; a builder would be its own service in front of this metadata.
- A question that needs several tool calls: sequential when the second lookup needs the first's result, parallel when the question names two independent things; the model decides which from what it has read.

## 3 · v2: additional features

Page: [docs/V2_FEATURES.md](docs/V2_FEATURES.md)

- What the loop already does beyond the picture, each with a run: the context is the memory, parallel tool calls, a clock on every tool call, the agent's tool list as the gate, the pass cap and the budget, chaining across passes, the answer.

## 4 · Durability

Page: [docs/DURABILITY.md](docs/DURABILITY.md)

- The split: the HTTP face and the loop become two processes with a queue between them, so a turn outlives the request that created it. A turn is a message; the visibility timeout is the lease; the worker extends it on every saved step and acks last.
- A dead worker's message comes back by itself; the next worker loads the saved rows and continues, mid-chain, without asking the model again for a decision it has.
- The contract: saved steps run once, the one call in flight at a crash at least once, so tool writes are idempotent on the key the harness gives them. Temporal is the step up; it was never evaluated, and the shape scoped here is its shape.
- What the POC runs: one jar, two processes on one laptop, one SQLite file, the queue table standing in for SQS. Not containers.

## 5 · Scaling

Page: [docs/SCALING.md](docs/SCALING.md)

- The pods scale on load and on queue depth; nothing new there.
- The persistent connection: the queue cut the line between the pod holding the client and the pod running the turn. The loop is the producer of the run's stream; a Redis Stream per job id carries it to whichever API pod holds the connection, with replay from the last id; the store is the fallback. v2 has the same shape on one machine.
- The GPU, the one thing that does not autoscale: a governor in front of the shared model with a fair share per tenant, the room lent only while it is substantial, and a saturation measure calculated from calls and tokens because the provider's number comes late.
- Tools and state: timeouts and limits on the downstreams, telemetry off the state store.

## 6 · Performance

Page: [docs/PERFORMANCE.md](docs/PERFORMANCE.md)

- Time and tokens, paid on every call: the prefix cache and the order of the prompt; tools in hundreds, with one search tool in the prompt and the catalog and the index behind it; dedupe of tool results; multi-agent leakage, in the DAG too.

## 7 · Sub-agents

Page: [docs/SUBAGENTS.md](docs/SUBAGENTS.md)

- Three patterns I have run at scale: group chat or supervisor, which leaks the transcript and doubles the calls; agent as a tool, built here, where the child sees the goal and its own tools and the parent gets the answer; the DAG for a known sequence, best on a workflow engine.

## 8 · Measuring the harness

Page: [docs/EVAL.md](docs/EVAL.md)

- Two questions, never one score: did the loop do its job, was the answer right. A golden set with the expected trace before ship, built; a sampled judge live with named rates per agent version, designed. The harness scores are facts in the rows and code scores them exactly.

## 9 · Routing across providers

Design, from running a harness in production at scale; nothing of it is in v2 beyond the seam.

- The seam is the model interface: the loop asks for a reply and streams it; it never knows which provider answered. v2 has that interface with a rule stub behind it; a real model goes behind it unchanged.
- In production a gateway sits behind that interface and in front of every provider. It picks the route by tenant and by the agent's tier, holds the credentials, retries a dropped call, and counts the tokens per tenant and model. The loop never carries a provider key.
- Three ways to host a model, and a tenant chooses per agent: a dedicated endpoint the tenant pays for, a shared on-demand pool, or the tenant's own key with its own provider. The gateway is where that choice lives, so an agent moves between them without changing.
- The shared pool is where every tenant meets: that is the governor on the scaling page, the fair share and the room.
- What I ran, on my platform in production: the three hosting choices behind one gateway, dedicated, on demand and bring your own, with the tokens counted per tenant and model at the gateway. Routing by cost or by quality across providers for one agent is the design; the gateway is where it goes.

## 10 · Sandboxing tools

Design, from the same experience; v2 has the first level and the gate.

- A tool runs where the harness binds it, and the question is how much of the harness it can reach. Four levels, each a step away from the loop: in the loop's process, as an adapter; a separate process with limits on time, memory and the file system; a container per task, thrown away after; a remote sandbox the harness only talks to.
- What picks the level is what the tool does. A lookup against a service needs none of this. A tool that runs code the model wrote needs the container, every time.
- Whatever the level, the tool acts as the user, not as the platform: the user's own credentials, scoped to the call, never a platform key. The agent's tool list is the first gate; the credentials are the second.
- v2: every tool is an adapter in the loop's process, under the agent's clock and the agent's tool list, which is the first level and the gate. The timeout and the list are proven on the features page.
- What I ran, on my platform in production: tools calling the business systems as the user, with the user's own credentials passed through and scoped to the call. A container per task for code-running tools is what I built as a proof of concept on my own time, headless, with the approvals pre-gated; it is the level I would ship next for any tool that executes what a model wrote.

## 11 · The picture at the end

Three diagrams, in the order the pages added them, are the harness at scale: [the split and the queue](docs/durability.svg), [the stream across pods](docs/scaling-stream.svg), [the governor in front of the GPU](docs/scaling-gpu.svg). Everything built in v2 keeps its seam in that picture: the API layer, the loop in the worker, the run's stream, the store, the model interface, the tool registry.

## 12 · What I would build next, in order

1. The live loop of the eval: a sampled judge on a schedule, rates per agent version, a confirmed failure back into the golden set.
2. The Redis Stream per job id and the dead-letter queue, replacing the two stand-ins.
3. The tool catalog, the index and the search tool.
4. The governor in front of the model.
5. The DAG on a workflow engine for the sequences that are known up front.
6. A real model behind the interface, with tokens and cached tokens as columns in the eval.
