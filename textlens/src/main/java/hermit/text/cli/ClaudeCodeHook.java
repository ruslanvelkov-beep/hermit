package hermit.text.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.TextLens;
import hermit.text.check.Findings;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Check;
import hermit.text.pos.Reranker;
import hermit.text.pos.SearchModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * TextLens as a Claude Code Stop hook ({@code textlens hook claude-code}). When Claude Code is about to stop, the hook
 * reads the hook's JSON from standard input ({@code session_id}, {@code transcript_path}, {@code stop_hook_active}),
 * takes the turn's answer and its sources from the transcript ({@link #lastTurn}), annotates the answer (on a
 * {@link TextLensServer} when one answers on the port, else in this process) and appends one line to its log
 * ({@link #logLine}). It never keeps Claude Code from stopping unless {@code --block} is given (the owner, 2026-11-07:
 * refusals stay off, the hook only records): then, when the answer has findings and the hook has not already blocked
 * this stop, it writes {@code {"decision": "block", "reason": ...}} so that the agent checks those statements first.
 * Its own failures are logged, never raised, so the hook exits 0; only a usage error exits 1, which Claude Code shows
 * the user without blocking.
 *
 * <p>In Claude Code's settings: {@code "hooks": {"Stop": [{"hooks": [{"type": "command", "command":
 * "textlens hook claude-code"}]}]}}.
 */
public final class ClaudeCodeHook {

    static final String USAGE = """
            usage: textlens hook claude-code [options] < the hook's JSON
              --log <file>          the log, one JSON line per answer (default ~/.textlens/claude-code.jsonl)
              --server <port>       the server to ask first (default 7471; 0 annotates in this process only)
              --weights <file>      the tagger's reranker weights (the property hermit.pos.weights)
              --search <file>       the tagger's sentence search model (the property hermit.pos.search)
              --clock <instant>     the clock, an ISO-8601 instant (default now)
              --zone <id>           the time zone (default the system's)
              --block               block the stop once when the answer has findings
            """;

    /** One turn of a transcript: the answer and the texts it was written from. */
    record Turn(String answer, List<String> sources) {}

    /** The hook's options; {@code server} 0 means none. */
    record Options(Path log, int server, Path weights, Path search, Instant clock, ZoneId zone, boolean block) {}

    private ClaudeCodeHook() {
    }

    /** The log when none is named: claude-code.jsonl in the folder .textlens of the user's home. */
    static Path defaultLog() {
        return Path.of(System.getProperty("user.home"), ".textlens", "claude-code.jsonl");
    }

