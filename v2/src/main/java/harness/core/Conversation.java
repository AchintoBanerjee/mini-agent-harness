package harness.core;

import harness.agent.Agent;
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

    /** Opens the next turn. It knows this conversation, so its rows can point at earlier ones. */
    public Turn turn(String question) {
        Turn turn = new Turn(question, this);
        turns.add(turn);
        return turn;
    }

    /**
     * The turns of a logged run, rebuilt from its rows: the first row of each turn is the
     * LLM row that carries the question. Resume continues the last turn; GET /runs reads
     * its answer.
     */
    public static Conversation rebuild(String id, Agent agent, List<Span> rows) {
        Conversation conversation = new Conversation(id, agent);
        Turn turn = null;
        for (Span row : rows) {
            if (row.turn() != conversation.turns.size()) {
                String question = row.attrs().get(Orchestrator.QUESTION);
                if (question == null) {
                    throw new IllegalStateException("row " + row.step() + " of " + id + " carries no question: this log predates resume");
                }
                turn = conversation.turn(question);
            }
            turn.spans().add(row);
        }
        return conversation;
    }
}
