package harness.cli;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.core.ConsoleListener;
import harness.core.Orchestrator;
import harness.core.RelaySummarizer;
import harness.core.RunListener;
import harness.core.Span;
import harness.core.Turn;
import harness.eval.CaseResult;
import harness.eval.EvalRunner;
import harness.eval.GoldenCase;
import harness.model.RuleBasedLLM;
import harness.model.ToolCall;
import harness.store.FileRunStore;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import harness.tools.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command line, run from v2/: agents · tools · run --agent X "question" ["question" ...]
 * [--slow] [--crash-after N] [--crash-before-persist] · resume runId · replay runId [fromStep]
 * · eval [evals/golden.json]. Args are parsed by hand. Agents and tools are read from
 * agents/ and tools/; every run is logged to runs/. The two crash flags simulate a process
 * dying mid-run so that resume has something to pick up; --slow gives every lookup 300 ms
 * of simulated latency so that tools running together can be seen to overlap. Tool calls
 * are gated by the agent: only its listed tools run, each within its toolTimeoutMs. eval
 * scores the golden set in memory and prints one row per case and the totals.
 */
public class Main {

    static final Path AGENTS = Path.of("agents");
    static final Path TOOLS = Path.of("tools");
    static final Path RUNS = Path.of("runs");
    static final Path GOLDEN = Path.of("evals", "golden.json");
    static final int CRASH_EXIT = 3;

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        switch (args[0]) {
            case "agents" -> agents();
            case "tools" -> tools();
            case "run" -> run(rest);
            case "resume" -> resume(rest);
            case "replay" -> replay(rest);
            case "eval" -> eval(rest);
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

    /** Runs the questions as one conversation, printing each row as it lands, then the answer. */
    static void run(List<String> args) {
        String agentName = null;
        List<String> questions = new ArrayList<>();
        int crashAfter = 0;
        boolean crashBeforePersist = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--agent") && i + 1 < args.size()) {
                agentName = args.get(++i);
            } else if (arg.equals("--crash-after") && i + 1 < args.size()) {
                crashAfter = positive(args.get(++i), "--crash-after");
            } else if (arg.equals("--crash-before-persist")) {
                crashBeforePersist = true;
            } else if (arg.equals("--slow")) {
                ToolLoader.SLOW_LOOKUP_MS = 300;
                System.out.println("slow: every lookup sleeps 300 ms before answering (latency simulated)");
            } else if (arg.startsWith("--")) {
                fail("unknown option " + arg);
            } else {
                questions.add(arg);
            }
        }
        if (agentName == null || questions.isEmpty()) {
            fail("usage: run --agent <name> \"question\" [\"question\" ...] [--slow] [--crash-after N] [--crash-before-persist]");
        }

        Agent agent = findAgent(agentName);
        ToolRegistry registry = ToolLoader.load(TOOLS);
        FileRunStore store = new FileRunStore(RUNS);
        RunListener listener = crashAfter > 0 ? crashAfter(crashAfter) : new ConsoleListener();
        ToolCaller toolCaller = crashBeforePersist ? crashBeforePersist(registry, agent) : toolCaller(registry, agent);
        Orchestrator orchestrator = new Orchestrator(agent, registry, new RuleBasedLLM(),
                toolCaller, new RelaySummarizer(), store, listener);

