# Mini agent harness, v2

The harness from the live round (`../v1`, frozen) extended in place. Agents and tools are JSON
files; every run is a job row plus one step row per step in `harness.db` (SQLite), each step
written before it is acted on; a crashed run resumes from its rows; a runaway tool is cut off at
the agent's timeout; independent tool calls run together; a repeated tool call is deduped inside
a conversation; a golden set scores the harness itself; and `serve` puts the same loop behind an
HTTP API that streams the answer over Server-Sent Events (SSE). The model is still a stub
(`RuleBasedLLM`): no network, no keys. The high-level design of the service is
`../docs/V2_HLD.md`; the design write-up is `../DESIGN.md`; the live round's whiteboard is
`../docs/v1-whiteboard.png`.

## Build and test

```
cd v2
mvn -q test       # 84 tests, about 7 s; tests use the in-memory store or a temp SQLite file
mvn -q compile    # classes to target/classes, Jackson and sqlite-jdbc (the two dependencies) to target/lib
```

## Run it

Every command runs from `v2/`, where `agents/`, `tools/`, `evals/` and `harness.db` live:

```
java -cp "target/classes:target/lib/*" harness.cli.Main agents
java -cp "target/classes:target/lib/*" harness.cli.Main tools
java -cp "target/classes:target/lib/*" harness.cli.Main run --agent <name> "question" ["question" ...] [--slow] [--crash-after N] [--crash-before-persist]
java -cp "target/classes:target/lib/*" harness.cli.Main resume <runId>
java -cp "target/classes:target/lib/*" harness.cli.Main replay <runId> [fromStep]
java -cp "target/classes:target/lib/*" harness.cli.Main eval [evals/golden.json]
java -cp "target/classes:target/lib/*" harness.cli.Main serve [--port 8080]
```

On JDK 24+ put `--enable-native-access=ALL-UNNAMED` after `java`, or the SQLite driver's native
load prints a warning; JDK 21 needs nothing. `../demo.sh` compiles v2 and runs the commands below
in this order; `../docs/demo-output.txt` is its saved output (made on JDK 25, so the flag shows),
and every block here is pasted from that file. Run ids (`r-` + 4 hex), ports and milliseconds
vary from run to run; nothing else does.

### agents: an agent is a file in `agents/`

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main agents
chaos       Runs misbehaving tools (a hang, a throw) next to a good one  tools: order_status, hang_forever, throw_boom · maxPasses 5 · toolTimeout 1000 ms
inventory   Answers stock questions only                                tools: stock_status · maxPasses 2 · toolTimeout 2000 ms
support     Answers order, shipment and stock questions                 tools: order_status, shipping_status, stock_status · maxPasses 5 · toolTimeout 2000 ms
```

### tools: a tool is a file in `tools/`, its `type` picks the adapter

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main tools
hang_forever      sleep    Sleeps for a minute: the runaway tool                     keywords [hang] · id ORD-\d+
order_status      lookup   Status of an order, by order id                           keywords [order] · id ORD-\d+
refund_order      lookup   Refunds an order, by order id (listed by no agent)        keywords [refund] · id ORD-\d+
shipping_status   lookup   Status of a shipment, by shipment id                      keywords [shipment, shipping, tracking] · id SHP-\d+
stock_status      lookup   Units in stock for a product, by product id               keywords [stock, inventory, available] · id PRD-\d+
throw_boom        fail     Throws on every call: the broken tool                     keywords [boom] · no id
```

### eval: the golden set, two FAILs left in on purpose

Cases 11 and 12 fail by design: the stub model matches substrings, so `reorder` picks
`order_status` and `parcel` matches nothing. They stay in the set as the visible limit of the stub.
`stopped_by_done` and `errors_surfaced` are the harness's own scores and are held at 100% by
`GoldenSetTest`. Eval runs every case on an in-memory store; it does not touch `harness.db`.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main eval
 1  PASS  support    What is the status of order ORD-1?                      ran order_status                 2 passes     11 ms
 2  PASS  support    Where is my shipment SHP-1?                             ran shipping_status              2 passes      0 ms
 3  PASS  support    Is product PRD-2 available?                             ran stock_status                 2 passes      0 ms
 4  PASS  support    What is the status of order ORD-99?                     ran order_status                 2 passes      0 ms
 5  PASS  support    What is the weather today?                              ran no tool                      1 pass       0 ms
 6  PASS  chaos      Please hang while checking ORD-1                        ran hang_forever                 2 passes   1009 ms
 7  PASS  chaos      boom                                                    ran throw_boom                   2 passes      0 ms
 8  PASS  support    Status of order ORD-2 and is product PRD-1 available?   ran order_status,stock_status    2 passes      5 ms
 9  PASS  inventory  How many PRD-1 do we have in stock?                     ran stock_status                 2 passes      0 ms
