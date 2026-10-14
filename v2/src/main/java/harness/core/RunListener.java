package harness.core;

/** Told about each row right after it is stored. The console listener prints it; tests collect it. */
public interface RunListener {

    void on(Span span);
}
