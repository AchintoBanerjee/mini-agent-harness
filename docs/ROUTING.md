# Routing across providers

[The issue](#the-issue) · [The design](#the-design) · [The choices](#the-choices) · [What I ran](#what-i-ran-on-my-platform-in-production) · [What v2 implements](#what-v2-implements)

This page is design, from running a harness in production at scale. v2 has the seam and nothing behind it.

## The issue

- A platform has more than one model: a strong one and a cheap one, several providers, and each model in some regions and not others. A tenant's data may not leave its region. A provider throttles or goes down.
- The loop must not know any of this. It asks one entry point for a reply and gets one; where the reply came from is the entry point's problem.
- Two decisions hide in "which model": which class of model an agent needs, and which concrete model answers this call. The first is a quality decision; the second is a routing decision. Mixing them is how platforms end up guessing at runtime.

## The design

The entry point is the LLM proxy from the HLD. In production it is the gateway, and the governor on the scaling page lives in it: routing and capacity are one decision made at one place, on the same numbers. The decision, in the order it runs:

1. **What the tenant allows.** Region and residency, the providers the contract permits, a bring-your-own key. A filter, not a choice: it removes options before anything is chosen.
2. **An explicit pick.** The agent file or the user named a model: go there.
3. **Affinity for a live conversation.** A recent conversation stays on the model that served it, and on the same replica: the prefix cache lives on the server that read the prompt, so a switch mid-conversation pays a full read and can change behaviour between turns. Route by conversation id; switch only on failure.
4. **The agent's model class.** The agent declares what it needs, not which model: a capability tier, a context length, tool use, a latency budget. The gateway maps the class to a list of concrete models per tenant: the nearest region first, a backup of the same class in the next region. That mapping is also where the hosting choice lives, dedicated, on demand, or bring your own, so changing it moves every agent of that class without touching any of them.
5. **Health and capacity.** Among the candidates: error rate and latency per provider and region, a circuit breaker on the ones failing, and for the shared pool the governor's saturation number.
6. **The fallback chain.** Throttled or down: the next model in the class list. Never a silent step down to a lower class; that is a quality change, and it is decided elsewhere.

How the class is decided, strong or cheap: by evals, never by the router.

- A new agent starts on the strong model; its golden set is recorded against it.
- The same golden set runs against the cheap model. If tool choice holds and the answer score holds within the margin the owner set, the agent's class moves down and the strong model becomes its fallback. If not, it stays. The eval page's table is the decision.
- After a move, the live judge watches that agent version's rate; a drop is the signal to move back.
- Finer than per agent, when a step can declare its class: the cheap model for the steps that classify, route or extract tool arguments, the strong one for the step that reasons or writes the answer. Most of a turn's tokens are in the cheap steps.

## The choices

| Option | How it works | What you get | What it costs |
|---|---|---|---|
| a class mapping set by evals | the agent names a class; the gateway maps it per tenant and region; the golden set decides the class | explainable: every route is a lookup, every class change has a table behind it | a mapping to keep, and evals per agent before a change |
| a router model | a small classifier reads each request and sends the easy ones to the cheap model | cost down on mixed traffic | a call per request, and a hard question misrouted is a bad answer nobody can explain |
| a cascade | the cheap model first, a check on its answer, escalate on failure | the lowest average cost | the worst tail latency, and the check is a judge problem again |
| the provider's own router | the vendor routes within its catalog | nothing to build | one provider, and the quality rule is theirs, not the golden set's |

I pick the class mapping set by evals, with affinity, health and the fallback chain inside the class. A router model only for a traffic class where the eval already showed the cheap model holds, never as the default.

## What I ran, on my platform in production

- One entry point in front of every model, with the three hosting choices behind it, dedicated, on demand and bring your own, and the tokens counted per tenant and model there.
- The nearest region with a backup in the next; the governor on the shared pool, on the scaling page.
- Routing by model class set from evals is the design; the hosting choice and the governor are what ran.

## What v2 implements

| Part | In v2 | Design only |
|---|---|---|
| the entry point | built: the LLM proxy, with the rule stub behind it | the gateway: tenant filter, affinity, the class mapping, health, the fallback chain |
| the class decision | the golden set and the table that would decide it | running it against two models and moving the class |
