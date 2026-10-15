# Mini agent harness, v2

The harness from the live round (`../v1`, frozen) extended in place, with HTTP as its face.
A question is a turn (its id is the job id) in a conversation; `POST /runs` writes the turn
row and its queue message and answers with the id at once; a `worker` process runs the loop,
writing every span to `harness.db` (SQLite) before acting on it and pushing every step and
every piece of the answer to the server, which holds the run's stream; `GET /turns/{id}/events`
forwards that stream from the last id the client saw. A worker that dies loses its lease and
the next worker resumes the turn from its last saved span. A runaway tool is cut off at the agent's timeout,
independent tool calls run together, a repeated call inside a conversation is deduped, an
agent can be a tool (its loop runs inside the tool call, its rows nested in the same turn),
and a golden set scores the harness itself. The model is a stub (`RuleBasedLLM`): no network,
no keys. The high-level design is `../docs/V2_HLD.md`; the queue, the lease and the dead
worker are `../docs/DURABILITY.md`; an agent as a tool is `../docs/SUBAGENTS.md`; the design
write-up is `../DESIGN.md`.

## Build and test

```
cd v2
mvn -q test       # 113 tests, about 10 s with Maven's own start; tests use the in-memory store or a temp SQLite file
mvn -q compile    # classes to target/classes, Jackson and sqlite-jdbc (the two dependencies) to target/lib
```

## Run it

Everything runs from `v2/`, where `agents/`, `tools/`, `evals/` and `harness.db` live. On
JDK 24+ put `--enable-native-access=ALL-UNNAMED` after `java`, or the SQLite driver's native
load prints a warning; JDK 21 needs nothing. `../demo.sh` compiles v2 and runs the commands
below in this order from an empty `harness.db`; `../docs/demo-output.txt` is its saved output
(made on JDK 25, so the flag shows). Every block here is pasted from a run of that script: the
sub-agent blocks from the committed file, the others from the run before it. Ids (`r-` or `c-`
plus 4 hex), ports, pids and milliseconds vary from run to run; nothing else does.

```
java -cp "target/classes:target/lib/*" harness.cli.Main serve [--port 8080]
java -cp "target/classes:target/lib/*" harness.cli.Main worker [--once] [--visibility-ms 2000] [--crash-after N] [--slow] [--api http://localhost:8080]
```

