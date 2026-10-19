# The brief, in my words

A sixty-minute live round: my laptop, my editor, my AI coding assistant, screen shared.

Build a small agent harness, the runtime layer between a language model and the outside world: `run(goal)` with a loop that hands the current state to a model, takes back either a tool call or a final answer, runs the tool and feeds the result back, and stops at the answer. I supply all three pieces: the model, the tools, and the loop. The model is a stub I write, with no keys, no network and no real LLM.

Done, on the day, is one narrow path that runs end to end, a goal in, at least one tool executed, an answer out, and that I can explain. Not needed on the day: a real model, several providers, persistence, a UI, full test coverage, performance work.

Six follow-ups, on my own time:

- a durable run: interrupt it halfway, resume it later
- sub-agents, each with its own goal and tools
- independent tool calls run in parallel
- measuring whether the harness itself is any good, and keeping it measured
- routing across several model providers
- sandboxing tool execution, so a misbehaving tool cannot reach the rest of the system
