package harness.cli;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.core.Orchestrator;
import harness.core.RelaySummarizer;
import harness.core.Span;
import harness.core.Turn;
import harness.model.RuleBasedLLM;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command line, run from v2/: agents · tools · run --agent X "question" ["question" ...].
 * Args are parsed by hand. Agents and tools are read from agents/ and tools/.
 */
public class Main {

    static final Path AGENTS = Path.of("agents");
    static final Path TOOLS = Path.of("tools");

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        switch (args[0]) {
            case "agents" -> agents();
            case "tools" -> tools();
            case "run" -> run(rest);
            default -> usage();
        }
    }

    static void agents() {
        for (Agent agent : AgentLoader.loadAll(AGENTS)) {
            System.out.printf("%-10s  %-58s  tools: %s · maxPasses %d · toolTimeout %d ms%n",
                    agent.name(), agent.description(), String.join(", ", agent.toolNames()),
                    agent.maxPasses(), agent.toolTimeoutMs());
        }
    }

    static void tools() {
        for (Tool tool : ToolLoader.load(TOOLS).all()) {
            String id = tool.matching().idPattern() == null ? "no id" : "id " + tool.matching().idPattern();
            System.out.printf("%-16s  %-7s  %-56s  keywords %s · %s%n",
                    tool.name(), tool.type(), tool.description(), tool.matching().keywords(), id);
        }
    }

    static void run(List<String> args) {
        String agentName = null;
        List<String> questions = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--agent") && i + 1 < args.size()) {
                agentName = args.get(++i);
            } else if (arg.startsWith("--")) {
                fail("unknown option " + arg);
            } else {
                questions.add(arg);
            }
        }
        if (agentName == null || questions.isEmpty()) {
            fail("usage: run --agent <name> \"question\" [\"question\" ...]");
        }

        Agent agent = null;
        for (Agent candidate : AgentLoader.loadAll(AGENTS)) {
            if (candidate.name().equals(agentName)) {
                agent = candidate;
            }
        }
        if (agent == null) {
            fail("no agent named " + agentName + " in " + AGENTS + "/");
        }

        ToolRegistry registry = ToolLoader.load(TOOLS);
        Orchestrator orchestrator = new Orchestrator(
                agent, registry, new RuleBasedLLM(), new ToolCaller(registry), new RelaySummarizer());

        long started = System.nanoTime();
        int passes = 0;
        int toolCalls = 0;
        for (String question : questions) {
            String answer = orchestrator.run(question);
            Turn turn = orchestrator.conversation().turns().getLast();
            for (Span span : turn.spans()) {
                System.out.println(format(span));
                if (span.kind() == Span.Kind.LLM) {
                    passes++;
                }
                if (span.kind() == Span.Kind.TOOL) {
                    toolCalls++;
                }
            }
            System.out.println("answer: " + answer);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("run %s · %d passes · %d tool calls · %d ms%n",
                orchestrator.conversation().id(), passes, toolCalls, ms);
    }

    /** One row, one line: step, kind, request -> response, then status and time. */
    static String format(Span span) {
        String tail = span.response().isEmpty() ? "" : " -> " + span.response();
        return String.format("%3d  %-4s  %s%s  [%s %d ms]",
                span.step(), span.kind(), span.request(), tail, span.status(), span.ms());
    }

    static void usage() {
        fail("usage: agents | tools | run --agent <name> \"question\" [\"question\" ...]");
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
