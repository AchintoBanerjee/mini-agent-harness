# Performance: time and tokens

[The prefix cache](#the-prefix-cache) · [Tools in the hundreds](#tools-in-the-hundreds) · [Dedupe of tool results](#dedupe-of-tool-results) · [Multi-agent leakage](#multi-agent-leakage) · [What v2 implements](#what-v2-implements)

Performance in an agent loop is not only time. Every model call reads the whole prompt again, so a prompt that grows is paid twice on every turn: once in seconds, once in tokens. The four optimizations below each cut both, and each is measured the same way: replay one recorded conversation, plot latency and tokens per turn, and check function and cost in one run.

## The prefix cache

![the prefix cache: prompt order decides whether anything is reused](perf-kvcache.svg)

- A model call has two phases. Prefill reads the whole prompt in one pass; decode writes the answer one token at a time. On a big agent prompt prefill is about half the turn.
- The model server keeps the attention state of every token it has read. The next call that starts with the same bytes reuses it and only prefills the new tail. It matches the longest unchanged prefix, and one changed token invalidates everything after it.
- So prompt order is the whole trick: stable to volatile, and only ever append. System prompt first, tool definitions in a fixed order, the history exactly as it happened, the new turn at the bottom.
- Ours was inverted: the per-turn block on top, tools injected mid-prompt in a changing order, the system prompt at the bottom. The first tokens changed every turn, so zero cache hits, a full prefill of a bloated prompt, every turn.
- Two things fight the cache. Trimming the middle every turn rewrites the prefix every turn. Compaction rewrites it too, but once, at a checkpoint: one paid miss, then a stable prefix again.
- The cache lives on one model replica, so the gateway has to pin a conversation to its replica; without that the cache you engineered evaporates.

**Time and tokens.** A prefix hit skips most of the prefill, about half the turn on a big prompt. Cached input tokens are billed at a fraction by the hosted providers, roughly half price at one and about ninety percent off reads at another; on your own GPUs the same win is freed capacity. Measure it as cached tokens over prompt tokens per call, reported next to cost, and make it a column in the eval so an ordering regression shows up as a cost regression before it ships.

**What I ran.** The incident: every question in a conversation was slower and more expensive than the one before. Replaying one recorded conversation and plotting latency and tokens per turn gave a rising line; a model problem is flat, so the problem was ours. The reorder was done once in the shared context builder so no team changed its agent, and cached-token counts, which the provider did not return at first, were obtained and reported next to cost. With the other context fixes together, the long-conversation turn went from twenty or thirty seconds to about six, context to about a tenth, function held on the golden set. Trim, a window and dedupe shipped; summary compaction at checkpoints is the direction.

## Tools in the hundreds

![tools in the hundreds: the catalog outside the prompt, one search tool inside it](perf-tools.svg)

- The model never talks to a tool server. The harness builds the tool list for every call, and the protocol has no search: a server returns everything, so any narrowing lives in the harness.
- Every definition rides in every model call, about five hundred tokens each: five tools is a few thousand, thirty is fifteen thousand, three hundred is a hundred and fifty thousand, read again before the model sees the question. Past a few dozen the model also mis-picks among look-alikes.
- The design: list each server once at registration, keep every definition in a catalog, index name, description and parameter names. The model sees three to five pinned tools plus one search tool. When it searches, the harness answers from the index with names and one-liners, loads the found definitions for the next call, and remembers them with the conversation so later turns need no search.
- The trade: one extra model call the first time a capability is needed, then a handful of definitions instead of hundreds on every call after. Under about ten tools, just load them.
- Keep the found definition out of the cached prefix: append it as conversation content rather than rewriting the tools block, or the whole prefix is invalidated.

**Time and tokens.** Prefill at three hundred tools drops from about a hundred and fifty thousand tokens per call to a few thousand; the index query is milliseconds with no model; the only cost is the one extra call on first use. Published evals from the model vendors show about eighty-five percent fewer tokens and better selection accuracy with search than with everything loaded.

**What I ran.** My platform today hands the model every imported tool on every call and lists each tool server live on every turn. The catalog, the index and the search tool are my written proposal for it, not shipped.

## Dedupe of tool results

![dedupe: a repeat becomes a reference, never a copy](perf-dedupe.svg)

- In a long conversation the agent re-calls a tool it already has the answer for, because from inside the prompt it cannot tell whether the data changed. Each re-call injects the full result again. In one conversation, by turn sixteen, ten identical results had been re-injected: most of the prompt.
- Blocking the repeat is wrong: some data does change. Dropping the older copy is wrong too: it rewrites the prefix and the cache misses on every turn after.
- The rule: same tool plus same inputs already in the conversation, the new entry is a reference, "unchanged, see above", not a copy. The tool still runs; only what enters the prompt changes. Append-only, so it composes with the cache instead of fighting it.
- Only where the data is stable: a stability rule per tool. Volatile tools get the full result every time.

**Time and tokens.** The duplicate body is never paid for again, and the prefix stays intact, so the smaller prompt is also mostly cached. No dedupe-only number exists; it was one of the five context fixes behind the six-second turn.

**What I ran.** Dedupe shipped in the shared context builder next to the tool-call window and the token budget over history and tools, verified on the recorded golden set for function and cost in one run.

## Multi-agent leakage

![leakage: the broadcast transcript against agent as a tool](perf-leakage.svg)

- In the broadcast shapes, a supervisor or a group chat on one shared transcript, every agent's tool calls and reasoning land in the classifier's context and in every sibling's. Nobody needs it: the classifier needs enough to route, a sibling needs another agent's answer at most.
- It hurts twice. Cost: each hop re-sends the whole growing transcript to everyone, about two model calls per hop, growth with the square of the hops. Quality: an agent reading another agent's half-finished reasoning gets derailed.
- The fix is isolation by construction, not prompt discipline: each agent runs in its own context and sees only what it is given; only answers cross the boundary, never monologue. The lighter pattern has none of this: agent as a tool, where the child gets the goal and its own tools and the parent gets the answer.

**Time and tokens.** Linear growth instead of quadratic, one model call per hop instead of two. On my platform, moving the known paths to a fixed plan cut median turn latency by about thirty to forty percent.

**What I ran.** The broadcast pattern was our first default and the leak was found in production; new teams were stopped on it, known paths moved to the DAG, the rest to agent as a tool. The whole story is on the sub-agents page.

## What v2 implements

| Optimization | In v2 | Design only |
|---|---|---|
| the prefix cache | the context is built append-only, and a repeat is a reference | the stable-to-volatile order and the cached-token column apply once a real model is behind the interface |
| tools in the hundreds | nothing: v2 has six tools, under the threshold | the catalog, the index, the search tool |
| dedupe of tool results | built: the `DEDUPED` span, "unchanged, see step k", the handler runs once | the stability rule per tool |
| multi-agent leakage | built: agent as a tool, the child sees the goal and its tools, the parent gets the answer | |
