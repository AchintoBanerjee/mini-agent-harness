# Routing across providers

[The issue](#the-issue) · [The design](#the-design) · [The choices](#the-choices) · [What I ran](#what-i-ran-on-my-platform-in-production) · [What v2 implements](#what-v2-implements)

This page is design, from running a harness in production at scale. v2 has the seam and nothing behind it.

## The issue

- A platform has more than one model: a strong one and a cheap one, several providers, and each model in some regions and not others. A tenant's data may not leave its region. A provider throttles or goes down.
- The loop must not know any of this. It asks one entry point for a reply and gets one; where the reply came from is the entry point's problem.
- Two decisions hide in "which model": which models an agent may run on, and which one answers this call. The first is a quality decision; the second is a routing decision. Mixing them is how platforms end up guessing at runtime, for the agent.

## The design

The entry point is the LLM proxy from the HLD. In production it is the gateway, and the governor on the scaling page lives in it: routing and capacity are one decision made at one place, on the same numbers.

![routing: the agent brings its validated set, the router picks where to run it](routing.svg)

The stance: the router never chooses a model for an agent. The agent brings the list of models it was validated against, the ones its golden set passed on, and the router only picks where to run it. That leaves two runtime factors, where a model is and how busy it is. The second is the meter from the scaling page, the same number the governor gates on: saturation computed per window from calls and tokens, kept per model per region. Nothing new is measured for routing; the router reads what the governor already has.

1. **Candidates.** The agent's validated set, intersected with what the tenant allows: region law first, then the providers the contract permits, a bring-your-own key. If the agent names one model, the set has one entry and the router only picks a region.
2. **Affinity.** A live conversation stays on the model and the replica that served it, because the prefix cache lives on the server that read the prompt. It moves only if that replica is over the threshold or down.
3. **Pick.** Order the candidates by distance, the nearest permitted region first. Take the first whose meter is under the threshold. If none is under, take the lowest meter. Distance orders, the threshold gates, load breaks the tie.
4. **Fallback.** On failure or throttle, the next entry in the same order. The chain is the same list, so a failover never leaves the validated set and never steps down to a model the agent was not tested on.

Every decision is explainable by reading: the set, the order, and the meter's number at that moment, which the governor already stores with each admission.

How the validated set is made, strong or cheap: by evals, never by the router.

- A new agent starts on the strong model; its golden set is recorded against it.
- The same golden set runs against the cheap model. If tool choice holds and the answer score holds within the margin the owner set, the cheap model joins the set, and the router may now prefer it; if not, it stays out. The eval page's table is the decision.
- After a change, the live judge watches that agent version's rate; a drop is the signal to take the model out of the set again.
- Finer than per agent, when a step can declare its class: the cheap model for the steps that classify, route or extract tool arguments, the strong one for the step that reasons or writes the answer. Most of a turn's tokens are in the cheap steps.

## The choices

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| a validated set per agent | the agent brings the models its golden set passed on; the router picks within it by region and load | explainable: every route is a lookup and a number, every change to the set has a table behind it | the set to keep per agent, and evals before a model joins it |
| a router model | a small classifier reads each request and sends the easy ones to the cheap model | cost down on mixed traffic | a call per request, and a hard question misrouted is a bad answer nobody can explain |
| a cascade | the cheap model first, a check on its answer, escalate on failure | the lowest average cost | the worst tail latency, and the check is a judge problem again |
| the provider's own router | the vendor routes within its catalog | nothing to build | one provider, and the quality rule is theirs, not the golden set's |

I pick the validated set per agent, with affinity, the nearest region under the threshold, and the fallback chain inside the set. A router model only for a traffic class where the eval already showed the cheap model holds, never as the default.

## What I ran, on my platform in production

- One entry point in front of every model, with the three hosting choices behind it, dedicated, on demand and bring your own, and the tokens counted per tenant and model there.
- The nearest region with a backup in the next; the governor on the shared pool, on the scaling page.
- Routing within a validated set per agent is the design; the hosting choice, the nearest region with a backup, and the governor are what ran.

## What v2 implements

| Part | In v2 | Design only |
|---|---|---|
| the entry point | built: the LLM proxy, with the rule stub behind it | the gateway: the tenant filter, affinity, the nearest region under the threshold, the fallback chain |
| the validated set | the golden set and the table that would decide it | running it against two models and keeping the set per agent |
