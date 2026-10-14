package harness.agent;

import java.util.List;

/**
 * The bundle of tools this agent may use, by name (each must exist in the ToolRegistry),
 * with its loop cap and the time one tool call may take. An agent is data: agents/*.json.
 */
public record Agent(String name, String description, List<String> toolNames, int maxPasses, long toolTimeoutMs) {
}