    /**
     * {@code args} read as {@link Options}: each option of {@link #USAGE} followed by its value, but {@code --block},
     * which takes none. Defaults: {@link #defaultLog()}, {@link TextLensServer#DEFAULT_PORT}, no models, the clock
     * now, the system's zone, no blocking. Throws IllegalArgumentException naming the problem: an unknown option, an
     * option without its value, or a value that does not parse.
     */
    static Options parse(List<String> args) {
        Path log = defaultLog();
        int server = TextLensServer.DEFAULT_PORT;
        Path weights = null;
        Path search = null;
        Instant clock = Instant.now();
        ZoneId zone = ZoneId.systemDefault();
        boolean block = false;
        for (int i = 0; i < args.size(); i++) {
            String option = args.get(i);
            if (option.equals("--block")) {
                block = true;
                continue;
            }
            if (!List.of("--log", "--server", "--weights", "--search", "--clock", "--zone").contains(option)) {
                throw new IllegalArgumentException("unknown option: " + option);
            }
            if (i + 1 >= args.size()) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            String value = args.get(++i);
            try {
                switch (option) {
                    case "--log" -> log = Path.of(value);
                    case "--server" -> server = Integer.parseInt(value);
                    case "--weights" -> weights = Path.of(value);
                    case "--search" -> search = Path.of(value);
                    case "--clock" -> clock = Instant.parse(value);
                    default -> zone = ZoneId.of(value);
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(option + ": cannot read " + value);
            }
        }
        return new Options(log, server, weights, search, clock, zone, block);
    }

    /**
     * The last turn of a transcript, given as its lines (Claude Code's JSON lines, one entry each). Entries that are no
     * JSON, of another type than user or assistant, or marked {@code isMeta} or {@code isSidechain} are skipped. A user
     * entry whose content holds a {@code tool_result} block is a tool result, any other user entry a prompt. The answer
     * is the text of the {@code text} blocks of the assistant entries after the last user entry, joined by a blank
     * line; the sources are the text of the last prompt (its content when it is a string, else its {@code text} blocks
     * joined by a blank line) and then the text of each tool result after it (each {@code tool_result} block's content
     * when it is a string, else its {@code text} blocks joined by a blank line), empty ones left out. Null when the
     * answer has no text.
     */
    static Turn lastTurn(List<String> lines) {
        String prompt = null;
        List<String> results = new ArrayList<>();
        List<String> answers = new ArrayList<>();
        for (String line : lines) {
            JsonNode entry;
            try {
                entry = TextLensServer.JSON.readTree(line);
            } catch (IOException exception) {
                continue;
            }
            if (entry == null || entry.path("isMeta").asBoolean() || entry.path("isSidechain").asBoolean()) {
                continue;
            }
            String type = entry.path("type").asText();
            JsonNode content = entry.path("message").path("content");
            if ("user".equals(type)) {
                answers.clear();
                boolean toolResult = false;
                for (JsonNode block : content) {
                    if ("tool_result".equals(block.path("type").asText())) {
                        toolResult = true;
                        String text = contentText(block.get("content"));
                        if (!text.isBlank()) {
                            results.add(text);
                        }
                    }
                }
                if (!toolResult) {
                    prompt = contentText(content);
                    results.clear();
                }
            } else if ("assistant".equals(type)) {
                String text = contentText(content);
                if (!text.isBlank()) {
                    answers.add(text);
                }
            }
        }
        if (answers.isEmpty()) {
            return null;
        }
        List<String> sources = new ArrayList<>();
        if (prompt != null && !prompt.isBlank()) {
            sources.add(prompt);
        }
        sources.addAll(results);
        return new Turn(String.join("\n\n", answers), List.copyOf(sources));
    }

    /**
     * The log's line for one answer, as compact JSON: {@code time} (the clock), {@code session}, {@code characters}
     * (the answer's length), {@code checks} (each status's name and how many checks have it, in the names' order) and
     * {@code findings} (each with its {@code checker} and {@code sentence}, in order).
     */
    static String logLine(Instant clock, String session, AnnotationDocument document) {
        ObjectNode line = TextLensServer.JSON.createObjectNode();
        line.put("time", clock.toString());
        line.put("session", session);
        line.put("characters", document.text().length());
        Map<String, Integer> counts = new TreeMap<>();
        for (Check check : document.checks()) {
            counts.merge(check.status().name(), 1, Integer::sum);
        }
        ObjectNode checks = line.putObject("checks");
        counts.forEach(checks::put);
        ArrayNode findings = line.putArray("findings");
        for (Findings.Finding finding : Findings.of(document)) {
            ObjectNode value = findings.addObject();
            value.put("checker", finding.checker());
            value.put("sentence", finding.sentence());
        }
        return line.toString();
    }

    /**
     * Runs the hook: {@code args} are its options ({@link #parse}), {@code in} holds the hook's JSON. A usage error
     * writes the problem and {@link #USAGE} to {@code err} and returns 1. The models named are set as their system
     * properties. Without a turn ({@link #lastTurn} of the transcript's lines gives null) nothing is logged. Else the
     * answer is annotated with its sources, the clock and the zone: by the server's {@code /annotate} when the port is
     * not 0 and a server answers there (a connection refused, or no answer within 2 seconds of connecting, means none),
     * else by {@link TextLens#standard()}; then {@link #logLine} is appended to the log (its folders made when
     * missing). With {@code --block}, findings, and {@code stop_hook_active} not true, it writes
     * {@code {"decision":"block","reason":"TextLens found: <the findings' sentences, joined by a space> Check these
     * statements before you finish."}} and a line break to {@code out}. Any failure (no such transcript, a server that
     * answers other than 200, an annotation that throws) is appended to the log as {@code {"time", "session", "error"}}
     * instead, or written to {@code err} when the log cannot be written. Returns 0 but for a usage error.
     */
    static int run(List<String> args, InputStream in, PrintStream out, PrintStream err) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException failure) {
            err.println("textlens: " + failure.getMessage());
            err.print(USAGE);
            return 1;
        }
        if (options.weights() != null) {
            System.setProperty("hermit.pos.weights", options.weights().toString());
        }
        if (options.search() != null) {
            System.setProperty("hermit.pos.search", options.search().toString());
        }
        String session = "";
        try {
            JsonNode hook = TextLensServer.JSON.readTree(in);
            session = hook.path("session_id").asText();
            Path transcript = Path.of(hook.path("transcript_path").asText());
            Turn turn = lastTurn(Files.readAllLines(transcript, StandardCharsets.UTF_8));
            if (turn == null) {
                return 0;
            }
            AnnotationDocument document = annotate(options, turn);
            appendLine(options.log(), logLine(options.clock(), session, document));
            List<Findings.Finding> findings = Findings.of(document);
            if (options.block() && !findings.isEmpty() && !hook.path("stop_hook_active").asBoolean()) {
                String sentences = String.join(" ", findings.stream().map(Findings.Finding::sentence).toList());
                ObjectNode decision = TextLensServer.JSON.createObjectNode();
                decision.put("decision", "block");
                decision.put("reason", "TextLens found: " + sentences
                        + " Check these statements before you finish.");
                out.println(decision);
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ObjectNode line = TextLensServer.JSON.createObjectNode();
            line.put("time", options.clock().toString());
            line.put("session", session);
            line.put("error", failure.getMessage());
            try {
                appendLine(options.log(), line.toString());
            } catch (IOException logFailure) {
                err.println("textlens: " + failure.getMessage());
                err.println("textlens: cannot write log: " + logFailure.getMessage());
            }
        }
        return 0;
    }

