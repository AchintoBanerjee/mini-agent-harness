package harness.cli;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.core.ConsoleListener;
import harness.core.CrashAfter;
import harness.core.Orchestrator;
import harness.core.RelaySummarizer;
import harness.core.RunListener;
import harness.core.Span;
import harness.core.Turn;
import harness.eval.CaseResult;
import harness.eval.EvalRunner;
import harness.eval.GoldenCase;
import harness.http.HttpApi;
import harness.model.RuleBasedLLM;
import harness.model.ToolCall;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.tools.Tool;
import harness.tools.ToolCaller;
import harness.tools.ToolLoader;
import harness.tools.ToolRegistry;
import harness.tools.ToolResult;
import harness.worker.Worker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Command line, run from v2/: agents · tools · run --agent X "question" ["question" ...]
 * [--slow] [--crash-after N] [--crash-before-persist] · resume runId · replay runId [fromStep]
 * · eval [evals/golden.json] · serve [--port 8080] · submit --agent X "question" · worker
 * [--once] [--visibility-ms 2000] [--crash-after N] [--max-receives 3]. Args are parsed by
 * hand. Agents and tools are read from agents/ and tools/; every run is written to
 * harness.db (SQLite), the same file the HTTP server and the workers use. run is the loop
 * in this process; submit puts a job on the queue and worker takes jobs off it. The crash
 * flags simulate a process dying mid-run so that resume (or the next worker) has something
 * to pick up; --slow gives every lookup 300 ms of simulated latency so that tools running
 * together can be seen to overlap. Tool calls are gated by the agent: only its listed
 * tools run, each within its toolTimeoutMs. eval scores the golden set in memory and
 * prints one row per case and the totals.
 */
public class Main {

