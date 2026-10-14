package harness.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Reads tools/*.json. A tool is data: the "type" field picks the adapter that builds its
 * handler (lookup: a static map; sleep: an interruptible sleep; fail: throws). Adding a
 * tool means adding a file.
 */
public class ToolLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * How many times each lookup handler ran in this process, by tool name. The run and
     * resume footers print it, so a call that ran twice around a crash is visible.
     */
    public static final Map<String, Integer> HANDLER_CALLS = new ConcurrentHashMap<>();

    /** "order_status=2, stock_status=1" in name order, or "none". */
    public static String handlerCalls() {
        if (HANDLER_CALLS.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(HANDLER_CALLS).entrySet()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    /** Every *.json in the folder, as one registry. */
    public static ToolRegistry load(Path dir) {
        ToolRegistry registry = new ToolRegistry();
        for (Path file : jsonFiles(dir)) {
            registry.register(loadOne(file));
        }
        return registry;
    }

    public static Tool loadOne(Path file) {
        JsonNode node = read(file);
        String name = required(node, "name", file).asText();
        String description = required(node, "description", file).asText();
        String type = required(node, "type", file).asText();
        String inputField = node.has("inputField") ? node.get("inputField").asText() : null;
        MatchingCondition matching = matching(required(node, "match", file));

        Function<Map<String, String>, String> handler;
        if (type.equals("lookup")) {
            handler = lookup(node, name, inputField, file);
        } else if (type.equals("sleep")) {
            handler = sleep(required(node, "ms", file).asLong());
        } else if (type.equals("fail")) {
            handler = fail(required(node, "message", file).asText());
        } else {
            throw new IllegalArgumentException(file + ": unknown tool type \"" + type + "\"");
        }
        return new Tool(name, description, type, inputField, matching, handler);
    }

    private static MatchingCondition matching(JsonNode node) {
        List<String> keywords = new ArrayList<>();
        for (JsonNode keyword : node.path("keywords")) {
            keywords.add(keyword.asText());
        }
        Pattern idPattern = node.has("idPattern")
                ? Pattern.compile(node.get("idPattern").asText(), Pattern.CASE_INSENSITIVE)
                : null;
        return new MatchingCondition(keywords, idPattern);
    }

    private static Function<Map<String, String>, String> lookup(JsonNode node, String name, String inputField, Path file) {
        Map<String, String> data = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : required(node, "data", file).properties()) {
            data.put(entry.getKey(), entry.getValue().asText());
        }
        String found = required(node, "found", file).asText();
        String notFound = required(node, "notFound", file).asText();
        return input -> {
            HANDLER_CALLS.merge(name, 1, Integer::sum);
            String id = input.getOrDefault(inputField, "");
            String value = data.get(id);
            if (value == null) {
                return notFound.replace("{id}", id);
            }
            return found.replace("{id}", id).replace("{value}", value);
        };
    }

    private static Function<Map<String, String>, String> sleep(long ms) {
        return input -> {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while sleeping " + ms + " ms");
            }
            return "slept " + ms + " ms";
        };
    }

    private static Function<Map<String, String>, String> fail(String message) {
        return input -> {
            throw new IllegalStateException(message);
        };
    }

    private static JsonNode read(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static JsonNode required(JsonNode node, String field, Path file) {
        if (!node.has(field)) {
            throw new IllegalArgumentException(file + ": missing \"" + field + "\"");
        }
        return node.get(field);
    }

    static List<Path> jsonFiles(Path dir) {
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
