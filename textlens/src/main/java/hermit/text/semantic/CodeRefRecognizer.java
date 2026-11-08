package hermit.text.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.lex.Tokenizer;
import hermit.text.model.Span;
import hermit.text.model.Tag;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code references, commands and flags (milestone A3 of the annotation plan, section 6.1).
 * <ul>
 *   <li>CODE_REF (rule {@code code-ref}): a URL, PATH, MEMBER_REF, FILE_LINE, HASH or IDENT token, valued
 *       {@code {"kind": "<kind>", "name": "<the token as written>"}} with the kind of {@link #kindOf}; a FILE_LINE's
 *       name is the file and it adds {@code "line"} (a number), for a range ({@code X.java:44-60})
 *       {@code "endLine"}, and for a column ({@code Reader.kt:14:9}) {@code "column"}.</li>
 *   <li>No CODE_REF for an IDENT token that is a word with a hyphen whose last part is three or more lower-case letters
 *       ({@code 404-ing}, {@code L2-normalized}, {@code OpenAI-compatible}): an ordinary word written with a digit or
 *       a capital in it.</li>
 *   <li>CODE_REF (rule {@code code-ref}) also for a commit hash of digits only ({@link #digitHash}), of the kind
 *       {@code hash}; and for a WORD token that is a code name with a possessive ending ({@code TreeEdit's}): its
 *       {@link #possessiveStem} is read by the standard tokenizer as one IDENT or MEMBER_REF token, and the span, which
 *       covers the ending, is named by the stem, of the kind {@link #kindOf} gives for that shape.</li>
 *   <li>CODE_REF (rule {@code code-ref}) for a WORD token that {@link #wordKind} gives a kind (a code literal, an
 *       enum-like constant, an exception abbreviation, a javadoc parameter or inline code), named by the word as
 *       written, except a literal, named in lower case ({@code Null} gives {@code "null"}).</li>
 *   <li>FLAG (rule {@code flag}): a FLAG token, valued with the flag as written.</li>
 *   <li>COMMAND (rule {@code command}) outside a code span, from a token that {@link #commandEnd} gives an end,
 *       valued with the text from that token to the end; the tokens inside keep their own spans.</li>
 *   <li>A CODE_SPAN token, by what lies between its backticks: a COMMAND (rule {@code command}) valued with that
 *       inside text when the tagger reads the code span as a command (its tag is NOUN, as
 *       {@code PosTagger#shapeCandidates} gives it); else a flag, a FLAG valued with it; otherwise a CODE_REF named by
 *       it, of the kind {@link #codeSpanKind} gives it. The span covers the backticks.</li>
 * </ul>
 * Spans come in text order, ids {@code k1}, {@code k2}, ...
 */
public final class CodeRefRecognizer implements ConceptRecognizer {

    /** A task id of the rewrite's notes: {@code t154}, {@code t162a}, {@code ta0r}, {@code ta2r2}. */
    public static final Pattern TASK_ID = Pattern.compile("t[a-z]?[0-9]+[a-z0-9]*");
    /** A label of a work item or a log entry: {@code L1}, {@code T4}, {@code FM-007}, {@code 037a}. */
    public static final Pattern WORK_LABEL = Pattern.compile("[A-Z]{1,2}-?[0-9]{1,3}[a-z]?|[0-9]{3}[a-z][a-z0-9]*");
    /** Words that hold a value in code, in lower case: control and status words and the literals. */
    public static final List<String> VALUE_WORDS = List.of("continue", "retry", "stop", "unresolved", "executed",
            "green", "red", "true", "false", "null");
    /** Java keywords: a code span holding one alone, or a slash list of them, is of the kind {@code syntax}. */
    public static final List<String> KEYWORDS = List.of("abstract", "assert", "boolean", "break", "byte", "case",
            "catch", "char", "class", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "record", "return", "sealed", "short", "static", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "var", "void", "volatile", "while", "yield");
    /** Exception names written as abbreviations: a CODE_REF of the kind {@code type}. */
    public static final List<String> EXCEPTION_ABBREVIATIONS = List.of("NPE", "IOOBE", "AIOOBE", "SIOOBE", "IAE",
            "ISE", "UOE", "CCE", "NFE", "OOME", "SOE");
    /** JDK value types named in prose ({@code a String returned}): a CODE_REF of the kind {@code type}. */
    public static final List<String> JDK_TYPES = List.of("String", "Integer", "Long", "Boolean", "Double", "Float",
            "Short", "Byte", "Character", "StringBuilder", "BigDecimal", "BigInteger", "Optional");
    /** The words, in lower case, after which a single letter is a meta-variable: {@code argument k}, {@code type T}. */
    public static final List<String> META_CUES = List.of("argument", "arguments", "parameter", "parameters", "type",
            "types", "variable", "variables");
    /** HTTP methods: written in capitals alone, a CODE_REF of the kind {@code constant}. */
    public static final List<String> HTTP_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD",
            "OPTIONS");
    /** The words, in lower case, after which {@code true} or {@code false} is a code value. */
    public static final List<String> VALUE_CUES = List.of("return", "returns", "returned", "stays", "stay", "remains",
            "remain", "to", "=");
    /** Programs whose command, between quotes, is a COMMAND outside a code span. */
    public static final List<String> PROGRAMS = List.of("git", "sed", "grep", "jq", "curl", "java", "javac", "gradle",
            "mvn", "npm", "uv", "python", "python3", "bash", "docker", "lms", "codex", "gh");
    /** The git subcommands with which {@code git} starts a COMMAND without quotes ({@code see git log}). */
    public static final List<String> GIT_SUBCOMMANDS = List.of("add", "archive", "blame", "branch", "checkout",
            "clone", "commit", "config", "diff", "fetch", "init", "log", "merge", "mv", "pull", "push", "rebase",
            "reset", "restore", "revert", "rm", "show", "stash", "status", "switch", "tag");
    /** A commit hash written in digits only: seven to forty of them. */
    public static final Pattern DIGIT_HASH = Pattern.compile("[0-9]{7,40}");
    /** A file and line, optionally a line range. */
    private static final Pattern FILE_LINE_PATTERN = Pattern.compile("^(.*):([0-9]+)(?:-([0-9]+))?$");

    /** The spans of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Span> recognize(Input input) {
        List<Span> spans = new ArrayList<>();
        Tokenizer tokenizer = Tokenizer.standard();
        for (int i = 0; i < input.tokens().size(); i++) {
            int commandEnd = commandEnd(input, i);
            if (commandEnd >= i) {
                spans.add(Spans.over(input, i, commandEnd, "k" + (spans.size() + 1), Span.Concept.COMMAND,
                        Spans.JSON.textNode(Spans.surface(input, i, commandEnd)), List.of(), "command"));
            }
            Token token = input.tokens().get(i);
            String text = Spans.surface(input, i, i);
            Span.Concept concept;
            JsonNode value;
            String rule;
            if (token.shape() == Token.Shape.CODE_SPAN) {
                String inside = text.substring(1, text.length() - 1);
                if (token.tag() == Tag.NOUN) {
                    concept = Span.Concept.COMMAND;
                    value = Spans.JSON.textNode(inside);
                    rule = "command";
                } else {
                    Token.Shape shape = tokenizer.shapeOf(inside);
                    if (shape == Token.Shape.FLAG) {
                        concept = Span.Concept.FLAG;
                        value = Spans.JSON.textNode(inside);
                        rule = "flag";
                    } else {
                        concept = Span.Concept.CODE_REF;
                        value = Spans.JSON.objectNode().put("kind", codeSpanKind(inside)).put("name", inside);
                        rule = "code-ref";
                    }
                }
            } else if (token.shape() == Token.Shape.FLAG) {
                concept = Span.Concept.FLAG;
                value = Spans.JSON.textNode(text);
                rule = "flag";
            } else if (digitHash(input, i)) {
                concept = Span.Concept.CODE_REF;
                value = Spans.JSON.objectNode().put("kind", "hash").put("name", text);
                rule = "code-ref";
            } else {
                String stem = token.shape() == Token.Shape.WORD ? possessiveStem(text) : null;
                Token.Shape stemShape = stem == null ? null : tokenizer.shapeOf(stem);
                String proseKind = token.shape() == Token.Shape.WORD ? wordKind(input, i) : null;
                if (stemShape == Token.Shape.IDENT || stemShape == Token.Shape.MEMBER_REF) {
                    concept = Span.Concept.CODE_REF;
                    value = Spans.JSON.objectNode().put("kind", kindOf(stemShape, stem)).put("name", stem);
                    rule = "code-ref";
                } else if (proseKind != null) {
                    String name = proseKind.equals("value") && VALUE_WORDS.contains(text.toLowerCase(Locale.ROOT))
                            ? text.toLowerCase(Locale.ROOT) : text;
                    concept = Span.Concept.CODE_REF;
                    value = Spans.JSON.objectNode().put("kind", proseKind).put("name", name);
                    rule = "code-ref";
                } else if (token.shape() == Token.Shape.URL || token.shape() == Token.Shape.PATH
                        || token.shape() == Token.Shape.MEMBER_REF || token.shape() == Token.Shape.FILE_LINE
                        || token.shape() == Token.Shape.HASH || token.shape() == Token.Shape.IDENT) {
                    if (token.shape() == Token.Shape.IDENT && text.matches(".*-[a-z]{3,}$")) {
                        continue;
                    }
                    concept = Span.Concept.CODE_REF;
                    rule = "code-ref";
                    if (token.shape() == Token.Shape.FILE_LINE) {
                        Matcher match = Pattern.compile(
                                "^(.*?):([0-9]+)(?:-([0-9]+))?(?::([0-9]+))?$").matcher(text);
                        if (!match.matches()) {
                            continue;
                        }
                        ObjectNode object = Spans.JSON.objectNode().put("kind", "line")
                                .put("name", match.group(1)).put("line", Integer.parseInt(match.group(2)));
                        if (match.group(3) != null) {
                            object.put("endLine", Integer.parseInt(match.group(3)));
                        }
                        if (match.group(4) != null) {
                            object.put("column", Integer.parseInt(match.group(4)));
                        }
                        value = object;
                    } else {
                        value = Spans.JSON.objectNode().put("kind", kindOf(token.shape(), text))
                                .put("name", text);
                    }
                } else {
                    continue;
                }
            }
            spans.add(Spans.over(input, i, i, "k" + (spans.size() + 1), concept, value, List.of(), rule));
        }
        spans.sort((left, right) -> {
            int start = Integer.compare(left.range().start(), right.range().start());
            if (start != 0) {
                return start;
            }
            return Integer.compare(right.range().end(), left.range().end());
        });
        List<Span> result = new ArrayList<>();
        for (Span span : spans) {
            result.add(new Span("k" + (result.size() + 1), span.range(), span.concept(), span.value(),
                    span.alternatives(), span.provenance()));
        }
        return List.copyOf(result);
    }

    /**
     * The kind of a code reference written {@code text} with the token shape {@code shape}: a URL is {@code host} when
     * {@link #isHost} holds for it, else {@code value}; a PATH {@code file}, except a slash list of
     * {@link #VALUE_WORDS} and numbers ({@code null/0/false}), which is {@code value}, and one of {@link #KEYWORDS}
     * ({@code class/interface/enum/record}), which is {@code syntax}; a FILE_LINE {@code line}, a HASH {@code hash}; a
     * MEMBER_REF is {@code member} when it holds {@code #} or a parenthesis or its last dotted part starts with a
     * lower-case letter, else {@code type}; an IDENT is {@code task} when it is a {@link #TASK_ID} or a
     * {@link #WORK_LABEL}, {@code constant} when it has no lower-case letter, {@code type} when it starts with a
     * capital, else {@code identifier}; any other shape, or none, is {@code value}.
     */
    public static String kindOf(Token.Shape shape, String text) {
        if (shape == null) {
            return "value";
        }
        return switch (shape) {
            case URL -> isHost(text) ? "host" : "value";
            case PATH -> {
                String[] parts = text.split("/", -1);
                boolean values = parts.length > 1;
                boolean keywords = parts.length > 1;
                for (String part : parts) {
                    String lower = part.toLowerCase(Locale.ROOT);
                    values &= VALUE_WORDS.contains(lower) || part.matches("[0-9]+");
                    keywords &= KEYWORDS.contains(lower);
                }
                yield values ? "value" : keywords ? "syntax" : "file";
            }
            case FILE_LINE -> "line";
            case HASH -> "hash";
            case MEMBER_REF -> {
                int dot = text.lastIndexOf('.');
                String last = dot < 0 ? text : text.substring(dot + 1);
                boolean lower = !last.isEmpty() && Character.isLowerCase(last.charAt(0));
                yield text.contains("#") || text.contains("(") || lower ? "member" : "type";
            }
            case IDENT -> {
                if (TASK_ID.matcher(text).matches() || WORK_LABEL.matcher(text).matches()) {
                    yield "task";
                }
                if (!text.matches(".*[a-z].*")) {
                    yield "constant";
                }
                yield !text.isEmpty() && Character.isUpperCase(text.charAt(0)) ? "type" : "identifier";
            }
            default -> "value";
        };
    }

    /**
     * Whether {@code text} names a host alone, with no scheme and no path: {@code localhost}, an IPv4 address, or a
     * host name as {@link Tokenizer#HOST_DOMAINS} says (lower-case labels joined by periods, the last one of the
     * domains and the first not), with an optional {@code :port} of digits. {@code example.org}, {@code api.example.com:443},
     * {@code localhost:8080} and {@code 127.0.0.1} are hosts; {@code org.example}, {@code example.org/feed},
     * {@code https://example.org} and {@code ws://127.0.0.1:3001} are not.
     */
    public static boolean isHost(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String host = text;
        int colon = text.lastIndexOf(':');
        if (colon >= 0) {
            String port = text.substring(colon + 1);
            if (colon == 0 || port.isEmpty() || !port.matches("[0-9]+")
                    || text.indexOf(':') != colon) {
                return false;
            }
            host = text.substring(0, colon);
        }
        if (host.equals("localhost")) {
            return true;
        }
        if (host.matches("[0-9]+(?:\\.[0-9]+){3}")) {
            for (String octet : host.split("\\.")) {
                try {
                    if (Integer.parseInt(octet) > 255) {
                        return false;
                    }
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return true;
        }
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2 || Tokenizer.HOST_DOMAINS.contains(labels[0])
                || !Tokenizer.HOST_DOMAINS.contains(labels[labels.length - 1])) {
            return false;
        }
        for (String label : labels) {
            if (!label.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
                return false;
            }
        }
        return true;
    }

    /**
     * The kind of a code reference held by a code span, by the text between its backticks, {@code inside}, stripped of
     * surrounding whitespace; the first rule that holds:
     * <ol>
     *   <li>one of {@link #VALUE_WORDS}, in any case: {@code value};</li>
     *   <li>a quoted string ({@code "/path/to/x"}): {@code value};</li>
     *   <li>only operator signs ({@code ==}, {@code &&}, {@code ->}) or one of {@link #KEYWORDS}: {@code syntax};</li>
     *   <li>an arrow (U+2192 or {@code ->}) between names, an {@code =}, a placeholder in angle brackets
     *       ({@code <name>}), a name after a period ({@code .driverCtx}), one of {@link #HTTP_METHODS} with one path
     *       after a space and nothing more ({@code GET /v1/models}), or a path that starts with {@code /} and holds a
     *       {@code ?} ({@code /callers?spec=}): {@code identifier};</li>
     *   <li>{@code #} and a name in capitals ({@code #TIMEOUT_SECONDS}): {@code constant}; any other {@code #}:
     *       {@code member};</li>
     *   <li>a parenthesis at the end, opened in it: {@code member} when the last dotted part before the first
     *       {@code (} starts with a lower-case letter ({@code evict()}, {@code captor.capture()}), else {@code type}
     *       ({@code Escape(typeName, tier)});</li>
     *   <li>an annotation ({@code @Disabled}) or a capitalised name with type arguments ({@code List<X>}):
     *       {@code type};</li>
     *   <li>a URL, PATH, FILE_LINE, HASH, MEMBER_REF or IDENT as the standard tokenizer reads it: {@link #kindOf}
     *       (a host alone, {@code api.example.com} or {@code localhost:8080}, is {@code host});</li>
     *   <li>a dotted name ending in {@code .*}, or of lower-case parts only ({@code java.util.*},
     *       {@code analysis.lex}): {@code package}; a dotted name with a capital in a later part
     *       ({@code app.baseUrl}): {@code identifier};</li>
     *   <li>a slash in it, a letter and no whitespace ({@code runs/t088a}, {@code rewritten/}; not {@code 3/4}):
     *       {@code file};</li>
     *   <li>a single name: {@code constant} in capitals, {@code type} with a capital first, else
     *       {@code identifier};</li>
     *   <li>anything else: {@code value}.</li>
     * </ol>
     */
    public static String codeSpanKind(String inside) {
        String text = inside.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if (VALUE_WORDS.contains(lower)) {
            return "value";
        }
        if (text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("'") && text.endsWith("'")))) {
            return "value";
        }
        if (text.matches("[!%&*+\\-/<=>?^|~]+") || KEYWORDS.contains(text)) {
            return "syntax";
        }
        if (text.matches(".*[A-Za-z_$][A-Za-z0-9_.$]*\\s*(?:→|->)\\s*"
                + "[A-Za-z_$][A-Za-z0-9_.$]*.*") || text.contains("=")
                || text.matches("<[A-Za-z_$][A-Za-z0-9_$]*>")
                || text.matches("\\.[A-Za-z_$][A-Za-z0-9_$]*")
                || text.matches("(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+/\\S+")
                || text.startsWith("/") && text.contains("?")) {
            return "identifier";
        }
        int hash = text.indexOf('#');
        if (hash >= 0) {
            return text.substring(hash + 1).matches("[A-Z][A-Z0-9_]*") ? "constant" : "member";
        }
        int open = text.indexOf('(');
        if (open >= 0 && text.endsWith(")")) {
            String before = text.substring(0, open);
            int dot = before.lastIndexOf('.');
            String last = dot < 0 ? before : before.substring(dot + 1);
            return !last.isEmpty() && Character.isLowerCase(last.charAt(0)) ? "member" : "type";
        }
        if (text.matches("@[A-Za-z_$][A-Za-z0-9_$]*")
                || text.matches("[A-Z][A-Za-z0-9_$]*<.+>")) {
            return "type";
        }
        Token.Shape shape = Tokenizer.standard().shapeOf(text);
        if (shape == Token.Shape.URL || shape == Token.Shape.PATH || shape == Token.Shape.FILE_LINE
                || shape == Token.Shape.HASH || shape == Token.Shape.MEMBER_REF || shape == Token.Shape.IDENT) {
            return kindOf(shape, text);
        }
        if (text.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$*][A-Za-z0-9_$*]*)+")) {
            String[] parts = text.split("\\.");
            boolean packageName = text.endsWith(".*");
            if (!packageName) {
                packageName = true;
                for (String part : parts) {
                    packageName &= !part.isEmpty() && part.equals(part.toLowerCase(Locale.ROOT));
                }
            }
            return packageName ? "package" : "identifier";
        }
        if (text.contains("/") && text.matches(".*[A-Za-z].*") && !text.matches(".*\\s.*")) {
            return "file";
        }
        if (text.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            if (text.matches("[A-Z][A-Z0-9_]*")) {
                return "constant";
            }
            return Character.isUpperCase(text.charAt(0)) ? "type" : "identifier";
        }
        return "value";
    }

    /**
     * The kind of code reference that the WORD token {@code i} is, or null when it is none; the first rule that holds:
     * <ul>
     *   <li>{@code null} in any case, or {@code true} or {@code false} in any case right after one of
     *       {@link #VALUE_CUES} or as the first word of its sentence before {@code when} or {@code if}
     *       ({@code Returns true if}, {@code True when}): {@code value};</li>
     *   <li>the word after a javadoc {@code @param} (the touching tokens {@code @} and {@code param}):
     *       {@code identifier};</li>
     *   <li>the only word inside javadoc inline code (a left brace, {@code @} and {@code code} as touching tokens
     *       before it, a right brace after it): the kind {@link #codeSpanKind} gives it;</li>
     *   <li>a single letter other than {@code a} and {@code I} right after one of {@link #META_CUES}, in any case
     *       ({@code argument k}): {@code identifier};</li>
     *   <li>one of {@link #JDK_TYPES} that is not the first word of its sentence: {@code type};</li>
     *   <li>a word in capitals (a capital, then capitals, digits or underscores): {@code type} for one of
     *       {@link #EXCEPTION_ABBREVIATIONS}; {@code constant} for one of {@link #HTTP_METHODS}, for one joined to
     *       another word in capitals by a touching {@code /} ({@code CATCH/FINALLY}) and for one right after
     *       {@code return}, {@code returns} or {@code returned} in any case; {@code value} for one between touching
     *       double quotes ({@code "UP"}). Other words in capitals are emphasis, not code.</li>
     * </ul>
     */
    public static String wordKind(Input input, int i) {
        if (i < 0 || i >= input.tokens().size() || input.tokens().get(i).shape() != Token.Shape.WORD) {
            return null;
        }
        String text = Spans.surface(input, i, i);
        String lower = text.toLowerCase(Locale.ROOT);
        String previous = Spans.lower(input, i - 1);
        List<Integer> sentence = Spans.sentenceTokens(input, i);
        boolean first = !sentence.isEmpty() && sentence.get(0) == i;
        if (lower.equals("null")) {
            return "value";
        }
        if (lower.equals("true") || lower.equals("false")) {
            boolean cue = previous != null && VALUE_CUES.contains(previous);
            boolean sentenceRule = first && ("when".equals(Spans.lower(input, i + 1))
                    || "if".equals(Spans.lower(input, i + 1)));
            if (cue || sentenceRule) {
                return "value";
            }
        }
        if (i >= 2 && Spans.surface(input, i - 2, i - 2).equals("@")
                && "param".equals(previous) && Spans.touches(input, i - 2)
                && !Spans.touches(input, i - 1)) {
            return "identifier";
        }
        if (i >= 3 && i + 1 < input.tokens().size()
                && Spans.surface(input, i - 3, i - 3).equals("{")
                && Spans.surface(input, i - 2, i - 2).equals("@")
                && "code".equals(previous)
                && Spans.surface(input, i + 1, i + 1).equals("}")
                && Spans.touches(input, i - 3) && Spans.touches(input, i - 2)) {
            return codeSpanKind(text);
        }
        if (text.length() == 1 && !text.equals("a") && !text.equals("I")
                && previous != null && META_CUES.contains(previous)) {
            return "identifier";
        }
        if (JDK_TYPES.contains(text) && !first) {
            return "type";
        }
        if (!text.matches("[A-Z][A-Z0-9_]*")) {
            return null;
        }
        if (EXCEPTION_ABBREVIATIONS.contains(text)) {
            return "type";
        }
        if (HTTP_METHODS.contains(text)) {
            return "constant";
        }
        boolean slashBefore = i >= 2 && Spans.surface(input, i - 1, i - 1).equals("/")
                && Spans.touches(input, i - 2) && Spans.touches(input, i - 1)
                && Spans.surface(input, i - 2, i - 2).matches("[A-Z][A-Z0-9_]*");
        boolean slashAfter = i + 2 < input.tokens().size() && Spans.surface(input, i + 1, i + 1).equals("/")
                && Spans.touches(input, i) && Spans.touches(input, i + 1)
                && Spans.surface(input, i + 2, i + 2).matches("[A-Z][A-Z0-9_]*");
        boolean returned = previous != null
                && List.of("return", "returns", "returned").contains(previous);
        if (slashBefore || slashAfter || returned) {
            return "constant";
        }
        boolean quoted = i > 0 && i + 1 < input.tokens().size()
                && Spans.surface(input, i - 1, i - 1).equals("\"")
                && Spans.surface(input, i + 1, i + 1).equals("\"")
                && Spans.touches(input, i - 1) && Spans.touches(input, i);
        return quoted ? "value" : null;
    }

    /**
     * The last token of the command outside a code span that token {@code i} starts, or -1 when it starts none: a WORD
     * token that is one of {@link #PROGRAMS}, in any case, right after a touching opening quote ({@code '},
     * {@code "}, U+2018 or U+201C) runs to the token before the closing quote ({@code '}, {@code "}, U+2019 or U+201D,
     * the one that matches), when at least one more token comes before it and it is no more than forty tokens away;
     * {@code git} without such a quote, followed by one of {@link #GIT_SUBCOMMANDS}, runs to that subcommand.
     */
    public static int commandEnd(Input input, int i) {
        if (i < 0 || i >= input.tokens().size() || input.tokens().get(i).shape() != Token.Shape.WORD) {
            return -1;
        }
        String program = Spans.lower(input, i);
        if (!PROGRAMS.contains(program)) {
            return -1;
        }
        if (i > 0 && Spans.touches(input, i - 1)) {
            String opening = Spans.surface(input, i - 1, i - 1);
            String closing = switch (opening) {
                case "'" -> "'";
                case "\"" -> "\"";
                case "\u2018" -> "\u2019";
                case "\u201c" -> "\u201d";
                default -> null;
            };
            if (closing != null) {
                int limit = Math.min(input.tokens().size() - 1, i + 40);
                for (int end = i + 2; end <= limit; end++) {
                    if (Spans.surface(input, end, end).equals(closing)) {
                        return end - 1;
                    }
                }
            }
        }
        if (program.equals("git") && i + 1 < input.tokens().size()
                && GIT_SUBCOMMANDS.contains(Spans.lower(input, i + 1))) {
            return i + 1;
        }
        return -1;
    }

    /**
     * Whether token {@code i} is a commit hash of digits only: a NUMBER token whose text is a {@link #DIGIT_HASH}, in a
     * sentence of the input that also holds a token starting with {@code commit} ({@code commit}, {@code committed},
     * {@code commits}) or the token {@code hash}, in any case.
     */
    public static boolean digitHash(Input input, int i) {
        if (i < 0 || i >= input.tokens().size()) {
            return false;
        }
        Token token = input.tokens().get(i);
        String text = Spans.surface(input, i, i);
        if (token.shape() != Token.Shape.NUMBER || !DIGIT_HASH.matcher(text).matches()) {
            return false;
        }
        String tokenId = token.id();
        for (var sentence : input.sentences()) {
            if (!sentence.tokenIds().contains(tokenId)) {
                continue;
            }
            for (String sentenceTokenId : sentence.tokenIds()) {
                for (int j = 0; j < input.tokens().size(); j++) {
                    if (!input.tokens().get(j).id().equals(sentenceTokenId)) {
                        continue;
                    }
                    String lower = Spans.surface(input, j, j).toLowerCase(Locale.ROOT);
                    if (lower.startsWith("commit") || lower.equals("hash")) {
                        return true;
                    }
                    break;
                }
            }
            return false;
        }
        return false;
    }

    /**
     * The text before a final {@code 's}, written with an ASCII apostrophe or with the right single quotation mark
     * U+2019; null when the text ends in neither or nothing comes before the ending.
     */
    public static String possessiveStem(String text) {
        if (text == null || text.length() <= 2) {
            return null;
        }
        if (text.endsWith("'s") || text.endsWith("\u2019s")) {
            return text.substring(0, text.length() - 2);
        }
        return null;
    }
}
