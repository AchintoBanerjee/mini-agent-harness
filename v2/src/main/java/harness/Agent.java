package harness;

import java.util.List;

/** The bundle of tools this agent may use, by name. Each must exist in the ToolRegistry. */
public record Agent(String name, List<String> toolNames) {
}
