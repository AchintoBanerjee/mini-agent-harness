# Sub-agents: an agent as a tool

[The issue](#the-issue) · [The choices](#the-choices) · [What I ran](#what-i-ran-on-my-platform) · [Agent as a tool](#agent-as-a-tool) · [The implementation](#the-implementation) · [Proof](#proof) · [The DAG](#the-dag)

A run can hand a question to another agent and get the answer back as a tool result: the child is the same loop class, run inside the tool call, its rows in the same turn (job id). There are more multi-agent patterns in the frameworks than the three on this page; these are the ones I have run on my platform, group chat only as far as pre-production and the other two in production, and they cover most of what teams need. Every output below is pasted from `demo-output.txt`, which `./demo.sh` writes.

## The issue

- Several specialists need several agents: the concierge knows orders, the logistics agent knows shipments, one question can need both.
- Every hand-off costs model calls and context, and a hop nobody measured is a cost nobody sees.
- The question every multi-agent shape has to answer: who decides the next step, and who sees what.

## The choices

Three ways to let agents work together. The first is the one we picked on my platform, and it was the wrong default. The other two work, and they answer different questions.

![three ways to let agents work together](subagents-patterns.svg)

| option | who decides the next step | who sees what | cost per hop |
|---|---|---|---|
| group chat / supervisor broadcast | a supervisor model, every round, from the whole transcript | every agent sees the whole monologue | about two model calls per hop, the worker's and the supervisor's, and the transcript grows with every hop, so the tokens grow with the square of the hops |
| agent as a tool | the parent's own tool choice: the call that happens anyway is the routing | the child sees the goal and its tools; the parent sees the answer | one model call per hop on the child's side, no routing call; linear in the hops |
| DAG (directed acyclic graph: the flow drawn as nodes and edges) | nobody at run time: the edges hold the sequence | each node sees its inputs | zero tokens for the routing; replayable, testable |

One question decides: do I know the sequence up front? Known → the DAG. Unknown → agent as a tool. Group chat is risky and never the default.

Things to watch for:

- one agent's context leaking into another's: the answer should cross, not the transcript;
- the same leak inside a DAG, and it is common. The router node, a model or a plain rule, and the nodes after it share one conversation. A node's tool calls and any child agent's context must stay inside the node; only its output passes on. Otherwise the DAG is a broadcast again;
- a supervisor that must see everything to decide anything;
- cost per hop that nobody measured;
- an agent that delegates to itself, which needs a cap on depth;
- a tree of agents that spends the whole turn's model calls, which needs one budget shared down the tree.

## What I ran, on my platform

- We picked a framework's group chat as our first multi-agent default. I had not researched the pattern enough.
- One release in, with teams building on it, the numbers came back: up to twice the model calls, and every agent reading the whole conversation.
- I went to leadership and stopped new teams on it. The paths we knew moved onto our DAG, the rest onto agent as a tool.
- Median latency on the migrated paths dropped by about a third. The cost: the teams on it missed one release while they moved, before any of their agents reached customers.

## Agent as a tool

When the sequence is not known up front, the parent's own tool choice is the router. The child loop is the same Orchestrator class, run inside a tool call; the HLD picture does not change.

The example: `concierge` (tools `order_status`, `ask_logistics`) is asked "Where is shipment SHP-1 for my order?"; `logistics` (tools `shipping_status`) is the agent behind `ask_logistics`.

| span | depth | kind | what happened |
|---|---|---|---|
| 1 | 0 | LLM | the concierge decides `ask_logistics`, goal = the question; written before the child runs |
| 2 | 1 | LLM | the logistics agent decides `shipping_status` (parent 1) |
| 3 | 1 | TOOL | `shipping_status` → "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16" (parent 1) |
| 4 | 1 | LLM | the logistics agent says Done (parent 1) |
| 5 | 0 | AGENT | `ask_logistics` → the child's answer: the one row that crosses back |
| 6 | 0 | LLM | the concierge says Done; the turn's answer is the child's answer |

The child sees only what the parent hands it: the goal, and its own tools. The parent sees only what comes back: the answer, as a tool result. Nothing else crosses, in either direction: not the child's prompt, reasoning or tool outputs, not the parent's rows or history. The goal is all the child gets, and with the stub the goal is the parent's question.

- **API layer**: nothing new; a `concierge` turn is one id and one stream like any other.
- **Orchestrator**: the parent loop; an `agent` tool's call runs a child Orchestrator, the same class, with depth + 1 and the turn's budget passed into its constructor.
- **Tool layer**: a tool type of its own, `agent`: the file names the agent, the loop that offers the tool binds it to itself.
- **Child loop**: the logistics agent's own loop, tools and timeout; it returns an answer, never its transcript; fresh per call.
- **Model**: the same stub for both; it hands the child the whole question as its goal.
- **Run store**: no new table, and no turn row for the child, whose rows land under the parent's turn id; the child's spans carry `parent` and `depth` in `attrs`, so the API, the stream and `replay` show the tree.
- **Bounds in the loop, not in the prompt**: a depth cap of 2, so an agent that lists itself as a tool ends with an `ERROR` row, `depth limit`, never a stack overflow. And one budget of model calls per turn (`maxModelCalls`, default 20), shared down the tree; when it is spent the turn is `FAILED`, with a NOTE row, `budget exhausted`.
- Out of scope for the POC: a worker that dies inside a child loop (resume reads the saved rows as one flat list); two agent tools in one pass, which interleave their rows.

## The implementation

An agent tool is a file. The loop that offers it binds the handler to itself, and the handler runs a child loop and hands the answer back. That is what v2 does: the child runs inside the tool call. If that agent lived on another host, the same call would go over the network to it, and nothing else would change.

<details><summary>the tool file: type agent</summary>

```json
{ "name": "ask_logistics", "description": "Shipment questions go to the logistics agent", "type": "agent",
  "agent": "logistics", "inputField": "goal", "match": { "keywords": ["shipment", "shipping", "tracking"], "idPattern": "SHP-\\d+" } }
```
`agent` names the file in `agents/`; `match` picks the tool like any other; `inputField` is the goal the child gets.
</details>

<details><summary>the adapter: a child Orchestrator inside the tool call</summary>

```java
// in the constructor: the loop that offers an agent tool binds it to itself, and the caller runs the bound copy
tools.add(tool.isAgent() ? tool.bound(input -> delegate(tool.agent(), input.getOrDefault(tool.inputField(), ""))) : tool);
toolCaller.bind(tools);

// the adapter: the same class, one level deeper, this loop's last span and the turn's budget passed in
private String delegate(Agent child, String goal) {
    if (depth >= MAX_DEPTH) {
        throw new IllegalStateException("depth limit: a loop at depth " + depth + " cannot delegate");
    }
    Turn turn = conversation.turns().getLast();
    ToolCaller childCaller = new ToolCaller(registry, child.toolNames(), Duration.ofMillis(child.toolTimeoutMs()));
    Orchestrator childLoop = new Orchestrator(child, registry, llm, childCaller, store, listener,
            depth + 1, lastSpan, budget);
    return childLoop.asChild(conversation.id(), turn.id(), goal);
}

// the child's side: the goal is its question, in a turn that shares the parent's id; no row of its own, no status change
String asChild(String conversationId, String turnId, String goal) {
    conversation = new Conversation(conversationId, agent);
    Turn turn = conversation.turn(turnId, goal);
    return loop(turn, 1);
}
```
The child is built from the parent's pieces, with its own agent, tool caller and depth; a throw inside it is a tool error to the parent, which is how the depth limit surfaces as a row.
</details>

What changed:

- a tool type, `agent`, in the loader, and a row kind, `AGENT`;
- a `TurnBudget` per turn: it hands out the span numbers and counts the model calls;
- the loop's constructor takes the budget, the depth and the parent span, and binds the loop's agent tools to itself;
- `record` takes each row's number from the budget and stamps `parent` and `depth` on a child's rows;
- `loop` asks the budget before each model call;
- the caller runs the bound copy, and the printers indent a child's rows.

What did not change: the loop's steps (ask, write, run, write, repeat), the resume path, the store, the schema, the queue, the endpoints, the stream.

## Proof

The demo over HTTP: the concierge turn, then the loop agent stopped by the depth cap.

<details><summary>proof: the concierge turn, the nested spans, the one answer</summary>

```
$ curl -s -X POST http://localhost:64430/runs -d '{"agent":"concierge","question":"Where is shipment SHP-1 for my order?"}'
{
  "turnId" : "r-3991",
  "jobId" : "r-3991",
  "conversationId" : "c-deff",
  "status" : "QUEUED"
}
(the worker prints the child's rows indented: the parent's decision, the child's three rows, the AGENT row with the child's answer, the parent's done)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --api http://localhost:64430
worker pid 64420 · lease 2000 ms · once · streams to http://localhost:64430 · harness.db
received r-3991: attempt 1 · agent concierge · "Where is shipment SHP-1 for my order?"
  1  LLM   pass 1 -> ToolCall[toolName=ask_logistics, input={goal=Where is shipment SHP-1 for my order?}]  [OK 2 ms]
    2  LLM   pass 1 -> ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]  [OK 0 ms]
    3  TOOL  shipping_status {shipmentId=SHP-1} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 0 ms]
    4  LLM   pass 2 -> Done[]  [OK 0 ms]
  5  AGENT  ask_logistics {goal=Where is shipment SHP-1 for my order?} -> Shipment SHP-1: IN TRANSIT, arriving 2026-10-16  [OK 9 ms]
  6  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Shipment SHP-1: IN TRANSIT, arriving 2026-10-16
done r-3991 · 6 spans · 131 ms · DONE in harness.db · acked · handler calls: shipping_status=1
(the same rows over HTTP: the child's spans carry parent and depth in attrs, the AGENT span carries the child's answer, and the turn's answer is that answer)
$ curl -s http://localhost:64430/turns/r-3991
{
  "id" : "r-3991",
  "conversationId" : "c-deff",
  "agent" : "concierge",
  "question" : "Where is shipment SHP-1 for my order?",
  "answer" : "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16",
  "status" : "DONE",
  "claimedBy" : "64420",
  "attempts" : 1,
  "createdAt" : 1792113825074,
  "updatedAt" : 1792113825766,
  "spans" : [ {
    "turnId" : "r-3991",
    "span" : 1,
    "pass" : 1,
    "kind" : "LLM",
    "request" : "pass 1",
    "response" : "ToolCall[toolName=ask_logistics, input={goal=Where is shipment SHP-1 for my order?}]",
    "status" : "OK",
    "ms" : 2,
    "attrs" : {
      "question" : "Where is shipment SHP-1 for my order?",
      "tool" : "ask_logistics",
      "goal" : "Where is shipment SHP-1 for my order?"
    }
  }, {
    "turnId" : "r-3991",
    "span" : 2,
    "pass" : 1,
    "kind" : "LLM",
    "request" : "pass 1",
    "response" : "ToolCall[toolName=shipping_status, input={shipmentId=SHP-1}]",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "question" : "Where is shipment SHP-1 for my order?",
      "tool" : "shipping_status",
      "shipmentId" : "SHP-1",
      "parent" : "1",
      "depth" : "1"
    }
  }, {
    "turnId" : "r-3991",
    "span" : 3,
    "pass" : 1,
    "kind" : "TOOL",
    "request" : "shipping_status {shipmentId=SHP-1}",
    "response" : "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "tool" : "shipping_status",
      "shipmentId" : "SHP-1",
      "key" : "r-3991/2",
      "parent" : "1",
      "depth" : "1"
    }
  }, {
    "turnId" : "r-3991",
    "span" : 4,
    "pass" : 2,
    "kind" : "LLM",
    "request" : "pass 2",
    "response" : "Done[]",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "question" : "Where is shipment SHP-1 for my order?",
      "parent" : "1",
      "depth" : "1"
    }
  }, {
    "turnId" : "r-3991",
    "span" : 5,
    "pass" : 1,
    "kind" : "AGENT",
    "request" : "ask_logistics {goal=Where is shipment SHP-1 for my order?}",
    "response" : "Shipment SHP-1: IN TRANSIT, arriving 2026-10-16",
    "status" : "OK",
    "ms" : 9,
    "attrs" : {
      "tool" : "ask_logistics",
      "goal" : "Where is shipment SHP-1 for my order?",
      "key" : "r-3991/1"
    }
  }, {
    "turnId" : "r-3991",
    "span" : 6,
    "pass" : 2,
    "kind" : "LLM",
    "request" : "pass 2",
    "response" : "Done[]",
    "status" : "OK",
    "ms" : 0,
    "attrs" : {
      "question" : "Where is shipment SHP-1 for my order?"
    }
  } ]
}
```
</details>

<details><summary>proof: the depth cap, an agent that lists itself as a tool</summary>

```
$ curl -s -X POST http://localhost:64430/runs -d '{"agent":"loop","question":"loop, please"}'
{
  "turnId" : "r-f68e",
  "jobId" : "r-f68e",
  "conversationId" : "c-05d2",
  "status" : "QUEUED"
}
(loop delegates to loop delegates to loop; the loop at depth 2 may not delegate again, so its call is refused with an ERROR row, depth limit, and the answer climbs back up)
$ java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/lib/*" harness.cli.Main worker --once --api http://localhost:64430
worker pid 64427 · lease 2000 ms · once · streams to http://localhost:64430 · harness.db
received r-f68e: attempt 1 · agent loop · "loop, please"
  1  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 3 ms]
    2  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 0 ms]
      3  LLM   pass 1 -> ToolCall[toolName=ask_loop, input={goal=loop, please}]  [OK 0 ms]
      4  AGENT  ask_loop {goal=loop, please} -> Tool failed: depth limit: a loop at depth 2 cannot delegate  [ERROR 0 ms]
      5  LLM   pass 2 -> Done[]  [OK 0 ms]
    6  AGENT  ask_loop {goal=loop, please} -> Tool ask_loop ERROR: Tool failed: depth limit: a loop at depth 2 cannot delegate  [OK 8 ms]
    7  LLM   pass 2 -> Done[]  [OK 0 ms]
  8  AGENT  ask_loop {goal=loop, please} -> Tool ask_loop ERROR: Tool failed: depth limit: a loop at depth 2 cannot delegate  [OK 16 ms]
  9  LLM   pass 2 -> Done[]  [OK 0 ms]
answer: Tool ask_loop ERROR: Tool failed: depth limit: a loop at depth 2 cannot delegate
done r-f68e · 9 spans · 142 ms · DONE in harness.db · acked · handler calls: none
```
</details>

- Six rows, one numbering, one turn, as the span table under [Agent as a tool](#agent-as-a-tool) lists them.
- Span 2's `attrs` hold the child's question, the goal, and nothing of the parent's conversation; span 5 is the only row of the child's work the parent's loop saw.
- The loop agent goes three loops deep and stops: row 4, at depth 2, is the refused call, `depth limit`, an `ERROR`; each loop above relays it and says done; six model calls, no stack overflow.

## The DAG

The other pattern, for when the sequence is known. Most workflows are known up front and long-running, so what matters is that a run survives its worker and can be replayed; that is why the engine, not the model, should hold the sequence. The DAG holds the flow and the model never decides it: the routing is the edges, zero tokens, replayable, testable. Where a model node does route, at the top of the DAG, it routes on its inputs only. The leak to guard is the one in the watch list under [The choices](#the-choices): the nodes share one conversation, so only a node's output may pass to the next.

For this proof of concept I built agent as a tool, because the example's sequence is not known up front and it fits the loop we already have. The DAG is just as implementable. My platform ended up with both, the DAG on our own engine. If I built it again I would put the DAG on a workflow engine such as Temporal, for the durability and the replay: a long workflow survives its worker. I have not run one, so that is a direction, not a result.