`serve` is the JDK's own `HttpServer` on localhost (`--port 0` picks a free one); it runs no
loop. `worker` takes turns off the queue in `harness.db` and runs them; `--api` is the server
it pushes each event of a turn to (default `http://localhost:8080`), `--slow` gives every
lookup 300 ms and every token 40 ms of simulated latency. The demo starts one of each in the
background:

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main serve --port 0
listening on http://localhost:58833 · agents/ tools/ harness.db · POST /runs queues a turn for a worker · ctrl-c stops it
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker
worker pid 31582 · lease 2000 ms · polling every 500 ms, ctrl-c stops it · harness.db
```

| method | path | what it does | returns |
| --- | --- | --- | --- |
| GET | `/agents` | the agent files | `200`, a list |
| GET | `/agents/{name}` | one agent file | `200`; `404` |
| GET | `/tools` | the tool files | `200`, a list |
| POST | `/runs` | `{"agent","question"}`: a new conversation and its first turn, QUEUED; `{"conversationId","question"}`: the next turn of that conversation | `202 {"turnId","jobId","conversationId","status"}`; `400` |
| GET | `/turns/{id}` (or `/runs/{id}`) | the turn row (status, `claimedBy`, `attempts`, the answer when DONE) and its spans | `200`; `404` |
| POST | `/turns/{id}/events` | the worker's push: `{"event":"step"|"token"|"done","data":{...}}`, appended to the run's stream the server holds | `204`; `404`; `400` |
| GET | `/turns/{id}/events` | SSE: the run's stream from `Last-Event-ID` or `?after=N`: each `step`, each `token`, then `done`; the saved rows once when the stream is gone | `200 text/event-stream`; `404` |
| POST | `/evals` | runs `evals/golden.json` in the server, every case on its own in-memory store | `200`, the table as `cases` and as `lines` |

JSON in and out, one virtual thread per request, errors as `{"error": "..."}`. `jobId` repeats
`turnId`: the turn's id is the job id.

### a turn: `POST /runs`, then `GET /turns/{id}`

```
$ curl -s -X POST http://localhost:58833/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
{
  "turnId" : "r-478b",
  "jobId" : "r-478b",
  "conversationId" : "c-2674",
  "status" : "QUEUED"
}
(202 came back at once with the turn QUEUED; the worker took it, and one second later the turn row says DONE, who ran it, the answer, and the spans:)
$ curl -s http://localhost:58833/turns/r-478b
{
  "id" : "r-478b",
  "conversationId" : "c-2674",
  "agent" : "support",
  "question" : "What is the status of order ORD-1?",
  "answer" : "Order ORD-1: SHIPPED, shipment SHP-1",
  "status" : "DONE",
  "claimedBy" : "31582",
  "attempts" : 1,
  "createdAt" : 1792034458861,
  "updatedAt" : 1792034459271,
  "spans" : [ {
    "turnId" : "r-478b",
    "span" : 1,
    "pass" : 1,
    "kind" : "LLM",
    "request" : "pass 1",
    "response" : "ToolCall[toolName=order_status, input={orderId=ORD-1}]",
    "status" : "OK",
    "ms" : 4,
    "attrs" : {
      "question" : "What is the status of order ORD-1?",
      "tool" : "order_status",
      "orderId" : "ORD-1"
    }
  }, {
    "turnId" : "r-478b",
    "span" : 2,
    "pass" : 1,
    "kind" : "TOOL",
    "request" : "order_status {orderId=ORD-1}",
    "response" : "Order ORD-1: SHIPPED, shipment SHP-1",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "tool" : "order_status",
      "orderId" : "ORD-1",
      "key" : "r-478b/1"
    }
  }, {
    "turnId" : "r-478b",
    "span" : 3,
    "pass" : 2,
    "kind" : "LLM",
    "request" : "pass 2",
    "response" : "Done[]",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "question" : "What is the status of order ORD-1?"
    }
  } ]
}
```

The LLM span's `attrs` carry the decided call, so a resume never asks the model for it again;
the TOOL span's `key` is the turn id plus the span that decided it, the idempotency key a tool
that writes would use.

### the stream: every step and every piece of the answer as it happens, then `done`; a reconnect gets only the tail

```
$ curl -sN --max-time 5 http://localhost:58833/turns/r-478b/events
id: 1
event: step
data: {"turnId":"r-478b","span":1,"pass":1,"kind":"LLM","request":"pass 1","response":"ToolCall[toolName=order_status, input={orderId=ORD-1}]","status":"OK","ms":4,"attrs":{"question":"What is the status of order ORD-1?","tool":"order_status","orderId":"ORD-1"}}

id: 2
event: step
data: {"turnId":"r-478b","span":2,"pass":1,"kind":"TOOL","request":"order_status {orderId=ORD-1}","response":"Order ORD-1: SHIPPED, shipment SHP-1","status":"OK","ms":0,"attrs":{"tool":"order_status","orderId":"ORD-1","key":"r-478b/1"}}

id: 3
event: step
data: {"turnId":"r-478b","span":3,"pass":2,"kind":"LLM","request":"pass 2","response":"Done[]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?"}}

event: done
data: {"turnId":"r-478b","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1"}

(a client that reconnects sends the last id it saw:)
$ curl -sN --max-time 5 -H 'Last-Event-ID: 2' http://localhost:58833/turns/r-478b/events
id: 3
event: step
data: {"turnId":"r-478b","span":3,"pass":2,"kind":"LLM","request":"pass 2","response":"Done[]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?"}}

