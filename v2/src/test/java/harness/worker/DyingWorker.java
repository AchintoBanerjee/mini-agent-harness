package harness.worker;

import harness.store.RunQueue;
import harness.store.SqliteRunStore;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;

/**
 * A dead worker without a dead process, for tests: dies after the Nth row it writes by
 * parking its thread for good, so like a killed process it never acks and never writes
 * again. The turn stays RUNNING, its lease runs out, and the next worker receives it.
 */
public class DyingWorker extends Worker {

    public final CountDownLatch died = new CountDownLatch(1);

    public DyingWorker(Path agents, Path tools, SqliteRunStore store, RunQueue queue, String name, long visibilityMs, int crashAfter) {
        super(agents, tools, store, queue, name, visibilityMs);
        crashAfter(crashAfter);
    }

    @Override
    protected void die() {
        died.countDown();
        while (true) {
            LockSupport.park();
        }
    }
}
