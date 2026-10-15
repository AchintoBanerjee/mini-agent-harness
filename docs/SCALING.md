# Scaling and performance

[The pods](#the-pods) · [The stream](#the-stream) · [The GPU](#the-gpu) · [The tools and the state](#the-tools-and-the-state) · [Performance: what the model sees](#performance-what-the-model-sees) · [Out of scope](#out-of-scope)

The loop itself is cheap. What has to scale is around it: the pods that run the loop, the connections that carry the answer back, the one GPU every tenant shares, and the context the model reads on every call. Durability already moved the loop behind a queue; this page builds on that picture.

## The pods

- A worker pod holds a run, not a request: a turn lives thirty to sixty seconds and makes five to ten model and tool calls in a row. Memory per in-flight run, not CPU, is the real cost of a pod.
- The lever is queue depth, not request count: backlog grows, add workers; the queue drains, remove them. That is the Kubernetes autoscaler reading the queue instead of CPU.
- The in-flight run survives the pod because of the lease and the saved steps. Nothing new here; it is the durability design doing its job.

## The stream

The client holds an open connection on one API node while the loop runs on a worker somewhere else, and the worker never knows which node that is.

![the stream across stateless API nodes](scaling-stream.svg)

- **API nodes are stateless** behind the load balancer. The only thing pinned is the open stream, to the node that holds it, for as long as it is open.
- **The worker publishes every step by job id** onto Redis. It does not address a node.
- **The node holding the connection subscribes by job id** and forwards each step as a Server-Sent Event, the span number as the event id.
- **Reconnect anywhere:** the client sends the last id it saw, the new node reads the tail from the store and then follows the bus. Nothing is lost, nothing is sent twice.
- **A dropped connection is not a cancel.** The worker keeps going; cancel is its own request, checked between steps, and a tool call in flight finishes.
- **The bus is needed on exactly one leg,** worker to the node holding the connection. The model and the tools stay point-to-point with a retry.

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| Redis pub/sub by job id | the worker publishes, the holding node subscribes and forwards; replay comes from the store | fixes location: the loop runs anywhere, the stream lives anywhere; the cheapest piece to add | nothing published while the client was away is on the bus; reconnect is a store read |
| Redis Streams per job id | the worker appends with ids; the node reads from the client's last id; a TTL after done | fixes location and the gap: replay from the last id without a store read, de-duplication by id for free | a stream per job in Redis until its TTL; ids and de-duplication on the client; the finished turn still comes from the store after the TTL |
| poll the store | the node or the client reads rows past the last id on a timer | no new infrastructure; the store is the truth anyway | latency is the poll interval; a store read per poll per open connection |

I pick pub/sub for the live leg because it is the smallest thing that frees the loop from the node, and the store for replay because the spans are already there. Streams is the step up when reconnects are common enough that the store read on every one of them matters.

**What I ran.** The first shape streamed the turn on the same request that started it, one node running the whole loop inside that request's timeout; a mid-turn drop lost the in-flight events and kept what was persisted at the end. The design above is the next shape from my own note: a thin API layer that holds the connection and subscribes to tokens by run id over Redis pub/sub, the loop on queued workers. Replay from the last id is the designed fix, not shipped.

## The GPU

The GPU does not autoscale. It is a fixed number of cards shared by every tenant, so the job is to use all of it and never let it fall over.

![the governor in front of the GPU](scaling-gpu.svg)

- **The opener.** Open the GPU to everyone and one busy tenant slows the model for all of them; a slow model is a slow platform. Close it and you pay for an idle GPU while customers wait for their batch. "Let it in and it slowed everyone's live chat. Keep it out and the GPU sat idle while customers waited."
- **The catch.** The decision needs the GPU's utilization, and the cloud provider's number is a reporting metric: minutes late, behind a rate-limited metrics API. You call it for reporting, not for a decision made per call. "Delayed is useless for the gate, perfect for the calibration."
- **So the meter computes saturation itself,** per window, from what every call shows: calls, input tokens, output tokens, weighted. The weights come from running the GPU to saturation and reading what it processed when the time started going up, keyed by GPU type and model family. Output tokens carry most of the weight: generating is the cooking, reading the input is cheap.
- **Gate one, the fair share.** Every tenant gets a share of that number; within the share a live call is admitted.
- **Gate two, the room.** Over the share, a call is admitted only while the meter says the GPU is under the threshold. Batch is admitted the same way, a whole run at a time, only into headroom, and once admitted it runs to the end; the wait between calls is the brake, a run is never paused mid-flight.
- **The threshold is configurable per cluster, and we run it conservative,** because the cost of a run is an estimate and the meter is a window behind.
- **The guarantee for batch:** wait for headroom up to your deadline, then run through the live channel under your tenant's share. Worst case is what you had before. Rising deadline breaches are the signal to buy more GPU, with the evidence in hand.

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| the provider's utilization gauge | each pod polls it and gates on it | nothing to build | minutes late, rate limited; a reporting number, not one you can gate a call on |
| a rate limit that counts calls | calls per tenant per window in each pod | simple, needed anyway | counts calls, not work: too loose and one output-heavy tenant tips the GPU, too tight and live is throttled on an idle GPU; no door for batch at all |
| a dedicated GPU for batch | a second cluster for batch jobs | isolation by construction | idle whenever there is no batch: the same waste, mirrored |
| run batch at night | a fixed off-peak window | no admission logic | midnight here is daytime elsewhere; there is no night |
| pause a running job when the GPU tips | preempt batch on a spike | the fastest brake | mid-flight state; too invasive, and my own first idea; the run became the unit, admitted whole, never cancelled |
| own meter, one shared service in front of the GPU | the formula above, fair share for live, batch into headroom under a conservative threshold | a fresh number nobody had; batch in the valleys, day or night; live protected; no new GPUs | the first shared service in a single-tenant estate; calibration per GPU type and model; the threshold has to stay conservative |

I pick the meter and the shared service, because a pod only ever sees its own slice and only something in front of the whole GPU can have the number.

**What I ran.** Nobody asked for a capacity system; I drove it, the platform's first shared service. The first rate limit counted calls, not work, and getting the number right took incidents: too loose one day, too tight the next. We wanted the provider's utilization and could not use it, so we calculated it ourselves and ran the GPU to saturation to get the formula; when the provider swapped the GPU type under a cluster the numbers fell apart, and the GPU type became part of the calibration. Live got a fair share per tenant on that number. Batch got a queue, admitted into headroom under a threshold we run conservative, a run at a time, never cancelled; I wanted to pause runs mid-flight, the team said too invasive, and they were right. The second gate for live, over the share into the room, is designed on the same number and not built. My part: the direction, the business case, and the admission side, the queue, the worker, the advisor and the runbooks.

## The tools and the state

- **Tools.** One question is several tool calls and an authorization check for each of them, so the downstreams see ten to fifty times the traffic the same work makes through the UI. They were sized for a human clicking, not an agent looping. Every tool call gets a timeout, and the next fixes are a pooled client, a per-tool limit and a circuit breaker on the hottest path.
- **State.** The step row is the ledger; telemetry is written behind it, never one fat synchronous write per step. The scar: with no telemetry store, the relational state store quietly absorbed the observability writes too, tens of rows and a hundred kilobytes per conversation, and write latency became conversation latency. The right answer is a stream into a column store, separated from the state store on day one.
- **Tenancy.** The tenant id rides on the turn and on every call. Where the tenant owns the infrastructure, isolation is routing. At the one shared step, the GPU, isolation is admission: that is the governor above.

## Performance: what the model sees

A model call is stateless; on every pass the harness decides what the model reads, and that decision is most of the cost of a turn.

- **The prefix cache.** The model server reuses the longest unchanged prefix of the prompt; one changed token invalidates everything after it. So prompt order decides whether anything is ever reused: stable first, volatile last, and only ever append. System prompt, tool definitions in a fixed order, the history as it happened, the new turn at the bottom. On a big prompt the prefix hit is about half the turn.
- **The incident.** Every question in a conversation was slower and more expensive than the one before. Replaying one recorded conversation and plotting latency and tokens per turn showed a rising line; a model problem is flat, so the problem was ours. The prompt was ordered volatile-first, so the cache never hit, and by turn sixteen ten identical tool results had been re-injected, most of the prompt. Reordering, append-only, and references instead of copies cut context to about a tenth; the long-conversation turn went from twenty or thirty seconds to about six. Trim, a window and dedupe shipped; summary compaction is the direction.
- **Dedupe of tool results.** The agent re-checks because it cannot tell whether the data changed. Blocking the repeat is wrong when the data does change; dropping the old result rewrites the prefix and kills the cache. So the repeat becomes a reference, "unchanged, see above", and the old result is never touched. Only where the data is stable. This is built in v2.
- **Which tools to offer, once the catalog reaches hundreds.** Every tool definition rides in every model call, about five hundred tokens each, and past a few dozen the model mis-picks among look-alikes. My platform today hands the model every imported tool on every call and lists each tool server live on every turn. The proposal: build the catalog once at registration and index it; offer the model a few pinned tools plus one search tool; when it searches, answer from the index with names and one-liners, load the found definitions for the next call, and remember them with the conversation. The honest trade is one extra model call the first time a capability is needed, and a handful of definitions instead of hundreds on every call after. Under about ten tools, just load them.
- **Multi-agent leakage.** Each agent sees only what it needs; the classifier gets just enough to route. A sibling's reasoning in your prompt is cost, and it can derail you. Sub-agents are built that way here: the child gets the goal and its own tools, the parent gets the answer.

| Tool catalog | How it works | What you get | What it costs |
|---|---|---|---|
| load everything (today) | every imported tool in every call | fine under about ten tools | five hundred tokens per tool per call; mis-picks past a few dozen |
| threshold plus a search tool, no new infrastructure | keyword scoring over names and descriptions in the orchestrator; a few pinned tools plus `search_tools` | starts working now | keyword scoring flatters hand-written descriptions |
| catalog plus a hybrid index (the target) | the catalog indexed at registration, keyword plus vector, the agent's allowlist as the filter | a search in tens of milliseconds with no model call; a handful of definitions per call | one extra model call on first use; a refresh job; the index to run |

## Out of scope

Not built in this repo, said once: Redis and the multi-node stream, the inference gateway and the governor, the context budget and the summary fold, the tool catalog and its index. Each is a design above with the seam it plugs into named: the API layer, the model interface, the context builder, the tool registry.
