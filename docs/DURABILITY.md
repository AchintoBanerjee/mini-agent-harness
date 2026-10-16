# Durability

[The issue](#the-issue) · [The design](#the-design) · [The guarantee](#the-guarantee) · [The choices](#the-choices) · [What I ran](#what-i-ran) · [The implementation](#the-implementation) · [Proof](#proof)

- A turn (its id is the job id) is a message on a queue. A worker runs it. If the worker dies, the next worker loads the saved steps and continues from the last one.
- This is the shape I ran on my platform, in production, with an SQS-style queue. The POC shows the bare minimum: a SQLite table standing in for the queue, with the same three calls.
- Everything beyond that is out of scope here and says so.
- Every output below is pasted from `demo-output.txt`, which `./demo.sh` writes.

## The issue

- The agent is called; a turn is seconds of model and tool calls; the server dies in the middle of one.
- In v1 the loop lived in the request, so it died with the server, and the client's answer with it.
- The question is how the next worker continues the turn from its last saved step, without running the saved steps twice, and what happens to the one call that was in flight.

## The design

![durability](durability.svg)

- **The split.** The HLD page, `V2_HLD.md`, drew the HTTP face and the loop as one service. Here they become two processes with a queue between them: the API process takes the request, a worker process runs the turn. Two reasons. A turn has to outlive the request that created it. And the two scale on different signals: requests on one side, queue depth on the other.
- **End users → API layer**: the question comes in; the API writes the turn row (`QUEUED`) and a message on the queue in one transaction, and returns the turn's id, the job id, at once. No loop runs in this process.
- **Run queue**: the message is the turn. `receive` hides it for the visibility window, and that window is the lease: whoever holds it is the only worker on the turn.
- **Workers**: a worker receives, claims the turn row (`RUNNING`, its process id, attempts + 1) and runs the loop; each step is written to the store before the next one, and each written step extends the lease. The window has to outlast the longest step, the model call plus the tool timeout, or a heartbeat extends it on a timer; the demo's 2 s window beside a 2 s tool timeout is a demo setting, not the rule.
- **Ack**: only after the last step is saved and the turn row says `DONE` with the answer does the worker delete the message.
- **Dead worker**: no ack, the lease runs out, the message is visible again; the next worker receives it, finds the saved steps, and resumes from the last one, so the model is asked only for what comes next.
- **DLQ** (dead-letter queue), design only: after N receives the message goes to a dead-letter queue instead of looping forever.
- **The stream back to the client**: the worker pushes each step and each piece of the answer to the run's stream as they happen. The API node holding the client's connection forwards the stream as Server-Sent Events (SSE), so it does not care which worker is on the turn. If the stream is gone, the API sends the saved rows instead, once.
- **The call in flight**: a worker that dies after a tool did its work and before the row landed leaves one call that runs twice. Every call carries a key, and a tool that writes ignores the repeat. In v2 the key is the job id plus the step of the model call that asked for the tool, so two calls from one batch share one key. Production needs one more part in the key: the call's position in the batch.

| table | columns | what it is for |
| --- | --- | --- |
| `conversation` | `id` · `agent` · `created_at` | one row per conversation: who talks to which agent |
| `turn` | `id` (the job id) · `conversation_id` · `question` · `answer` · `status` (QUEUED · RUNNING · DONE · FAILED) · `claimed_by` · `attempts` · `created_at` · `updated_at` | one row per turn: where it is, who has it, how many workers tried, the answer |
| `span` | `turn_id` · `span` · `pass` · `kind` · `request` · `response` · `status` · `ms` · `attrs` (JSON) · key (`turn_id`, `span`) | one row per saved step: what resume and the stream read |
| `queue` | `turn_id` · `visible_at` · `receives` | the message: hidden until `visible_at`, counted on every receive |

Out of scope for the POC, in the design only: the dead-letter queue, a sweeper for jobs that never finish, more than one worker process at a time on one file.

## The guarantee

What the queue promises, and what it cannot, on one turn.

![the two places a worker can die](durability-windows.svg)

| What | Guarantee | What makes it true |
|---|---|---|
| a step that is saved | runs once | resume starts after it |
| the model's decision | recorded, never asked twice | it is saved before the tool runs; `crashAfterTheLlmRowIsPersistedResumesWithoutAskingTheModelAgain` in `ResumeTest` |
| the one call in flight when the worker dies | may run twice | the harness cannot know whether the tool did its work, so it runs it again: at least once. Not in the demo; `crashBeforeTheToolRowIsPersistedRunsThePendingCallAgain` in `ResumeTest` is the proof |
| a tool that writes | no double effect | it is idempotent on the key the harness gives it, job id plus step |
| the whole turn | finishes, or is handed to the next worker | ack only after the last step is saved; no ack, the lease runs out |

- In the diagram, window A is a death after a step is saved: the table's first two rows. Window B is a death after the tool ran but before its row was saved: the next two rows; a read that repeats there is harmless.
- So the queue is at-least-once, and the tools carry the rest. That is good enough for production; it is what I ran.
- Temporal does not change this contract. A completed step is in its history and never re-runs, but the side effect inside a step is still at least once, so activities must still be idempotent. What you buy is that the bookkeeping is no longer yours to get right.

## The choices

| option | how it works | what you get | what it costs |
| --- | --- | --- | --- |
| a job table polled by workers | workers `SELECT` the oldest `QUEUED` row and `UPDATE` it to `RUNNING` with their name; a stuck row is found by its age | nothing new: the table you already have | the claim race, the "is it stuck" clock and the sweeper for orphans are all yours to write and get right |
| a queue with a visibility timeout | the three calls of SQS (Amazon's Simple Queue Service): `receive` hides the message for a window, `extend` pushes the window, `ack` deletes; no ack and the window runs out on its own | the orphan problem is solved by the protocol: a dead worker's message comes back by itself, counted, and a limit sends it to the DLQ | the message and the job row are two things to keep in step; the one call in flight at a crash can run twice |
| Temporal | a workflow engine replays the turn's history to rebuild its state; activities retry by policy, timers and signals are built in | a completed step is in its history and never re-runs; retries, timers, long waits, versioning, a UI | a cluster to run, a programming model to learn, the loop rewritten as a workflow |

The queue is what I ran: the simplest thing that worked at that scale, and the lease and the redelivery come from the protocol instead of from code I have to get right. Temporal is the step up: its own infrastructure and a programming model, in return for replay, timers and long waits. We never evaluated it; if I built this again, that is where I would start.

## What I ran

- The first version checkpointed the run only at wait points: an approval, a timer, a long call. Between them the run lived in memory, and a crash between them lost it.
- The next version put an SQS-style queue with a visibility timeout in front of the loop: the worker extends it while the run is live, acks only after the last step is persisted, a dead worker's message becomes visible again and another pod picks it up, repeated failures go to the DLQ. The scaling lever became queue depth, not request count.
- Resume is an opt-in log of completed tool calls per conversation: the new pod rebuilds the context from it and the model continues.
- The contract: completed tool calls run once, the one in-flight call at least once, so tool writes are idempotent by key. If that is ever not enough, the fix is a started-marker before the call and a check on resume.
- With hindsight durability was the real hard problem; where Temporal fits, and why I would start there next time, is in The choices above.

## The implementation

What runs here:

- One build, two commands. `serve` is the API process; `worker` is the orchestrator process. Two JVM processes on one laptop, one SQLite file between them.
- Not containers, not pods. A process exiting stands in for a pod dying, a second process for the next pod, the `queue` table for SQS, the file for the tenant database. The one network call between the two is the worker pushing the run's stream to the API over HTTP; the API holds that stream in memory, standing in for the Redis Stream.
- One worker at a time on the file. More than one worker process at once is out of scope here: SQLite has one writer, and the three calls would move to SQS before a second worker is needed.
- The queue is one table in the same file as the runs, and `RunQueue` is the three calls on it; swap the table for SQS and the three calls are the same.

<details><summary>the three SQL statements of the queue: receive, extend, ack</summary>

```java
public Optional<Message> receive(long visibilityMs) {                    // the oldest visible message, now hidden and counted
    """UPDATE queue SET visible_at = ?, receives = receives + 1
       WHERE turn_id = (SELECT turn_id FROM queue WHERE visible_at <= ? ORDER BY rowid LIMIT 1)
       RETURNING turn_id, receives"""                                   // ? = now + visibilityMs, now
}
public void extend(String turnId, long visibilityMs) {                    // the worker is alive and still on it
    "UPDATE queue SET visible_at = ? WHERE turn_id = ?"                   // ? = now + visibilityMs
}
public void ack(String turnId) {                                          // the turn is done
    "DELETE FROM queue WHERE turn_id = ?"
}
```

One `UPDATE` both finds the oldest visible message and hides it, so two workers asking at the same moment cannot both get it; `extend` and `ack` are one statement each. The database plumbing around each statement is left out here; `RunQueue.java` has it.
</details>

The worker is the loop around those calls, `Worker.once`: receive, claim the row, load the saved rows, `start` when there are none and `resume` when there are, ack last. The store says whether a worker died on this turn, so resuming is the normal path, not a special one. The worker's listener on the loop runs after each row is in the store and extends the lease there, so the lease moves with the saved work.

## Proof

The demo, the bare minimum, two processes on one laptop: submit, a worker process that dies after its second row, two seconds for the lease to run out, a second worker process resuming from that row and continuing the chain, the turn DONE over HTTP.

<details><summary>proof: the crash, the expired lease, the resume</summary>

```
(submit writes the turn row QUEUED and its queue message without HTTP, and comes back with the id:)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main submit --agent support "What is the status of order ORD-1?"
submitted r-9329 · QUEUED in harness.db · agent support · "What is the status of order ORD-1?"
$ curl -s http://localhost:64430/turns/r-9329
{
  "id" : "r-9329",
  "conversationId" : "c-6222",
  "agent" : "support",
  "question" : "What is the status of order ORD-1?",
  "answer" : null,
  "status" : "QUEUED",
  "claimedBy" : null,
  "attempts" : 0,
  "createdAt" : 1792113834392,
  "updatedAt" : 1792113834392,
  "spans" : [ ]
}
(a worker takes it with a 2000 ms lease and dies right after its 2nd row is persisted: exit 3, no ack; the order lookup ran once in this process, so the crash line counts order_status=1)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --crash-after 2 --visibility-ms 2000 --api http://localhost:64430
worker pid 64467 · lease 2000 ms · once · streams to http://localhost:64430 · harness.db
received r-9329: attempt 1 · agent support · "What is the status of order ORD-1?"
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 2 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
crash: exit 3 right after row 2 of turn r-9329 was persisted (simulated) · handler calls: order_status=1
(process exited with code 3; the turn row says RUNNING, the message is still queued but hidden until the lease runs out)
$ sleep 2
(the lease has run out, so the next worker receives the same message again and resumes from the last saved step: it does not run the saved order_status call again, it continues the chain at pass 2, reads SHP-1 in the saved result, asks for the shipment and says done; its footer counts shipping_status=1 only, because order_status ran in the process that died:)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --api http://localhost:64430
worker pid 64469 · lease 2000 ms · once · streams to http://localhost:64430 · harness.db
received again: attempt 2, lease had expired · r-9329 resumes from step 2
  3  NOTE  resumed from step 2  [OK 0 ms]
  4  LLM   pass 2 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]  [OK 1 ms]
  5  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 0 ms]
  6  LLM   pass 3 -> Done[]  [OK 0 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
done r-9329 · 6 spans · 94 ms · DONE in harness.db · acked · handler calls: shipping_status=1
(the API reads the same rows, whoever wrote them: DONE, attempt 2, claimed by the second worker's pid, all six spans, the answer with both lines)
$ curl -s http://localhost:64430/turns/r-9329
{
  "id" : "r-9329",
  "conversationId" : "c-6222",
  "agent" : "support",
  "question" : "What is the status of order ORD-1?",
  "answer" : "Order ORD-1: SHIPPED, shipment SHP-1\nShipment SHP-1: IN TRANSIT, arriving 2026-10-16",
  "status" : "DONE",
  "claimedBy" : "64469",
  "attempts" : 2,
  "createdAt" : 1792113834392,
  "updatedAt" : 1792113838090,
  "spans" : [ {
    "turnId" : "r-9329",
    "span" : 1,
    "pass" : 1,
    "kind" : "LLM",
    "request" : "pass 1",
    "response" : "ToolCall[toolName=order_status, input={orderId=ORD-1}]",
    "status" : "OK",
    "ms" : 2,
    "attrs" : {
      "question" : "What is the status of order ORD-1?",
      "tool" : "order_status",
      "orderId" : "ORD-1"
    }
  }, {
    "turnId" : "r-9329",
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
      "key" : "r-9329/1"
    }
  }, {
    "turnId" : "r-9329",
    "span" : 3,
    "pass" : 1,
    "kind" : "NOTE",
    "request" : "resumed from step 2",
    "response" : "",
    "status" : "OK",
    "ms" : 0,
    "attrs" : { }
  }, {
    "turnId" : "r-9329",
    "span" : 4,
    "pass" : 2,
    "kind" : "LLM",
    "request" : "pass 2",
    "response" : "ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]",
    "status" : "OK",
    "ms" : 1,
    "attrs" : {
      "question" : "What is the status of order ORD-1?",
      "tool" : "shipping_status",
      "shipmentId" : "SHP-1"
    }
  }, {
    "turnId" : "r-9329",
    "span" : 5,
    "pass" : 2,
    "kind" : "TOOL",
    "request" : "shipping_status {shipmentId=SHP-1}",
    "response" : "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "tool" : "shipping_status",
      "shipmentId" : "SHP-1",
      "key" : "r-9329/4"
    }
  }, {
    "turnId" : "r-9329",
    "span" : 6,
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
</details>

- The first worker's last line is row 2 and `exit 3`, and its crash line counts `order_status=1`. The second worker's first line is `received again: attempt 2, lease had expired`, its first new row is step 3, `resumed from step 2`, and it continues the chain: it reads SHP-1 in the saved result, asks for the shipment at pass 2, and says done at pass 3. Its footer counts `shipping_status=1` only: the saved order lookup did not run again, and the model was not asked again for the decision it had already recorded.
- `GET /turns/{id}` after that: `DONE`, `attempts 2`, `claimedBy` is the second worker's pid, six spans and the answer with both lines; the API read the rows and never knew which process wrote which.
