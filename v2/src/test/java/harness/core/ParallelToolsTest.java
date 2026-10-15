package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.model.RuleBasedLLM;
import harness.store.InMemoryRunStore;
import harness.tools.MatchingCondition;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Several tool calls in one model reply run together. Their TOOL rows still land in request
 * order, each with its own time, and a NOTE row shows the overlap: wall time against work.
 */
class ParallelToolsTest {

    private static final Pattern TOGETHER = Pattern.compile("ran (\\d+) tools together: wall (\\d+) ms, work (\\d+) ms");

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final Agent support = AgentLoader.load(Path.of("agents", "support.json"));
    private final InMemoryRunStore store = new InMemoryRunStore();

    @BeforeEach
    void resetHandlerCounts() {
        ToolLoader.HANDLER_CALLS.clear();
    }

    @AfterEach
    void noSimulatedLatency() {
        ToolLoader.SLOW_LOOKUP_MS = 0;
    }

    @Test
    void twoCallsInOneReplyRunTogetherAndTheirRowsLandInRequestOrderWithTheirOwnTime() {
        ToolRegistry pair = new ToolRegistry();
        pair.register(sleeper("slow", 300));
        pair.register(sleeper("quick", 50));
        Agent agent = new Agent("pair", "a slow tool before a quick one", List.of("slow", "quick"), 5, 2000);
        Orchestrator orchestrator = new Orchestrator(agent, pair, new RuleBasedLLM(), new ToolCaller(pair), new RelaySummarizer(), store, span -> { });
        long started = System.nanoTime();

        String answer = orchestrator.run("run slow and quick");

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertEquals("slow: slept 300 ms\nquick: slept 50 ms", answer, "the last pass's outputs, joined with newlines");
        List<Span> rows = orchestrator.conversation().turns().get(0).spans();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.TOOL, Span.Kind.NOTE, Span.Kind.LLM),
                rows.stream().map(Span::kind).toList());
        assertEquals("slow,quick", rows.get(0).attrs().get("tool"), "the LLM row carries both decided calls");
        assertEquals("slow", rows.get(1).attrs().get("tool"), "the slower call's row still comes first");
        assertEquals("quick", rows.get(2).attrs().get("tool"));
        assertTrue(rows.get(1).ms() >= 250 && rows.get(2).ms() < 250, "each row carries its own time: " + rows.get(1).ms() + " / " + rows.get(2).ms());
        String runId = orchestrator.conversation().turns().getFirst().id();
        assertEquals(runId + "/1", rows.get(1).attrs().get("key"));
        assertEquals(runId + "/1", rows.get(2).attrs().get("key"), "both rows name the LLM row that decided them");
        Matcher note = TOGETHER.matcher(rows.get(3).request());
        assertTrue(note.matches(), "the NOTE row: " + rows.get(3).request());
        assertEquals("2", note.group(1));
        long wall = Long.parseLong(note.group(2));
        long work = Long.parseLong(note.group(3));
        assertTrue(wall < work, "overlap shows as wall " + wall + " < work " + work);
        assertEquals(wall, rows.get(3).ms());
        assertTrue(elapsed < 500, "the turn took " + elapsed + " ms: the calls did not run one after the other");
    }

    @Test
    void slowLookupsRunningTogetherTakeOneSleepNotTwo() {
        ToolLoader.SLOW_LOOKUP_MS = 300;
        Orchestrator orchestrator = new Orchestrator(support, registry, new RuleBasedLLM(), new ToolCaller(registry), new RelaySummarizer(), store, span -> { });

        String answer = orchestrator.run("Status of order ORD-2 and is product PRD-1 available?");

        assertEquals("Order ORD-2: PROCESSING\nProduct PRD-1: 42 units in stock", answer);
        List<Span> rows = orchestrator.conversation().turns().get(0).spans();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.TOOL, Span.Kind.NOTE, Span.Kind.LLM),
                rows.stream().map(Span::kind).toList());
        assertTrue(rows.get(1).ms() >= 250 && rows.get(2).ms() >= 250, "each lookup slept: " + rows.get(1).ms() + " / " + rows.get(2).ms());
        Matcher note = TOGETHER.matcher(rows.get(3).request());
        assertTrue(note.matches());
        assertTrue(Long.parseLong(note.group(2)) < 500, "wall under one and a half sleeps: " + rows.get(3).request());
        assertTrue(Long.parseLong(note.group(3)) >= 500, "work is the two sleeps added: " + rows.get(3).request());
    }

    @Test
    void resumeRunsOnlyTheCallsOfTheBatchWhoseRowsNeverLanded() {
        RunListener dieAfterRow2 = span -> {
            if (span.span() == 2) {
                throw new ResumeTest.Crash();
            }
        };
        Orchestrator first = new Orchestrator(support, registry, new RuleBasedLLM(), new ToolCaller(registry), new RelaySummarizer(), store, dieAfterRow2);
        assertThrows(ResumeTest.Crash.class, () -> first.run("Status of order ORD-2 and is product PRD-1 available?"));
        String runId = first.conversation().turns().getFirst().id();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL), store.load(runId).stream().map(Span::kind).toList());
        assertEquals(Map.of("order_status", 1, "stock_status", 1), ToolLoader.HANDLER_CALLS, "both ran; only the first row landed");

        Orchestrator second = new Orchestrator(support, registry, new RuleBasedLLM(), new ToolCaller(registry), new RelaySummarizer(), store, span -> { });
        String answer = second.resume(runId);

        assertEquals("Order ORD-2: PROCESSING\nProduct PRD-1: 42 units in stock", answer);
        List<Span> rows = store.load(runId);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.NOTE, Span.Kind.TOOL, Span.Kind.LLM),
                rows.stream().map(Span::kind).toList());
        assertEquals("order_status", rows.get(1).attrs().get("tool"));
        assertEquals("resumed from step 2", rows.get(2).request());
        assertEquals("stock_status", rows.get(3).attrs().get("tool"), "only the call without a row ran again");
        assertEquals("PRD-1", rows.get(3).attrs().get("productId"), "rebuilt with its own input field");
        assertEquals(runId + "/1", rows.get(3).attrs().get("key"));
        assertEquals(1, rows.get(3).pass(), "it belongs to the pass that decided it");
        assertEquals(Map.of("order_status", 1, "stock_status", 2), ToolLoader.HANDLER_CALLS, "at-least-once for the unlanded call only");
    }

    private static Tool sleeper(String name, long ms) {
        return new Tool(name, "sleeps " + ms + " ms", "x", null, new MatchingCondition(List.of(name), null), input -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                throw new IllegalStateException("interrupted");
            }
            return name + ": slept " + ms + " ms";
        });
    }
}
