package harness;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Every tool that exists, by name. In production this would be a catalog service. */
public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Tool require(String name) {
        return find(name).orElseThrow(() -> new IllegalArgumentException("Tool not in registry: " + name));
    }
}
