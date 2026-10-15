# v2 HLD: keeping it simple

[What v2 is](#what-v2-is) · [One question, several tool calls](#one-question-several-tool-calls) · [The data model](#the-data-model) · [The HTTP endpoints](#the-http-endpoints) · [Streaming the answer](#streaming-the-answer) · [Persistence](#persistence) · [Agents and tools are metadata](#agents-and-tools-are-metadata) · [Try it](#try-it) · next: [v2: additional features](V2_FEATURES.md)

The harness as a small service: you call it over HTTP, its state lives in one SQLite file, and the answer streams back as the steps happen. The model is still a stub; nothing needs a key or a network. Every output below is pasted from `demo-output.txt`, which `./demo.sh` writes; the two SQLite queries at the end were run by hand after another run, so their ids differ.

## What v2 is

![v2 HLD](hld.svg)

- **End users** ask a question and get a job id back at once; the answer follows as a stream.
- **API layer** is the one face: `POST /runs` writes the turn row and returns its id; `GET /turns/{id}` reads it; `GET /turns/{id}/events` streams it.
- **Orchestrator** is the loop: build the context, call the model, run the tool it asks for, repeat until the model says done or the agent's pass cap hits. Every step is written to the store before the next one is taken. The loop already does more than that: parallel tool calls, a clock on every tool call, the agent's tool list as the gate, the pass cap and the budget, and the answer. Each is on the next page, [v2: additional features](V2_FEATURES.md), with a run.
- **One service, two layers.** The diagram draws the HTTP face and the loop as one service. In v2 they already run as two processes, `serve` and `worker`, so a turn outlives the request that created it; the Try it section runs both. Why they are split is on [the durability page](DURABILITY.md).
- **LLM proxy** is the model interface, the same box v1 drew: the loop hands it the context and gets back a tool to call with its input, or done. In this POC the implementation is a rule-based stub: keywords on the question in the first pass, then the ids the results name. In production a real model on a GPU sits behind the same interface, shared by every customer; that is what the scaling and performance pages are about.
- **Tool caller and registry** run what the model names: tools are files in `tools/`, the file's type picks the adapter, the agent's file says which tools it may call and for how long. In this POC the adapters are lookups over data in the tool file; in production they are REST and MCP calls made as the user, through the same adapter seam.
- **Run store** is SQLite here and the tenant database at scale, same tables.
- **The dashed arrow** is the run's stream: the loop pushes each step and each piece of the answer as it happens, the API holding the connection forwards it to the client. The store is read only if the stream is gone.

Why a job id and a stream, when the stub answers in milliseconds:

- In production a turn is several seconds of model and tool calls, and longer once tools call real systems.
- So a turn is a job: the id comes back at once, the answer streams as the steps land, a client that drops reconnects with the last step it saw.
- Durability needs the same id, so we design for it now.

## One question, several tool calls

- The loop asks the model, runs what it names, writes the rows, and asks again with those rows in the context, until the model says done. A chain is nothing more than that: pass 2 decided from pass 1's result.
- **Sequential**, when the second lookup needs the first's result. "What is the status of order ORD-1?": pass 1 asks for the order; pass 2 reads "shipment SHP-1" in the order's result and asks for the shipment; pass 3 says done because the results name nothing new. Five rows, three passes, and the answer relays both results.
- **Parallel**, when the question names two independent things. "Status of order ORD-2 and is product PRD-1 available?": pass 1 names both tools in one reply, they run together, a note row says wall against work, pass 2 says done.
- What decides which: the model, from what it has read so far. The stub does the same with rules, the question in pass 1 and the results after it. The loop does not know the difference.
- A dead worker's successor continues a chain: it loads the saved rows, does not run the saved call again, and takes the next decision from them. The durability page has that run.

The two runs, from the worker's log:

```
received r-fda1: attempt 1 · agent support · "What is the status of order ORD-1?"
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 3 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
  3  LLM   pass 2 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]  [OK 0 ms]
  4  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 0 ms]
  5  LLM   pass 3 -> Done[]  [OK 0 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
```

```
received r-ab46: attempt 1 · agent support · "Status of order ORD-2 and is product PRD-1 available?"
  1  LLM   pass 1 -> ToolCalls[calls=[ToolCall[toolName=order_status, input={orderId=ORD-2}], ToolCall[toolName=stock_status, input={productId=PRD-1}]]]  [OK 3 ms]
  2  TOOL  order_status {orderId=ORD-2} -> Order ORD-2: PROCESSING  [OK 302 ms]
  3  TOOL  stock_status {productId=PRD-1} -> Product PRD-1: 42 units in stock  [OK 302 ms]
  4  NOTE  ran 2 tools together: wall 305 ms, work 604 ms  [OK 305 ms]
  5  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-2: PROCESSING
Product PRD-1: 42 units in stock
```

## The data model

Three tables, from the whiteboard: a conversation has turns, a turn is one job, a turn has spans.

| Table | Columns |
|---|---|
| `conversation` | `id` · `agent` · `created_at` |
| `turn` | `id` (the job id) · `conversation_id` · `question` · `answer` · `status` (QUEUED · RUNNING · DONE · FAILED) · `claimed_by` · `attempts` · `created_at` · `updated_at` |
| `span` | `turn_id` · `span` (the step number) · `pass` · `kind` (LLM · TOOL · AGENT · NOTE) · `request` · `response` · `status` · `ms` · `attrs` (JSON) · key (`turn_id`, `span`) |

v2 has a fourth table, `queue` (`turn_id` · `visible_at` · `receives`), the queue that `POST /runs` writes to; [the durability page](DURABILITY.md) explains it.

Span numbers run on through a conversation, so a later turn points at an earlier turn's span by one number.

<details><summary>the SQL that creates the tables</summary>

```sql
CREATE TABLE IF NOT EXISTS conversation (
  id TEXT PRIMARY KEY, agent TEXT, created_at INTEGER);
CREATE TABLE IF NOT EXISTS turn (
  id TEXT PRIMARY KEY, conversation_id TEXT, question TEXT, answer TEXT, status TEXT,
  claimed_by TEXT, attempts INTEGER DEFAULT 0, created_at INTEGER, updated_at INTEGER);
CREATE TABLE IF NOT EXISTS span (
  turn_id TEXT, span INTEGER, pass INTEGER, kind TEXT, request TEXT, response TEXT,
  status TEXT, ms INTEGER, attrs TEXT, PRIMARY KEY (turn_id, span));
```
</details>

## The HTTP endpoints

| Method | Path | What it does | Returns |
|---|---|---|---|
| GET | `/agents` | the agents, from `agents/*.json` | `200`, a list |
| GET | `/agents/{name}` | one agent file | `200`; `404` |
| GET | `/tools` | the tools, from `tools/*.json` | `200`, a list |
| POST | `/runs` | body `{"agent","question"}`: writes the turn row and queues it; `{"conversationId","question"}` adds a turn to that conversation | `202 {"turnId","jobId","conversationId","status":"QUEUED"}` at once |
| GET | `/turns/{id}` (also `/runs/{id}`) | the turn, its spans, the answer when done | `200`; `404` |
| GET | `/turns/{id}/events` | the run's stream from `Last-Event-ID`: `step` and `token` events as they happen, then `done` with the answer; the saved rows only if the stream is gone | `200 text/event-stream` |
| POST | `/turns/{id}/events` | the worker's push: body `{"event","data"}`, appended to the run's stream; the stand-in for the Redis Stream append | `204`; `404` |
| POST | `/evals` | the golden set, scored in the server | `200`, the table as `cases` and `lines` |

The server is the JDK's own HTTP server, one virtual thread per request, JSON by hand. No framework.

`POST /runs` (`HttpApi.submit`) writes the turn row and its queue message in one transaction (`RunQueue.submit`) and answers `202` with the id. The loop runs in the other process, `worker`, over the same SQLite file: it takes the message and runs the turn.

<details><summary>proof: POST a question, then GET the turn</summary>

```
$ curl -s -X POST http://localhost:62588/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
{
  "turnId" : "r-fda1",
  "jobId" : "r-fda1",
  "conversationId" : "c-4404",
  "status" : "QUEUED"
}
(202 came back at once with the turn QUEUED; the worker took it, and one second later the turn row says DONE, who ran it, the answer, and the spans: five rows over three passes. Pass 1 asks for the order; pass 2 reads SHP-1 in the order's result and asks for the shipment, decided from that row, not from the question; pass 3 says done because the results name nothing new. The answer relays both results, one line each:)
$ curl -s http://localhost:62588/turns/r-fda1
{
  "id" : "r-fda1",
  "conversationId" : "c-4404",
  "agent" : "support",
  "question" : "What is the status of order ORD-1?",
  "answer" : "Order ORD-1: SHIPPED, shipment SHP-1\nShipment SHP-1: IN TRANSIT, arriving 2026-10-16",
  "status" : "DONE",
  "claimedBy" : "48745",
  "attempts" : 1,
  "createdAt" : 1792045726436,
  "updatedAt" : 1792045726891,
  "spans" : [ {
    "turnId" : "r-fda1",
    "span" : 1,
    "pass" : 1,
    "kind" : "LLM",
    "request" : "pass 1",
    "response" : "ToolCall[toolName=order_status, input={orderId=ORD-1}]",
    "status" : "OK",
    "ms" : 3,
    "attrs" : {
      "question" : "What is the status of order ORD-1?",
      "tool" : "order_status",
      "orderId" : "ORD-1"
    }
  }, {
    "turnId" : "r-fda1",
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
      "key" : "r-fda1/1"
    }
  }, {
    "turnId" : "r-fda1",
    "span" : 3,
    "pass" : 2,
    "kind" : "LLM",
    "request" : "pass 2",
    "response" : "ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "question" : "What is the status of order ORD-1?",
      "tool" : "shipping_status",
      "shipmentId" : "SHP-1"
    }
  }, {
    "turnId" : "r-fda1",
    "span" : 4,
    "pass" : 2,
    "kind" : "TOOL",
    "request" : "shipping_status {shipmentId=SHP-1}",
    "response" : "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "tool" : "shipping_status",
      "shipmentId" : "SHP-1",
      "key" : "r-fda1/3"
    }
  }, {
    "turnId" : "r-fda1",
    "span" : 5,
    "pass" : 3,
    "kind" : "LLM",
    "request" : "pass 3",
    "response" : "Done[]",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "question" : "What is the status of order ORD-1?"
    }
  } ]
}
```
`claimedBy` is the pid of the worker that ran it. Span 3 is the chain: pass 2 asked for the shipment because span 2 named it. Each TOOL span's `key` is the job id plus the span that decided it, the idempotency key a tool that writes would use.
</details>

## Streaming the answer

- The stream is what the loop pushes out as the turn runs: a `step` event for every row it writes, a `token` event for each piece of the answer as the model produces it, and `done` with the answer at the end.
- The model streams only to the loop. The loop forwards answer text to the run's stream as it arrives, and buffers a tool call until it is complete, because it has to see the whole call to run it. In v2 the stub's decisions have no text; its final reply, the relayed answer, streams word by word through the same model interface.
- The API holds the run's stream and forwards it over SSE on a second request, `GET /turns/{id}/events`, kept open. Every entry has an id; a client that reconnects sends `Last-Event-ID` and gets only the tail. Nothing twice.
- In production the run's stream is a Redis Stream per job id, so any API node can serve any client; that is on the scaling page. In v2 it is a map in the API process, and the worker pushes into it over HTTP, `POST /turns/{id}/events`: the one network call between the two processes.
- The store is not the source of the stream. It is read only if the stream is gone: the API restarted, the client came after the stream's time ran out, or no worker fed it. Then the saved rows go out once, with the answer.
- A client that disconnects does not stop the turn; the worker keeps pushing, the next listener reads from its last id.

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| polling `GET /turns/{id}` | the client asks again and again | nothing new to learn | latency is the poll interval; the whole turn every time |
| SSE | one response kept open; events with ids; the browser reconnects with the last id by itself | one-way is all an answer needs; replay is in the protocol | one open connection per listener on the node that holds it |
| WebSocket | a two-way socket | the client can talk back | a second protocol, no built-in replay, the same open-connection cost |

I pick SSE: the answer flows one way, and the protocol already has the reconnect-with-last-id a step log needs. How that open connection scales across nodes is its own section.

Where the stream lives in the code:

- Only the parent loop streams answer text. The loop forwards each piece of the model's final reply to its listener; a child agent's answer is a tool result for its parent, not text the user sees (`Orchestrator.finish`).
- The worker's listener does three things per row, in this order: extend the lease on the turn, push the row to the API's stream, print it. The simulated crash of the durability page happens at the print, so the row reaches the stream before the worker dies (`Worker.Forwarding`).
- The API reads the run's stream from the client's last id with a blocking read and writes each entry as an event; `done` ends the response (`HttpApi.events`).
- `RunStreams` is the stand-in for the Redis Stream: entries with ids per turn, a blocking read, `done` ends it, a 60 s time to live, no sweeper. `HttpPush` is the worker's side of `POST /turns/{id}/events`.
- `replay` is the only code that sends saved rows on `GET /turns/{id}/events`; it is the fallback. `GET /turns/{id}` reads the store by design: that is the polling row in the table above.

<details><summary>proof: the stream, then a reconnect with Last-Event-ID</summary>

```
$ curl -sN --max-time 5 http://localhost:62588/turns/r-fda1/events
id: 1
event: step
data: {"turnId":"r-fda1","span":1,"pass":1,"kind":"LLM","request":"pass 1","response":"ToolCall[toolName=order_status, input={orderId=ORD-1}]","status":"OK","ms":3,"attrs":{"question":"What is the status of order ORD-1?","tool":"order_status","orderId":"ORD-1"}}

id: 2
event: step
data: {"turnId":"r-fda1","span":2,"pass":1,"kind":"TOOL","request":"order_status {orderId=ORD-1}","response":"Order ORD-1: SHIPPED, shipment SHP-1","status":"OK","ms":0,"attrs":{"tool":"order_status","orderId":"ORD-1","key":"r-fda1/1"}}

id: 3
event: step
data: {"turnId":"r-fda1","span":3,"pass":2,"kind":"LLM","request":"pass 2","response":"ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?","tool":"shipping_status","shipmentId":"SHP-1"}}

id: 4
event: step
data: {"turnId":"r-fda1","span":4,"pass":2,"kind":"TOOL","request":"shipping_status {shipmentId=SHP-1}","response":"Shipment SHP-1: IN TRANSIT, arriving 2026-10-16","status":"OK","ms":0,"attrs":{"tool":"shipping_status","shipmentId":"SHP-1","key":"r-fda1/3"}}

id: 5
event: step
data: {"turnId":"r-fda1","span":5,"pass":3,"kind":"LLM","request":"pass 3","response":"Done[]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?"}}

id: 6
event: token
data: {"turnId":"r-fda1","text":"Order "}

id: 7
event: token
data: {"turnId":"r-fda1","text":"ORD-1: "}

id: 8
event: token
data: {"turnId":"r-fda1","text":"SHIPPED, "}

id: 9
event: token
data: {"turnId":"r-fda1","text":"shipment "}

id: 10
event: token
data: {"turnId":"r-fda1","text":"SHP-1\nShipment "}

id: 11
event: token
data: {"turnId":"r-fda1","text":"SHP-1: "}

id: 12
event: token
data: {"turnId":"r-fda1","text":"IN "}

id: 13
event: token
data: {"turnId":"r-fda1","text":"TRANSIT, "}

id: 14
event: token
data: {"turnId":"r-fda1","text":"arriving "}

id: 15
event: token
data: {"turnId":"r-fda1","text":"2026-10-16"}

event: done
data: {"turnId":"r-fda1","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1\nShipment SHP-1: IN TRANSIT, arriving 2026-10-16"}

(a client that reconnects sends the last id it saw:)
$ curl -sN --max-time 5 -H 'Last-Event-ID: 2' http://localhost:62588/turns/r-fda1/events
id: 3
event: step
data: {"turnId":"r-fda1","span":3,"pass":2,"kind":"LLM","request":"pass 2","response":"ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?","tool":"shipping_status","shipmentId":"SHP-1"}}

id: 4
event: step
data: {"turnId":"r-fda1","span":4,"pass":2,"kind":"TOOL","request":"shipping_status {shipmentId=SHP-1}","response":"Shipment SHP-1: IN TRANSIT, arriving 2026-10-16","status":"OK","ms":0,"attrs":{"tool":"shipping_status","shipmentId":"SHP-1","key":"r-fda1/3"}}

id: 5
event: step
data: {"turnId":"r-fda1","span":5,"pass":3,"kind":"LLM","request":"pass 3","response":"Done[]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?"}}

id: 6
event: token
data: {"turnId":"r-fda1","text":"Order "}

id: 7
event: token
data: {"turnId":"r-fda1","text":"ORD-1: "}

id: 8
event: token
data: {"turnId":"r-fda1","text":"SHIPPED, "}

id: 9
event: token
data: {"turnId":"r-fda1","text":"shipment "}

id: 10
event: token
data: {"turnId":"r-fda1","text":"SHP-1\nShipment "}

id: 11
event: token
data: {"turnId":"r-fda1","text":"SHP-1: "}

id: 12
event: token
data: {"turnId":"r-fda1","text":"IN "}

id: 13
event: token
data: {"turnId":"r-fda1","text":"TRANSIT, "}

id: 14
event: token
data: {"turnId":"r-fda1","text":"arriving "}

id: 15
event: token
data: {"turnId":"r-fda1","text":"2026-10-16"}

event: done
data: {"turnId":"r-fda1","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1\nShipment SHP-1: IN TRANSIT, arriving 2026-10-16"}
```
Five steps, ten tokens, then `done`; the reconnect starts at 3. In the demo's `--slow` section the stream was opened before the worker started and received every step and every token as they happened. The saved text has no timestamps, so `demo-output.txt` cannot show that timing; what it does show is the two tools of the parallel run overlapping, in the NOTE row: wall 305 ms against work 604 ms.
</details>

## Persistence

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| a file per run | one JSON line per step in `runs/<id>.jsonl` | nothing to install | no turn row, no status, nothing to query across turns |
| SQLite, one file | the three tables in `harness.db` | one file, zero setup, SQL for status and queries, the same shape a real database has | one writer at a time, one machine |
| a database server | the same tables on Postgres | many API nodes and workers share one truth | something to run |

I pick SQLite here: the smallest thing with a turn row and a query, and the tables move to a database server unchanged the day a second machine needs them.

<details><summary>proof: the file after the demo</summary>

```
$ sqlite3 -header -column harness.db "SELECT t.id, c.agent, t.status, t.attempts FROM turn t JOIN conversation c ON c.id = t.conversation_id ORDER BY t.rowid"
id      agent      status  attempts
------  ---------  ------  --------
r-082d  support    DONE    1       
r-6be2  chaos      DONE    1       
r-7b0d  support    DONE    1       
r-f777  support    DONE    1       
r-40f3  concierge  DONE    1       
r-c774  loop       DONE    1       
r-c128  support    DONE    2       
$ sqlite3 -header -column harness.db "SELECT span, pass, kind, status, ms FROM span WHERE turn_id = 'r-c128'"
span  pass  kind  status  ms
----  ----  ----  ------  --
1     1     LLM   OK      4 
2     1     TOOL  OK      0 
3     1     NOTE  OK      0 
4     2     LLM   OK      1 
5     2     TOOL  OK      0 
6     3     LLM   OK      0 
```
Seven turns from one run of the demo, two of them from the sub-agent page. The last, `r-c128`, is the durability page's turn: two attempts and six spans. The second worker wrote the last four: the NOTE that says it resumed, then the chain's second lookup (pass 2) and the done (pass 3).
</details>

## Agents and tools are metadata

- An agent is a file in `agents/`: name, the tools it may call, its pass cap, its per-call timeout.
- A tool is a file in `tools/` whose `type` picks the adapter; adding a tool is adding a file.
- A builder, the screen where someone defines an agent, would be its own service in front of this metadata, behind the API gateway. It adds nothing to this design, so it is not here.

## Try it

From `v2/`, with Java 21 or newer, Maven and curl, a server and a worker in two terminals:

```
mvn -q compile
java -cp "target/classes:target/lib/*" harness.cli.Main serve
java -cp "target/classes:target/lib/*" harness.cli.Main worker            # pushes the stream to --api, default http://localhost:8080
curl -s -X POST http://localhost:8080/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
curl -sN http://localhost:8080/turns/<turnId>/events
```

`./demo.sh` from the repo root runs all of it and `docs/demo-output.txt` is its saved output.
