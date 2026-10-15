# The loop's features, already built

[Parallel tool calls](#parallel-tool-calls) · [A clock on every tool call](#a-clock-on-every-tool-call) · [The agent's tool list is the gate](#the-agents-tool-list-is-the-gate) · [The pass cap and the budget](#the-pass-cap-and-the-budget) · [Chaining across passes](#chaining-across-passes) · [The answer](#the-answer)

Small things the loop does that have no page of their own. Each one: what it does, a real run, where it lives. Every output is from `./demo.sh`; the committed `demo-output.txt` is a later run, so ids and timings differ, nothing else.

| Feature | In v2 | The proof |
|---|---|---|
| parallel tool calls | built: two tools in one reply run together, rows in request order, a note row says wall against work | the demo's `--slow` section |
| a clock on every tool call | built: the agent's `toolTimeoutMs`, the call is cut off, the answer names it | the chaos stream in the demo |
| the agent's tool list is the gate | built: a tool the model names outside the list is `DENIED` without running, the loop moves on | `OrchestratorTest` |
| the pass cap and the budget | built: `maxPasses` per agent, `maxModelCalls` shared down a sub-agent tree, the depth cap | `OrchestratorTest`, `SubAgentTest`, the loop agent in the demo |
| chaining across passes | the loop and resume support it; the stub decides once, in pass 1, and says done in pass 2, so no chain shows in v2 | the sub-agent run is the one chain the demo shows |
| the answer | built: the last pass's results relayed, failures named; a model's final reply replaces it | every turn in the demo |

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

- A chain is inside one turn: pass 2 asks for a tool because of what pass 1 returned. The loop is built for it. Pass N's tool result is in the context the model sees in pass N+1, so a model that needs a second lookup asks for it next, and so on until it says done. With a real model: "Where is my order ORD-1?" → pass 1 `order_status(ORD-1)` → "SHIPPED, shipment SHP-1" → pass 2 `shipping_status(SHP-1)` → pass 3 done. Resume works in the middle of a chain, because every pass is rows in the store.
- The stub never chains. It reads the question once, in pass 1: every tool whose keywords and id pattern fit is called, one as a single call, several as one batch run together. In pass 2 it sees a call row in the context and says done. The missing piece is a decision made from a result, "the order says shipment SHP-1, look that up", and that is the model's job. Saying so plainly beats faking it with a rule.
- The one chain v2 does show is the sub-agent run: the parent decides, the child runs its own pass, the parent gets the answer and finishes. Six rows, one turn:

```
  1  LLM   pass 1 -> ToolCall[toolName=ask_logistics, input={goal=Where is shipment SHP-1 for my order?}]
    2  LLM   pass 1 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]
    3  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
    4  LLM   pass 2 -> Done[]
  5  AGENT  ask_logistics {goal=…} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
  6  LLM   pass 2 -> Done[]
```

Where: `Orchestrator.loop` and `Orchestrator.context`; the sub-agents page has the rest.

## The answer

- What v2 does: the last pass's tool results, relayed unchanged, one per line in the order they ran. A repeated call is swapped for the output of the step it repeats. A tool that did not end OK is named with its status, so the failure reaches the user. No tool ran: "No tool for this question."

| The turn | The answer |
|---|---|
| one tool, `order_status(ORD-1)` | `Order ORD-1: SHIPPED, shipment SHP-1` |
| two tools in one pass | `Order ORD-2: PROCESSING` ⏎ `Product PRD-1: 42 units in stock` |
| the tool timed out | `Tool hang_forever TIMEOUT: cut off after 1000 ms` |
| the tool threw | `Tool throw_boom ERROR: Tool failed: boom` |
| nothing matched | `No tool for this question.` |

- Why it is this simple: a rule stub decides, it does not write. In production the model's final reply is the answer, written with the tool results in its prompt, and it reaches the user through the same path, streamed piece by piece. The relay is the stand-in for that reply, not a design.

Where: `RelaySummarizer`, behind the model interface's final reply.
