package harness.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The answer relays the whole turn: every call row of this loop, in the order it ran, each
 * once, joined with newlines. A row that ended OK gives its output unchanged. An agent run
 * as a tool counts the same: its AGENT row's output, which is the child's answer. The
 * turn's first row is the loop's own, so the rows at its depth are this loop's; a child's
 * rows sit deeper and are not relayed again. A DEDUPED row repeats an earlier step, so its
 * line is that step's output. A tool that did not end OK is named with its status so the
 * failure reaches the user. No call row means no tool fit.
 */
public class RelaySummarizer {

    public static final String NO_TOOL = "No tool for this question.";

    public String summarize(Turn turn) {
        List<String> outputs = new ArrayList<>();
        String depth = turn.spans().isEmpty() ? "0" : depthOf(turn.spans().getFirst());
        for (Span span : turn.spans()) {
            if (!span.kind().isCall() || !depthOf(span).equals(depth)) {
                continue;
            }
            if (span.status() == Span.Status.OK) {
                outputs.add(span.response());
            } else if (span.status() == Span.Status.DEDUPED) {
                outputs.add(original(turn, span));
            } else {
                outputs.add("Tool " + span.attrs().get("tool") + " " + span.status() + ": " + span.response());
            }
        }
        return outputs.isEmpty() ? NO_TOOL : String.join("\n", outputs);
    }

    /** How deep the loop that wrote the row runs: 0 for the turn's own, deeper for a child's. */
    private static String depthOf(Span span) {
        return span.attrs().getOrDefault("depth", "0");
    }

    /** Follows a DEDUPED row's ref to the step it repeats, anywhere in the conversation: that step's output. */
    private static String original(Turn turn, Span deduped) {
        int ref = Integer.parseInt(deduped.attrs().getOrDefault("ref", "-1"));
        List<Turn> turns = turn.conversation() == null ? List.of(turn) : turn.conversation().turns();
        for (Turn earlier : turns) {
            for (Span row : earlier.spans()) {
                if (row.span() == ref) {
                    return row.response();
                }
            }
        }
        return deduped.response();
    }
}
