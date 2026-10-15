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
    void theConversationTheTurnAndItsSpansRoundTripThroughTheFile() {
        Path file = dir.resolve("harness.db");
        String conversation;
        String turnId;
        Span llm;
        Span tool;
        Span cut;
        try (SqliteRunStore store = new SqliteRunStore(file)) {
            conversation = store.newConversation("support");
            turnId = store.newTurn(conversation, "What is the status of order ORD-1?");
            llm = new Span(turnId, 1, 1, Span.Kind.LLM, "pass 1", "ToolCall[toolName=order_status, input={orderId=ORD-1}]",
                    Span.Status.OK, 0, Map.of());
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("tool", "order_status");
            attrs.put("orderId", "ORD-1");
            tool = new Span(turnId, 2, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "Order ORD-1: SHIPPED, shipment SHP-1",
                    Span.Status.OK, 2, attrs);
            cut = new Span(turnId, 3, 1, Span.Kind.NOTE, "cut short at maxPasses=1", "", Span.Status.CUT_SHORT, 0, Map.of());

            store.append(turnId, llm);
            store.append(turnId, tool);
            store.append(turnId, cut);

            assertTrue(conversation.matches("c-[0-9a-f]{4}"), conversation);
            assertTrue(turnId.matches("r-[0-9a-f]{4}"), turnId);
            assertTrue(store.exists(turnId));
            assertEquals(List.of(llm, tool, cut), store.load(turnId));
            assertEquals(List.of(cut), store.load(turnId, 3));

            assertEquals("support", store.conversation(conversation).agent());
            TurnRow turn = store.turn(turnId);
            assertEquals(turnId, turn.id());
            assertEquals(conversation, turn.conversationId());
            assertEquals("support", turn.agent(), "the agent comes from the conversation");
            assertEquals("What is the status of order ORD-1?", turn.question());
            assertEquals(TurnRow.Status.QUEUED, turn.status(), "opened QUEUED");
            assertEquals(null, turn.answer(), "no answer yet");
            assertEquals(null, turn.claimedBy(), "no worker yet");
            assertEquals(0, turn.attempts());
            assertTrue(turn.createdAt() > 0 && turn.updatedAt() == turn.createdAt());

            store.done(turnId, "Order ORD-1: SHIPPED, shipment SHP-1");
            assertEquals(TurnRow.Status.DONE, store.turn(turnId).status());
            assertEquals("Order ORD-1: SHIPPED, shipment SHP-1", store.turn(turnId).answer());
            assertTrue(store.turn(turnId).updatedAt() >= turn.updatedAt());
        }
        try (SqliteRunStore again = new SqliteRunStore(file)) {
            assertEquals(List.of(llm, tool, cut), again.load(turnId), "a fresh store over the same file reads it");
            assertEquals(TurnRow.Status.DONE, again.turn(turnId).status());
            assertEquals(List.of(turnId), again.turns(conversation).stream().map(TurnRow::id).toList());
        }
    }

    @Test
    void aConversationListsItsTurnsOldestFirst() {
        try (SqliteRunStore store = new SqliteRunStore(dir.resolve("harness.db"))) {
            String mine = store.newConversation("support");
            String other = store.newConversation("inventory");
            String first = store.newTurn(mine, "one");
            String elsewhere = store.newTurn(other, "stock?");
            String second = store.newTurn(mine, "two");

            assertEquals(List.of(first, second), store.turns(mine).stream().map(TurnRow::id).toList());
            assertEquals(List.of(elsewhere), store.turns(other).stream().map(TurnRow::id).toList());
            assertEquals("inventory", store.turn(elsewhere).agent());
            assertEquals(List.of(), store.turns("c-0000"), "an unknown conversation has no turns");
        }
    }

    @Test
    void claimSetsRunningNamesTheWorkerAndCountsTheAttempt() {
        try (SqliteRunStore store = new SqliteRunStore(dir.resolve("harness.db"))) {
            String turnId = store.newTurn(store.newConversation("support"), "q");
            assertEquals(1, store.claim(turnId, "12345"));
            TurnRow claimed = store.turn(turnId);
            assertEquals(TurnRow.Status.RUNNING, claimed.status());
            assertEquals("12345", claimed.claimedBy());
            assertEquals(1, claimed.attempts());

            assertEquals(2, store.claim(turnId, "12346"), "the next worker: one more attempt");
            assertEquals("12346", store.turn(turnId).claimedBy());

            store.setStatus(turnId, TurnRow.Status.FAILED);
            assertEquals(TurnRow.Status.FAILED, store.turn(turnId).status());
            assertEquals(null, store.turn(turnId).answer(), "a turn that threw has no answer");
        }
    }

    @Test
    void theFileHoldsTheThreeTablesWithAttrsAsJsonText() throws Exception {
        Path file = dir.resolve("harness.db");
        String turnId;
        try (SqliteRunStore store = new SqliteRunStore(file)) {
            turnId = store.newTurn(store.newConversation("support"), "q");
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("tool", "order_status");
            attrs.put("orderId", "ORD-1");
            store.append(turnId, new Span(turnId, 1, 1, Span.Kind.TOOL, "order_status {orderId=ORD-1}", "x", Span.Status.OK, 2, attrs));
        }
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = db.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")) {
                while (result.next()) {
                    tables.add(result.getString(1));
                }
            }
            assertEquals(List.of("conversation", "span", "turn"), tables);
            try (ResultSet result = statement.executeQuery("SELECT turn_id, status, attrs FROM span WHERE span = 1")) {
                assertTrue(result.next());
                assertEquals(turnId, result.getString("turn_id"));
                assertEquals("OK", result.getString("status"));
                assertEquals("{\"tool\":\"order_status\",\"orderId\":\"ORD-1\"}", result.getString("attrs"));
            }
        }
    }

    @Test
    void aFileFromBeforeTheRenameIsRefusedWithAMessage() throws Exception {
        Path file = dir.resolve("old.db");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file); Statement statement = db.createStatement()) {
            statement.execute("CREATE TABLE job (id TEXT PRIMARY KEY, agent TEXT, status TEXT)");
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new SqliteRunStore(file));
        assertTrue(refused.getMessage().endsWith("is from before the conversation, turn and span tables: delete it and start again"), refused.getMessage());
    }

    @Test
    void aTurnThatWasNeverOpenedIsRefusedEverywhere() {
        try (SqliteRunStore store = new SqliteRunStore(dir.resolve("harness.db"))) {
            assertFalse(store.exists("r-0000"));
            assertThrows(IllegalArgumentException.class, () -> store.load("r-0000"));
            assertThrows(IllegalArgumentException.class, () -> store.turn("r-0000"));
            assertThrows(IllegalArgumentException.class, () -> store.conversation("c-0000"));
            assertThrows(IllegalArgumentException.class, () -> store.newTurn("c-0000", "q"));
            assertThrows(IllegalArgumentException.class, () -> store.setStatus("r-0000", TurnRow.Status.DONE));
            assertThrows(IllegalArgumentException.class, () -> store.append("r-0000", new Span(Span.Kind.NOTE, "x", "")));

            String conversation = store.newConversation("support");
            String first = store.newTurn(conversation, "one");
            String second = store.newTurn(conversation, "two");
            assertTrue(store.exists(first) && store.exists(second));
            assertEquals(List.of(), store.load(second), "a new turn has a row and no spans");
        }
    }
}
