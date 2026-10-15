package harness.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads agents/*.json. Adding an agent means adding a file. */
public class AgentLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every *.json in the folder, in file name order. */
    public static List<Agent> loadAll(Path dir) {
        List<Agent> agents = new ArrayList<>();
        for (Path file : jsonFiles(dir)) {
            agents.add(load(file));
        }
        return agents;
    }

    public static Agent load(Path file) {
        JsonNode node;
        try {
            node = JSON.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return parse(node, file.toString());
    }

    /** The agent in a JSON document, from a file or a PUT body; source names it in the error. */
    public static Agent parse(JsonNode node, String source) {
        List<String> toolNames = new ArrayList<>();
        for (JsonNode name : required(node, "tools", source)) {
            toolNames.add(name.asText());
        }
        return new Agent(
                required(node, "name", source).asText(),
                required(node, "description", source).asText(),
                List.copyOf(toolNames),
                required(node, "maxPasses", source).asInt(),
                required(node, "toolTimeoutMs", source).asLong());
    }

    private static JsonNode required(JsonNode node, String field, String source) {
        if (!node.has(field)) {
            throw new IllegalArgumentException(source + ": missing \"" + field + "\"");
        }
        return node.get(field);
    }

    private static List<Path> jsonFiles(Path dir) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + dir, e);
        }
        Collections.sort(files);
        return files;
    }
}
