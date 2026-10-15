package harness.store;

import harness.core.Span;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversation rows, turn rows and spans kept in maps, for tests and the eval. Gone when the process ends. */
public class InMemoryRunStore implements RunStore {

    private final Map<String, ConversationRow> conversations = new LinkedHashMap<>();
    private final Map<String, TurnRow> turns = new LinkedHashMap<>();
    private final Map<String, List<Span>> spans = new LinkedHashMap<>();

    @Override
    public String newConversation(String agent) {
        String id = RunStore.newId("c");
        while (conversations.containsKey(id)) {
            id = RunStore.newId("c");
        }
        conversations.put(id, new ConversationRow(id, agent, System.currentTimeMillis()));
        return id;
    }

    @Override
    public String newTurn(String conversationId, String question) {
        ConversationRow conversation = conversation(conversationId);
        String turnId = RunStore.newId("r");
        while (spans.containsKey(turnId)) {
            turnId = RunStore.newId("r");
        }
        long now = System.currentTimeMillis();
        turns.put(turnId, new TurnRow(turnId, conversationId, conversation.agent(), question, null,
                TurnRow.Status.QUEUED, null, 0, now, now));
        spans.put(turnId, new ArrayList<>());
        return turnId;
    }

    @Override
    public void append(String turnId, Span span) {
        rows(turnId).add(span);
    }

    @Override
    public List<Span> load(String turnId) {
        return List.copyOf(rows(turnId));
    }

    @Override
    public boolean exists(String turnId) {
        return spans.containsKey(turnId);
    }

    @Override
    public TurnRow turn(String turnId) {
        rows(turnId);
        return turns.get(turnId);
    }

    @Override
    public ConversationRow conversation(String conversationId) {
        ConversationRow conversation = conversations.get(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("no conversation " + conversationId);
        }
        return conversation;
    }

    @Override
    public List<TurnRow> turns(String conversationId) {
        List<TurnRow> matching = new ArrayList<>();
        for (TurnRow turn : turns.values()) {
            if (turn.conversationId().equals(conversationId)) {
                matching.add(turn);
            }
        }
        return matching;
    }

    @Override
    public void setStatus(String turnId, TurnRow.Status status) {
        TurnRow turn = turn(turnId);
        turns.put(turnId, turn.with(status, turn.answer(), System.currentTimeMillis()));
    }

    @Override
    public void done(String turnId, String answer) {
        turns.put(turnId, turn(turnId).with(TurnRow.Status.DONE, answer, System.currentTimeMillis()));
    }

    @Override
    public int claim(String turnId, String claimedBy) {
        TurnRow claimed = turn(turnId).claimedBy(claimedBy, System.currentTimeMillis());
        turns.put(turnId, claimed);
        return claimed.attempts();
    }

    private List<Span> rows(String turnId) {
        List<Span> rows = spans.get(turnId);
        if (rows == null) {
            throw new IllegalArgumentException("no turn " + turnId);
        }
        return rows;
    }
}
