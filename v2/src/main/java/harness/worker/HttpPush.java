package harness.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import harness.core.RunListener;
import harness.core.Span;
import harness.store.TurnRow;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The stand-in for the append to the run's stream: each event the loop raises (a row, a
 * piece of the answer, done) is posted to the API at base/turns/{id}/events, and the API
 * appends it to the stream it holds for that turn. A push that fails, or that the API turns
 * away (a reply that is not 2xx), never stops the loop: the rows are in the store, so a
 * client reads them from there. The first failure is printed once; later ones are quiet.
 */
public class HttpPush implements RunListener {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    private final String base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private boolean reported;

    /** base is the API's address, such as http://localhost:8080. */
    public HttpPush(String base) {
        this.base = base.replaceAll("/+$", "");
    }

    @Override
    public void on(Span span) {
        push(span.turnId(), "step", JSON.valueToTree(span));
    }

    @Override
    public void token(String turnId, String text) {
        push(turnId, "token", JSON.createObjectNode().put("turnId", turnId).put("text", text));
    }

    @Override
    public void done(String turnId, TurnRow.Status status, String answer) {
        ObjectNode data = JSON.createObjectNode().put("turnId", turnId).put("status", status.name());
        if (status == TurnRow.Status.DONE) {
            data.put("answer", answer);
        }
        push(turnId, "done", data);
    }

    private void push(String turnId, String event, JsonNode data) {
        ObjectNode body = JSON.createObjectNode().put("event", event);
        body.set("data", data);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/turns/" + turnId + "/events"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                failed();
            }
        } catch (IOException | RuntimeException e) {
            failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failed();
        }
    }

    /** Said once: the stream is not being fed, the rows still are. */
    private void failed() {
        if (!reported) {
            reported = true;
            System.out.println("stream: cannot reach " + base + ", clients will read the saved rows");
        }
    }
}