event: done
data: {"turnId":"r-478b","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1"}
```

The loop is the producer. The worker pushes each event to the server as the loop raises it
(`POST /turns/{id}/events`: a `step` with its span, a `token` with a piece of the answer as the
model produces it, `done`); the server holds the run's stream in memory (`RunStreams`: in
production a Redis Stream per job id, so any API node can read it; here a map in the server's
process); the client reads it from the last id it saw, so a reconnect gets only the tail. The
store is read only when the stream is gone (60 s after `done`, after a server restart, or when
no worker fed it): then the saved rows go out once, the span number as the id, then `done`. A
client that disconnects does not stop the turn. Opened right after a `POST` on `chaos`, it held
the response for the 1000 ms the hanging tool took to be cut off (`TIMEOUT`, in `demo-output.txt`).

### a follow-up turn in the same conversation: the repeated call is not run again

The model sees the earlier turns as `Q: ... -> A: ...` lines. A call with the same tool and
input as an earlier OK span anywhere in the conversation is not run: its span is `DEDUPED` and
points at the original. Span numbers run on through the conversation, so the pointer is one number.

```
$ curl -s -X POST http://localhost:58833/runs -d '{"conversationId":"c-2674","question":"What is the status of order ORD-1, again?"}'
{
  "turnId" : "r-45b2",
  "jobId" : "r-45b2",
  "conversationId" : "c-2674",
  "status" : "QUEUED"
}
```

The worker's log for that turn, and for the two before it (the server ran no loop):

```
received r-478b: attempt 1 · agent support · "What is the status of order ORD-1?"
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 4 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
  3  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
done r-478b · 3 spans · 59 ms · DONE in harness.db · acked · handler calls: order_status=1
received r-8659: attempt 1 · agent chaos · "Please hang while checking ORD-1"
  1  LLM   pass 1 -> ToolCall[toolName=hang_forever, input={orderId=ORD-1}]  [OK 0 ms]
  2  TOOL  hang_forever {orderId=ORD-1} -> cut off after 1000 ms  [TIMEOUT 1000 ms]
  3  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Tool hang_forever TIMEOUT: cut off after 1000 ms
done r-8659 · 3 spans · 1019 ms · DONE in harness.db · acked · handler calls: order_status=1
received r-45b2: attempt 1 · agent support · "What is the status of order ORD-1, again?"
  4  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 0 ms]
  5  TOOL  order_status {orderId=ORD-1} -> unchanged, see step 2  [DEDUPED 0 ms]
  6  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
done r-45b2 · 3 spans · 9 ms · DONE in harness.db · acked · handler calls: order_status=1
```

`handler calls: order_status=1` after the third turn: one real call for the two questions. A
tool that throws gets `ERROR` the same way the hang got `TIMEOUT`, and a tool the agent does
not list gets `DENIED` without running.

### two tools in one pass run together: a worker started with `--slow`

The worker runs the tools, so the simulated latency is its flag: `--slow` gives every `lookup`
300 ms and every token 40 ms. Two lookups in one pass take one sleep of wall time, not two, and
their spans land in request order with their own `ms`; a stream opened before the worker starts
shows the answer arriving word by word (`demo-output.txt`).

```
$ curl -s -X POST http://localhost:58833/runs -d '{"agent":"support","question":"Status of order ORD-2 and is product PRD-1 available?"}'
{
  "turnId" : "r-b722",
  "jobId" : "r-b722",
  "conversationId" : "c-7590",
  "status" : "QUEUED"
}
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --slow
worker pid 31629 · lease 2000 ms · once · slow: every lookup sleeps 300 ms (latency simulated) · harness.db
received r-b722: attempt 1 · agent support · "Status of order ORD-2 and is product PRD-1 available?"
  1  LLM   pass 1 -> ToolCalls[calls=[ToolCall[toolName=order_status, input={orderId=ORD-2}], ToolCall[toolName=stock_status, input={productId=PRD-1}]]]  [OK 4 ms]
  2  TOOL  order_status {orderId=ORD-2} -> Order ORD-2: PROCESSING  [OK 302 ms]
  3  TOOL  stock_status {productId=PRD-1} -> Product PRD-1: 42 units in stock  [OK 302 ms]
  4  NOTE  ran 2 tools together: wall 305 ms, work 604 ms  [OK 305 ms]
  5  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-2: PROCESSING
