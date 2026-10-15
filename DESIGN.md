# Mini agent harness: design

> [YOUR POSITION SENTENCE, in your words: what you ran at scale, for how long, and that this harness is the small version of it.]

## 0 · Read this first

v1 is the harness as I designed and built it in the live round. v2 extends it: the issues that fit in one process are fixed in code, the rest is designed here with the mechanism stated, and every section ends with what broke on my platform at scale and what we changed.

| The brief's follow-up | Section | The command |
|---|---|---|
| Making a run durable: interrupt it halfway, resume it later | 2.3 | `run --crash-after 2`, then `resume <id>` |
| Letting a run spawn sub-agents | 2.9 | designed |
| Executing independent tool calls in parallel | 2.5 | `run --slow` |
| Measuring whether the harness is any good, and keep measuring | 2.10 | `eval` |
| Routing across multiple model providers | 2.8 | designed |
| Sandboxing tool execution | 2.4 | `run --agent chaos` |

To run it: `./demo.sh` from the repo root compiles v2 and runs every command below in order; `docs/demo-output.txt` is its saved output, and `v2/README.md` is the manual.

Three labels run through the document. **Built here** is what the v2 code does. **Designed here** is what this document proposes and the code does not do. **Ran on my platform** is what I ran in production.

## 1 · v1: what was built live

I built v1 live against a stub model: one question, one tool call, one answer.

![v1 as built, issues marked](docs/v1-issues.svg)

**The loop.**

1. Build a Context: the question plus this turn's spans.
2. Ask the model: a ToolCall or Done. Record an LLM span.
3. Done ends the loop. A ToolCall goes to the ToolCaller, which never throws; a failure becomes an error result.
4. Record a TOOL span with the output. Go again.
5. Stop after five passes. The Summarizer relays the last tool output as the answer.

**The data model.** A Conversation holds an Agent and its Turns; a Turn holds a question, its Spans and the answer; an Agent is a name and a list of tool names; a Tool is a name, a description, an input field and a matching rule the stub reads.

**Ten issues.**

| # | Issue | In the brief? |
|---|---|---|
| 1 | The model is one shot: Done once any tool ran, so no chaining; substring match, first match wins; no rationale recorded. | no |
| 2 | Tools run on the loop thread with no clock and no allowlist. | yes |
| 3 | The five-pass stop is silent, and a tool error comes back as the answer. | no |
| 4 | The record lives in memory and dies with the process. | yes |
| 5 | One tool call per pass. | yes |
| 6 | The agent is a name and a tool list, hard-coded in the driver. | no |
| 7 | Nothing measures the harness. | yes |
| 8 | The model sees only this turn: no budget, no memory across turns. | no |
| 9 | One model, no router. | yes |
| 10 | No sub-agents. | yes |

The brief's six follow-ups are issues 4, 10, 5, 7, 9 and 2. v2 fixes 2, 3, 4, 5, 6 and 7 in code, gives the model memory across turns, and designs the rest.

## 2 · v2: the features

### 2.1 Agents and tools as data

In v1 the agent and every tool are fixed in code.

**Why it matters.** A harness that needs a rebuild per tool stays a demo. The people who add tools do not own the loop.

**In v2.**
- **Built here.** An agent is a JSON file: name, description, tools, maxPasses, toolTimeoutMs. A tool is a JSON file whose `type` picks the adapter: `lookup`, `sleep` or `fail`. Adding a tool means adding a file. `agents` and `tools` list what loaded; `run --agent support` assembles the orchestrator from the agent file.
- **Designed here.** A type that calls a real service, with the credential minted for the user and injected per call, never stored on the tool.

**How it works.** The registry is built from the `tools/` folder. The loader reads each file's `type` and hands the rest to that adapter, which returns a Tool and its handler. The `match` block is the keyword hint the stub reads; a real model reads the description. The orchestrator resolves the agent's tool names against the registry and takes maxPasses and toolTimeoutMs from the file. A tool the agent does not list returns `DENIED` without running.

