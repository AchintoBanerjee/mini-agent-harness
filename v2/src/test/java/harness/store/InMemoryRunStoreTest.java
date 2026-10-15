package harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import harness.core.Span;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryRunStoreTest {

    private final InMemoryRunStore store = new InMemoryRunStore();

    @Test
    void replayFromSpanThreeIsExactlyTheTail() {
        String turnId = store.newTurn(store.newConversation("stubborn"), "anything");
        List<Span> rows = List.of(
                row(turnId, 1, Span.Kind.LLM), row(turnId, 2, Span.Kind.TOOL), row(turnId, 3, Span.Kind.LLM),
                row(turnId, 4, Span.Kind.TOOL), row(turnId, 5, Span.Kind.NOTE));
        for (Span row : rows) {
            store.append(turnId, row);
        }

        assertEquals(rows, store.load(turnId));
        assertEquals(rows.subList(2, 5), store.load(turnId, 3));
        assertEquals(List.of(3, 4, 5), store.load(turnId, 3).stream().map(Span::span).toList());
        assertEquals(List.of(), store.load(turnId, 6));
    }

    @Test
    void idsAreShortHexAndUnknownOnesAreRefused() {
        String conversation = store.newConversation("support");
        String turnId = store.newTurn(conversation, "q");

        assertTrue(conversation.matches("c-[0-9a-f]{4}"), conversation);
        assertTrue(turnId.matches("r-[0-9a-f]{4}"), turnId);
        assertTrue(store.exists(turnId));
        assertFalse(store.exists("r-nope"));
        assertEquals("support", store.conversation(conversation).agent());
        assertEquals(List.of(turnId), store.turns(conversation).stream().map(TurnRow::id).toList());
        assertThrows(IllegalArgumentException.class, () -> store.load("r-nope"));
        assertThrows(IllegalArgumentException.class, () -> store.conversation("c-nope"));
        assertThrows(IllegalArgumentException.class, () -> store.newTurn("c-nope", "q"));
        assertThrows(IllegalArgumentException.class, () -> store.append("r-nope", row("r-nope", 1, Span.Kind.NOTE)));
    }

    @Test
    void theTurnRowFollowsTheWorkers() {
        String turnId = store.newTurn(store.newConversation("support"), "q");
        assertEquals(TurnRow.Status.QUEUED, store.turn(turnId).status());
        assertEquals(0, store.turn(turnId).attempts());
        assertEquals("support", store.turn(turnId).agent());

        assertEquals(1, store.claim(turnId, "worker-a"));
        assertEquals(2, store.claim(turnId, "worker-b"));
        TurnRow turn = store.turn(turnId);
        assertEquals(TurnRow.Status.RUNNING, turn.status());
        assertEquals("worker-b", turn.claimedBy());
        assertEquals(2, turn.attempts());

        store.done(turnId, "the answer");
        assertEquals(TurnRow.Status.DONE, store.turn(turnId).status());
        assertEquals("the answer", store.turn(turnId).answer());
        assertEquals("worker-b", store.turn(turnId).claimedBy(), "done keeps who ran it");
    }

    private static Span row(String turnId, int span, Span.Kind kind) {
        return new Span(turnId, span, (span + 1) / 2, kind, kind + " " + span, "", Span.Status.OK, 0, Map.of());
    }
}
