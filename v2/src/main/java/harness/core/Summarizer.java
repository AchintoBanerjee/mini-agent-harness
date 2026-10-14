package harness.core;

/** Turns a finished turn into the answer the user sees. Swap for an LLM summarizer later. */
public interface Summarizer {

    String summarize(Turn turn);
}