10  PASS  inventory  refund order ORD-1                                      ran no tool                      1 pass       0 ms
11  FAIL  support    reorder ORD-1                                           ran order_status                 2 passes      0 ms  tool_choice: expected [] got [order_status]; answer: expected "No tool" got "Order ORD-1: SHIPPED, shipment SHP-1"
12  FAIL  support    track my parcel SHP-3                                   ran no tool                      1 pass       0 ms  tool_choice: expected [shipping_status] got []; answer: expected "DELIVERED" got "No tool for this question."
harness: tool_choice 10/12 · stopped_by_done 12/12 · errors_surfaced 12/12 · p50 0 ms | agent: answer 10/12
```

### a plain run: LLM row, TOOL row, LLM row, answer

Each row prints the moment it lands in `harness.db`. The footer names the job's final status in
the file and counts handler calls per `lookup` tool, so the crash and dedupe runs below can show
what did and did not run.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main run --agent support "What is the status of order ORD-1?"
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 6 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
  3  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
run r-e4db · 2 passes · 1 tool calls · 26 ms · DONE in harness.db · handler calls: order_status=1
```

### the `--slow` parallel run: two tools in one pass, wall < work

`--slow` gives every `lookup` 300 ms of simulated latency so the overlap is visible: two
300 ms tools, one pass, 307 ms of wall time for 606 ms of work. TOOL rows land in request order
with their own `ms`.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main run --agent support "Status of order ORD-2 and is product PRD-1 available?" --slow
slow: every lookup sleeps 300 ms before answering (latency simulated)
  1  LLM   pass 1 -> ToolCalls[calls=[ToolCall[toolName=order_status, input={orderId=ORD-2}], ToolCall[toolName=stock_status, input={productId=PRD-1}]]]  [OK 5 ms]
  2  TOOL  order_status {orderId=ORD-2} -> Order ORD-2: PROCESSING  [OK 303 ms]
  3  TOOL  stock_status {productId=PRD-1} -> Product PRD-1: 42 units in stock  [OK 303 ms]
  4  NOTE  ran 2 tools together: wall 307 ms, work 606 ms  [OK 307 ms]
  5  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-2: PROCESSING
Product PRD-1: 42 units in stock
run r-eab6 · 2 passes · 2 tool calls · 334 ms · DONE in harness.db · handler calls: order_status=1, stock_status=1
```

### the crash and the resume

`--crash-after 1` exits the process (code 3) right after row 1, the LLM row that decided the
tool call, is persisted. The rows survived in `harness.db` and the job row still says `RUNNING`,
so `resume` rebuilds the turn, runs the decided call without asking the model again, finishes
the loop and sets the job `DONE`. The second window is below.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main run --agent support "What is the status of order ORD-1?" --crash-after 1
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 4 ms]
crash: exit 3 right after row 1 of run r-6c23 was persisted (simulated) · handler calls: none
(process exited with code 3; the rows of r-6c23 survived in harness.db and its job row still says RUNNING, so:)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main resume r-6c23
  2  NOTE  resumed from step 1  [OK 0 ms]
  3  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
  4  LLM   pass 2 -> Done[]  [OK 1 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
run r-6c23 · 2 passes · 1 tool calls · 31 ms · DONE in harness.db · handler calls: order_status=1
```

### the other crash window: the tool ran, the row never landed

`--crash-before-persist` exits after the tool did its work but before its TOOL row was written. The crash line prints `handler calls: shipping_status=1`; `resume` finds only the LLM row, runs the decided call again, and its own footer prints `shipping_status=1` too, so the handler ran twice across the two processes. That is the at-least-once window: the harness supplies the idempotency key (the TOOL row's `key`, job id plus deciding step), and a tool with a side effect dedups by it.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main run --agent support "Where is my shipment SHP-1?" --crash-before-persist
  1  LLM   pass 1 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]  [OK 5 ms]