        long started = System.nanoTime();
        for (String question : questions) {
            System.out.println("answer: " + orchestrator.run(question));
        }
        footer(orchestrator, store, started);
    }

    /** Picks a logged run up where it stopped: the agent comes from the run's start line. */
    static void resume(List<String> args) {
        if (args.size() != 1) {
            fail("usage: resume <runId>");
        }
        String runId = args.get(0);
        FileRunStore store = new FileRunStore(RUNS);
        if (!store.exists(runId)) {
            fail("no run " + runId + " in " + RUNS + "/");
        }
        Agent agent = findAgent(store.agent(runId));
        ToolRegistry registry = ToolLoader.load(TOOLS);
        Orchestrator orchestrator = new Orchestrator(agent, registry, new RuleBasedLLM(),
                toolCaller(registry, agent), new RelaySummarizer(), store, new ConsoleListener());

        long started = System.nanoTime();
        System.out.println("answer: " + orchestrator.resume(runId));
        footer(orchestrator, store, started);
    }

    /** Prints the stored rows of a run from the given step on (default: from the first). */
    static void replay(List<String> args) {
        if (args.isEmpty() || args.size() > 2) {
            fail("usage: replay <runId> [fromStep]");
        }
        String runId = args.get(0);
        int fromStep = args.size() == 2 ? positive(args.get(1), "fromStep") : 1;
        FileRunStore store = new FileRunStore(RUNS);
        if (!store.exists(runId)) {
            fail("no run " + runId + " in " + RUNS + "/");
        }
        for (Span span : store.load(runId, fromStep)) {
            System.out.println(ConsoleListener.format(span));
        }
    }

    /** Scores the golden set: one row per case, then the footer with the harness scores and the agent score. */
    static void eval(List<String> args) {
        if (args.size() > 1) {
            fail("usage: eval [evals/golden.json]");
        }
        Path file = args.isEmpty() ? GOLDEN : Path.of(args.get(0));
        if (!Files.exists(file)) {
            fail("no golden set at " + file);
        }
        EvalRunner runner = new EvalRunner(AgentLoader.loadAll(AGENTS), ToolLoader.load(TOOLS));
        List<CaseResult> results = runner.run(GoldenCase.loadAll(file));
        for (int i = 0; i < results.size(); i++) {
            System.out.println(results.get(i).line(i + 1));
        }
        System.out.println(EvalRunner.footer(results));
    }

    /** Simulated crash: row N is in the file and on screen, then the process is gone. */
    static RunListener crashAfter(int n) {
        ConsoleListener console = new ConsoleListener();
        return span -> {
            console.on(span);
            if (span.step() == n) {
                System.out.println("crash: exit " + CRASH_EXIT + " right after row " + n + " of run " + span.run()
                        + " was persisted (simulated) · handler calls: " + ToolLoader.handlerCalls());
                System.exit(CRASH_EXIT);
            }
        };
    }

    /** The agent's tool list and per-call timeout gate every call. */
    static ToolCaller toolCaller(ToolRegistry registry, Agent agent) {
        return new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs()));
    }

    /** Simulated crash in the at-least-once window: the tool did its work, its TOOL row never lands. */
    static ToolCaller crashBeforePersist(ToolRegistry registry, Agent agent) {
        return new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs())) {
            @Override
            public ToolResult call(ToolCall call) {
                ToolResult result = super.call(call);
                System.out.println("crash: exit " + CRASH_EXIT + " after " + call.toolName()
                        + " ran, before its TOOL row was written (simulated) · handler calls: " + ToolLoader.handlerCalls());
                System.exit(CRASH_EXIT);
                return result;
            }
        };
    }

    static void footer(Orchestrator orchestrator, FileRunStore store, long started) {
        int passes = 0;
        int toolCalls = 0;
        for (Turn turn : orchestrator.conversation().turns()) {
            for (Span span : turn.spans()) {
                if (span.kind() == Span.Kind.LLM) {
                    passes++;
                }
                if (span.kind() == Span.Kind.TOOL) {
                    toolCalls++;
                }
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        String runId = orchestrator.conversation().id();
        System.out.printf("run %s · %d passes · %d tool calls · %d ms · %s · handler calls: %s%n",
                runId, passes, toolCalls, ms, store.file(runId), ToolLoader.handlerCalls());
    }

    static Agent findAgent(String name) {
        for (Agent candidate : AgentLoader.loadAll(AGENTS)) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        fail("no agent named " + name + " in " + AGENTS + "/");
        return null;
    }

    static int positive(String value, String what) {
        try {
            int n = Integer.parseInt(value);
            if (n >= 1) {
                return n;
            }
        } catch (NumberFormatException e) {
            // falls through to the message below
        }
        fail(what + " must be a whole number from 1 up, got " + value);
        return 0;
    }

    static void usage() {
        fail("usage: agents | tools | run --agent <name> \"question\" [\"question\" ...] [--slow] [--crash-after N] [--crash-before-persist]"
                + " | resume <runId> | replay <runId> [fromStep] | eval [evals/golden.json]");
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