Product PRD-1: 42 units in stock
done r-b722 · 5 spans · 368 ms · DONE in harness.db · acked · handler calls: order_status=1, stock_status=1
```

### an agent as a tool: the concierge hands the shipment question to the logistics agent

A tool of type `agent` names another agent. Its call runs that agent's own loop, the same
`Orchestrator` class, inside the tool call: the goal is the child's question, the child's tools
are its own agent's, its rows go into the same turn with `parent` (the span that delegated) and
`depth` in `attrs`, and only its answer comes back, as the parent's `AGENT` row. The depth is
capped at 2 and one budget of model calls (`maxModelCalls` on the agent file, default 20) is
shared by parent and children. The whole section is `../docs/SUBAGENTS.md`.

```
$ curl -s -X POST http://localhost:65286/runs -d '{"agent":"concierge","question":"Where is shipment SHP-1 for my order?"}'
{
  "turnId" : "r-2bb0",
  "jobId" : "r-2bb0",
  "conversationId" : "c-1e17",
  "status" : "QUEUED"
}
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once
worker pid 34457 · lease 2000 ms · once · harness.db
received r-2bb0: attempt 1 · agent concierge · "Where is shipment SHP-1 for my order?"
  1  LLM   pass 1 -> ToolCall[toolName=ask_logistics, input={goal=Where is shipment SHP-1 for my order?}]  [OK 4 ms]
    2  LLM   pass 1 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]  [OK 0 ms]
    3  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 0 ms]
    4  LLM   pass 2 -> Done[]  [OK 0 ms]
  5  AGENT  ask_logistics {goal=Where is shipment SHP-1 for my order?} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 5 ms]
  6  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
done r-2bb0 · 6 spans · 71 ms · DONE in harness.db · acked · handler calls: shipping_status=1
```

The child's rows are indented; `GET /turns/r-2bb0` shows the same six spans, the child's with
`"parent" : "1"` and `"depth" : "1"` in `attrs` (`demo-output.txt`). An agent that lists itself as
a tool (`loop`, tool `ask_loop`) goes three loops deep and no further:

```
$ curl -s -X POST http://localhost:65286/runs -d '{"agent":"loop","question":"loop, please"}'
{
  "turnId" : "r-000b",
  "jobId" : "r-000b",
  "conversationId" : "c-58b7",
  "status" : "QUEUED"
}
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once
worker pid 34465 · lease 2000 ms · once · harness.db
received r-000b: attempt 1 · agent loop · "loop, please"
  1  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 4 ms]
    2  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 0 ms]
      3  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 0 ms]
      4  AGENT  ask_loop {goal=loop, please} -> Tool failed: depth limit: a loop at depth 2 cannot delegate  [ERROR 0 ms]
      5  LLM   pass 2 -> Done[]  [OK 0 ms]
    6  AGENT  ask_loop {goal=loop, please} -> Tool ask_loop ERROR: Tool failed: depth limit: a loop at depth 2 cannot delegate  [OK 4 ms]
    7  LLM   pass 2 -> Done[]  [OK 0 ms]
  8  AGENT  ask_loop {goal=loop, please} -> Tool ask_loop ERROR: Tool failed: depth limit: a loop at depth 2 cannot delegate  [OK 6 ms]
  9  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Tool ask_loop ERROR: Tool failed: depth limit: a loop at depth 2 cannot delegate
done r-000b · 9 spans · 71 ms · DONE in harness.db · acked · handler calls: none
```

A turn that spends its budget of model calls ends `FAILED` with a NOTE row `budget exhausted`
(`SubAgentTest`).

### durability: `submit`, a worker that dies, the next worker resumes

A turn is a message on the `queue` table with the three calls of SQS (Amazon's Simple Queue
Service): `receive` hands out the oldest visible message and hides it for the visibility
window, which is the lease; `extend` pushes the window, and the worker does that after every
span it writes; `ack` deletes the message, and the worker does that last. A worker that dies
never acks, so its message comes back when the lease runs out, and the next worker finds the
saved spans and resumes from the last one. The whole section is `../docs/DURABILITY.md`.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main submit --agent support "What is the status of order ORD-1?"
submitted r-5a9e · QUEUED in harness.db · agent support · "What is the status of order ORD-1?"
(a worker takes it with a 2000 ms lease and dies right after its 2nd row is persisted: exit 3, no ack)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --crash-after 2 --visibility-ms 2000
worker pid 31638 · lease 2000 ms · once · harness.db
received r-5a9e: attempt 1 · agent support · "What is the status of order ORD-1?"
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 3 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
crash: exit 3 right after row 2 of turn r-5a9e was persisted (simulated) · handler calls: order_status=1
(process exited with code 3; the turn row says RUNNING, the message is still queued but hidden until the lease runs out)
$ sleep 2
(the lease has run out, so the next worker receives the same message again and resumes from the last saved step:)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once
worker pid 31642 · lease 2000 ms · once · harness.db
received again: attempt 2, lease had expired · r-5a9e resumes from step 2
  3  NOTE  resumed from step 2  [OK 0 ms]
  4  LLM   pass 2 -> Done[]  [OK 1 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
done r-5a9e · 4 spans · 27 ms · DONE in harness.db · acked · handler calls: none
```

