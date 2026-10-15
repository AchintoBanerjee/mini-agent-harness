# Scaling

[Scaling the orchestrator pods, CPU](#scaling-the-orchestrator-pods-cpu) · [Scaling the persistent connection](#scaling-the-persistent-connection) · [Scaling the GPU, the one thing that does not autoscale](#scaling-the-gpu-the-one-thing-that-does-not-autoscale) · [Scaling the tools and the state](#scaling-the-tools-and-the-state) · [What v2 implements](#what-v2-implements)

The loop itself is cheap. What has to scale is around it: the pods that run the loop, the connections that carry the answer back, the one GPU every tenant shares, and the context the model reads on every call. Durability already moved the loop behind a queue; this page builds on that picture.

## Scaling the orchestrator pods, CPU

- Nothing new here, and nothing in v2: the API pods autoscale on request load, the orchestrator pods on queue depth, a few kept warm for spikes. That is what my platform ran once the queue was in: the lever became queue depth, not request count.


## Scaling the persistent connection

- The user's connection is open on one API pod. Their turn runs on an orchestrator pod.
- There is no direct line between the two: the queue sits in the middle, so the orchestrator never knows which pod holds the connection.
- If the connection drops and comes back, it may be on any pod. The orchestrator has to reach whichever pod holds it, every time.

![the stream across pods](scaling-stream.svg)

- **A Redis Stream per job id.** The orchestrator appends each step to it as the step happens. The API pod holding the connection reads the stream from the last id the client saw and forwards each entry as a Server-Sent Event.
- **Reconnect on any pod.** The client sends the last id it saw; the new pod reads from there. Nothing lost, nothing twice. A TTL clears the stream after the turn is done.
- **Why a Stream and not pub/sub.** Pub/sub has no buffer: a client away for a second misses what was published. A Stream keeps the entries with ids, so the gap is replayed from Redis. Other queues do this too, but they are heavier, and my platform already runs a Redis cluster.
- **The stream is for the live experience.** The orchestrator writes every step and the final answer to the store anyway. If the stream fails, the API or the client gets the answer from the store: that works, and it is a bad experience. The stream is what makes it a good one. v2 has the same shape on one machine: the worker pushes each event to the API over HTTP, the API holds the run's stream in memory, and the store is read only when the stream is gone.
- **A dropped connection is not a cancel.** The turn keeps running; cancel is its own request.

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| Redis pub/sub by job id | the orchestrator publishes, the holding pod forwards | the smallest piece that frees the turn from the pod | no buffer: anything published while the client was away is gone |
| Redis Stream per job id | the orchestrator appends with ids; the holding pod reads from the client's last id; a TTL after done | the buffer: replay from the last id; de-duplication by id for free | a stream per job in Redis until its TTL; ids on the client |
| poll the store | the client reads rows past its last id on a timer | no new infrastructure | latency is the poll interval; a store read per poll per connection |

I pick the Redis Stream: my platform already runs a Redis cluster, and the buffer is what I need. It is the standard shape for streaming a long job: a run id, a stream per run, any node replays from the last id.

**What I ran, on my platform in production**

- The first shape streamed the turn on the same request that started it: one pod ran the whole loop inside that request's timeout.
- A mid-turn drop lost the in-flight events; what was persisted at the end was kept.
- The next shape shipped with the queue: a thin API layer subscribing by run id over Redis pub/sub, which carries the stream to whichever pod holds the connection but has no buffer, so a client away for a second missed what was published. The Stream with replay from the last id is the designed fix, not shipped.

## Scaling the GPU, the one thing that does not autoscale

The GPU does not autoscale. It is a fixed number of cards shared by every tenant, so the job is to use all of it and never let it fall over.

![the governor in front of the GPU](scaling-gpu.svg)

- **The problem.** Open the GPU to everyone and one busy tenant slows the model for all of them; a slow model is a slow platform. Close it and you pay for an idle GPU while customers wait for their batch. On my platform, in production, we hit both: let it in and it slowed everyone's live chat, keep it out and the GPU sat idle while customers waited.
- **The catch.** The decision needs the GPU's utilization, and no number you can fetch is good enough. The cloud provider's gauge is a reporting metric, minutes late behind a rate-limited API, and often not there at all. The serving engine's own utilization depends on the KV-cache memory as much as on the compute. A number computed from what you sent is the one you can gate on.
- **The meter.** Every model call goes through one wrapper in front of the GPU, and the wrapper does the measurement and the math: calls, input tokens, output tokens, per window. The number exists for every call; nothing is fetched.
- **The formula.** Load is calls plus input tokens plus output tokens, each against the saturated row: what the GPU processed per minute in real saturated minutes. Weighted 0.1 for calls, 0.2 for input, 0.7 for output, so the number reads exactly 100 at saturation. Generating is the cooking; reading the input is cheap. Those weights are the defaults. The saturation test sets the row, and can re-set the weights, per GPU type and model family: push load until the time to first token starts to climb, and take that minute as 100. Keyed by GPU type and model family, so it transfers to every cluster of that shape. Uncalibrated means observe only, never guessed.
- **The decision.** Admit when the window's number plus the estimated cost of what you are admitting stays under the threshold. The same number routes: the routing page picks, within the agent's validated set, the nearest permitted region whose meter is under the threshold. Every decision stores the number, the threshold and the allowance it saw, so a deny at three in the morning is explained at nine by reading, not by reproducing.
- **After.** Compare the meter's number with the provider's gauge once a day. A late number is useless for the gate and perfect for the calibration.
- **Gate one, the fair share.** Every tenant gets a share of the GPU; within the share a live call is always admitted.
- **Gate two, the room.** A tenant that is not using its share leaves room. That capacity is not set aside anywhere; it is simply wasted. So a call over its tenant's share can be admitted too, but only while the room is substantial: the meter's number under the threshold. For batch this runs; for live it is designed on the same number, below.
- **Why the threshold.** It is the margin: a tenant coming back for its own share must never queue behind someone else's burst.
- **Batch.** A batch run goes through gate two only: admitted a whole run at a time, and only into the room. Once admitted it runs to the end. The brake is the wait between calls; a run is never paused mid-flight. On my platform this is what runs.
- **The threshold is configurable per cluster, and we run it conservative,** because the cost of a run is an estimate and the meter is a window behind.
- **The guarantee for batch:** a run waits for room up to its deadline; if the deadline arrives first, it runs through the live channel under its tenant's share. The worst case is what batch had before the governor. Rising deadline breaches are the signal to buy more GPU, and the breaches themselves are the evidence.

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| the provider's utilization gauge | each pod polls it and gates on it | nothing to build | minutes late, rate limited; a reporting number, not one you can gate a call on |
| a rate limit that counts calls | calls per tenant per window in each pod | simple, needed anyway | counts calls, not work: too loose and one output-heavy tenant tips the GPU, too tight and live is throttled on an idle GPU; no door for batch at all |
| a dedicated GPU for batch | a second cluster for batch jobs | isolation by construction | idle whenever there is no batch: the same waste, mirrored |
| run batch at night | a fixed off-peak window | no admission logic | midnight here is daytime elsewhere; there is no night |
| pause a running job when the GPU tips | preempt batch on a spike | the fastest brake | mid-flight state; too invasive, and my own first idea; the run became the unit, admitted whole, never cancelled |
| own meter, one shared service in front of the GPU | the formula above, fair share for live, batch into headroom under a conservative threshold | a fresh number nobody had; batch in the valleys, day or night; live protected; no new GPUs | the first shared service in a single-tenant estate; calibration per GPU type and model; the threshold has to stay conservative |

I pick the meter and the shared service, because a pod only ever sees its own slice and only something in front of the whole GPU can have the number.

**What I ran, on my platform in production**

- Nobody asked for a capacity system. I drove it: the platform's first shared service.
- The first rate limit counted calls, not work. Getting the number right took incidents: too loose one day, too tight the next.
- We wanted the provider's utilization and could not use it. So we calculated it ourselves: we ran the GPU to saturation to get the formula. When the provider swapped the GPU type under a cluster the numbers fell apart, and the GPU type became part of the calibration.
- Live got a fair share per tenant on that number.
- Batch got a queue, admitted into headroom under a threshold we run conservative, a run at a time, never cancelled. I wanted to pause runs mid-flight; the team said too invasive, and they were right.
- The second gate for live, over the share into the room, is designed on the same number and not built.
- My part: the direction, the business case, and the admission side: the batch queue, the worker that admits a run, the advisor that answers admit or wait from the meter's number, and the runbooks.

## Scaling the tools and the state

- **Tools.** On my platform, in production, one question was several tool calls and an authorization check for each of them, so the downstreams saw ten to fifty times the traffic the same work made through the UI. They were sized for a human clicking, not an agent looping. v2 has the timeout on every tool call ([a clock on every tool call](V2_FEATURES.md#a-clock-on-every-tool-call)). The pooled client, the per-tool limit and the circuit breaker on the hottest path are the design, not built here.
- **State.** The step row is the ledger. Telemetry is written after it, off the request path, never as one fat synchronous write per step; in v2 this is design, not built. On my platform, in production, we learned this the hard way. We had no telemetry store, so the observability writes went into the same relational store as the state, synchronous in the loop: tens of rows and about a hundred kilobytes per conversation, and write latency became conversation latency. The right answer is a stream into a column store, separate from the state store from day one.
- **Tenancy.** Design, nothing in v2: the tenant id rides on the turn and on every call. On my platform each tenant runs on its own stack, so isolation is which stack a request is routed to. At the one shared step, the GPU, isolation is admission: that is the governor above.

## What v2 implements

| Part | In v2 | Design only |
|---|---|---|
| the pods | nothing: two processes on one laptop | autoscaling on load and queue depth; what my platform ran |
| the stream across pods | built on one machine: the worker pushes each event over HTTP, the API holds the run's stream, a reconnect replays from the last id, the store only when the stream is gone | the Redis Stream per job id across API pods |
| the GPU governor | nothing: the model is a stub | the meter, the fair share for live and batch into the room: what my platform ran; the room for live over its share: designed on the same number |
| tools and state | the clock on every tool call | the pooled client, the per-tool limit and the circuit breaker; telemetry into a column store; the tenant id on every row |

Each design plugs into a seam v2 already has: the API layer and the model interface. Time and tokens have their own page: [PERFORMANCE.md](PERFORMANCE.md).
