package harness.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import harness.model.ToolCall;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ToolCallerTest {

    private final ToolRegistry registry = ToolLoader.load(Path.of("tools"));
    private final ToolCaller caller = new ToolCaller(registry);

    @Test
    void runsTheNamedTool() {
        ToolResult result = caller.call(new ToolCall("stock_status", Map.of("productId", "PRD-1")));

        assertEquals(Span.Status.OK, result.status());
        assertEquals("Product PRD-1: 42 units in stock", result.output());
    }

    @Test
    void unknownIdIsANormalOutputNotAnError() {
        ToolResult result = caller.call(new ToolCall("order_status", Map.of("orderId", "ORD-99")));

        assertEquals(Span.Status.OK, result.status());
        assertEquals("Order ORD-99 not found", result.output());
    }

    @Test
    void unknownToolIsAnErrorResult() {
        ToolResult result = caller.call(new ToolCall("cancel_order", Map.of()));

        assertEquals(Span.Status.ERROR, result.status());
        assertEquals("Unknown tool: cancel_order", result.output());
    }

    @Test
    void aToolThatThrowsBecomesAnErrorResult() {
        ToolResult result = caller.call(new ToolCall("throw_boom", Map.of()));

        assertEquals(Span.Status.ERROR, result.status());
        assertEquals("Tool failed: boom", result.output());
    }

    @Test
    void aToolThatThrowsAnErrorNotAnExceptionIsStillAnErrorResult() {
        ToolRegistry one = new ToolRegistry();
        one.register(new Tool("asserting", "throws an Error", "x", null, new MatchingCondition(List.of(), null), input -> {
            throw new AssertionError("not even an exception");
        }));

        ToolResult result = new ToolCaller(one).call(new ToolCall("asserting", Map.of()));

        assertEquals(Span.Status.ERROR, result.status());
        assertEquals("Tool failed: not even an exception", result.output());
    }

    @Test
    void aRegisteredToolNotOnTheAgentsListIsDeniedWithoutRunning() {
        ToolCaller support = new ToolCaller(registry, List.of("order_status", "shipping_status", "stock_status"), Duration.ofSeconds(2));
        int before = ToolLoader.HANDLER_CALLS.getOrDefault("refund_order", 0);

        ToolResult result = support.call(new ToolCall("refund_order", Map.of("orderId", "ORD-1")));

        assertEquals(Span.Status.DENIED, result.status());
        assertEquals("not in this agent's tool list", result.output());
        assertEquals(before, ToolLoader.HANDLER_CALLS.getOrDefault("refund_order", 0), "the handler never ran");
    }

    @Test
    void aHangingToolIsCutOffAtTheTimeoutAndTheCallerStaysUsable() {
        ToolCaller impatient = new ToolCaller(registry, List.of("hang_forever", "order_status"), Duration.ofMillis(300));
        long started = System.nanoTime();

        ToolResult result = impatient.call(new ToolCall("hang_forever", Map.of("orderId", "ORD-1")));

        long elapsed = (System.nanoTime() - started) / 1_000_000;
        assertEquals(Span.Status.TIMEOUT, result.status());
        assertEquals("cut off after 300 ms", result.output());
        assertTrue(elapsed < 600, "took " + elapsed + " ms: the timeout is 300 ms, plus 300 ms slack");
        ToolResult next = impatient.call(new ToolCall("order_status", Map.of("orderId", "ORD-1")));
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", next.output(), "the executor outlives the cut-off call");
    }

    @Test
    void theCutOffToolIsInterruptedNotLeftRunning() throws InterruptedException {
        CountDownLatch interrupted = new CountDownLatch(1);
        ToolRegistry one = new ToolRegistry();
        one.register(new Tool("stuck", "sleeps until interrupted", "x", null, new MatchingCondition(List.of(), null), input -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw new IllegalStateException("interrupted");
            }
            return "never";
        }));
        ToolCaller impatient = new ToolCaller(one, List.of("stuck"), Duration.ofMillis(300));

        assertEquals(Span.Status.TIMEOUT, impatient.call(new ToolCall("stuck", Map.of())).status());

        assertTrue(interrupted.await(300, TimeUnit.MILLISECONDS), "cancel(true) must interrupt the tool's thread");
    }

    @Test
    void callAllRunsTheCallsTogetherAndAnswersInRequestOrder() {
        ToolRegistry two = new ToolRegistry();
        two.register(sleeper("first", 300));
        two.register(sleeper("second", 300));
        long started = System.nanoTime();

        List<ToolResult> results = new ToolCaller(two).callAll(List.of(
                new ToolCall("first", Map.of()), new ToolCall("second", Map.of())));

        long wall = (System.nanoTime() - started) / 1_000_000;
        assertEquals(List.of("first: slept 300 ms", "second: slept 300 ms"),
                results.stream().map(ToolResult::output).toList());
        assertTrue(wall < 500, "two 300 ms tools ran together, wall " + wall + " ms");
        assertTrue(results.get(0).ms() >= 250 && results.get(1).ms() >= 250, "each result carries its own time: " + results);
    }

    @Test
    void aThrowingSiblingLeavesTheOtherIntact() {
        List<ToolResult> results = caller.callAll(List.of(
                new ToolCall("throw_boom", Map.of()), new ToolCall("order_status", Map.of("orderId", "ORD-1"))));

        assertEquals(Span.Status.ERROR, results.get(0).status());
        assertEquals("Tool failed: boom", results.get(0).output());
        assertEquals(Span.Status.OK, results.get(1).status());
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", results.get(1).output());
    }

    @Test
    void aHangingSiblingTimesOutAloneAndOnItsOwnDeadline() {
        ToolCaller impatient = new ToolCaller(registry, List.of("hang_forever", "order_status"), Duration.ofMillis(300));
        long started = System.nanoTime();

        List<ToolResult> results = impatient.callAll(List.of(
                new ToolCall("hang_forever", Map.of("orderId", "ORD-1")), new ToolCall("order_status", Map.of("orderId", "ORD-1"))));

        long wall = (System.nanoTime() - started) / 1_000_000;
        assertEquals(Span.Status.TIMEOUT, results.get(0).status());
        assertEquals("cut off after 300 ms", results.get(0).output());
        assertEquals(300, results.get(0).ms(), "a cut-off call is charged the time it was allowed");
        assertEquals(Span.Status.OK, results.get(1).status());
        assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", results.get(1).output());
        assertTrue(wall < 600, "the batch ends at the hang's deadline, wall " + wall + " ms");
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
