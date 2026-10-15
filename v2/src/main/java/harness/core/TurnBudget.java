package harness.core;

/**
 * One per turn, shared by the loop and every child loop it spawns: the span numbers, handed
 * out in order so a child's rows slot into the parent's turn, and the model calls the whole
 * tree may make. The budget is a bound in the orchestrator, not in the prompt: a parent and
 * its children draw on the same count, and when it is gone the turn ends. Two children of
 * one pass may run together, so the calls are synchronized.
 */
public final class TurnBudget {

    private final int maxModelCalls;
    private int modelCalls;
    private int lastSpan;

    public TurnBudget(int maxModelCalls) {
        this.maxModelCalls = maxModelCalls;
    }

    /** The next span number of the turn. */
    public synchronized int nextSpan() {
        return ++lastSpan;
    }

    /** The numbers run on through a conversation: an earlier turn's last span is where this one starts. */
    public synchronized void spansAtLeast(int span) {
        lastSpan = Math.max(lastSpan, span);
    }

    /** One model call, if the turn has any left. */
    public synchronized boolean takeModelCall() {
        if (modelCalls >= maxModelCalls) {
            return false;
        }
        modelCalls++;
        return true;
    }

    public int maxModelCalls() {
        return maxModelCalls;
    }

    public synchronized int modelCalls() {
        return modelCalls;
    }
}
