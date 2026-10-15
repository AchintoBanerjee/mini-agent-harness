# Measuring the harness

[The issue](#the-issue) · [The design](#the-design) · [The choices](#the-choices) · [What I ran](#what-i-ran-on-my-platform-in-production) · [The implementation](#the-implementation) · [Proof](#proof) · [What v2 implements](#what-v2-implements)

An agent can be up, fast, error-free, and completely wrong. Measuring a harness means two questions, asked separately and never blended into one score: did the loop do its job, and was the answer right. Every output below is from a real run of `./demo.sh`; the committed `demo-output.txt` is a later run, so ids and timings differ, nothing else.

## The issue

- The harness does not own any one agent's answer. It owns that the loop did its job, and that nothing moved when the harness changed, for every agent on it. That has to be measurable without reading each agent's prose.
- The two questions get mixed. "Right tool, stopped by done, errors surfaced, how many passes, how long" are facts about the loop. "Was the answer right" is about the agent. One blended number cannot say which moved, since when, or who owns the fix.
- Before ship there is an answer key. Live there is none: the only things that can be measured on real traffic are the facts in the rows and whether the answer stuck to what the tools returned.

## The design

![measuring the harness: two loops over the same rows](eval.svg)

- **Two loops over the same rows.** Before ship: a golden set replayed before every change. Live: a sample of real turns scored on a schedule. Both read the step rows the loop writes anyway; neither sits in the request path.
- **The golden set holds the expected trace, not just the expected answer.** A case is a question, an agent, the tools that must be called, a phrase the answer must contain, a pass cap. That is the only way to catch a wrong tool call that still yields a plausible answer.
- **The harness questions are facts in the rows, and code scores them exactly.** Right tool, stopped by done, errors surfaced, passes, time, tokens. No model, no reference, every trace.
- **The answer needs a reference before ship and a judge live.** Before ship: a phrase or an exact value from the key, or a judge with the reference in hand. Live: a judge without a reference measures grounded and relevant, never right. Correctness lives in the golden set.
- **Code first, judge last.** A judge answers one yes/no question per check with a one-line reason, never a 1 to 10 score; it is a different model from the agent's; its version is pinned on every score; it is checked against a few human labels a week.
- **Score per turn, never per step.** A step is one model call or one tool call; "did it do what was asked" has no meaning at that grain. A conversation is scored once it goes quiet.
- **One named rate per agent and version.** Scores become rates per agent, version and tool, compared to the previous version, last week, or the owner's target. Alert on the one rate that moved. A finding is the rate, the slice, a few example traces, a cause as a hypothesis, and a next step with an owner.
- **The loop closes on a golden case.** A confirmed live failure becomes a new case, replayed before every release after.

## The choices

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| expected answer only | a case is a question and an answer phrase | cheap to write | a wrong tool with a plausible answer passes |
| expected trace too | the case also names the tools that must run; the scorer reads the rows | a tool-choice regression fails on its own, before anyone reads prose | the trace must be graded loosely, as a set, or it goes stale when tools change |
| judge every request, in the request path | an extra model call on each turn | a verdict per conversation | latency and a bill on every turn; nothing to compare against |
| a sampled batch judge | a scheduled job over a share of turns, a cap on spend | a trend per agent version at a bounded cost, zero added latency | minutes to hours late; rare failures need a second, triggered stream |
| one blended health score | quality, satisfaction, reliability, performance weighted into one number | one number to alert on | it cannot say which rate moved, since when, or who owns it |
| named rates per agent and version | every check is its own rate, compared to the last version and last week | the finding names the slice and the owner | more alerts, no single dashboard number |

I pick the expected trace, the sampled judge and named rates. The blended score was designed once and thrown away: a number that cannot say what moved is not actionable. The harness scores are code over the rows, and the judge is spent only where words must be read.

## What I ran, on my platform in production

- Golden sets were recorded from real sessions by the teams that own the agents, with the expected trace stored in the schema next to the expected answer, and replayed as a batch gate before promotion. Not a CI gate: a bad change reached the gate, not the pull request.
- A scheduled judge over a sample of production turns, per agent, throttled, scoring groundedness, answer and context relevance, correctness and completeness, published per agent version. A model switch that broke agents in subtle ways showed as a trend break; teams had anecdotes, the judge had numbers; stabilized in days. Model changes needed an evaluation gate after that.
- The scorer that never shipped: the expected trace was stored on every case, and the route and step scorers were on my own gap list as the first thing to add. The data was there, the scorer was not; tool-call regressions were found by people reading traces.
- Tool errors sat in the rows uncounted. The cheapest checks, tool error rate by tool and version, right tool against the expected set, are pure code, and they were the ones missing.
- The judge that ran sampled by step, not by turn, and published the result under a turn label. The grain of every score is decided before it reaches a chart now.
- A composite health score was designed in an early one-pager and dropped for the reason in the table.
- The finding that made the measurement real was reached by hand: a per-turn chart from the rows showed about 175k prompt tokens by turn sixteen, most of it ten copies of one tool result. A model problem is flat; a climbing curve is the context builder. The fix cut tokens by about eighty percent with the tenants' golden sets flat. That incident is the eval system done by a person, and it is why the harness scores come first.

## The implementation

v2 builds the before-ship loop: a golden set, a runner, a table. A case is one line of JSON:

```json
{ "question": "What is the status of order ORD-1?", "agent": "support",
  "expectTools": ["order_status", "shipping_status"], "answerContains": "SHIPPED", "maxPasses": 3 }
```

The runner gives every case a fresh loop over its own in-memory store, runs it, and scores the rows the loop wrote, not the answer alone. The harness scores are exact; the agent score is a phrase check standing in for a judge with the reference.

<details><summary>the scorer</summary>

```java
public static CaseResult score(GoldenCase golden, List<Span> rows, String answer, long ms) {
    Set<String> tools = new TreeSet<>();
    boolean stoppedByDone = true;
    boolean errorsSurfaced = true;
    int passes = 0;
    for (Span row : rows) {
        if (row.kind() == Span.Kind.LLM) {
            passes++;
        }
        if (row.status() == Span.Status.CUT_SHORT) {
            stoppedByDone = false;
        }
        if (!row.kind().isCall() || row.status() == Span.Status.DEDUPED) {
            continue;
        }
        tools.add(row.attrs().getOrDefault("tool", "?"));
        if (row.status() != Span.Status.OK && !answer.contains(row.status().name())) {
            errorsSurfaced = false;
        }
    }
    boolean toolChoice = tools.equals(new TreeSet<>(golden.expectTools()));
    boolean answerOk = normalize(answer).contains(normalize(golden.answerContains()));
    return new CaseResult(golden, tools, toolChoice, stoppedByDone, errorsSurfaced, answerOk, passes, ms, answer);
}
```
`tool_choice` is the set of tools that ran against the set expected, a repeat excluded. `stopped_by_done` is no `CUT_SHORT` row. `errors_surfaced` is every non-OK call named by its status in the answer. `answer` is the phrase, normalized. The table is one line per case and a footer with the harness scores and the agent score, apart.
</details>

`POST /evals` runs the set in the server and returns the table; `eval` on the command line prints the same.

## Proof

<details><summary>proof: the golden set over HTTP, two FAILs left in on purpose</summary>

```
 1  PASS  support    What is the status of order ORD-1?                      ran order_status,shipping_status  3 passes     12 ms
 2  PASS  support    Where is my shipment SHP-1?                             ran shipping_status              2 passes      0 ms
 3  PASS  support    Is product PRD-2 available?                             ran stock_status                 2 passes      0 ms
 4  PASS  support    What is the status of order ORD-99?                     ran order_status                 2 passes      0 ms
 5  PASS  support    What is the weather today?                              ran no tool                      1 pass       0 ms
 6  PASS  chaos      Please hang while checking ORD-1                        ran hang_forever                 2 passes   1006 ms
 7  PASS  chaos      boom                                                    ran throw_boom                   2 passes      0 ms
 8  PASS  support    Status of order ORD-2 and is product PRD-1 available?   ran order_status,stock_status    2 passes      2 ms
 9  PASS  inventory  How many PRD-1 do we have in stock?                     ran stock_status                 2 passes      0 ms
10  PASS  inventory  refund order ORD-1                                      ran no tool                      1 pass       0 ms
11  FAIL  support    reorder ORD-1                                           ran order_status,shipping_status  3 passes      0 ms  tool_choice: expected [] got [order_status, shipping_status]; answer: expected "No tool" got "Order ORD-1: SHIPPED, shipment SHP-1 | Shipment SHP-1: IN TRANSIT, arriving 2026-10-16"
12  FAIL  support    track my parcel SHP-3                                   ran no tool                      1 pass       0 ms  tool_choice: expected [shipping_status] got []; answer: expected "DELIVERED" got "No tool for this question."
13  PASS  support    Where is my order ORD-3?                                ran order_status,shipping_status  3 passes      0 ms
harness: tool_choice 11/13 · stopped_by_done 13/13 · errors_surfaced 13/13 · p50 0 ms | agent: answer 11/13
```
The two failures are the stub's known limits, left in so the table has something to say: "reorder" matches the order tool's keyword and runs it, "parcel" matches nothing. Both fail on `tool_choice` first, the harness column, before the answer column says anything; that is the point of the expected trace. Cases 1 and 13 are the chain: two tools, three passes, and the answer with both lines.
</details>

## What v2 implements

| Part | In v2 | Design only |
|---|---|---|
| the golden set with the expected trace | built: `evals/golden.json`, tools that must run, a phrase, a pass cap | recording cases from real sessions |
| the harness scores from the rows | built: tool choice, stopped by done, errors surfaced, passes, time | tokens and cached tokens, once a real model is behind the interface |
| the agent score | a phrase check against the key | a judge with the reference; a rubric for prose |
| the table | built: one line per case, the harness footer and the agent score apart | rates per agent version, compared to the last version and last week |
| the live loop | | a sampled judge on a schedule, grounded and relevant without a reference, a finding with a slice and an owner, a confirmed failure back into the golden set |