The second worker's footer says `handler calls: none`: the saved tool call did not run again,
and the model was asked once more, for pass 2 only. `GET /turns/r-5a9e` after that shows
`DONE`, `attempts 2`, the second worker's pid in `claimedBy` and all four spans
(`demo-output.txt`). A dead-letter queue after N receives is out of scope for this POC.

### eval over HTTP: `POST /evals`, two FAILs left in on purpose

Cases 11 and 12 fail by design: the stub model matches substrings, so `reorder` picks
`order_status` and `parcel` matches nothing. `stopped_by_done` and `errors_surfaced` are the
harness's own scores and are held at 100% by `GoldenSetTest`. The reply carries one object
per case and the same table as text lines; the lines, from the demo:

```
$ curl -s -X POST http://localhost:58833/evals
  "lines" : [
    " 1  PASS  support    What is the status of order ORD-1?                      ran order_status                 2 passes      3 ms",
    " 2  PASS  support    Where is my shipment SHP-1?                             ran shipping_status              2 passes      0 ms",
    " 3  PASS  support    Is product PRD-2 available?                             ran stock_status                 2 passes      0 ms",
    " 4  PASS  support    What is the status of order ORD-99?                     ran order_status                 2 passes      0 ms",
    " 5  PASS  support    What is the weather today?                              ran no tool                      1 pass       0 ms",
    " 6  PASS  chaos      Please hang while checking ORD-1                        ran hang_forever                 2 passes   1005 ms",
    " 7  PASS  chaos      boom                                                    ran throw_boom                   2 passes      0 ms",
    " 8  PASS  support    Status of order ORD-2 and is product PRD-1 available?   ran order_status,stock_status    2 passes      3 ms",
    " 9  PASS  inventory  How many PRD-1 do we have in stock?                     ran stock_status                 2 passes      0 ms",
    "10  PASS  inventory  refund order ORD-1                                      ran no tool                      1 pass       0 ms",
    "11  FAIL  support    reorder ORD-1                                           ran order_status                 2 passes      0 ms  tool_choice: expected [] got [order_status]; answer: expected \"No tool\" got \"Order ORD-1: SHIPPED, shipment SHP-1\"",
    "12  FAIL  support    track my parcel SHP-3                                   ran no tool                      1 pass       0 ms  tool_choice: expected [shipping_status] got []; answer: expected \"DELIVERED\" got \"No tool for this question.\"",
    "harness: tool_choice 10/12 · stopped_by_done 12/12 · errors_surfaced 12/12 · p50 0 ms | agent: answer 10/12"
  ]
```

(The `cases` array above the lines, trimmed here, is in `demo-output.txt`.)

## The CLI that remains

The developer's local tools; `serve` and `worker` are above.

```
java -cp "target/classes:target/lib/*" harness.cli.Main submit --agent <name> "question"
java -cp "target/classes:target/lib/*" harness.cli.Main eval [evals/golden.json]
java -cp "target/classes:target/lib/*" harness.cli.Main replay <turnId> [fromSpan]
```

`submit` queues one turn without HTTP (above). `eval` prints the table `POST /evals` returns.
`replay` prints a turn's stored spans from that number on, a child loop's indented:

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main replay r-5a9e
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 3 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
  3  NOTE  resumed from step 2  [OK 0 ms]
  4  LLM   pass 2 -> Done[]  [OK 1 ms]
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main replay r-2bb0
  1  LLM   pass 1 -> ToolCall[toolName=ask_logistics, input={goal=Where is shipment SHP-1 for my order?}]  [OK 4 ms]
    2  LLM   pass 1 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]  [OK 0 ms]
    3  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 0 ms]
    4  LLM   pass 2 -> Done[]  [OK 0 ms]
  5  AGENT  ask_logistics {goal=Where is shipment SHP-1 for my order?} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 5 ms]
  6  LLM   pass 2 -> Done[]  [OK 0 ms]