**At scale.** **Ran on my platform.** A tool is a row: its type plus call metadata picks the adapter at load, and on the first turn the row becomes the schema the model sees. [confirm] The agent definition is loaded from a config store where every row carries a version and a status. [confirm]

| Tool type | What the adapter does | Tools |
|---|---|---|
| `lookup` | reads a static map; answers with the found or notFound template | order_status, shipping_status, stock_status, refund_order |
| `sleep` | sleeps `ms` milliseconds; interruptible, so a clock can cut it | hang_forever |
| `fail` | throws `message` | throw_boom |

### 2.2 Streaming: the loop is the only producer

The caller sees nothing until the answer, and a dropped connection loses a running turn.

**Why it matters.** Silence hides a slow tool behind a dead node, and a client that cannot reconnect has to start over.

**In v2.**
- **Built here.** The Orchestrator writes each step row to `runs/<id>.jsonl` before the next step. A console listener prints each row as it lands. `replay <runId> [fromStep]` prints the stored rows from that step on. No server, no SSE, no bus.
- **Designed here.** POST returns the run id at once; GET /runs/{id}/events is the SSE. A reconnecting client sends the last id it saw and gets the tail from the log, then live. Disconnect is not cancel. Cancel is explicit, checked between hops; a tool call in flight finishes.

**How it works.** The model's stream ends at the loop, which must see the whole reply to pick the next hop, so the loop is the only producer the caller sees. Each hop writes one row keyed by run id and step, then publishes it; the row is in the log before anyone reads it, so stream and resume share one append.

| Leg | Transport | Needs a bus? | If it drops |
|---|---|---|---|
| Client to the node holding the stream | SSE on GET /runs/{id}/events | no | the client reconnects anywhere with its last id; that node replays from the log |
| Loop to the node holding the stream | pub/sub keyed by run id for live; the log for replay | yes, the only one | nothing lost; a row is in the log before it is published |
| Loop to the model | one call, whole or streamed, ending at the loop | no | retry; a model call has no side effects |
| Loop to a tool | one call per tool, as the user | no | retry a read freely; retry a write only with its idempotency key |

**At scale.** **Ran on my platform.** The first shape streamed the turn over SSE on the POST; the loop was the producer, inside that request's timeout, and a mid-turn drop lost the in-flight events while everything persisted survived. [confirm] We then moved the loop to queued workers; a thin API layer holds the connection and subscribes by run id. [confirm] Replay from the last id plus an explicit cancel is the fix, designed, not shipped. [confirm]

### 2.3 Durable runs: interrupt and resume

The harness is easy; durability is the hard part: a run must outlive the process that started it.

**Why it matters.** A turn is many seconds of model and tool calls, and a deploy kills the process halfway. Without a record the turn is lost, and a blind retry may fire a write twice.

**In v2.**
- **Built here.** The Orchestrator writes before it acts: the LLM row lands in `runs/<runId>.jsonl` before the tool runs, the TOOL row before the model is asked again. `resume <runId>` rebuilds the Turn, executes the pending call without re-asking the model, and appends a NOTE row `resumed from step N`. Every call carries an idempotency key, the run id plus the step of the LLM row that decided it, so the two executions of one decision share a key. `run --crash-after N` exits with code 3 right after row N is persisted and names the run id; `--crash-before-persist` exits after the tool ran, before its TOOL row. The crash line and the resume footer each print the handler calls, so the two windows are visible: none then one, against one then one.
- **Designed here.** A queue in front of the loop. Accept writes the run row and returns the id. A worker claims the run with a lease, heartbeats while live, and acks only after the final row persists. An expired lease makes the run visible to another worker, which resumes from the last row. After N receives, a dead-letter queue.

**How it works.** Each LLM row carries the decided calls, so a decided step is never re-asked; with a real model a re-ask costs tokens and can diverge. Resume counts the TOOL rows after the last LLM row; fewer rows than calls means a pending call, which runs first. The hole is the in-flight call: the tool fired, the process died before its row, and resume fires it again. The contract is persisted steps once, the in-flight call at least once. The harness supplies the key and the tool dedups by it, at the price of one store write per step.

![write before act](docs/resume.svg)

