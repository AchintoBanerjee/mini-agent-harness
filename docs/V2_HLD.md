# v2 HLD: keeping it simple

[What v2 is](#what-v2-is) · [The data model](#the-data-model) · [The HTTP endpoints](#the-http-endpoints) · [Streaming the answer](#streaming-the-answer) · [Persistence](#persistence) · [Agents and tools are metadata](#agents-and-tools-are-metadata) · [Try it](#try-it)

The harness as a small service: you call it over HTTP, its state lives in one SQLite file, and the answer streams back as the steps happen. The model is still a stub; nothing needs a key or a network. Every output below is from a real run of `./demo.sh`; the committed `demo-output.txt` is a later run, so its ids and timings differ, nothing else.

## What v2 is

![v2 HLD](v2-hld.svg)

- **End users** ask a question and get a job id back at once; the answer follows as a stream.
- **API layer** is the one face: `POST /runs` writes the turn row and returns its id; `GET /turns/{id}` reads it; `GET /turns/{id}/events` streams it.
- **Orchestrator** is the loop: build the context, call the model, run the tool it asks for, repeat until the model says done or the agent's pass cap hits. Every step is written to the store before the next one is taken.
- **Tool layer** is the registry built from `tools/*.json`; the file's type picks the adapter, the agent's file says which tools it may call and for how long.
- **Model** is a rule stub today; a real model goes behind the same interface, and in production it is the one step shared by every customer.
- **Run store** is SQLite here and the tenant database at scale, same tables.
- **The dashed arrow** is the stream: events by job id go back to whichever API node holds the client's connection.

Why a job id and a stream, when the stub answers in milliseconds:

- In production a turn is several seconds of model and tool calls, and longer once tools call real systems.
- So a turn is a job: the id comes back at once, the answer streams as the steps land, a client that drops reconnects with the last step it saw.
- Durability needs the same id, so we design for it now.

## The data model

Three tables, from the whiteboard: a conversation has turns, a turn is one job, a turn has spans.

| Table | Columns |
|---|---|
| `conversation` | `id` · `agent` · `created_at` |
| `turn` | `id` (the job id) · `conversation_id` · `question` · `answer` · `status` (QUEUED · RUNNING · DONE · FAILED) · `claimed_by` · `attempts` · `created_at` · `updated_at` |
| `span` | `turn_id` · `span` (the step number) · `pass` · `kind` (LLM · TOOL · AGENT · NOTE) · `request` · `response` · `status` · `ms` · `attrs` (JSON) · key (`turn_id`, `span`) |

The durability section adds a fourth, `queue` (`turn_id` · `visible_at` · `receives`). Span numbers run on through a conversation, so a later turn points at an earlier turn's span by one number.

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
| GET | `/turns/{id}/events` | the stream: stored spans from `Last-Event-ID`, then live, then `done` | `200 text/event-stream` |
| POST | `/evals` | the golden set, scored in the server | `200`, the table as `cases` and `lines` |

The server is the JDK's own HTTP server, one virtual thread per request, JSON by hand. No framework. It runs no loop: a `worker` process over the same file does, which is the durability page.

<details><summary>the handler for POST /runs</summary>

```java
String agentName = text(body, "agent");
agent = findAgent(agentName).orElseThrow(() -> new HttpError(400, "no agent named " + agentName));
conversationId = store.newConversation(agent.name());
String turnId = queue.submit(conversationId, question);
return send(exchange, 202, JSON.createObjectNode().put("turnId", turnId).put("jobId", turnId)
        .put("conversationId", conversationId).put("status", TurnRow.Status.QUEUED.name()));
```
The turn row and its queue message go in first, in one transaction, the `202` goes out, and a worker runs the loop.
</details>

<details><summary>proof: POST a question, then GET the turn</summary>

```
$ curl -s -X POST http://localhost:58833/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
{
  "turnId" : "r-478b",
  "jobId" : "r-478b",
  "conversationId" : "c-2674",
  "status" : "QUEUED"
}
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
  "spans" : [
    { "span" : 1, "pass" : 1, "kind" : "LLM",  "response" : "ToolCall[toolName=order_status, input={orderId=ORD-1}]", "status" : "OK", "ms" : 4 },
    { "span" : 2, "pass" : 1, "kind" : "TOOL", "response" : "Order ORD-1: SHIPPED, shipment SHP-1", "status" : "OK", "ms" : 0, "attrs" : { "key" : "r-478b/1" } },
    { "span" : 3, "pass" : 2, "kind" : "LLM",  "response" : "Done[]", "status" : "OK", "ms" : 0 }
  ]
}
```
The `202` came back before any worker had looked. One second later the turn is `DONE` with three spans, the answer, and the pid of the worker that ran it. The TOOL span's `key` is the job id plus the span that decided it: the idempotency key a tool that writes would use. (Trimmed; the full rows are in `demo-output.txt`.)
</details>

## Streaming the answer

- The stream is a second request: `GET /turns/{id}/events`, kept open.
- Every event is one stored span; the event id is the span number.
- Catch up, then follow: the spans already stored go out at once, then the store is polled every 200 ms until the turn is done, and a final `done` event carries the answer.
- A client that reconnects sends `Last-Event-ID` and gets only the tail. Nothing twice.
- A client that disconnects does not stop the turn; the worker keeps writing, the next listener reads the rows.

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| polling `GET /turns/{id}` | the client asks again and again | nothing new to learn | latency is the poll interval; the whole turn every time |
| SSE | one response kept open; events with ids; the browser reconnects with the last id by itself | one-way is all an answer needs; replay is in the protocol | one open connection per listener on the node that holds it |
| WebSocket | a two-way socket | the client can talk back | a second protocol, no built-in replay, the same open-connection cost |

I pick SSE: the answer flows one way, and the protocol already has the reconnect-with-last-id a step log needs. How that open connection scales across nodes is its own section.

<details><summary>the stream loop</summary>

```java
while (!stopped) {
    TurnRow turn = store.turn(id);
    for (Span row : store.load(id, after + 1)) {
        write(out, "id: " + row.span() + "\nevent: step\ndata: " + JSON.writeValueAsString(row) + "\n\n");
        after = row.span();
    }
    if (turn.status().ended()) { write(out, "event: done\ndata: " + done + "\n\n"); return 200; }
    Thread.sleep(POLL_MS);
}
```
Status is read before the rows, so `done` never goes out with a row unsent.
</details>

<details><summary>proof: the stream, then a reconnect with Last-Event-ID</summary>

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

$ curl -sN --max-time 5 -H 'Last-Event-ID: 2' http://localhost:58833/turns/r-478b/events
id: 3
event: step
data: {"turnId":"r-478b","span":3,"pass":2,"kind":"LLM","request":"pass 2","response":"Done[]","status":"OK","ms":0,"attrs":{"question":"What is the status of order ORD-1?"}}

event: done
data: {"turnId":"r-478b","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1"}
```
Three events then `done`; the reconnect starts at 3. A live one with the hanging tool held the response open for the 1000 ms the tool took to be cut off (`demo-output.txt`).
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
r-e841  support    DONE    1       
r-446c  chaos      DONE    1       
r-0630  support    DONE    1       
r-c6f0  support    DONE    1       
r-5085  concierge  DONE    1       
r-badd  loop       DONE    1       
r-505e  support    DONE    2       
$ sqlite3 -header -column harness.db "SELECT span, pass, kind, status, ms FROM span WHERE turn_id = 'r-505e'"
span  pass  kind  status  ms
----  ----  ----  ------  --
1     1     LLM   OK      4 
2     1     TOOL  OK      0 
3     1     NOTE  OK      0 
4     2     LLM   OK      1 
```
Seven turns from one run of the demo, two of them the sub-agent page's; the last one is the durability turn, two attempts, four spans including the resume note.
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
java -cp "target/classes:target/lib/*" harness.cli.Main worker
curl -s -X POST http://localhost:8080/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
curl -sN http://localhost:8080/turns/<turnId>/events
```

`./demo.sh` from the repo root runs all of it and `docs/demo-output.txt` is its saved output.