    static final Path AGENTS = Path.of("agents");
    static final Path TOOLS = Path.of("tools");
    static final Path DB = SqliteRunStore.DEFAULT_FILE;
    static final Path GOLDEN = Path.of("evals", "golden.json");
    static final int CRASH_EXIT = CrashAfter.EXIT_CODE;
    static final int DEFAULT_PORT = 8080;

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
            case "serve" -> serve(rest);
            case "submit" -> submit(rest);
            case "worker" -> worker(rest);
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
        try (SqliteRunStore store = new SqliteRunStore(DB)) {
            RunListener listener = crashAfter > 0
                    ? new CrashAfter(crashAfter, new ConsoleListener(), () -> System.exit(CRASH_EXIT))
                    : new ConsoleListener();
            Orchestrator[] holder = new Orchestrator[1];
            ToolCaller toolCaller = crashBeforePersist
                    ? crashBeforePersist(registry, agent, () -> holder[0].conversation().id())
                    : toolCaller(registry, agent);
            holder[0] = new Orchestrator(agent, registry, new RuleBasedLLM(), toolCaller, new RelaySummarizer(), store, listener);

            long started = System.nanoTime();
            for (String question : questions) {
                System.out.println("answer: " + holder[0].run(question));
            }
            footer(holder[0], store, started);
        }
    }

    /** Picks a logged run up where it stopped: the agent comes from the job row. */
    static void resume(List<String> args) {
        if (args.size() != 1) {
            fail("usage: resume <runId>");
        }
        String runId = args.get(0);
        try (SqliteRunStore store = new SqliteRunStore(DB)) {
            if (!store.exists(runId)) {
                fail("no run " + runId + " in " + DB);
            }
            Agent agent = findAgent(store.job(runId).agent());
            ToolRegistry registry = ToolLoader.load(TOOLS);
            Orchestrator orchestrator = new Orchestrator(agent, registry, new RuleBasedLLM(),
                    toolCaller(registry, agent), new RelaySummarizer(), store, new ConsoleListener());

            long started = System.nanoTime();
            System.out.println("answer: " + orchestrator.resume(runId));
            footer(orchestrator, store, started);
        }
    }

    /** Prints the stored rows of a run from the given step on (default: from the first). */
    static void replay(List<String> args) {
        if (args.isEmpty() || args.size() > 2) {
            fail("usage: replay <runId> [fromStep]");
        }
        String runId = args.get(0);
        int fromStep = args.size() == 2 ? positive(args.get(1), "fromStep") : 1;
        try (SqliteRunStore store = new SqliteRunStore(DB)) {
            if (!store.exists(runId)) {
                fail("no run " + runId + " in " + DB);
            }
            for (Span span : store.load(runId, fromStep)) {
                System.out.println(ConsoleListener.format(span));
            }
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

    /** The HTTP API on localhost: agents/, tools/ and harness.db behind it; a worker runs the jobs. Runs until killed. */
    static void serve(List<String> args) {
        int port = DEFAULT_PORT;
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--port") && i + 1 < args.size()) {
                port = port(args.get(++i));
            } else {
                fail("usage: serve [--port 8080]   (--port 0 picks a free port)");
            }
        }
        SqliteRunStore store = new SqliteRunStore(DB);
        HttpApi api = new HttpApi(AGENTS, TOOLS, store, new RunQueue(store));
        try {
            api.start(port);
        } catch (IOException e) {
            fail("cannot listen on port " + port + ": " + e.getMessage());
        }
        System.out.println("listening on http://localhost:" + api.port() + " · agents/ tools/ " + DB
                + " · POST /runs queues a job for a worker · ctrl-c stops it");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Puts one question on the queue as a job, QUEUED, and prints its id; a worker runs it. */
    static void submit(List<String> args) {
        String agentName = null;
        String question = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--agent") && i + 1 < args.size()) {
                agentName = args.get(++i);
            } else if (arg.startsWith("--") || question != null) {
                fail("usage: submit --agent <name> \"question\"");
            } else {
                question = arg;
            }
        }
        if (agentName == null || question == null) {
            fail("usage: submit --agent <name> \"question\"");
        }
        Agent agent = findAgent(agentName);
        try (SqliteRunStore store = new SqliteRunStore(DB)) {
            String jobId = new RunQueue(store).submit(agent.name(), question);
            System.out.println("submitted " + jobId + " · " + store.job(jobId).status() + " in " + DB
                    + " · agent " + agent.name() + " · \"" + question + "\"");
        }
    }

    /**
     * Takes jobs off the queue and runs them, resuming any a dead worker left behind.
     * --once handles one message and exits (1 when there was none); otherwise it polls
     * every 500 ms until killed. --crash-after N is the simulated death: exit 3 right
     * after row N is persisted, without acking.
     */
    static void worker(List<String> args) {
        boolean once = false;
        long visibilityMs = Worker.DEFAULT_VISIBILITY_MS;
        int crashAfter = 0;
        int maxReceives = RunQueue.DEFAULT_MAX_RECEIVES;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--once")) {
                once = true;
            } else if (arg.equals("--visibility-ms") && i + 1 < args.size()) {
                visibilityMs = positive(args.get(++i), "--visibility-ms");
            } else if (arg.equals("--crash-after") && i + 1 < args.size()) {
                crashAfter = positive(args.get(++i), "--crash-after");
            } else if (arg.equals("--max-receives") && i + 1 < args.size()) {
                maxReceives = positive(args.get(++i), "--max-receives");
            } else {
                fail("usage: worker [--once] [--visibility-ms 2000] [--crash-after N] [--max-receives 3]");
            }
        }
        String pid = String.valueOf(ProcessHandle.current().pid());
        try (SqliteRunStore store = new SqliteRunStore(DB)) {
            Worker worker = new Worker(AGENTS, TOOLS, store, new RunQueue(store, maxReceives), pid, visibilityMs);
            worker.crashAfter(crashAfter);
            System.out.println("worker pid " + pid + " · lease " + visibilityMs + " ms · max receives " + maxReceives
                    + (once ? " · once" : " · polling every " + Worker.POLL_MS + " ms, ctrl-c stops it") + " · " + DB);
            if (!once) {
                worker.loop();
            } else if (!worker.once()) {
                System.out.println("nothing to receive");
                System.exit(1);
            }
        }
    }

    /** The agent's tool list and per-call timeout gate every call. */
    static ToolCaller toolCaller(ToolRegistry registry, Agent agent) {
        return new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs()));
    }

    /** Simulated crash in the at-least-once window: the tool did its work, its TOOL row never lands. */
    static ToolCaller crashBeforePersist(ToolRegistry registry, Agent agent, Supplier<String> runId) {
        return new ToolCaller(registry, agent.toolNames(), Duration.ofMillis(agent.toolTimeoutMs())) {
            @Override
            public ToolResult call(ToolCall call) {
                ToolResult result = super.call(call);
                System.out.println("crash: exit " + CRASH_EXIT + " after " + call.toolName() + " ran, before its TOOL row of run "
                        + runId.get() + " was written (simulated) · handler calls: " + ToolLoader.handlerCalls());
                System.exit(CRASH_EXIT);
                return result;
            }
        };
    }

    static void footer(Orchestrator orchestrator, SqliteRunStore store, long started) {
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
        System.out.printf("run %s · %d passes · %d tool calls · %d ms · %s in %s · handler calls: %s%n",
                runId, passes, toolCalls, ms, store.job(runId).status(), store.file(), ToolLoader.handlerCalls());
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

    static int port(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n >= 0 && n <= 65535) {
                return n;
            }
        } catch (NumberFormatException e) {
            // falls through to the message below
        }
        fail("--port must be 0 to 65535, got " + value);
        return 0;
    }

    static void usage() {
        fail("usage: agents | tools | run --agent <name> \"question\" [\"question\" ...] [--slow] [--crash-after N] [--crash-before-persist]"
                + " | resume <runId> | replay <runId> [fromStep] | eval [evals/golden.json] | serve [--port 8080]"
                + " | submit --agent <name> \"question\" | worker [--once] [--visibility-ms 2000] [--crash-after N] [--max-receives 3]");
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