**At scale.** **Ran on my platform.** The first version checkpointed the whole run context at wait points only, approval, timer, async API, one row per job, and resumed from the recorded next node; a crash between wait points lost the run, and inside the agent loop the turn is the default retry unit. [confirm] The next version put an SQS-style queue with a lease in front of the loop: extend while live, ack after persist, dead-letter after N receives. [confirm] Resume became an opt-in log of completed tool calls per conversation, default off. [confirm] Tool authors own idempotent writes; where a tool cannot, the upgrade is a started-marker plus verify-on-resume. [confirm] We never evaluated Temporal; the shape we scoped is its shape. [confirm]

| | Queue, lease, step log | Durable-execution engine |
|---|---|---|
| Who owns idempotency | the tool, by key `<runId>/<step>`; the harness only supplies the key | still the tool; the engine retries the activity and cannot undo a fired write |
| Who owns the lease | our worker: heartbeat, ack after persist, a sweeper for expired leases | the engine: task queues and timeouts |
| What you add to the stack | a queue and a step table | the engine and its SDK; the loop becomes a workflow, each model and tool call an activity |
| What the loop must obey | write before act; one key per call | deterministic code; model replies cached as activity results |

### 2.4 Sandboxing: runaway tools

A tool is code the loop did not write, so it can hang, throw, eat memory, or reach disk, network and credentials.

**Why it matters.** In v1 one hang stops the run, and an unlisted tool still runs. Neither reaches the answer.

**In v2.**
- **Built here.** Each call runs on its own virtual thread with a clock. A tool outside the agent's `tools` list returns `DENIED` without running. Past `toolTimeoutMs` the call returns `TIMEOUT`, `cut off after 1000 ms`; a throw becomes `ERROR`. The loop moves on and the answer names the status. A time boundary, not a security boundary.
- **Designed here.** The next two levels in the table, drawn, not built.

**How it works.** One long-lived executor is a field and is never closed on the call path, because close waits for the hung tool. The caller waits on the future, cancels on expiry and writes the `TIMEOUT` row. Cancel only interrupts, so a tool that ignores it keeps its thread. That leak is why the process level exists: a kill is real, and a scrubbed environment leaves no credential behind.

![sandbox levels](docs/isolation-levels.svg)

| Sandbox level | hang | crash | memory | filesystem | network | credentials |
|---|---|---|---|---|---|---|
| In the loop thread, v1 | no | partly | no | no | no | no |
| Own thread with a clock, built in v2 | partly | stops | no | no | no | no |
| Separate process, scrubbed environment | stops | stops | partly | partly | no | stops |
| Container with one door out, a resource boundary | stops | stops | stops | stops | stops, by the doors | stops, by the doors |

**At scale.** **Ran on my platform.** Code-executing agents run in a container with two doors the platform owns: a model door on loopback that signs with credentials the agent never sees, and an egress door with a domain allowlist that injects the user's token. [confirm] The container is non-root with a read-only root filesystem and a scrubbed environment; a `timeout` wrapper ends it with exit 124; the workspace is diffed before and after, and the diff is the verdict, not the agent's report. [confirm] Loop tools stay in-process; only code-executing agents get the container; the egress door is a per-environment switch, so the network cell is "partly" for my platform today. [confirm]

### 2.5 Parallel tool calls

Two tools named in one reply still run one after the other.

**Why it matters.** Serial execution doubles the wait. The hard part is a deterministic log while calls finish out of order.

**In v2.**
- **Built here.** Several tools in one reply become one `ToolCalls` batch. `ToolCaller.callAll` runs it on virtual threads, one clock per call, and returns results in request order. A throwing sibling is an `ERROR` row, a hanging one a `TIMEOUT` row, and the others finish. `--slow` adds 300 ms per lookup, prints `(latency simulated)`, and a NOTE row `ran N tools together: wall X ms, work Y ms` shows wall below work.
- **Designed here.** Reads fan out; writes stay serial. A side-effect flag on the tool file marks the writes, and the batch runs flagged calls in order after the reads have fanned out, each with its idempotency key.

