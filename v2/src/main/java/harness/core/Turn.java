package harness.core;

import java.util.ArrayList;
import java.util.List;

/** One question and its final answer, with every step taken in between. */
public class Turn {

    private final String question;
    private final List<Span> spans = new ArrayList<>();
    private String answer;

    public Turn(String question) {
        this.question = question;
    }

    public String question() {
        return question;
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
