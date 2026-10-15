package harness.core;

import harness.agent.Agent;
import harness.store.RunStore;
import harness.store.TurnRow;
import java.util.ArrayList;
import java.util.List;

/** One user talking to one agent. Holds the turns in order. */
public class Conversation {

    private final String id;
    private final Agent agent;
    private final List<Turn> turns = new ArrayList<>();

    public Conversation(String id, Agent agent) {
        this.id = id;
        this.agent = agent;
    }

    public String id() {
        return id;
    }

    public Agent agent() {
        return agent;
    }

    public List<Turn> turns() {
        return turns;
    }

    /** Opens the next turn under its id (the job id). It knows this conversation, so its rows can point at earlier ones. */
    public Turn turn(String id, String question) {
        Turn turn = new Turn(id, question, this);
        turns.add(turn);
        return turn;
    }

    /**
     * The conversation as the store holds it, up to and including the given turn: each
     * earlier turn with its question, its spans and its answer, so the model sees the
     * history and a repeated call can be found; then the turn itself, with whatever rows a
     * worker before this one left.
     */
    public static Conversation load(RunStore store, String conversationId, Agent agent, String upToTurnId) {
        Conversation conversation = new Conversation(conversationId, agent);
        for (TurnRow row : store.turns(conversationId)) {
            Turn turn = conversation.turn(row.id(), row.question());
            turn.spans().addAll(store.load(row.id()));
            turn.setAnswer(row.answer());
            if (row.id().equals(upToTurnId)) {
                return conversation;
            }
        }
        throw new IllegalArgumentException("turn " + upToTurnId + " is not in conversation " + conversationId);
    }
}
