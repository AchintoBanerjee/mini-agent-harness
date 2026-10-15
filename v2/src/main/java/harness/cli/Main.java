package harness.cli;

import harness.agent.Agent;
import harness.agent.AgentLoader;
import harness.core.ConsoleListener;
import harness.core.CrashAfter;
import harness.core.Span;
import harness.eval.CaseResult;
import harness.eval.EvalRunner;
import harness.eval.GoldenCase;
import harness.http.HttpApi;
import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import harness.tools.ToolLoader;
import harness.worker.Worker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The developer's local tools, run from v2/: serve [--port 8080] · worker [--once]
 * [--visibility-ms 2000] [--crash-after N] [--slow] · submit --agent X "question" · eval
 * [evals/golden.json] · replay turnId [fromSpan]. The HTTP API is the face of the harness;
 * these are what you run next to it. Args are parsed by hand. Agents and tools are read
 * from agents/ and tools/; every turn lives in harness.db (SQLite), the same file the
 * server and the workers share. serve queues turns, worker takes them off the queue and
 * runs them, submit queues one without HTTP. --crash-after simulates a worker dying
 * mid-turn so the next worker has something to pick up; --slow gives every lookup 300 ms
 * of simulated latency so that tools running together can be seen to overlap. eval scores
 * the golden set in memory and prints one row per case and the totals.
 */
public class Main {

    static final Path AGENTS = Path.of("agents");
    static final Path TOOLS = Path.of("tools");
    static final Path DB = SqliteRunStore.DEFAULT_FILE;
    static final Path GOLDEN = Path.of("evals", "golden.json");
    static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        switch (args[0]) {
            case "serve" -> serve(rest);
            case "worker" -> worker(rest);
            case "submit" -> submit(rest);
            case "eval" -> eval(rest);
            case "replay" -> replay(rest);
            default -> usage();
        }
    }

    /** The HTTP API on localhost: agents/, tools/ and harness.db behind it; a worker runs the turns. Runs until killed. */
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
        HttpApi api = new HttpApi(AGENTS, TOOLS, GOLDEN, store, new RunQueue(store));
        try {
            api.start(port);
        } catch (IOException e) {
            fail("cannot listen on port " + port + ": " + e.getMessage());
        }
        System.out.println("listening on http://localhost:" + api.port() + " · agents/ tools/ " + DB
                + " · POST /runs queues a turn for a worker · ctrl-c stops it");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Takes turns off the queue and runs them, resuming any a dead worker left behind.
     * --once handles one message and exits (1 when there was none); otherwise it polls
     * every 500 ms until killed. --crash-after N is the simulated death: exit 3 right
     * after row N is persisted, without acking. --slow is the simulated latency on every
     * lookup, so two tools in one pass can be seen to overlap.
     */
    static void worker(List<String> args) {
        boolean once = false;
        long visibilityMs = Worker.DEFAULT_VISIBILITY_MS;
        int crashAfter = 0;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("--once")) {
                once = true;
            } else if (arg.equals("--visibility-ms") && i + 1 < args.size()) {
                visibilityMs = positive(args.get(++i), "--visibility-ms");
            } else if (arg.equals("--crash-after") && i + 1 < args.size()) {
                crashAfter = positive(args.get(++i), "--crash-after");
            } else if (arg.equals("--slow")) {
                ToolLoader.SLOW_LOOKUP_MS = 300;
            } else {
                fail("usage: worker [--once] [--visibility-ms 2000] [--crash-after N] [--slow]");
            }
        }
        String pid = String.valueOf(ProcessHandle.current().pid());
        try (SqliteRunStore store = new SqliteRunStore(DB)) {
            Worker worker = new Worker(AGENTS, TOOLS, store, new RunQueue(store), pid, visibilityMs);
            worker.crashAfter(crashAfter);
            System.out.println("worker pid " + pid + " · lease " + visibilityMs + " ms"
                    + (once ? " · once" : " · polling every " + Worker.POLL_MS + " ms, ctrl-c stops it")
                    + (ToolLoader.SLOW_LOOKUP_MS > 0 ? " · slow: every lookup sleeps " + ToolLoader.SLOW_LOOKUP_MS + " ms (latency simulated)" : "")
                    + " · " + DB);
            if (!once) {
                worker.loop();
            } else if (!worker.once()) {
                System.out.println("nothing to receive");
                System.exit(1);
            }
        }
    }

    /** Puts one question on the queue as a turn of a new conversation, QUEUED, and prints its id; a worker runs it. */
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
            String turnId = new RunQueue(store).submit(store.newConversation(agent.name()), question);
            System.out.println("submitted " + turnId + " · " + store.turn(turnId).status() + " in " + DB
                    + " · agent " + agent.name() + " · \"" + question + "\"");
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

    /** Prints the stored rows of a turn from the given span on (default: from the first). */
    static void replay(List<String> args) {
        if (args.isEmpty() || args.size() > 2) {
            fail("usage: replay <turnId> [fromSpan]");
        }
        String turnId = args.get(0);
        int fromSpan = args.size() == 2 ? positive(args.get(1), "fromSpan") : 1;
        try (SqliteRunStore store = new SqliteRunStore(DB)) {
            if (!store.exists(turnId)) {
                fail("no turn " + turnId + " in " + DB);
            }
            for (Span span : store.load(turnId, fromSpan)) {
                System.out.println(ConsoleListener.format(span));
            }
        }
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
        fail("usage: serve [--port 8080] | worker [--once] [--visibility-ms 2000] [--crash-after N] [--slow]"
                + " | submit --agent <name> \"question\" | eval [evals/golden.json] | replay <turnId> [fromSpan]"
                + "   (exit " + CrashAfter.EXIT_CODE + " is the simulated crash)");
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
