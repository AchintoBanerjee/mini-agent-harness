# v2 HLD: keeping it simple

[What v2 is](#what-v2-is) · [The data model](#the-data-model) · [The HTTP endpoints](#the-http-endpoints) · [Streaming the answer](#streaming-the-answer) · [Persistence](#persistence) · [Agents and tools are metadata](#agents-and-tools-are-metadata) · [Try it](#try-it)

The harness as a small service: you call it over HTTP, its state lives in one SQLite file, and the answer streams back as the steps happen. The model is still a stub; nothing needs a key or a network.

## What v2 is

![v2 HLD](v2-hld.svg)

- **End users** ask a question and get a job id back at once; the answer follows as a stream.
- **API layer** is the one face: `POST /runs` writes the turn row and returns its id; `GET /runs/{id}` reads it; `GET /runs/{id}/events` streams it.
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
| `turn` | `id` (the job id) · `conversation_id` · `question` · `answer` · `status` (QUEUED · RUNNING · DONE · FAILED · DEAD) · `claimed_by` · `attempts` · `created_at` · `updated_at` |
| `span` | `turn_id` · `span` (the step number) · `pass` · `kind` (LLM · TOOL · NOTE) · `request` · `response` · `status` · `ms` · `attrs` (JSON) · key (`turn_id`, `span`) |

The durability section adds a fourth, `queue` (`turn_id` · `visible_at` · `receives`). In the code today the turn table is still called `job` and the span table `step`; the rename lands with the next commit.

<details><summary>the SQL that creates the tables</summary>

```sql
CREATE TABLE IF NOT EXISTS job (
  id TEXT PRIMARY KEY, conversation_id TEXT, agent TEXT, question TEXT,
  status TEXT, created_at INTEGER, updated_at INTEGER);
CREATE TABLE IF NOT EXISTS step (
  job_id TEXT, step INTEGER, turn INTEGER, pass INTEGER, kind TEXT, request TEXT,
  response TEXT, status TEXT, ms INTEGER, attrs TEXT, PRIMARY KEY (job_id, step));
```
</details>

## The HTTP endpoints

| Method | Path | What it does | Returns |
|---|---|---|---|
| GET | `/agents` | the agents, from `agents/*.json` | `200`, a list |
| GET | `/tools` | the tools, from `tools/*.json` | `200`, a list |
| POST | `/runs` | body `{"agent","question"}`: writes the turn row, starts the loop | `202 {"jobId","status"}` at once |
| GET | `/runs/{id}` | the turn, its spans, the answer when done | `200`; `404` |
| GET | `/runs/{id}/events` | the stream: stored spans from `Last-Event-ID`, then live, then `done` | `200 text/event-stream` |

The server is the JDK's own HTTP server, one virtual thread per request, JSON by hand. No framework.

<details><summary>the handler for POST /runs</summary>

```java
String jobId = store.newRun(agent.name(), question);
Thread.ofVirtual().name("run-" + jobId).start(() -> runJob(agent, jobId, question));
return send(exchange, 202, JSON.createObjectNode().put("jobId", jobId).put("status", "RUNNING"));
```
The turn row goes in first, the `202` goes out, the loop runs on its own thread.
</details>

<details><summary>proof: POST a question, then GET the turn</summary>

```
$ curl -s -X POST http://localhost:59760/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
{
  "jobId" : "r-be37",
  "status" : "RUNNING"
}
$ curl -s http://localhost:59760/runs/r-be37
{
  "id" : "r-be37",
  "agent" : "support",
  "question" : "What is the status of order ORD-1?",
  "status" : "DONE",
  "steps" : [
    { "step" : 1, "pass" : 1, "kind" : "LLM",  "response" : "ToolCall[toolName=order_status, input={orderId=ORD-1}]", "status" : "OK", "ms" : 3 },
    { "step" : 2, "pass" : 1, "kind" : "TOOL", "response" : "Order ORD-1: SHIPPED, shipment SHP-1", "status" : "OK", "ms" : 0, "attrs" : { "key" : "r-be37/1" } },
    { "step" : 3, "pass" : 2, "kind" : "LLM",  "response" : "Done[]", "status" : "OK", "ms" : 0 }
  ],
  "answer" : "Order ORD-1: SHIPPED, shipment SHP-1"
}
```
The `202` came back before the loop ran. One second later the turn is `DONE` with three spans and the answer. The TOOL span's `key` is the job id plus the step that decided it: the idempotency key a tool that writes would use. (Trimmed; the full rows are in `demo-output.txt`.)
</details>

## Streaming the answer

- The stream is a second request: `GET /runs/{id}/events`, kept open.
- Every event is one stored span; the event id is the span number.
- Catch up, then follow: the spans already stored go out at once, then the store is polled every 200 ms until the turn is done, and a final `done` event carries the answer.
- A client that reconnects sends `Last-Event-ID` and gets only the tail. Nothing twice.
- A client that disconnects does not stop the run; the loop keeps writing, the next listener reads the rows.

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| polling `GET /runs/{id}` | the client asks again and again | nothing new to learn | latency is the poll interval; the whole turn every time |
| SSE | one response kept open; events with ids; the browser reconnects with the last id by itself | one-way is all an answer needs; replay is in the protocol | one open connection per listener on the node that holds it |
| WebSocket | a two-way socket | the client can talk back | a second protocol, no built-in replay, the same open-connection cost |

I pick SSE: the answer flows one way, and the protocol already has the reconnect-with-last-id a step log needs. How that open connection scales across nodes is its own section.

<details><summary>the stream loop</summary>

```java
while (!stopped) {
    Job job = store.job(id);
    for (Span row : store.load(id, after + 1)) {
        write(out, "id: " + row.step() + "\nevent: step\ndata: " + JSON.writeValueAsString(row) + "\n\n");
        after = row.step();
    }
    if (job.status() != RUNNING) { write(out, "event: done\ndata: " + done + "\n\n"); return 200; }
    Thread.sleep(POLL_MS);
}
```
Status is read before the rows, so `done` never goes out with a row unsent.
</details>

<details><summary>proof: the stream, then a reconnect with Last-Event-ID</summary>

```
$ curl -sN --max-time 5 http://localhost:59760/runs/r-be37/events
id: 1
event: step
data: {"run":"r-be37","step":1,"kind":"LLM","response":"ToolCall[toolName=order_status, input={orderId=ORD-1}]","status":"OK", ...}

id: 2
event: step
data: {"run":"r-be37","step":2,"kind":"TOOL","response":"Order ORD-1: SHIPPED, shipment SHP-1","status":"OK", ...}

id: 3
event: step
data: {"run":"r-be37","step":3,"kind":"LLM","response":"Done[]","status":"OK", ...}

event: done
data: {"jobId":"r-be37","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1"}

$ curl -sN --max-time 5 -H 'Last-Event-ID: 2' http://localhost:59760/runs/r-be37/events
id: 3
event: step
data: {"run":"r-be37","step":3,"kind":"LLM","response":"Done[]","status":"OK", ...}

event: done
data: {"jobId":"r-be37","status":"DONE","answer":"Order ORD-1: SHIPPED, shipment SHP-1"}
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
$ sqlite3 -header -column harness.db "SELECT id, agent, status FROM job ORDER BY created_at DESC LIMIT 3"
id      agent    status
------  -------  ------
r-4d64  chaos    DONE
r-be37  support  DONE
r-184b  support  DONE

$ sqlite3 -header -column harness.db "SELECT step, kind, status, ms FROM step WHERE job_id = 'r-be37'"
step  kind  status  ms
----  ----  ------  --
1     LLM   OK      3
2     TOOL  OK      0
3     LLM   OK      0
```
</details>

## Agents and tools are metadata

- An agent is a file in `agents/`: name, the tools it may call, its pass cap, its per-call timeout.
- A tool is a file in `tools/` whose `type` picks the adapter; adding a tool is adding a file.
- A builder, the screen where someone defines an agent, would be its own service in front of this metadata, behind the API gateway. It adds nothing to this design, so it is not here.

## Try it

From `v2/`, with Java 21 or newer, Maven and curl:

```
mvn -q compile
java -cp "target/classes:target/lib/*" harness.cli.Main serve
curl -s -X POST http://localhost:8080/runs -d '{"agent":"support","question":"What is the status of order ORD-1?"}'
curl -sN http://localhost:8080/runs/<jobId>/events
```

`./demo.sh` from the repo root runs all of it and `docs/demo-output.txt` is its saved output.
