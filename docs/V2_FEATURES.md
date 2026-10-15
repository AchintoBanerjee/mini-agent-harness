# v2: additional features

[The context is the memory](#the-context-is-the-memory) · [Parallel tool calls](#parallel-tool-calls) · [A clock on every tool call](#a-clock-on-every-tool-call) · [The agent's tool list is the gate](#the-agents-tool-list-is-the-gate) · [The pass cap and the budget](#the-pass-cap-and-the-budget) · [Chaining across passes](#chaining-across-passes) · [The answer](#the-answer)

What the loop already does beyond the HLD picture, before the deep dives. Each one: what it does, a real run, where it lives. Every output is from `./demo.sh`; the committed `demo-output.txt` is a later run, so ids and timings differ, nothing else.

| Feature | In v2 | The proof |
|---|---|---|
| the context is the memory | built: every pass the model gets the question plus every row of the turn so far, rebuilt from the store, so a resumed worker sees the same | every turn; the durability demo for the rebuild |
| parallel tool calls | built: two tools in one reply run together, rows in request order, a note row says wall against work | the demo's `--slow` section |
| a clock on every tool call | built: the agent's `toolTimeoutMs`, the call is cut off, the answer names it | the chaos stream in the demo |
| the agent's tool list is the gate | built: a tool the model names outside the list is `DENIED` without running, the loop moves on | `OrchestratorTest` |
| the pass cap and the budget | built: `maxPasses` per agent, `maxModelCalls` shared down a sub-agent tree, the depth cap | `OrchestratorTest`, `SubAgentTest`, the loop agent in the demo |
| chaining across passes | built: after pass 1 the stub reads the results and asks for what they name; the status question is a three-pass chain, and a dead worker's successor continues it | the first turn of the demo; the durability section |
| the answer | built: the last pass's results relayed, failures named; a model's final reply replaces it | every turn in the demo |

## The context is the memory

- The model keeps nothing between calls. Every pass, the loop builds the context again: the question, and every row of this turn so far, what was decided and what each tool returned. That is the memory inside a turn, and it is the rows.
- The rows live in the store before the next pass is taken, so a worker that resumes loads them and builds the same context the dead one had. A chain continues where it stopped; the first lookup does not run again.
- The rows only ever grow at the end. That is what lets the model server reuse what it already read, the prefix cache on the performance page.

What pass 2 sees for the status question: the question again, row 1 (the decision) and row 2 (the result). The stub reads row 2, finds SHP-1 and asks for `shipping_status`; a real model does the same from the text. That is the chain.

```
question: What is the status of order ORD-1?
rows so far:
  1  LLM   pass 1 -> ToolCall[toolName=order_status, input={orderId=ORD-1}]
  2  TOOL  order_status {orderId=ORD-1} -> Order ORD-1: SHIPPED, shipment SHP-1
```

<details><summary>the code</summary>

```java
/** What the model sees: the question, the rows already recorded in this turn, and the earlier turns as Q/A lines. */
public record Context(String question, List<Span> spans, List<String> history) { }

// Orchestrator, before every model call
private Context context(Turn turn) {
    ...
    return new Context(turn.question(), List.copyOf(turn.spans()), history);
}

// RuleBasedLLM: pass 1 reads the question, later passes read the rows
boolean callRowExists = context.spans().stream().anyMatch(span -> span.kind().isCall());
List<ToolCall> calls = callRowExists ? chained(context.spans(), tools) : fromQuestion(context.question(), tools);
if (calls.isEmpty()) {
    return new Done();
}
```
</details>

Where: `Context`, `Orchestrator.context`, `Orchestrator.open` for the rebuild on resume. The durability page's proof is the rebuild in action.

## Parallel tool calls

- The model may name several tools in one reply. The loop runs them together, not one after the other.
- Each call gets its own row, in the order the model asked for them, with its own time. A note row after them says the wall time against the summed work, so you can see they overlapped.
- Each call is still inside the agent's timeout, counted from the moment the batch was submitted.

The run, with `--slow` giving every lookup 300 ms of simulated latency; two lookups take one sleep of wall time, not two:

```
received r-08b6: attempt 1 · agent support · "Status of order ORD-2 and is product PRD-1 available?"
  1  LLM   pass 1 -> ToolCalls[calls=[ToolCall[toolName=order_status, input={orderId=ORD-2}], ToolCall[toolName=stock_status, input={productId=PRD-1}]]]  [OK 4 ms]
  2  TOOL  order_status {orderId=ORD-2} -> Order ORD-2: PROCESSING  [OK 303 ms]
  3  TOOL  stock_status {productId=PRD-1} -> Product PRD-1: 42 units in stock  [OK 302 ms]
  4  NOTE  ran 2 tools together: wall 306 ms, work 605 ms  [OK 306 ms]
  5  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Order ORD-2: PROCESSING
Product PRD-1: 42 units in stock
```

Where: `ToolCaller.callAll` submits every call to one virtual-thread executor and waits for each within the timeout; `Orchestrator.runCalls` writes the rows in request order and the note.

<details><summary>the code</summary>

```java
/** Runs the calls together, each within the timeout from this submission; results come back in request order. */
public List<ToolResult> callAll(List<ToolCall> calls) {
    long submitted = System.nanoTime();
    List<Future<ToolResult>> futures = new ArrayList<>();
    for (ToolCall call : calls) {
        futures.add(executor.submit(() -> run(call)));
    }
    List<ToolResult> results = new ArrayList<>();
    for (Future<ToolResult> future : futures) {
        results.add(await(future, submitted));
    }
    return results;
}
```
</details>

## A clock on every tool call

- Every tool call runs under the agent's `toolTimeoutMs`. Past it the call is cancelled, its row says `TIMEOUT`, and the loop goes on to the next pass as if the tool had answered.
- The failure reaches the user: the answer names the tool and the cut-off. Nothing hangs the turn, nothing hangs the worker.

The chaos agent has `toolTimeoutMs: 1000`; its `hang_forever` tool sleeps for a minute:

```
id: 1
event: step
data: {"turnId":"r-a091","span":1,"pass":1,"kind":"LLM","request":"pass 1","response":"ToolCall[toolName=hang_forever, input={orderId=ORD-1}]","status":"OK","ms":0, …}

id: 2
event: step
data: {"turnId":"r-a091","span":2,"pass":1,"kind":"TOOL","request":"hang_forever {orderId=ORD-1}","response":"cut off after 1000 ms","status":"TIMEOUT","ms":1000, …}

id: 3
event: step
data: {"turnId":"r-a091","span":3,"pass":2,"kind":"LLM","request":"pass 2","response":"Done[]","status":"OK","ms":0, …}

event: done
data: {"turnId":"r-a091","status":"DONE","answer":"Tool hang_forever TIMEOUT: cut off after 1000 ms"}
```

Where: `ToolCaller.await` waits for the call until its deadline and cancels it past it. A tool that throws is the same shape with `ERROR` and the exception's message.

## The agent's tool list is the gate

- An agent file lists the tools it may call. A tool the model names outside that list is not run: its row says `DENIED`, "not in this agent's tool list", and the loop moves on.
- A tool that does not exist at all is `ERROR`, "Unknown tool". Both are decided in the harness, before anything runs.

Where: `ToolCaller.run`, the first two checks. The test `aModelThatNamesAToolOutsideTheAgentsListIsDeniedAndTheLoopMovesOn` in `OrchestratorTest` is the proof.

## The pass cap and the budget

- `maxPasses` per agent caps the loop. A model that never says done is stopped at the cap, the last row is a note, "cut short at maxPasses=2", status `CUT_SHORT`, and the turn ends `DONE` with what it has.
- `maxModelCalls` per agent, default 20, is one budget of model calls for the whole turn, parent and child loops together. Exhausted, the turn ends `FAILED` with a note row, "budget exhausted: 3 model calls for this turn". The cap is on the loop, not in a prompt.
- The depth cap: a child loop at depth 2 cannot delegate again. An agent that lists itself as a tool stops there with an error row instead of a stack overflow:

```
  1  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 4 ms]
    2  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 0 ms]
      3  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 0 ms]
      4  AGENT  ask_loop {goal=loop, please} -> Tool failed: depth limit: a loop at depth 2 cannot delegate  [ERROR 0 ms]
```

Where: `Orchestrator.loop` for the cap and the budget, `TurnBudget`, `Orchestrator.MAX_DEPTH`. Tests: `hittingTheCapLeavesACutShortNoteAsTheLastRow`, `oneBudgetOfModelCallsStopsParentAndChildTogether`, `anAgentThatListsItselfStopsAtTheDepthCapWithAnErrorRow`.

## Chaining across passes

- A chain is inside one turn: pass 2 asks for a tool because of what pass 1 returned. Pass N's tool result is in the context the model sees in pass N+1, so a model that needs a second lookup asks for it next, and so on until it says done. Resume works in the middle of a chain, because every pass is rows in the store.
- The stub does it with one rule. Pass 1 reads the question: every tool whose keywords and id pattern fit is called, one as a single call, several as one batch run together. Later passes read the results: every OK row is scanned for the ids each id-taking tool knows, and each tool and id that has no call row yet is called. A row that is a reference or a failure is not read. Done when nothing new is named. That is what a model does when a result names something it can look up.

The status question, from the worker's log: pass 1 asks for the order, pass 2 asks for the shipment the order named, pass 3 says done:

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

- A dead worker's successor continues the chain: it loads rows 1 and 2, does not run the order lookup again, and takes the pass 2 decision from the saved result. The durability page has that run, with the two footers `order_status=1` and `shipping_status=1`.
- The other shape of a chain is a child agent: the parent decides, the child runs its own pass, the parent gets the answer and finishes. Six rows, one turn:

```
  1  LLM   pass 1 -> ToolCall[toolName=ask_logistics, input={goal=Where is shipment SHP-1 for my order?}]
    2  LLM   pass 1 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]
    3  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
    4  LLM   pass 2 -> Done[]
  5  AGENT  ask_logistics {goal=…} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
  6  LLM   pass 2 -> Done[]
```

Where: `RuleBasedLLM.next` for the rule, `Orchestrator.loop` and `Orchestrator.context` for the loop; the sub-agents page has the rest of the child.

## The answer

- What v2 does: every tool result of the turn, relayed unchanged, one per line in the order they ran, each once. A repeated call is swapped for the output of the step it repeats. A tool that did not end OK is named with its status, so the failure reaches the user. A child agent's own rows stay inside its answer. No tool ran: "No tool for this question."

| The turn | The answer |
|---|---|
| one lookup, `order_status(ORD-2)` | `Order ORD-2: PROCESSING` |
| a chain, the order then the shipment it named | `Order ORD-1: SHIPPED, shipment SHP-1` ⏎ `Shipment SHP-1: IN TRANSIT, arriving 2026-10-16` |
| two tools in one pass | `Order ORD-2: PROCESSING` ⏎ `Product PRD-1: 42 units in stock` |
| the tool timed out | `Tool hang_forever TIMEOUT: cut off after 1000 ms` |
| the tool threw | `Tool throw_boom ERROR: Tool failed: boom` |
| nothing matched | `No tool for this question.` |

- Why it is this simple: a rule stub decides, it does not write. In production the model's final reply is the answer, written with the tool results in its prompt, and it reaches the user through the same path, streamed piece by piece. The relay is the stand-in for that reply, not a design.

Where: `RelaySummarizer`, behind the model interface's final reply.