crash: exit 3 after shipping_status ran, before its TOOL row of run r-ada2 was written (simulated) · handler calls: shipping_status=1
(the handler ran once in that process and the TOOL row never landed; harness.db holds only the LLM row of r-ada2, so resume runs the decided call again:)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main resume r-ada2
  2  NOTE  resumed from step 1  [OK 0 ms]
  3  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 0 ms]
  4  LLM   pass 2 -> Done[]  [OK 1 ms]
answer: Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
run r-ada2 · 2 passes · 1 tool calls · 29 ms · DONE in harness.db · handler calls: shipping_status=1
```

`replay <runId> [fromStep]` prints the stored rows of any run from that step on, in the same format.

### the hang on `chaos`: a runaway tool is cut off at the agent's `toolTimeoutMs`

The call runs on a virtual thread with a deadline; on timeout it is cancelled, the row says
`TIMEOUT`, the loop moves on and the answer names it. A tool that throws gets `ERROR` the same
way, and a tool the agent does not list gets `DENIED` without running.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main run --agent chaos "Please hang while checking ORD-1"
  1  LLM   pass 1 -> ToolCall[toolName=hang_forever, input={orderId=ORD-1}]  [OK 5 ms]
  2  TOOL  hang_forever {orderId=ORD-1} -> cut off after 1000 ms  [TIMEOUT 1000 ms]
  3  LLM   pass 2 -> Done[]  [OK 1 ms]
answer: Tool hang_forever TIMEOUT: cut off after 1000 ms
run r-ae38 · 2 passes · 1 tool calls · 1034 ms · DONE in harness.db · handler calls: none
```

### the dedupe run: the same question twice in one conversation

Several questions in one `run` form one conversation; the model sees the earlier turns as
`Q: ... -> A: ...` lines. A call with the same tool and input as an earlier OK row is not run:
its row is `DEDUPED`, points at the original step, and the handler count stays at 1.

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main run --agent support "status of order ORD-1" "status of order ORD-1 again"
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 5 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
  3  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
  4  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 0 ms]
  5  TOOL  order_status {orderId=ORD-1} -> unchanged, see step 2  [DEDUPED 0 ms]
  6  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
run r-184b · 4 passes · 2 tool calls · 24 ms · DONE in harness.db · handler calls: order_status=1
```

### the HTTP API: `serve`, then curl

`serve` starts the JDK's own `HttpServer` on localhost (default port 8080; `--port 0` picks a
free one) over the same `agents/`, `tools/` and `harness.db`. JSON in and out, one virtual
thread per request, errors as `{"error": "..."}`.

| method | path | what it does | returns |
| --- | --- | --- | --- |
| GET | `/agents` | lists the agent files | `200`, a list |
| GET | `/agents/{name}` | one agent file | `200`; `404` |
| PUT | `/agents/{name}` | writes `agents/{name}.json` from the body | `201` created, `200` changed; `400` unknown tool, missing field, name mismatch |
| GET | `/tools` | lists the tool files | `200`, a list |
| POST | `/runs` | `{"agent","question"}`: job row in, loop on a virtual thread | `202 {"jobId","status":"RUNNING"}`; `400` |
| GET | `/runs/{id}` | the job row, its steps, the answer when DONE | `200`; `404` |
| GET | `/runs/{id}/events` | SSE: the stored steps from `Last-Event-ID` or `?after=N`, then live, then `done` | `200 text/event-stream`; `404` |

The 202 comes back before the loop runs; the stream sends every stored step with the step number
as its event id, follows the run by polling the file every 200 ms, and ends with `done` and the
answer. A client that disconnects does not stop the run. The full outputs of every endpoint are in
`../docs/V2_HLD.md`; the short ones:

```
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main serve --port 0
listening on http://localhost:59760 · agents/ tools/ harness.db · ctrl-c stops it
(the server stays in the background; --port 0 picked a free port, the manual uses 8080)

