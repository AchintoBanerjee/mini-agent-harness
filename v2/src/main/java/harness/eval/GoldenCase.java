package harness.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One case of the golden set, evals/golden.json: a question for an agent, the tools that
 * should run (as a set; none means no tool should run), a piece of text the answer must
 * contain, and a pass budget. maxPasses caps the loop for this case, so a case that needs
 * more passes is CUT_SHORT and fails stopped_by_done; 0 means the agent's own cap. A case
 * without answerContains checks nothing about the answer.
 */
public record GoldenCase(String question, String agent, List<String> expectTools, String answerContains, int maxPasses) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public GoldenCase {
        expectTools = expectTools == null ? List.of() : List.copyOf(expectTools);
        answerContains = answerContains == null ? "" : answerContains;
    }

    /** The whole file, in file order. */
    public static List<GoldenCase> loadAll(Path file) {
        try {
            return JSON.readValue(file.toFile(), new TypeReference<List<GoldenCase>>() { });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
