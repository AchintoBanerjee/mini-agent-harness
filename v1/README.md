# Mini Agent Harness

The harness exactly as designed and built in the live round on 2026-10-14, frozen at tag `v1`. The brief is [PROBLEM.md](../PROBLEM.md); what v2 changed, and why, is [DESIGN.md](../DESIGN.md).

## Design

![the whiteboard from the live round](../docs/v1-whiteboard.png)

**Goal:** `run(question) -> answer`. A rule-based stub model picks one tool, the harness runs it, and the answer comes back.

**Done means**
- "what is the status of order ORD-1" runs OrderStatus and the answer carries that tool's output
- the same for ShippingStatus (SHP-1) and StockStatus (PRD-1)
- a question no tool matches ends with "no tool for this question", never an endless loop

**Components:** the picture above. Orchestrator owns the loop. Agent is the bundle of tools it may use; every one must exist in ToolRegistry. LLMProxy is the rule-based model. ToolCaller runs one tool. Summarizer turns the finished turn into the answer.

**Main data flow**
1. User asks → Orchestrator opens a Conversation and a Turn(question).
2. Orchestrator builds Context(question, spans so far) → `LLMProxy.next(context, agent.tools)`.
3. LLMProxy: no TOOL span yet → keyword match over each tool's MatchingCondition → `ToolCall(name, input)`; a TOOL span exists → `Done`; nothing matches → `Done`.
4. ToolCall → `ToolCaller.call(registry.get(name), input)` → TOOL span appended → back to step 2.
5. Done → `Summarizer.summarize(turn)` → answer stored on the Turn and returned. Safety stop after 5 passes.

**Contract**
- `Orchestrator.run(question) -> String answer`
- `LLMProxy.next(Context, List<Tool>) -> ModelReply`, where ModelReply is `ToolCall(name, input)` or `Done`
- `ToolCaller.call(ToolCall) -> ToolResult(output)`; unknown tool → ToolResult with an error, loop still ends
- `Summarizer.summarize(Turn) -> String`; PoC = relay last tool output, later = LLM summarizer
- `Tool`: `name`, `description`, `inputField`, `MatchingCondition(keywords, idPattern)`, `run(input map) -> output`

**Data model:** Conversation(id, agent, turns); Turn(question, spans, answer); Span(kind LLM|TOOL, request, response); Context(question, spans). Tools: OrderStatus(orderId), ShippingStatus(shipmentId), StockStatus(productId), each over a hard-coded map.

**Assumptions:** one tool call per turn, extendable to chains; the tool input id is pulled from the question by the tool's idPattern; first matching tool wins; Java 21.

**Cuts:** multi-step tool chains (brief wants one narrow path); real LLM (brief says stub); persistence, UI, parallel tools (not needed today).

## Run and test

```
mvn -q test
mvn -q compile && java -cp target/classes harness.Main
```

Code is in `src/main/java/harness`. `SampleTools` holds the three tools and their hard-coded data (a real system would call order, shipping and inventory services). `Main` is the demo driver.

## Repo map

- `src/main/java/harness`: the code, as built in the hour. `SampleTools` holds the three tools and their hard-coded data; `Main` is the demo driver.
- `../docs/v1-whiteboard.png`: the whiteboard picture behind the design above.
- `../v2/`: the harness extended into a small service; its manual is `../v2/README.md`.
