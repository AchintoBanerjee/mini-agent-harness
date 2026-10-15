package harness.core;

import java.util.ArrayList;
import java.util.List;

/** One question and its final answer, with every step taken in between. */
public class Turn {

    private final String question;
    private final Conversation conversation;
    private final List<Span> spans = new ArrayList<>();
    private String answer;

    /** A turn on its own, for tests and hand-built contexts: no conversation around it. */
    public Turn(String question) {
        this(question, null);
    }

    public Turn(String question, Conversation conversation) {
        this.question = question;
        this.conversation = conversation;
    }

    public String question() {
        return question;
    }

    /** The conversation this turn belongs to; null for a turn built on its own. */
    public Conversation conversation() {
        return conversation;
    }

    public List<Span> spans() {
        return spans;
    }

    public String answer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }
}
