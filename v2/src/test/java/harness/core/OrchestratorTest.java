package harness.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.model.Done;
import harness.model.LLMProxy;
import harness.model.ModelReply;
import harness.model.RuleBasedLLM;
import harness.model.ToolCall;
import harness.store.InMemoryRunStore;
import harness.store.TurnRow;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class OrchestratorTest {

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final Agent agent = AgentLoader.load(Path.of("agents", "support.json"));

    private Orchestrator orchestrator() {
        return new Orchestrator(agent, registry, new RuleBasedLLM(), new ToolCaller(registry));
    }

    @Test
    void orderQuestionIsAnsweredByTheOrderTool() {
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", orchestrator().run("What is the status of order ORD-1?"));
    }

    @Test
    void shipmentQuestionIsAnsweredByTheShippingTool() {
        assertEquals("Shipment SHP-1: IN TRANSIT, arriving 2026-10-16", orchestrator().run("Where is my shipment SHP-1?"));
    }

    @Test
    void stockQuestionIsAnsweredByTheStockTool() {
        assertEquals("Product PRD-2: OUT OF STOCK", orchestrator().run("Is product PRD-2 available?"));
    }

    @Test
    void questionWithNoMatchingToolEndsWithNoToolAnswer() {
        assertEquals(RelaySummarizer.NO_TOOL, orchestrator().run("What is the weather today?"));
    }

    @Test
    void turnRecordsModelCallThenToolCallThenDone() {
        Orchestrator orchestrator = orchestrator();

        orchestrator.run("What is the status of order ORD-2?");

        Turn turn = orchestrator.conversation().turns().get(0);
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM),
                turn.spans().stream().map(Span::kind).toList());
        assertEquals("Order ORD-2: PROCESSING", turn.answer());
    }

    @Test
    void eachQuestionIsItsOwnTurnInTheSameConversation() {
        Orchestrator orchestrator = orchestrator();

        orchestrator.run("What is the status of order ORD-1?");
        orchestrator.run("Is product PRD-1 available?");

        assertEquals(2, orchestrator.conversation().turns().size());
        assertEquals("Product PRD-1: 42 units in stock", orchestrator.conversation().turns().get(1).answer());
    }

    @Test
    void theAnswerIsStreamedToTheListenerPieceByPieceThenDone() {
        Collecting listener = new Collecting();
        Orchestrator orchestrator = new Orchestrator(agent, registry, new RuleBasedLLM(), new ToolCaller(registry),
                new InMemoryRunStore(), listener);

        String answer = orchestrator.run("What is the status of order ORD-1?");

        String turnId = orchestrator.conversation().turns().getFirst().id();
        assertEquals(List.of("Order ", "ORD-1: ", "SHIPPED, ", "shipment ", "SHP-1"), listener.tokens, "one word per piece, the space kept");
        assertEquals(answer, String.join("", listener.tokens), "the pieces concatenate to the answer");
        assertEquals(List.of(turnId), listener.tokenTurns.stream().distinct().toList(), "every piece names the turn");
        assertEquals(List.of(turnId + " DONE " + answer), listener.ends, "done once, after the row says DONE");
        assertEquals(List.of("step", "step", "step", "token", "token", "token", "token", "token", "done"), listener.order,
                "the rows first, then the pieces, then done");
    }

    @Test
    void aFailedTurnEndsWithDoneFailedAndNoTokens() {
        Collecting listener = new Collecting();
        Agent concierge = AgentLoader.load(Path.of("agents", "concierge.json"));
        ToolCaller caller = new ToolCaller(registry, concierge.toolNames(), Duration.ofMillis(concierge.toolTimeoutMs()));
        Orchestrator orchestrator = new Orchestrator(concierge, registry, new RuleBasedLLM(), caller,
                new InMemoryRunStore(), listener, 0, 0, new TurnBudget(3));

        assertThrows(IllegalStateException.class, () -> orchestrator.run("Where is shipment SHP-1 for my order?"));

        String turnId = orchestrator.conversation().turns().getFirst().id();
        assertEquals(List.of(), listener.tokens, "no answer was produced");
        assertEquals(List.of(turnId + " FAILED null"), listener.ends);
    }

    @Test
    void aChildLoopStreamsNoTokensAndTheTurnEndsOnce() {
        Collecting listener = new Collecting();
        Agent concierge = AgentLoader.load(Path.of("agents", "concierge.json"));
        ToolCaller caller = new ToolCaller(registry, concierge.toolNames(), Duration.ofMillis(concierge.toolTimeoutMs()));
        Orchestrator orchestrator = new Orchestrator(concierge, registry, new RuleBasedLLM(), caller,
                new InMemoryRunStore(), listener);

        String answer = orchestrator.run("Where is shipment SHP-1 for my order?");

        assertEquals("Shipment SHP-1: IN TRANSIT, arriving 2026-10-16", answer);
        assertEquals(answer, String.join("", listener.tokens), "only the parent's answer streams");
        assertEquals(List.of("Shipment ", "SHP-1: ", "IN ", "TRANSIT, ", "arriving ", "2026-10-16"), listener.tokens,
                "the child's answer came back as a tool result, not as pieces: the same words once, not twice");
        assertEquals(1, listener.ends.size(), "exactly one done for the turn, none from the child");
        assertEquals(6, listener.order.stream().filter("step"::equals).count(), "the child's rows are still announced");
        assertEquals("done", listener.order.getLast());
    }

    @Test
    void agentWithAToolMissingFromTheRegistryIsRejected() {
        Agent bad = new Agent("bad", "lists a tool that does not exist", List.of("order_status", "cancel_order"), 5, 2000);

        assertThrows(IllegalArgumentException.class, () ->
                new Orchestrator(bad, registry, new RuleBasedLLM(), new ToolCaller(registry)));
    }

    @Test
    void aModelThatNeverSaysDoneIsStoppedAfterMaxPasses() {
        LLMProxy stubborn = deciding((context, tools) -> new ToolCall("order_status", Map.of("orderId", "ORD-1")));
        Orchestrator orchestrator = new Orchestrator(agent, registry, stubborn, new ToolCaller(registry));

        orchestrator.run("anything");

        Turn turn = orchestrator.conversation().turns().get(0);
        long modelCalls = turn.spans().stream().filter(s -> s.kind() == Span.Kind.LLM).count();
        assertEquals(agent.maxPasses(), modelCalls);
    }

    @Test
    void hittingTheCapLeavesACutShortNoteAsTheLastRow() {
        LLMProxy stubborn = deciding((context, tools) -> new ToolCall("order_status", Map.of("orderId", "ORD-1")));
        Agent twoPasses = new Agent("stubborn", "never says done", List.of("order_status"), 2, 2000);
        Orchestrator orchestrator = new Orchestrator(twoPasses, registry, stubborn, new ToolCaller(registry));

        String answer = orchestrator.run("anything");

        List<Span> rows = orchestrator.conversation().turns().get(0).spans();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.NOTE),
                rows.stream().map(Span::kind).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), rows.stream().map(Span::span).toList());
        Span note = rows.getLast();
        assertEquals(Span.Status.CUT_SHORT, note.status());
        assertEquals("cut short at maxPasses=2", note.request());
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", answer);
    }

    @Test
    void theLoopSurvivesAHangAndTheAnswerNamesTheCutOff() {
        Agent chaos = AgentLoader.load(Path.of("agents", "chaos.json"));
        ToolCaller impatient = new ToolCaller(registry, chaos.toolNames(), Duration.ofMillis(300));
        Orchestrator orchestrator = new Orchestrator(chaos, registry, new RuleBasedLLM(), impatient);
        long started = System.nanoTime();

        String answer = orchestrator.run("Please hang while checking ORD-1");

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertEquals("Tool hang_forever TIMEOUT: cut off after 300 ms", answer);
        List<Span> rows = orchestrator.conversation().turns().get(0).spans();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM), rows.stream().map(Span::kind).toList(),
                "two passes, well under maxPasses=" + chaos.maxPasses() + ", no CUT_SHORT");
        assertEquals(Span.Status.TIMEOUT, rows.get(1).status());
        assertTrue(rows.get(1).ms() >= 250 && rows.get(1).ms() < 600, "the TOOL row carries the wait: " + rows.get(1).ms() + " ms");
        assertTrue(elapsed < 600, "the whole turn took " + elapsed + " ms");
    }

    @Test
    void theLoopSurvivesAThrowingToolAndTheAnswerNamesTheError() {
        Agent chaos = AgentLoader.load(Path.of("agents", "chaos.json"));
        Orchestrator orchestrator = new Orchestrator(chaos, registry, new RuleBasedLLM(),
                new ToolCaller(registry, chaos.toolNames(), Duration.ofSeconds(1)));

        String answer = orchestrator.run("boom");

        assertEquals("Tool throw_boom ERROR: Tool failed: boom", answer);
        List<Span> rows = orchestrator.conversation().turns().get(0).spans();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM), rows.stream().map(Span::kind).toList());
        assertEquals(Span.Status.ERROR, rows.get(1).status());
    }

    @Test
    void aModelThatNamesAToolOutsideTheAgentsListIsDeniedAndTheLoopMovesOn() {
        LLMProxy rogue = deciding((context, tools) -> context.spans().isEmpty()
                ? new ToolCall("refund_order", Map.of("orderId", "ORD-1"))
                : new Done());
        Orchestrator orchestrator = new Orchestrator(agent, registry, rogue,
                new ToolCaller(registry, agent.toolNames(), Duration.ofSeconds(1)));

        String answer = orchestrator.run("refund order ORD-1");

        assertEquals("Tool refund_order DENIED: not in this agent's tool list", answer);
        List<Span> rows = orchestrator.conversation().turns().get(0).spans();
        assertEquals(List.of(Span.Kind.LLM, Span.Kind.TOOL, Span.Kind.LLM), rows.stream().map(Span::kind).toList());
        assertEquals(Span.Status.DENIED, rows.get(1).status());
    }

    /** A model that decides as given and gives its final reply the way the stub does. */
    static LLMProxy deciding(BiFunction<Context, List<Tool>, ModelReply> next) {
        RuleBasedLLM stub = new RuleBasedLLM();
        return new LLMProxy() {
            @Override
            public ModelReply next(Context context, List<Tool> tools) {
                return next.apply(context, tools);
            }

            @Override
            public String answer(Turn turn, Consumer<String> out) {
                return stub.answer(turn, out);
            }
        };
    }

    /** Everything the loop announces, in order: each row, each piece of the answer, the end. */
    static class Collecting implements RunListener {

        final List<String> order = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        final List<String> tokenTurns = new ArrayList<>();
        final List<String> ends = new ArrayList<>();

        @Override
        public void on(Span span) {
            order.add("step");
        }

        @Override
        public void token(String turnId, String text) {
            order.add("token");
            tokens.add(text);
            tokenTurns.add(turnId);
        }

        @Override
        public void done(String turnId, TurnRow.Status status, String answer) {
            order.add("done");
            ends.add(turnId + " " + status + " " + answer);
        }
    }
}