$ curl -s -X POST http://localhost:59760/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
{
  "jobId" : "r-be37",
  "status" : "RUNNING"
}
```

```
(a client that reconnects sends the last id it saw and gets only the tail)
$ curl -sN --max-time 5 -H 'Last-Event-ID: 2' http://localhost:59760/runs/r-be37/events
id: 3
event: step
data: {"run":"r-be37","step":3,"turn":1,"pass":2,"kind":"LLM","request":"pass 2","response":"Done[]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?"}}

event: done
data: {"jobId":"r-be37","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1"}
```

```
(the builder experience: an agent is a file, PUT writes it; a tool that does not exist is refused first)
$ curl -s -X PUT http://localhost:59760/agents/returns -d @- <<< '{"name":"returns","description":"Refunds an order, by order id","tools":["refund_orders"],"maxPasses":3,"toolTimeoutMs":2000}'
{
  "error" : "no tool named refund_orders in tools/"
}
$ curl -s -X PUT http://localhost:59760/agents/returns -d @- <<< '{"name":"returns","description":"Refunds an order, by order id","tools":["refund_order"],"maxPasses":3,"toolTimeoutMs":2000}'
{
  "name" : "returns",
  "description" : "Refunds an order, by order id",
  "tools" : [ "refund_order" ],
  "maxPasses" : 3,
  "toolTimeoutMs" : 2000
}
```

The demo removes `agents/returns.json` again at the end so the shipped agent list stays at three.

## Agents and tools are data

An agent is a file in `agents/`: its name, the tools it may call, its pass cap and its per-call
timeout. A tool it does not list is `DENIED` before it runs (`refund_order` is registered and
listed by no agent for exactly that case). `PUT /agents/{name}` writes such a file over HTTP
after the same checks, so an agent can be created or changed without code or a restart.

```json
{ "name": "support", "description": "Answers order, shipment and stock questions",
  "tools": ["order_status", "shipping_status", "stock_status"], "maxPasses": 5, "toolTimeoutMs": 2000 }
```

A tool is a file in `tools/`; `type` picks the adapter in `ToolLoader` (`lookup`: a static map
keyed by the id the question mentions; `sleep`: an interruptible sleep of `ms`; `fail`: throws
`message`). Adding a tool is adding a file; the `match` block is what the stub model uses to
pick it.

```json
{ "name": "order_status", "description": "Status of an order, by order id", "type": "lookup",
  "inputField": "orderId", "match": { "keywords": ["order"], "idPattern": "ORD-\\d+" },
  "data": { "ORD-1": "SHIPPED, shipment SHP-1", "ORD-2": "PROCESSING", "ORD-3": "DELIVERED, shipment SHP-3" },
  "found": "Order {id}: {value}", "notFound": "Order {id} not found" }
```

The golden set `evals/golden.json` is a list of `{ question, agent, expectTools, answerContains, maxPasses }`.

## The store: `harness.db`

One SQLite file, created on first use, gitignored. Two tables: `job` (`id`, `conversation_id`,
`agent`, `question`, `status`, `created_at`, `updated_at`) and `step` (`job_id`, `step`, `turn`,
`pass`, `kind`, `request`, `response`, `status`, `ms`, `attrs` as JSON text). The job row is
`RUNNING` while the loop runs, `DONE` at the end, `FAILED` if the loop threw; a process that dies
leaves it `RUNNING`. `sqlite3 harness.db` opens it if you want to look.

## Layout

```
agents/*.json  tools/*.json  evals/golden.json  harness.db (gitignored)
src/main/java/harness/
  core/    Orchestrator, Conversation, Turn, Span, Context, Summarizer, RelaySummarizer, RunListener, ConsoleListener
  model/   LLMProxy, RuleBasedLLM, ModelReply, ToolCall, ToolCalls, Done
  tools/   Tool, ToolRegistry, ToolCaller, ToolResult, MatchingCondition, ToolLoader
  agent/   Agent, AgentLoader
  store/   RunStore, Job, SqliteRunStore, InMemoryRunStore
  http/    HttpApi
  eval/    GoldenCase, EvalRunner, CaseResult
  cli/     Main
```

What is simulated is labelled as such: `--slow` prints `(latency simulated)`, the crash flags
print `(simulated)`, the model is a rule-based stub, and the timeout is a thread with a deadline,
not a sandbox. The store and the HTTP server are real: one SQLite file, the JDK's `HttpServer`.