**How it works.** Two calls are independent when neither needs the other's output. With both inputs in hand, the model emits both calls at once; when one input comes from another tool's output, the model has to wait a pass. The pass boundary is the dependency edge, so the harness never parses arguments to find one. Rows land in request order, never completion order, so the next context is the same on every replay.

**At scale.** **Ran on my platform.** The loop ran model-emitted tool calls one at a time, a name lookup then an execute; parallelism lived one level up, as builder-declared branches in the workflow graph. [confirm]

### 2.6 What the model sees

A model call is stateless, so on each pass the harness decides what the model reads.

**Why it matters.** That choice sets the cost of every later turn. Products like Codex compact inside their harness; the API does not. This harness is that layer.

**In v2.**
- **Built here.** Several questions in one `run` form one Conversation, and earlier turns appear as `Q: ... → A: ...` lines. A repeat of an earlier OK TOOL row, same tool and same input, does not run; a TOOL row with status `DEDUPED` and the response `unchanged, see step <k>` points at it. The summarizer resolves the reference, and the handler runs once.
- **Designed here.** A context builder with a fixed order and a size budget: agent and tool names first, earlier turns, this turn's rows, the question last; one fold of the oldest rows into a summary row at overflow, never a trim of the middle on every pass.

**How it works.** Built: earlier turns as question and answer lines, this turn's rows, append only; a repeat is a reference, not a copy. Designed: the stable-to-volatile order keeps a provider's prefix cache warm, and the single fold at overflow is the one cache break; the fold keeps ids verbatim. The Turn keeps every row and only the Context is trimmed: the turn is the ledger, the context is the lens.

![what the model sees](docs/context.svg)

**At scale.** **Ran on my platform.** A long-running agent got slower and costlier every turn. [confirm] Replaying one recorded conversation showed latency and tokens ramping per turn, where a model problem is flat. [confirm] Identical tool results were re-injected, and the prompt ran volatile first, so the cache never hit. [confirm] Stable to volatile, append only, references not copies: context fell about 10x. [confirm] The long-conversation turn went from 20 to 30 seconds to about 6. [confirm] Budget trim, a window and dedup shipped; summary compaction is not shipped. [confirm]

| Option when the context grows | What breaks | Verdict |
|---|---|---|
| no budget, full history every pass | every turn slower and costlier than the last | no |
| trim the oldest rows every pass | the prefix changes every pass; the cache never hits | no |
| block the repeated tool call | wrong when the data changes | no |
| drop the earlier result, keep the new one | rewrites the prefix; cache miss | no |
| fold once at overflow; repeats become references | one cache break per fold; append only | chosen |

### 2.7 Which tools to offer

Every tool the model is offered costs tokens on every pass and is one more chance to pick wrong.

**Why it matters.** A definition costs about 500 tokens, re-read on every model call. Past a few dozen tools the model confuses look-alikes. The harness builds the tool list, so the fix belongs here.

**In v2.**
- **Built here.** No discovery. An agent's file names its tools, every pass offers that list, and a call outside it returns `DENIED`.
- **Designed here.** A catalog indexed at registration, one search tool the model calls, and the found definitions loaded for the next pass. Under about ten tools, offer all.

**How it works.** At registration the harness lists each tool server once, stores every definition in a catalog, and indexes name, description and parameter names. The first pass offers a few pinned tools plus the search tool; a search answers from the index, filtered by the agent's allowlist, with names and one-liners only, and the next pass adds the found definitions. The found set is saved with the conversation and stays fixed within a turn.

**At scale.** **Ran on my platform.** Today my platform hands the model every imported tool on every call and lists each tool server's catalog live on every turn, with no selection step. [confirm] The design above is my written proposal for that platform, not shipped. [confirm]

| Strategy | Extra model calls | Tokens per pass | Failure mode |
|---|---|---|---|
| all tools every call | none | about 500 per tool; 150k at 300 tools | cost grows with the catalog; wrong picks among look-alikes |
| static allowlist per agent, v2 today | none | 500 times the allowlist; fine under ten | the list grows with the agent and nobody prunes it |
| pre-selection by search | none | 500 times k | a recall miss is unrecoverable; the model never sees the tool |
| model-driven search tool | one, on first use of a capability | pinned plus found, about 2k to 8k | vague descriptions miss and retry |

