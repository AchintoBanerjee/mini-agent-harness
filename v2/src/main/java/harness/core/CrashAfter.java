package harness.core;

import harness.tools.ToolLoader;

/**
 * The simulated crash: prints each row like the console does, and right after the Nth
 * row this process wrote is in the store and on screen, says so and runs the exit. The
 * CLI's exit is System.exit(3): the process is gone, nothing after the row happens (no
 * ack, no next step). A test's exit can be something else that never returns. N counts
 * this process's own rows, so a worker that resumes a run at step 5 and has N = 1 dies
 * after step 6; for a fresh run that is the same as the step number.
 */
public class CrashAfter implements RunListener {

    public static final int EXIT_CODE = 3;

    private final int rows;
    private final RunListener print;
    private final Runnable exit;
    private int written;

    public CrashAfter(int rows, RunListener print, Runnable exit) {
        this.rows = rows;
        this.print = print;
        this.exit = exit;
    }

    @Override
    public void on(Span span) {
        print.on(span);
        if (++written == rows) {
            System.out.println("crash: exit " + EXIT_CODE + " right after row " + span.step() + " of run " + span.run()
                    + " was persisted (simulated) · handler calls: " + ToolLoader.handlerCalls());
            exit.run();
        }
    }
}
