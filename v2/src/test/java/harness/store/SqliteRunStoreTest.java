package harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The SQLite store on a temp file: never v2/harness.db. */
class SqliteRunStoreTest {

    @TempDir
    Path dir;

    @Test
    void rowsAndTheJobRowRoundTripThroughTheFile() {
        Path file = dir.resolve("harness.db");
        String runId;
        Span llm;
        Span tool;
        Span cut;
        try (SqliteRunStore store = new SqliteRunStore(file)) {
            runId = store.newRun("support", "What is the status of order ORD-1?");
            llm = new Span(runId, 1, 1, 1, Span.Kind.LLM, "pass 1", "ToolCall[toolName=order_status, input={orderId=ORD-1}]",
                    Span.Status.OK, 0, Map.of());
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("tool", "order_status");
            attrs.put("orderId", "ORD-1");
            tool = new Span(runId, 2, 1, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "Order ORD-1: SHIPPED, shipment SHP-1",
                    Span.Status.OK, 2, attrs);
            cut = new Span(runId, 3, 1, 1, Span.Kind.NOTE, "cut short at maxPasses=1", "", Span.Status.CUT_SHORT, 0, Map.of());

            store.append(runId, llm);
            store.append(runId, tool);
            store.append(runId, cut);

            assertTrue(runId.matches("r-[0-9a-f]{4}"), runId);
            assertTrue(store.exists(runId));
            assertEquals(List.of(llm, tool, cut), store.load(runId));
            assertEquals(List.of(cut), store.load(runId, 3));

            Job job = store.job(runId);
            assertEquals(runId, job.id());
            assertEquals(runId, job.conversationId(), "one conversation per job today");
            assertEquals("support", job.agent());
            assertEquals("What is the status of order ORD-1?", job.question());
            assertEquals(Job.Status.RUNNING, job.status(), "opened RUNNING");
            assertTrue(job.createdAt() > 0 && job.updatedAt() == job.createdAt());

            store.setStatus(runId, Job.Status.DONE);
            assertEquals(Job.Status.DONE, store.job(runId).status());
            assertTrue(store.job(runId).updatedAt() >= job.updatedAt());
        }
        try (SqliteRunStore again = new SqliteRunStore(file)) {
            assertEquals(List.of(llm, tool, cut), again.load(runId), "a fresh store over the same file reads it");
            assertEquals(Job.Status.DONE, again.job(runId).status());
        }
    }

    @Test
    void theFileHoldsExactlyTheTwoTablesWithAttrsAsJsonText() throws Exception {
        Path file = dir.resolve("harness.db");
        String runId;
        try (SqliteRunStore store = new SqliteRunStore(file)) {
            runId = store.newRun("support", "q");
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("tool", "order_status");
            attrs.put("orderId", "ORD-1");
            store.append(runId, new Span(runId, 1, 1, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "x", Span.Status.OK, 2, attrs));
        }
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = db.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
            assertEquals(List.of("job", "step"), tables);
            try (ResultSet result = statement.executeQuery("SELECT job_id, status, attrs FROM step WHERE step = 1")) {
                assertTrue(result.next());
                assertEquals(runId, result.getString("job_id"));
                assertEquals("OK", result.getString("status"));
                assertEquals("{\"tool\":\"order_status\",\"orderId\":\"ORD-1\"}", result.getString("attrs"));
            }
        }
    }

    @Test
    void aRunThatWasNeverOpenedIsRefusedEverywhere() {
        try (SqliteRunStore store = new SqliteRunStore(dir.resolve("harness.db"))) {
            assertFalse(store.exists("r-0000"));
            assertThrows(IllegalArgumentException.class, () -> store.load("r-0000"));
            assertThrows(IllegalArgumentException.class, () -> store.job("r-0000"));
            assertThrows(IllegalArgumentException.class, () -> store.setStatus("r-0000", Job.Status.DONE));
            assertThrows(IllegalArgumentException.class, () -> store.append("r-0000", new Span(Span.Kind.NOTE, "x", "")));

            String first = store.newRun("support", "one");
            String second = store.newRun("support", "two");
            assertTrue(store.exists(first) && store.exists(second));
            assertEquals(List.of(), store.load(second), "a new run has a job row and no steps");
        }
    }
}
