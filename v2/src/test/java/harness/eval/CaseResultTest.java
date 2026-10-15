package harness.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Scoring from hand-built rows: what each score looks at, and what a failing row says. */
class CaseResultTest {

    private static final GoldenCase ORDER = new GoldenCase("status of order ORD-1", "support", List.of("order_status"), "shipped", 3);
    private static final String ANSWER = "Order ORD-1: SHIPPED, shipment SHP-1";

    @Test
    void aCleanRunPassesEveryScoreAndCountsItsPasses() {
        CaseResult result = CaseResult.score(ORDER, List.of(llm(1, "order_status"), tool(2, "order_status", Span.Status.OK), llm(3, null)), ANSWER, 4);

        assertTrue(result.passed());
        assertEquals(List.of(), result.failures());
        assertEquals(Set.of("order_status"), result.tools());
        assertEquals(2, result.passes());
        assertEquals(4, result.ms());
        assertEquals(" 1  PASS  support    status of order ORD-1", result.line(1).substring(0, 42));
    }

    @Test
    void toolChoiceComparesSetsAndLeavesDedupedRowsOut() {
        List<Span> rows = List.of(llm(1, "order_status,stock_status"), tool(2, "order_status", Span.Status.OK),
                tool(3, "stock_status", Span.Status.DEDUPED), llm(4, null));

        assertTrue(CaseResult.score(ORDER, rows, ANSWER, 0).toolChoice(), "the DEDUPED row is not a choice");

        GoldenCase noTool = new GoldenCase("reorder ORD-1", "support", List.of(), "shipped", 3);
        CaseResult result = CaseResult.score(noTool, rows, ANSWER, 0);
        assertFalse(result.passed());
        assertEquals(List.of("tool_choice: expected [] got [order_status]"), result.failures());
        assertTrue(result.line(11).startsWith("11  FAIL  support    reorder ORD-1"), result.line(11));
        assertTrue(result.line(11).endsWith("tool_choice: expected [] got [order_status]"), result.line(11));
    }

    @Test
    void aCutShortRowFailsStoppedByDone() {
        Span cut = new Span("r-test", 5, 2, Span.Kind.NOTE, "cut short at maxPasses=2", "", Span.Status.CUT_SHORT, 0, Map.of());
        CaseResult result = CaseResult.score(ORDER, List.of(llm(1, "order_status"), tool(2, "order_status", Span.Status.OK), cut), ANSWER, 0);

        assertFalse(result.stoppedByDone());
        assertEquals(List.of("stopped_by_done: cut short at maxPasses=3"), result.failures());
    }

    @Test
    void aFailedToolMustBeNamedWithItsStatusInTheAnswer() {
        GoldenCase hang = new GoldenCase("hang ORD-1", "chaos", List.of("hang_forever"), "timeout", 3);
        List<Span> rows = List.of(llm(1, "hang_forever"), tool(2, "hang_forever", Span.Status.TIMEOUT), llm(3, null));

        assertTrue(CaseResult.score(hang, rows, "Tool hang_forever TIMEOUT: cut off after 300 ms", 300).errorsSurfaced());

        CaseResult hidden = CaseResult.score(hang, rows, "timeout, sorry", 300);
        assertFalse(hidden.errorsSurfaced(), "the status word itself must be in the answer");
        assertTrue(hidden.answerOk(), "the answer check is separate and case-blind");
        assertEquals(List.of("errors_surfaced: a tool failed and the answer does not say so"), hidden.failures());
    }

    @Test
    void theAnswerCheckIgnoresCaseAndSpacingAndSaysWhatItGot() {
        GoldenCase stock = new GoldenCase("PRD-2?", "support", List.of("stock_status"), "out  of stock", 3);
        List<Span> rows = List.of(llm(1, "stock_status"), tool(2, "stock_status", Span.Status.OK), llm(3, null));

        assertTrue(CaseResult.score(stock, rows, "Product PRD-2: OUT OF STOCK", 0).answerOk());

        CaseResult wrong = CaseResult.score(stock, rows, "Product PRD-2: 42 units\nin stock", 0);
        assertEquals(List.of("answer: expected \"out  of stock\" got \"Product PRD-2: 42 units | in stock\""), wrong.failures());
    }

    private static Span llm(int step, String tools) {
        Map<String, String> attrs = tools == null ? Map.of("question", "q") : Map.of("question", "q", "tool", tools);
        return new Span("r-test", step, (step + 1) / 2, Span.Kind.LLM, "pass", tools == null ? "Done[]" : "ToolCall", Span.Status.OK, 0, attrs);
    }

    private static Span tool(int step, String name, Span.Status status) {
        return new Span("r-test", step, 1, Span.Kind.TOOL, name + " {}", "out", status, 1, Map.of("tool", name));
    }
}
