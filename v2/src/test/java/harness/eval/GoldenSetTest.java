package harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.tools.ToolLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The golden set as a regression test on the harness: every case stops by Done within its
 * pass budget, and every tool that failed is named in the answer. tool_choice and answer are
 * reported, not asserted: two cases fail them on purpose ("reorder ORD-1" runs order_status
 * on the substring "order"; "track my parcel SHP-3" has no shipping keyword) and stay in so
 * the eval has something to show. Tool timeouts are capped to keep the suite fast; the hang
 * case still ends in TIMEOUT.
 */
class GoldenSetTest {

    private static final long QUICK_TIMEOUT_MS = 300;

    private final List<GoldenCase> cases = GoldenCase.loadAll(Path.of("evals", "golden.json"));
    private final EvalRunner runner = new EvalRunner(quickAgents(), ToolLoader.load(Path.of("tools")));

    @Test
    void theGoldenSetHasThirteenCasesOverTheThreeAgents() {
        assertEquals(13, cases.size());
        assertEquals(Set.of("support", "inventory", "chaos"), cases.stream().map(GoldenCase::agent).collect(Collectors.toSet()));
        assertEquals(List.of("hang_forever"), cases.get(5).expectTools(), "the hang case is in");
    }

    @Test
    void everyCaseStopsByDoneAndEveryFailedToolIsNamedInTheAnswer() {
        List<CaseResult> results = runner.run(cases);

        List<String> cutShort = new ArrayList<>();
        List<String> hidden = new ArrayList<>();
        for (CaseResult result : results) {
            if (!result.stoppedByDone()) {
                cutShort.add(result.golden().question());
            }
            if (!result.errorsSurfaced()) {
                hidden.add(result.golden().question());
            }
        }
        assertEquals(List.of(), cutShort, "stopped_by_done at 100%");
        assertEquals(List.of(), hidden, "errors_surfaced at 100%");
        String footer = EvalRunner.footer(results);
        assertTrue(footer.contains("stopped_by_done 13/13 · errors_surfaced 13/13"), footer);
        assertTrue(footer.startsWith("harness: tool_choice ") && footer.contains(" | agent: answer "), footer);

        CaseResult hang = results.get(5);
        assertEquals(Set.of("hang_forever"), hang.tools(), "the hang case ran the hanging tool alone");
        assertTrue(hang.answer().startsWith("Tool hang_forever TIMEOUT: cut off after " + QUICK_TIMEOUT_MS + " ms"), hang.answer());
        assertTrue(hang.passed(), hang.line(6));
        assertTrue(hang.ms() >= QUICK_TIMEOUT_MS - 50 && hang.ms() < QUICK_TIMEOUT_MS + 300, "the case's ms is the wait: " + hang.ms());
    }

    /** The agents as shipped, every tool timeout capped so the hang case costs 300 ms, not 1 s. */
    private static List<Agent> quickAgents() {
        List<Agent> quick = new ArrayList<>();
        for (Agent agent : AgentLoader.loadAll(Path.of("agents"))) {
            quick.add(new Agent(agent.name(), agent.description(), agent.toolNames(), agent.maxPasses(),
                    Math.min(agent.toolTimeoutMs(), QUICK_TIMEOUT_MS)));
        }
        return quick;
    }
}
