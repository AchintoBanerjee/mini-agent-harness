package harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileRunStoreTest {

    @TempDir
    Path dir;

    @Test
    void rowsRoundTripThroughTheFile() throws IOException {
        FileRunStore store = new FileRunStore(dir.resolve("runs"));
        String runId = store.newRun("support", "What is the status of order ORD-1?");
        Span llm = new Span(runId, 1, 1, 1, Span.Kind.LLM, "pass 1", "ToolCall[toolName=order_status, input={orderId=ORD-1}]",
                Span.Status.OK, 0, Map.of());
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tool", "order_status");
        attrs.put("orderId", "ORD-1");
        Span tool = new Span(runId, 2, 1, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "Order ORD-1: SHIPPED, shipment SHP-1",
                Span.Status.OK, 2, attrs);
        Span cut = new Span(runId, 3, 1, 1, Span.Kind.NOTE, "cut short at maxPasses=1", "", Span.Status.CUT_SHORT, 0, Map.of());

        store.append(runId, llm);
        store.append(runId, tool);
        store.append(runId, cut);

        assertTrue(runId.matches("r-[0-9a-f]{4}"), runId);
        assertTrue(store.exists(runId));
        assertEquals(List.of(llm, tool, cut), store.load(runId));
        assertEquals(List.of(llm, tool, cut), new FileRunStore(dir.resolve("runs")).load(runId), "a fresh store over the same dir reads it");
        assertEquals(List.of(cut), store.load(runId, 3));

        List<String> lines = Files.readAllLines(store.file(runId));
        assertEquals(4, lines.size(), "a start line, then one line per row");
        assertEquals("{\"run\":\"" + runId + "\",\"agent\":\"support\",\"question\":\"What is the status of order ORD-1?\"}", lines.get(0));
        assertEquals("{\"run\":\"" + runId + "\",\"step\":2,\"turn\":1,\"pass\":1,\"kind\":\"TOOL\","
                + "\"request\":\"order_status {orderId=ORD-1}\",\"response\":\"Order ORD-1: SHIPPED, shipment SHP-1\","
                + "\"status\":\"OK\",\"ms\":2,\"attrs\":{\"tool\":\"order_status\",\"orderId\":\"ORD-1\"}}", lines.get(2));
    }

    @Test
    void aRunThatWasNeverOpenedCannotBeAppendedToOrLoaded() {
        FileRunStore store = new FileRunStore(dir);

        assertFalse(store.exists("r-0000"));
        assertThrows(IllegalArgumentException.class, () -> store.load("r-0000"));
        assertThrows(IllegalArgumentException.class, () -> store.append("r-0000", new Span(Span.Kind.NOTE, "x", "")));
    }

    @Test
    void twoRunsGetTwoFiles() {
        FileRunStore store = new FileRunStore(dir);

        String first = store.newRun("support", "one");
        String second = store.newRun("support", "two");

        assertTrue(Files.exists(dir.resolve(first + ".jsonl")));
        assertTrue(Files.exists(dir.resolve(second + ".jsonl")));
        assertEquals(List.of(), store.load(second));
    }
}
