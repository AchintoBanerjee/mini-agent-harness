package harness.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * The run queue: a table in the store's file with the three operations of SQS (Amazon's
 * Simple Queue Service). receive hands out the oldest visible message and hides it for the
 * visibility window, in one UPDATE: that is the lease. extend pushes the window forward,
 * ack deletes the message. A worker that dies never acks, so its message becomes visible
 * again when the window runs out, and the next receive gets it, counted. Swap the table for
 * SQS and the three calls stay the same. A dead-letter queue after N receives is out of
 * scope for this POC: receive only leases and counts.
 */
public class RunQueue {

    static final String CREATE_QUEUE = """
            CREATE TABLE IF NOT EXISTS queue (
              turn_id TEXT PRIMARY KEY, visible_at INTEGER, receives INTEGER)""";

    /** What receive hands out: the turn (job id) and how many times it has been received, this one included. */
    public record Message(String turnId, int receives) {
    }

    private final SqliteRunStore store;
    private final Connection db;

    /** Opens the queue table in the store's file. */
    public RunQueue(SqliteRunStore store) {
        this.store = store;
        this.db = store.db();
        synchronized (store) {
            try (Statement statement = db.createStatement()) {
                statement.execute(CREATE_QUEUE);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot open the queue in " + store.file(), e);
            }
        }
    }

    /** The turn row (QUEUED) in the conversation and its message, in one transaction; the turn id comes back at once. */
    public String submit(String conversationId, String question) {
        synchronized (store) {
            try {
                db.setAutoCommit(false);
                String turnId = store.newTurn(conversationId, question);
                try (PreparedStatement insert = db.prepareStatement(
                        "INSERT INTO queue (turn_id, visible_at, receives) VALUES (?, ?, 0)")) {
                    insert.setString(1, turnId);
                    insert.setLong(2, System.currentTimeMillis());
                    insert.executeUpdate();
                }
                db.commit();
                return turnId;
            } catch (SQLException | RuntimeException e) {
                rollback();
                throw new IllegalStateException("cannot submit to the queue in " + store.file(), e);
            } finally {
                autoCommit();
            }
        }
    }

    /**
     * The lease: the oldest message whose visible_at has passed is hidden until now +
     * visibilityMs and counted, in one UPDATE, so two workers cannot both get it. Empty
     * when nothing is visible.
     */
    public Optional<Message> receive(long visibilityMs) {
        synchronized (store) {
            long now = System.currentTimeMillis();
            try (PreparedStatement lease = db.prepareStatement("""
                    UPDATE queue SET visible_at = ?, receives = receives + 1
                    WHERE turn_id = (SELECT turn_id FROM queue WHERE visible_at <= ? ORDER BY rowid LIMIT 1)
                    RETURNING turn_id, receives""")) {
                lease.setLong(1, now + visibilityMs);
                lease.setLong(2, now);
                try (ResultSet result = lease.executeQuery()) {
                    return result.next()
                            ? Optional.of(new Message(result.getString("turn_id"), result.getInt("receives")))
                            : Optional.empty();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot receive from the queue in " + store.file(), e);
            }
        }
    }

    /** The worker is alive and still on it: the message stays hidden until now + visibilityMs. */
    public void extend(String turnId, long visibilityMs) {
        synchronized (store) {
            try (PreparedStatement update = db.prepareStatement("UPDATE queue SET visible_at = ? WHERE turn_id = ?")) {
                update.setLong(1, System.currentTimeMillis() + visibilityMs);
                update.setString(2, turnId);
                update.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("cannot extend " + turnId + " in " + store.file(), e);
            }
        }
    }

    /** The turn is done: the message is gone for good. */
    public void ack(String turnId) {
        synchronized (store) {
            try (PreparedStatement delete = db.prepareStatement("DELETE FROM queue WHERE turn_id = ?")) {
                delete.setString(1, turnId);
                delete.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("cannot ack " + turnId + " in " + store.file(), e);
            }
        }
    }

    /** How many messages the queue holds, visible or not. */
    public int size() {
        synchronized (store) {
            try (Statement statement = db.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM queue")) {
                result.next();
                return result.getInt(1);
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read the queue in " + store.file(), e);
            }
        }
    }

    private void rollback() {
        try {
            db.rollback();
        } catch (SQLException e) {
            // the transaction is lost either way; the caller gets the first error
        }
    }

    private void autoCommit() {
        try {
            db.setAutoCommit(true);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot leave the transaction in " + store.file(), e);
        }
    }
}
