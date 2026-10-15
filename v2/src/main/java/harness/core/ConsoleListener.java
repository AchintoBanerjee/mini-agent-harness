package harness.core;

/** Prints each row the moment it lands: the live trace of a run. */
public class ConsoleListener implements RunListener {

    @Override
    public void on(Span span) {
        System.out.println(format(span));
    }

    /** One row, one line: step, kind, request -> response, then status and time. A child loop's row is indented two spaces per depth. */
    public static String format(Span span) {
        String tail = span.response().isEmpty() ? "" : " -> " + span.response();
        String indent = "  ".repeat(Integer.parseInt(span.attrs().getOrDefault("depth", "0")));
        return indent + String.format("%3d  %-4s  %s%s  [%s %d ms]",
                span.span(), span.kind(), span.request(), tail, span.status(), span.ms());
    }
}