```

## Agents and tools are data

An agent is a file in `agents/`: its name, the tools it may call, its pass cap, its per-call
timeout and, if it says so, `maxModelCalls` (default 20), the model calls one turn may spend,
children included. A tool it does not list is `DENIED` before it runs (`refund_order` is
registered and listed by no agent for exactly that case). A builder that writes such files
would be its own service in front of this metadata; there is no endpoint for it here.

```json
{ "name": "support", "description": "Answers order, shipment and stock questions",
  "tools": ["order_status", "shipping_status", "stock_status"], "maxPasses": 5, "toolTimeoutMs": 2000 }
```

A tool is a file in `tools/`; `type` picks the adapter in `ToolLoader` (`lookup`: a static map
keyed by the id the question mentions; `sleep`: an interruptible sleep of `ms`; `fail`: throws
`message`; `agent`: another agent, named by `agent` and read from `agents/`, run as a tool by
the loop that offers it, with the whole question as its `goal`). Adding a tool is adding a file;
the `match` block is what the stub model uses to pick it.

```json
{ "name": "order_status", "description": "Status of an order, by order id", "type": "lookup",
  "inputField": "orderId", "match": { "keywords": ["order"], "idPattern": "ORD-\\d+" },
  "data": { "ORD-1": "SHIPPED, shipment SHP-1", "ORD-2": "PROCESSING", "ORD-3": "DELIVERED, shipment SHP-3" },
  "found": "Order {id}: {value}", "notFound": "Order {id} not found" }
```

The golden set `evals/golden.json` is a list of `{ question, agent, expectTools, answerContains, maxPasses }`.

## The store: `harness.db`

One SQLite file, created on first use, gitignored. Four tables: `conversation` (`id`, `agent`,
`created_at`), `turn` (`id`, `conversation_id`, `question`, `answer`, `status`, `claimed_by`,
`attempts`, `created_at`, `updated_at`), `span` (`turn_id`, `span`, `pass`, `kind`, `request`,
`response`, `status`, `ms`, `attrs` as JSON text) and `queue` (`turn_id`, `visible_at`,
`receives`). The turn row is `QUEUED` from `POST /runs` or `submit`, `RUNNING` once a worker
claims it (its pid in `claimed_by`, `attempts` + 1), `DONE` with the answer at the end,
`FAILED` if the loop threw; a worker that dies leaves it `RUNNING` and its message in the
queue. A file from before these tables (`job`, `step`) is refused with a message: delete it.
`sqlite3 harness.db` opens it if you want to look.

## Layout

```
agents/*.json  tools/*.json  evals/golden.json  harness.db (gitignored)
src/main/java/harness/
  core/    Orchestrator, TurnBudget, Conversation, Turn, Span, Context, RelaySummarizer, RunListener, ConsoleListener, CrashAfter
  model/   LLMProxy, RuleBasedLLM, ModelReply, ToolCall, ToolCalls, Done
  tools/   Tool, ToolRegistry, ToolCaller, ToolResult, MatchingCondition, ToolLoader
  agent/   Agent, AgentLoader
  store/   RunStore, ConversationRow, TurnRow, SqliteRunStore, InMemoryRunStore, RunQueue
  http/    HttpApi, RunStreams
  worker/  Worker, HttpPush
  eval/    GoldenCase, EvalRunner, CaseResult
  cli/     Main
```

What is simulated is labelled as such: `--slow` prints `(latency simulated)`, `--crash-after`
prints `(simulated)`, the model is a rule-based stub, and the timeout is a thread with a
deadline, not a sandbox. The run's stream is a map in the server's process, the stand-in for a
Redis Stream per job id, and the worker's push over HTTP the stand-in for the append to it. The
store, the queue and the HTTP server are real: one SQLite file, a table with SQS's three calls,
the JDK's `HttpServer`; a worker's crash is a real `System.exit(3)`.
