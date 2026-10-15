# Durability

[The issue](#the-issue) · [The design](#the-design) · [The choices](#the-choices) · [The implementation](#the-implementation) · [Proof](#proof)

A turn (job id) becomes a message on a queue, a worker runs it, and when the worker dies the next one picks the turn up from its last saved step. Every output below is pasted from `demo-output.txt`, which `./demo.sh` writes.

## The issue

- The agent is called; a turn is seconds of model and tool calls; the server dies in the middle of one.
- In v1 the loop lived in the request, so it died with the server, and the client's answer with it.
- The question is how the next server picks the turn up from where it stopped, without running the saved steps twice.

## The design

![durability](durability.svg)

- **End users → API layer**: the question comes in; the API writes the job row (`QUEUED`) and a message on the queue in one transaction, and returns the job id at once. No loop runs in this process.
- **Run queue**: the message is the turn. `receive` hides it for the visibility window, and that window is the lease: whoever holds it is the only worker on the turn.
- **Workers**: a worker receives, claims the job row (`RUNNING`, its pid, attempts + 1) and runs the loop; each step is written to the store before the next one, and each written step extends the lease.
- **Ack**: only after the last step is saved and the job row says `DONE` does the worker delete the message.
- **Dead worker**: no ack, the lease runs out, the message is visible again; the next worker receives it, finds the saved steps, and resumes from the last one, so the model is asked only for what comes next.
- **DLQ** (dead-letter queue): a message received more than 3 times is not delivered a fourth time; the job row goes to `DEAD` and `GET /dlq` lists it.
- **Steps back to the client**: the API reads the steps from the store and streams them as Server-Sent Events (SSE), so it does not care which worker wrote them.
- **The call in flight**: a worker that dies after a tool did its work and before the row landed leaves one call that runs twice; every call carries a key (job id + deciding step), and a tool that writes ignores the repeat.

| table | columns | what it is for |
| --- | --- | --- |
| `job` | `id` · `conversation_id` · `agent` · `question` · `status` (QUEUED · RUNNING · DONE · FAILED · DEAD) · `claimed_by` · `attempts` · `created_at` · `updated_at` | one row per turn (job id): where it is, who has it, how many workers tried |
| `step` | `job_id` · `step` · `turn` · `pass` · `kind` · `request` · `response` · `status` · `ms` · `attrs` (JSON) · key (`job_id`, `step`) | one row per saved step: what resume and the stream read |
| `queue` | `job_id` · `visible_at` · `receives` | the message: hidden until `visible_at`, counted on every receive |

The diagram's names for the same rows, conversation → turn (job id) → span, are the whiteboard's; in the code today the tables are `job` and `step`. `FAILED` is the loop throwing on an attempt: the worker acks it and the rows stay for a human, since the same code would throw again.

## The choices

| option | how it works | what you get | what it costs |
| --- | --- | --- | --- |
| a job table polled by workers | workers `SELECT` the oldest `QUEUED` row and `UPDATE` it to `RUNNING` with their name; a stuck row is found by its age | nothing new: the table you already have | the claim race, the "is it stuck" clock and the sweeper for orphans are all yours to write and get right |
| a queue with a visibility timeout | the three calls of SQS (Amazon's Simple Queue Service): `receive` hides the message for a window, `extend` pushes the window, `ack` deletes; no ack and the window runs out on its own | the orphan problem is solved by the protocol: a dead worker's message comes back by itself, counted, and a limit sends it to the DLQ | the message and the job row are two things to keep in step; the one call in flight at a crash can run twice |
| Temporal | a workflow engine replays the turn's history to rebuild its state; activities retry by policy, timers and signals are built in | exactly-once workflow steps, retries, timers, long waits, versioning, a UI | a cluster to run, a programming model to learn, the loop rewritten as a workflow |

I pick the queue: the simplest thing that works at the scale I ran; Temporal is the step up if the shapes outgrow it.

## The implementation

The queue is one table in the same SQLite file as the runs, and `RunQueue` is the three calls on it; swap the table for SQS and the three calls are the same.

<details><summary>the three SQL statements of the queue: receive, extend, ack</summary>

```java
public Optional<Message> receive(long visibilityMs) {
    Message message = lease(visibilityMs);
    if (message != null && message.receives() > maxReceives) {        // one receive too many
        store.setStatus(message.jobId(), Job.Status.DEAD);
        ack(message.jobId());
        return Optional.of(new Message(message.jobId(), message.receives(), true));
    }
    return Optional.ofNullable(message);
}
private Message lease(long visibilityMs) {                               // the one UPDATE: hide and count
    """UPDATE queue SET visible_at = ?, receives = receives + 1
       WHERE job_id = (SELECT job_id FROM queue WHERE visible_at <= ? ORDER BY rowid LIMIT 1)
       RETURNING job_id, receives"""                                     // ? = now + visibilityMs, now
}
public void extend(String jobId, long visibilityMs) {                     // the worker is alive and still on it
    "UPDATE queue SET visible_at = ? WHERE job_id = ?"                    // ? = now + visibilityMs
}
public void ack(String jobId) {                                           // the job is done
    "DELETE FROM queue WHERE job_id = ?"
}
```

One `UPDATE` both finds the oldest visible message and hides it, so two workers asking at the same moment cannot both get it; `extend` and `ack` are one statement each. The JDBC plumbing around each statement is left out here; `RunQueue.java` has it.
</details>

The worker is the loop around those calls: receive, claim, run or resume, ack last.

<details><summary>the worker loop</summary>

```java
Optional<RunQueue.Message> next = queue.receive(visibilityMs);
if (next.isEmpty()) { return false; }                                     // loop() waits 500 ms and asks again
RunQueue.Message message = next.get();
String jobId = message.jobId();
if (message.dead()) { System.out.println("dead-lettered " + jobId + ...); return true; }
int attempt = store.claim(jobId, name);                                    // RUNNING, claimed_by, attempts + 1
List<Span> saved = store.load(jobId);
RunListener listener = span -> { queue.extend(jobId, visibilityMs); print.on(span); };   // after every written row
String answer = saved.isEmpty()
        ? orchestrator(job, listener).start(jobId, job.question())        // a fresh turn
        : orchestrator(job, listener).resume(jobId);                      // a dead worker's turn, from its last row
queue.ack(jobId);                                                          // the last thing
```

The store says whether a worker died on this job (it has steps), so resuming is the normal path, not a special one. The listener runs after each row is in the store, so the lease moves with the saved work; the ack comes last.
</details>

## Proof

The demo: submit, a worker that dies after its second row, two seconds for the lease to run out, the next worker resuming, the job DONE over HTTP; then a job whose worker dies three times, and the dead-letter list.

<details><summary>proof: the crash, the expired lease, the resume, the dead-letter case</summary>

```
(no worker is running now. submit writes the job row QUEUED and its queue message, and comes back with the id:)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main submit --agent support "What is the status of order ORD-1?"
submitted r-9441 · QUEUED in harness.db · agent support · "What is the status of order ORD-1?"
$ curl -s http://localhost:50352/runs/r-9441
{
  "id" : "r-9441",
  "conversationId" : "r-9441",
  "agent" : "support",
  "question" : "What is the status of order ORD-1?",
  "status" : "QUEUED",
  "claimedBy" : null,
  "attempts" : 0,
  "createdAt" : 1792031797031,
  "updatedAt" : 1792031797031,
  "steps" : [ ]
}
(a worker takes it with a 2000 ms lease and dies right after its 2nd row is persisted: exit 3, no ack)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --crash-after 2 --visibility-ms 2000
worker pid 27732 · lease 2000 ms · max receives 3 · once · harness.db
received r-9441: attempt 1 · agent support · "What is the status of order ORD-1?"
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]  [OK 3 ms]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1  [OK 0 ms]
crash: exit 3 right after row 2 of run r-9441 was persisted (simulated) · handler calls: order_status=1
(process exited with code 3; the job row says RUNNING, the message is still queued but hidden until the lease runs out)
$ sleep 2
(the lease has run out, so the next worker receives the same message again and resumes from the last saved step:)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once
worker pid 27734 · lease 2000 ms · max receives 3 · once · harness.db
received again: attempt 2, lease had expired · r-9441 resumes from step 2
  3  NOTE  resumed from step 2  [OK 0 ms]
  4  LLM   pass 2 -> Done[]  [OK 2 ms]
answer: Order ORD-1: SHIPPED, shipment SHP-1
done r-9441 · 4 steps · 29 ms · DONE in harness.db · acked · handler calls: none
(the API reads the same rows, whoever wrote them: DONE, attempt 2, claimed by the second worker's pid, all four steps)
$ curl -s http://localhost:50352/runs/r-9441
{
  "id" : "r-9441",
  "conversationId" : "r-9441",
  "agent" : "support",
  "question" : "What is the status of order ORD-1?",
  "status" : "DONE",
  "claimedBy" : "27734",
  "attempts" : 2,
  "createdAt" : 1792031797031,
  "updatedAt" : 1792031800194,
  "steps" : [ {
    "run" : "r-9441",
    "step" : 1,
    "turn" : 1,
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
    "run" : "r-9441",
    "step" : 2,
    "turn" : 1,
    "pass" : 1,
    "kind" : "TOOL",
    "request" : "order_status {orderId=ORD-1}",
    "response" : "Order ORD-1: SHIPPED, shipment SHP-1",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "tool" : "order_status",
      "orderId" : "ORD-1",
      "key" : "r-9441/1"
    }
  }, {
    "run" : "r-9441",
    "step" : 3,
    "turn" : 1,
    "pass" : 1,
    "kind" : "NOTE",
    "request" : "resumed from step 2",
    "response" : "",
    "status" : "OK",
    "ms" : 0,
    "attrs" : { }
  }, {
    "run" : "r-9441",
    "step" : 4,
    "turn" : 1,
    "pass" : 2,
    "kind" : "LLM",
    "request" : "pass 2",
    "response" : "Done[]",
    "status" : "OK",
    "ms" : 2,
    "attrs" : {
      "question" : "What is the status of order ORD-1?"
    }
  } ],
  "answer" : "Order ORD-1: SHIPPED, shipment SHP-1"
}

(the dead-letter case: a job whose worker dies after its first row every time; leases of 1000 ms, a second of sleep so each runs out)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main submit --agent support "Where is my shipment SHP-1?"
submitted r-e50c · QUEUED in harness.db · agent support · "Where is my shipment SHP-1?"
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --crash-after 1 --visibility-ms 1000
worker pid 27743 · lease 1000 ms · max receives 3 · once · harness.db
received r-e50c: attempt 1 · agent support · "Where is my shipment SHP-1?"
  1  LLM   pass 1 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]  [OK 3 ms]
crash: exit 3 right after row 1 of run r-e50c was persisted (simulated) · handler calls: none
$ sleep 1
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --crash-after 1 --visibility-ms 1000
worker pid 27748 · lease 1000 ms · max receives 3 · once · harness.db
received again: attempt 2, lease had expired · r-e50c resumes from step 1
  2  NOTE  resumed from step 1  [OK 0 ms]
crash: exit 3 right after row 2 of run r-e50c was persisted (simulated) · handler calls: none
$ sleep 1
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --crash-after 1 --visibility-ms 1000
worker pid 27751 · lease 1000 ms · max receives 3 · once · harness.db
received again: attempt 3, lease had expired · r-e50c resumes from step 2
  3  NOTE  resumed from step 2  [OK 0 ms]
crash: exit 3 right after row 3 of run r-e50c was persisted (simulated) · handler calls: none
$ sleep 1
(three receives, three dead workers; max receives is 3, so the fourth receive does not deliver the message: it dead-letters the job)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once
worker pid 27756 · lease 2000 ms · max receives 3 · once · harness.db
dead-lettered r-e50c: received 4 times, more than max 3 · DEAD in harness.db after 3 attempts
$ curl -s http://localhost:50352/dlq
[ {
  "id" : "r-e50c",
  "conversationId" : "r-e50c",
  "agent" : "support",
  "question" : "Where is my shipment SHP-1?",
  "status" : "DEAD",
  "claimedBy" : "27751",
  "attempts" : 3,
  "createdAt" : 1792031800687,
  "updatedAt" : 1792031805890
} ]
```
</details>

- The first worker's last line is row 2 and `exit 3`; the second worker's first line is `received again: attempt 2, lease had expired`, its first new row is step 3, `resumed from step 2`, and its footer says `handler calls: none`: the saved tool call did not run again, and the model was asked once more, for pass 2 only.
- `GET /runs/r-9441` after that: `DONE`, `attempts 2`, `claimedBy` is the second worker's pid, four steps and the answer; the API read the rows and never knew which process wrote which.
- The second job: three workers, each resuming at the row before and dying after its own first, `attempts 3`; the fourth receive delivered nothing and set the row `DEAD`, and `GET /dlq` is `SELECT ... FROM job WHERE status = 'DEAD'`.
