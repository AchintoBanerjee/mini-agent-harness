package harness.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import harness.core.Span;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One file per run, runs/<id>.jsonl: a start line (run, agent, question), then one JSON
 * line per row, appended as the loop goes. Append-only: a crash leaves a readable prefix.
 */
public class FileRunStore implements RunStore {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;

    public FileRunStore(Path dir) {
        this.dir = dir;
    }

    @Override
    public String newRun(String agent, String question) {
        String runId = RunStore.newId();
        while (exists(runId)) {
            runId = RunStore.newId();
        }
        JsonNode start = JSON.createObjectNode().put("run", runId).put("agent", agent).put("question", question);
        write(runId, start, StandardOpenOption.CREATE_NEW);
        return runId;
    }

    @Override
    public void append(String runId, Span span) {
        requireRun(runId);
        write(runId, span, StandardOpenOption.APPEND);
    }

    @Override
    public List<Span> load(String runId) {
        requireRun(runId);
        List<Span> rows = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file(runId))) {
                JsonNode node = JSON.readTree(line);
                if (node.has("step")) {
                    rows.add(JSON.treeToValue(node, Span.class));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file(runId), e);
        }
        return rows;
    }

    @Override
    public boolean exists(String runId) {
        return Files.exists(file(runId));
    }

    public Path file(String runId) {
        return dir.resolve(runId + ".jsonl");
    }

    /** The agent named on the start line: resume needs it to rebuild the same orchestrator. */
    public String agent(String runId) {
        requireRun(runId);
        try {
            return JSON.readTree(Files.readAllLines(file(runId)).get(0)).path("agent").asText();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file(runId), e);
        }
    }

    private void write(String runId, Object line, StandardOpenOption how) {
        try {
            Files.createDirectories(dir);
            Files.writeString(file(runId), JSON.writeValueAsString(line) + System.lineSeparator(), how);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file(runId), e);
        }
    }

    private void requireRun(String runId) {
        if (!exists(runId)) {
            throw new IllegalArgumentException("no run " + runId + " in " + dir + "/");
        }
    }
}
