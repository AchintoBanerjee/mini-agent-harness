<!-- The original challenge brief, exactly as received on 2026-10-14. -->

# Coding Challenge — Mini Agent Harness

**60 minutes · your laptop · your editor · your AI coding assistant · shared screen**

## The premise

An _agent harness_ is the runtime layer that sits between a language model and the outside
world. It assembles what the model sees, interprets what the model asks for, executes tools
on the model's behalf, and decides when the work is finished.

Build a small one.

Roughly this shape:

```
run(goal) -> result
```

Inside `run`, a loop: hand the current state to a model; the model replies with either a
**tool call** or a **final answer**; if it's a tool call, execute the tool, feed the result
back, and go again.

You supply all three pieces — the model, the tools, and the loop between them.

## Ground rules

- **Any language you're fluent in.** Greenfield, no starter repo, no framework required.
- **The model is a stub you write.** No API keys, no network, no real LLM. Script its
  replies however you need them.
- **Use your AI coding assistant — we expect it.** How you direct it is part of what we're
  looking at, so please think out loud.
- **Ask us anything.** We'll answer.

## What "done" looks like today

One narrow path runs end to end — a goal goes in, at least one tool actually executes, an
answer comes out — and you can explain why the pieces are shaped the way they are.

A narrow slice that runs beats a broad one that doesn't.

## Not needed today

Real model integration. Multiple providers. Persistence. A UI. Exhaustive test coverage.
Performance work.

## If you'd like to keep going — follow-up, on your own time

- Making a run **durable**: interrupt it halfway, resume it later
- Letting a run **spawn sub-agents** with their own goals and tool sets
- Executing independent tool calls **in parallel**
- How you'd **measure whether the harness itself is any good** — and keep measuring it
- **Routing across multiple model providers**
- **Sandboxing tool execution** so a misbehaving tool can't reach the rest of the system
