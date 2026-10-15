package harness.eval;

import harness.core.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One golden case, scored from the rows its run left behind. The harness scores say whether
 * the loop behaved: tool_choice (the TOOL rows name exactly the expected tools, DEDUPED rows
 * aside), stopped_by_done (no CUT_SHORT row) and errors_surfaced (every tool that ended
 * ERROR, TIMEOUT or DENIED is named with that status in the answer). The agent score says
 * whether the answer was right: answer (contains the expected text, case and spacing aside).
 * passes and ms are measurements, not scores.
 */
public record CaseResult(GoldenCase golden, Set<String> tools, boolean toolChoice, boolean stoppedByDone,
                         boolean errorsSurfaced, boolean answerOk, int passes, long ms, String answer) {

    public static CaseResult score(GoldenCase golden, List<Span> rows, String answer, long ms) {
        Set<String> tools = new TreeSet<>();
        boolean stoppedByDone = true;
        boolean errorsSurfaced = true;
        int passes = 0;
        for (Span row : rows) {
            if (row.kind() == Span.Kind.LLM) {
                passes++;
            }
            if (row.status() == Span.Status.CUT_SHORT) {
                stoppedByDone = false;
            }
            if (row.kind() != Span.Kind.TOOL || row.status() == Span.Status.DEDUPED) {
                continue;
            }
            tools.add(row.attrs().getOrDefault("tool", "?"));
            if (row.status() != Span.Status.OK && !answer.contains(row.status().name())) {
                errorsSurfaced = false;
            }
        }
        boolean toolChoice = tools.equals(new TreeSet<>(golden.expectTools()));
        boolean answerOk = normalize(answer).contains(normalize(golden.answerContains()));
        return new CaseResult(golden, tools, toolChoice, stoppedByDone, errorsSurfaced, answerOk, passes, ms, answer);
    }

    /** Every score holds. */
    public boolean passed() {
        return toolChoice && stoppedByDone && errorsSurfaced && answerOk;
    }

    /** The scores that failed, each saying what was expected and what happened. */
    public List<String> failures() {
        List<String> failures = new ArrayList<>();
        if (!toolChoice) {
            failures.add("tool_choice: expected " + golden.expectTools() + " got " + tools);
        }
        if (!stoppedByDone) {
            failures.add("stopped_by_done: cut short at maxPasses=" + golden.maxPasses());
        }
        if (!errorsSurfaced) {
            failures.add("errors_surfaced: a tool failed and the answer does not say so");
        }
        if (!answerOk) {
            failures.add("answer: expected \"" + golden.answerContains() + "\" got \"" + answer.replace("\n", " | ") + "\"");
        }
        return failures;
    }

    /** The table row: number, PASS or FAIL, agent, question, the tools that ran, passes, ms, and what failed. */
    public String line(int n) {
        return String.format("%2d  %s  %-9s  %-54s  ran %-27s  %d pass%s  %5d ms%s",
                n, passed() ? "PASS" : "FAIL", golden.agent(), golden.question(),
                tools.isEmpty() ? "no tool" : String.join(",", tools), passes, passes == 1 ? " " : "es", ms,
                passed() ? "" : "  " + String.join("; ", failures()));
    }

    /** Lower case, one space between words: "Out of  stock" matches "OUT OF STOCK". */
    static String normalize(String text) {
        return text.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
