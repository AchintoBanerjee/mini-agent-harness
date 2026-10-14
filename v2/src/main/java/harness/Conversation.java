package harness;

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
}