### 2.8 Routing and capacity

The model is the one shared, rate-limited step, and the loop must not know how it is served.

**Why it matters.** If each agent picks its provider, nobody sees the fleet. One seam is one place to meter, fall back and record.

**In v2.**
- **Built here.** The loop talks to one `LLMProxy` and never learns which provider answered; the rule stub is the only model, and every pass writes one `LLM` row.
- **Designed here.** A router behind `LLMProxy` decides model, provider, admission and failure, and the row carries model asked and model used.

**How it works.** Five decisions per call. Model: the agent pins one or names a class; a class lets the router choose. Provider: allowed, then healthy, then budget left; healthy is a rolling error rate and tail latency per provider over a window, and a tripped breaker drops the provider from the allowed list until a probe passes. Admission: one governor fronts the model, the step every consumer shares; each consumer gets a share, and live calls are admitted up to it. Lane: a consumer may burst above its share while capacity is free; batch runs are admitted whole into free headroom and never paused mid-run. Failure: one fallback inside the policy, the next allowed provider serving the same model class, so a pinned agent never gets another model; then a typed failure to the loop, which owns the turn. The row records model asked, model used and provider used; one adapter per provider owns the wire format and the keys.

![the governor](docs/governor.svg)

| Decision | Decided from | Hidden from the loop |
|---|---|---|
| model | the agent's policy: a pinned model or a class | nothing; model asked and model used go on the row |
| provider | allowed, then healthy, then budget left | wire format, keys, endpoints |
| admission | the consumer's share, then free headroom | the wait |
| failure | one fallback inside the policy, then a typed failure | the retry and the fallback hop |
| lane | live up to the share; batch whole, into headroom | the threshold |

**At scale.** **Ran on my platform.** The first rate limit counted calls, not work: too loose meant incidents, too tight throttled live traffic on an idle GPU, and batch had no door at all. [confirm] The capacity meter and batch admission then ran as the first shared service: each consumer counts its own work per window against its share, the governor enforces the total, and the threshold is configurable, run conservative. [confirm] Routing stayed single-provider, by capacity and pinned-or-class; never across providers by cost, never canaries. [confirm]

### 2.9 Sub-agents

Several specialties need several agents, and each hand-off costs model calls and context.

**Why it matters.** Every multi-agent shape answers two questions: who decides the next step, and who sees what. A wrong default is expensive, and teams build on it before anyone measures.

**In v2.**
- **Built here.** Nothing; v2 keeps sub-agents out of the code.
- **Designed here.** A sub-agent is an agent behind the tool interface. The parent's own tool choice is the router, so delegation costs no extra model call. The child runs its own loop with its own tool set, and only its answer crosses back.

**How it works.** The parent lists the child as a tool whose one input is the goal. When the model picks it, a fresh child orchestrator runs, and its answer is the tool result; nothing in the loop special-cases it. A fresh child per call means no memory across delegations. The orchestrator, not the prompt, enforces depth, passes and one model-call budget shared down the tree; an agent that lists itself hits the depth guard and returns an error. On another host the tool call becomes a network call, and nothing else changes.

| Shape | Who decides | Who sees | Cost per hop |
|---|---|---|---|
| supervisor chat | the model, after a routing call | every agent, the whole transcript | about two calls; context grows near quadratically |
| agent as a tool | the model, through the parent's own tool choice | answers only | one call per delegation; linear |
| fixed plan | the plan, at design time | each step, its own input | zero routing tokens; replayable |

**At scale.** **Ran on my platform.** I picked a framework's group chat as the default without running the cost math; the next release showed up to twice the model calls, with every agent seeing the whole monologue. [confirm] I stopped new teams on it, and moving the known paths to a fixed plan cut about 30 to 40 percent of median latency, measured in traces. [confirm] The cost was a missed release, caught before production. [confirm]

### 2.10 Measuring the harness

