package hermit.text.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hermit.text.TextLens;
import hermit.text.check.Findings;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TextLens's resident form: the models load once, then any number of texts are annotated over plain HTTP on 127.0.0.1
 * until the server is told to shut down or stays idle too long, so a hook that runs on every answer does not pay for a
 * fresh start each time.
 *
 * <p>Endpoints: {@code POST /annotate} answers the annotation document as JSON, {@code POST /check} the findings as
 * {@code {"findings": [{"checker", "targets", "sentence"}, ...]}}; both take a JSON body {@code {"text": ...,
 * "sources": [...], "clock": ..., "zone": ...}} of which only the text is required ({@link #request}).
 * {@code GET /stats} answers {@code {"requests": n}}, the texts annotated so far, and {@code GET /shutdown} stops the
 * server. Status 200 for an answer, 400 for a body that is no such request, 404 for another path, 405 for another
 * method and 500 when the annotation fails. Requests are served one at a time, on one thread.
 */
public final class TextLensServer {

    /** The port {@code serve} takes when none is given, and the port a hook looks for a running server on. */
    public static final int DEFAULT_PORT = 7471;
    static final ObjectMapper JSON = new ObjectMapper();

    /** One text to annotate: the text, the texts it was written from, the clock and the zone. */
    record Request(String text, List<String> sources, Instant clock, ZoneId zone) {}

    private final TextLens lens;
    private final HttpServer http;
    private final ScheduledExecutorService idleWatch;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private volatile long lastUsed = System.nanoTime();
    private int requests;

    /**
     * Starts serving {@code lens} at once on 127.0.0.1 and {@code port} (0 picks a free port, see {@link #port()}).
     * With {@code idleMinutes} above 0, a watch checks every minute and stops the server once no request has come for
     * that long.
     */
    public TextLensServer(TextLens lens, int port, int idleMinutes) throws IOException {
        this.lens = lens;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        http.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "textlens-server");
            thread.setDaemon(true);
            return thread;
        }));
        http.createContext("/", this::handle);
        http.start();
        if (idleMinutes > 0) {
            idleWatch = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "textlens-idle-watch");
                thread.setDaemon(true);
                return thread;
            });
            idleWatch.scheduleAtFixedRate(() -> {
                long idleNanos = System.nanoTime() - lastUsed;
                if (idleNanos >= TimeUnit.MINUTES.toNanos(idleMinutes)) {
                    stop();
                }
            }, 1, 1, TimeUnit.MINUTES);
        } else {
            idleWatch = null;
        }
    }

    /** The port the server listens on. */
    public int port() {
        return http.getAddress().getPort();
    }

    /** Stops the server and its idle watch; {@link #awaitShutdown} returns after it. */
    public void stop() {
        http.stop(0);
        if (idleWatch != null) {
            idleWatch.shutdownNow();
        }
        stopped.countDown();
    }

    /** Waits until the server has stopped. */
    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    /**
     * The request {@code body} holds: its {@code text} (required, a string), its {@code sources} (an array of strings,
     * default none), its {@code clock} (an ISO-8601 instant, default now) and its {@code zone} (a zone id, default the
     * system's). Throws IllegalArgumentException naming the first field that is missing or not of its kind.
     */
    static Request request(JsonNode body) {
        JsonNode text = body.get("text");
        if (text == null || !text.isTextual()) {
            throw new IllegalArgumentException("text: a string is required");
        }
        List<String> sources = new ArrayList<>();
        JsonNode sourceNodes = body.get("sources");
        if (sourceNodes != null) {
            if (!sourceNodes.isArray()) {
                throw new IllegalArgumentException("sources: an array of strings is required");
            }
            for (JsonNode source : sourceNodes) {
                if (!source.isTextual()) {
                    throw new IllegalArgumentException("sources: an array of strings is required");
                }
                sources.add(source.textValue());
            }
        }
        Instant clock = Instant.now();
        JsonNode clockNode = body.get("clock");
        if (clockNode != null) {
            if (!clockNode.isTextual()) {
                throw new IllegalArgumentException("clock: an instant is required");
            }
            try {
                clock = Instant.parse(clockNode.textValue());
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("clock: an instant is required", failure);
            }
        }
        ZoneId zone = ZoneId.systemDefault();
        JsonNode zoneNode = body.get("zone");
        if (zoneNode != null) {
            if (!zoneNode.isTextual()) {
                throw new IllegalArgumentException("zone: a zone id is required");
            }
            try {
                zone = ZoneId.of(zoneNode.textValue());
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("zone: a zone id is required", failure);
            }
        }
        return new Request(text.textValue(), List.copyOf(sources), clock, zone);
    }

    /** {@code findings} as {@code /check} answers them: {@code {"findings": [{"checker", "targets", "sentence"}]}}. */
    static String findingsJson(List<Findings.Finding> findings) {
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode values = answer.putArray("findings");
        for (Findings.Finding finding : findings) {
            ObjectNode value = values.addObject();
            value.put("checker", finding.checker());
            ArrayNode targets = value.putArray("targets");
            finding.targets().forEach(targets::add);
            value.put("sentence", finding.sentence());
        }
        return answer.toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        lastUsed = System.nanoTime();
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (path.equals("/stats")) {
            if (!method.equals("GET")) {
                send(exchange, 405, "text/plain", "method not allowed");
                return;
            }
            send(exchange, 200, "application/json", "{\"requests\": " + requests + "}");
            return;
        }
        if (path.equals("/shutdown")) {
            if (!method.equals("GET")) {
                send(exchange, 405, "text/plain", "method not allowed");
                return;
            }
            send(exchange, 200, "text/plain", "stopping");
            stop();
            return;
        }
        if (!path.equals("/annotate") && !path.equals("/check")) {
            send(exchange, 404, "text/plain", "not found");
            return;
        }
        if (!method.equals("POST")) {
            send(exchange, 405, "text/plain", "method not allowed");
            return;
        }
        try {
            Request request = request(JSON.readTree(exchange.getRequestBody()));
            var annotation = lens.annotate(
                    request.text(), request.sources(), request.clock(), request.zone());
            requests++;
            String body = path.equals("/annotate")
                    ? annotation.toJson()
                    : findingsJson(lens.findings(annotation));
            send(exchange, 200, "application/json", body);
        } catch (IllegalArgumentException failure) {
            send(exchange, 400, "text/plain", failure.getMessage());
        } catch (IOException failure) {
            send(exchange, 400, "text/plain", failure.getMessage());
        } catch (RuntimeException failure) {
            send(exchange, 500, "text/plain", failure.getMessage());
        }
    }
}
