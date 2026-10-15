package harness.eval;

import harness.agent.Agent;
import harness.core.Orchestrator;
import harness.core.Span;
import harness.model.RuleBasedLLM;
import harness.store.InMemoryRunStore;
import harness.tools.ToolCaller;
import harness.tools.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Runs the golden set: a fresh Orchestrator per case over its own in-memory store, the
 * case's agent gated as in a real run (its tool list, its toolTimeoutMs) and the case's
 * pass budget as the cap. The scores are read from the rows the run left, the same rows
 * a crash would be resumed from, not from the answer alone.
 */
public class EvalRunner {

    private final List<Agent> agents;
    private final ToolRegistry registry;

    public EvalRunner(List<Agent> agents, ToolRegistry registry) {
        this.agents = agents;
        this.registry = registry;
    }

    public List<CaseResult> run(List<GoldenCase> cases) {
        List<CaseResult> results = new ArrayList<>();
        for (GoldenCase golden : cases) {
            results.add(run(golden));
        }
        return results;
    }

    /** One case, one run, one orchestrator: nothing carries over between cases. */
    public CaseResult run(GoldenCase golden) {
        Agent agent = agent(golden);
        InMemoryRunStore store = new InMemoryRunStore();
        ToolCaller toolCaller = new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs()));
        Orchestrator orchestrator = new Orchestrator(agent, registry, new RuleBasedLLM(), toolCaller, store, span -> { });
        long started = System.nanoTime();
        String answer = orchestrator.run(golden.question());
        long ms = Span.millisSince(started);
        return CaseResult.score(golden, store.load(orchestrator.conversation().turns().getFirst().id()), answer, ms);
    }

    /** The case's agent as shipped, with the case's pass budget as its cap when the case sets one. */
    private Agent agent(GoldenCase golden) {
        for (Agent agent : agents) {
            if (agent.name().equals(golden.agent())) {
                int maxPasses = golden.maxPasses() > 0 ? golden.maxPasses() : agent.maxPasses();
                return new Agent(agent.name(), agent.description(), agent.toolNames(), maxPasses, agent.toolTimeoutMs(),
                        agent.maxModelCalls());
            }
        }
        throw new IllegalArgumentException("no agent named " + golden.agent() + " for \"" + golden.question() + "\"");
    }

    /** The totals: the three harness scores and the median time, then the one agent score. */
    public static String footer(List<CaseResult> results) {
        int n = results.size();
        int toolChoice = 0;
        int stoppedByDone = 0;
        int errorsSurfaced = 0;
        int answers = 0;
        List<Long> times = new ArrayList<>();
        for (CaseResult result : results) {
            toolChoice += result.toolChoice() ? 1 : 0;
            stoppedByDone += result.stoppedByDone() ? 1 : 0;
            errorsSurfaced += result.errorsSurfaced() ? 1 : 0;
            answers += result.answerOk() ? 1 : 0;
            times.add(result.ms());
        }
        Collections.sort(times);
        long p50 = n == 0 ? 0 : times.get((n - 1) / 2);
        return String.format("harness: tool_choice %d/%d · stopped_by_done %d/%d · errors_surfaced %d/%d · p50 %d ms | agent: answer %d/%d",
                toolChoice, n, stoppedByDone, n, errorsSurfaced, n, p50, answers, n);
    }
}