A loop that runs is not a loop that works, and the code cannot say which.

**Why it matters.** Good is two questions, never one score: did the harness do its job, and was the answer right. The first is facts in the step rows, scored by code; the second needs a reference or a judge.

**In v2.**
- **Built here.** Each run logs one row per step to `runs/<runId>.jsonl`. `evals/golden.json` holds twelve cases: question, agent, expected tools, answer phrase, max passes. `eval` replays each case in a fresh orchestrator and prints one row per case and a footer split harness vs agent. Two failures stay in on purpose, so the numbers move when the matcher improves. A regression test asserts the harness floor.
- **Designed here.** Live needs what this repo cannot hold: a store of every step row, a job that samples rows and asks a judge, and rates per agent version. The rows the harness already writes are the export. Build or buy: LangSmith is the buy, OpenTelemetry the neutral transport, neither built here.

**How it works.** `tool_choice` compares the expected tools to the TOOL rows. `stopped_by_done` means no CUT_SHORT row. `errors_surfaced` means the answer names any non-OK TOOL row. `answer` is a normalized contains, the slot a judge would take. Live, the same signals become rates per agent version, compared with the last version or last week.

![two loops](docs/eval-loops.svg)

| | Before ship | Live |
|---|---|---|
| when | before promotion, on demand | always on, on a schedule |
| answer key | yes, the golden set | none |
| who scores | code over the rows; contains on the answer | code for the rates; a sampled judge for quality |
| what it catches | a change that breaks a known case | drift nobody wrote a case for |
| where it runs | `eval` in this repo; a batch job at scale | a job over the step-row store, not in this repo |

**At scale.** **Ran on my platform.** Golden sets were recorded from real sessions with an expected trace, tool calls and arguments, and replayed as a batch job before promotion, on demand. [confirm] A scheduled judge sampled production turns and published scores per agent. [confirm] A model swap tripled the retrieval query length while answers held, and only the expected trace caught it. [confirm] The expected trace was stored, but the scorer never shipped; this runner is that scorer. [confirm]

## 3 · v2 at a glance

v2 is the live loop plus a step log, a clock on every tool call, and two seams: `LLMProxy` for the model and the tool interface for everything else.

![v2](docs/v2-at-a-glance.svg)

| Issue | v2 | Status | Command |
|---|---|---|---|
| 4 | a row per step, write before act; resume | **Built here** | `run`, `resume`, `replay` |
| 4 at scale | queue, lease, ack after persist, dead-letter | **Designed here** | |
| 5 | one batch; rows in request order | **Built here** | `run --slow` |
| 2 | thread, clock, allowlist: TIMEOUT, ERROR, DENIED | **Built here** | `run --agent chaos` |
| 2 at scale | separate process; container with two doors | **Designed here** | |
| 3 | a status on every row; the stop is a CUT_SHORT row | **Built here** | `run` |
| 7 | measured; two deliberate FAILs | **Built here** | `eval` |
| 8 | one Conversation; repeat calls DEDUPED | **Built here** | `run`, two questions |
| 6 | agents and tools are JSON files | **Built here** | `agents`, `tools` |
| 1, 9, 10 | the stub stays a stub; a router behind LLMProxy; an agent as a tool | **Designed here** | |

![the loop at scale](docs/at-scale.svg)

A loop of this shape runs on my platform behind an API, an SQS-style queue with a lease, and a governor on one shared model. [confirm]

## 4 · Honesty ledger

1. The model is a stub; right tool means right keyword.
2. The matcher mis-picks, and the eval shows it.
3. A thread with a clock is not a sandbox.
4. The in-flight call runs at least once.
5. Tokens are not counted; passes and milliseconds are the bill.

## 5 · What I would build next, in order

1. The queue with a lease behind the run store: a dead worker's run gets picked up.
2. The SSE endpoint over the same log: a reconnect replays from its last row.
3. A context budget with one fold: tokens per turn stay flat.
4. The sampled judge over the step rows: only a judge scores the answer.
5. A real model behind `LLMProxy`: one method, and the eval then scores a real matcher.