    private static String contentText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (!content.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        boolean first = true;
        for (JsonNode block : content) {
            if (!"text".equals(block.path("type").asText()) || !block.path("text").isTextual()) {
                continue;
            }
            if (!first) {
                text.append("\n\n");
            }
            text.append(block.path("text").asText());
            first = false;
        }
        return text.toString();
    }

    private static AnnotationDocument annotate(Options options, Turn turn) throws IOException, InterruptedException {
        if (options.server() == 0) {
            return TextLens.standard().annotate(turn.answer(), turn.sources(), options.clock(), options.zone());
        }
        ObjectNode body = TextLensServer.JSON.createObjectNode();
        body.put("text", turn.answer());
        ArrayNode sources = body.putArray("sources");
        turn.sources().forEach(sources::add);
        body.put("clock", options.clock().toString());
        body.put("zone", options.zone().getId());
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + options.server() + "/annotate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            HttpResponse<String> response = client.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("server answered " + response.statusCode() + ": " + response.body());
            }
            return AnnotationDocument.fromJson(response.body());
        } catch (ConnectException | HttpConnectTimeoutException exception) {
            return TextLens.standard().annotate(turn.answer(), turn.sources(), options.clock(), options.zone());
        }
    }

    private static void appendLine(Path log, String line) throws IOException {
        Path parent = log.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(log, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String errorLine(Instant clock, String session, Exception failure) {
        ObjectNode line = TextLensServer.JSON.createObjectNode();
        line.put("time", clock.toString());
        line.put("session", session);
        line.put("error", failure.getMessage());
        return line.toString();
    }
}
