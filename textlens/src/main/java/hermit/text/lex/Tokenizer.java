package hermit.text.lex;

import hermit.text.model.Provenance;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import hermit.text.model.Token.Shape;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a text into tokens with shapes (milestone A1 of the annotation plan, section 4), in one left-to-right scan over
 * the unchanged string. Ranges are half-open UTF-16 indices. Whitespace ({@link Character#isWhitespace}) is never a
 * token, and every other character lies in exactly one token, so the tokens are a valid document's token list. Ids are
 * {@code t1}, {@code t2}, ... in text order; candidates are left empty for the tagger, and the provenance is
 * {@link #PROVENANCE}.
 *
 * <p>At each position every shape is tried and the longest match wins; on a tie, the shape that comes first in
 * {@link Shape} wins.
 * <ul>
 *   <li>URL: a scheme (a letter, then letters, digits, {@code + . -}), {@code ://} and anything up to whitespace;
 *       or, after no letter, digit or {@code _ . / @}, a host ({@link #HOST_DOMAINS}), an optional {@code :port}, no
 *       word character and an optional path ({@code 127.0.0.1}, {@code localhost:8080}, {@code example.org}).</li>
 *   <li>PATH: where no letter, digit or underscore comes before it, a run of letters, digits and
 *       {@code _ . ~ $ @ + * { } / -} with a letter in it, and either a {@code /} and (a leading {@code /},
 *       {@code ./}, {@code ../} or {@code ~/}, two or more slashes, or a file extension on the last segment), or no
 *       {@code /} and a name ending in one of {@link #FILE_EXTENSIONS} ({@code CodeFence.java}).</li>
 *   <li>MEMBER_REF: {@code Type#member}, or a dotted chain of identifiers one of which starts with a capital
 *       ({@code TaskPlan.Verdict}, {@code hermit.text.model.Token}); a parenthesis group that follows directly and
 *       closes on the same line is part of it ({@code ClaimsGate#claimsTextOf(String)}); not a lower-case word, a
 *       period and a {@link #SENTENCE_STARTS} word ({@code passed.Then}).</li>
 *   <li>FILE_LINE: a file name with an extension, a colon and a line number or a line range, then optionally a colon
 *       and a column number ({@code AgentLoop.java:1281}, {@code src/X.java:44-60}, {@code Reader.kt:14:9}).</li>
 *   <li>FLAG: where no letter, digit or underscore comes before it, {@code -} or {@code --} and a letter, then letters,
 *       digits, dots and hyphens, and an optional {@code =value} ({@code -Dhermit.x=y}, {@code --offline},
 *       {@code -q}).</li>
 *   <li>VERSION: three or more dotted numbers ({@code 2.21.1}), {@code v} and a number ({@code v3}, {@code v1.2}), a name
 *       with a dotted number ({@code Qwen3.8-27B}), or two dotted numbers right after one of the
 *       {@link #VERSION_CUES} on the same line ({@code Gradle 8.10}); without a cue, {@code 0.95} and {@code 1.10} are
 *       numbers, and the annotator's spans keep the alternative.</li>
 *   <li>HASH: 7 to 40 lower-case hex digits, with a digit and a letter, not inside a longer word.</li>
 *   <li>NUMBER: digits, with a sign where no letter, digit or closing bracket comes before it, thousands groups
 *       ({@code 3,500}), a fraction, an exponent ({@code 1e-9}) and a {@code k}, {@code K}, {@code M} or {@code B}
 *       suffix ({@code 78k}).</li>
 *   <li>IDENT: camel case ({@code processRequest}, {@code RepeatGuard}), a name with an underscore
 *       ({@code CHARS_PER_TOKEN}), a hyphenated name with a digit ({@code gpt-oss-20b}), or letters with digits
 *       ({@code x86}), but not an ordinal ({@code 3rd} is a WORD).</li>
 *   <li>CODE_SPAN: one or two backticks, up to the next run of exactly as many on the same line, both runs included;
 *       {@link #inside} tokenizes what lies between them.</li>
 *   <li>WORD: letters, with apostrophes and hyphens between letters ({@code don't}, {@code well-known}), an ordinal,
 *       a known abbreviation with its periods ({@link Abbreviations}), or a capitalised name with {@code .js}
 *       ({@code Node.js}). A word that ends in one of {@link #CLITICS} right after a letter, in any case and with a
 *       typographic apostrophe (U+2019) too, is two tokens: the word, with the shape that its text alone is given
 *       ({@code TreeEdit's}: an IDENT), then the clitic, a WORD ({@code we'll}: {@code we} and {@code 'll};
 *       {@code agent's}: {@code agent} and {@code 's}; the owner, 2026-11-05). A negated form stays one token
 *       ({@code don't}, {@code won't}).</li>
 *   <li>PUNCT: one of {@code . , ; : ! ? ( ) [ ] { } " '}, a typographic quote, dash or ellipsis; a run of periods, of
 *       hyphens or of backticks that opens no code span is one token.</li>
 *   <li>SYMBOL: any other code point, one at a time, except that a run of two or more of
 *       {@code + - * / = < > ! & | ^ ~ %} is one token ({@code ->}, {@code !=}).</li>
 * </ul>
 * A URL, PATH or FLAG never ends with a period, comma, colon, semicolon, question or exclamation mark, quote, or a
 * closing parenthesis, bracket, brace, {@code >} or {@code &gt;} it did not open. A combining mark belongs to the token
 * before it, and a surrogate pair is never split.
 */
public final class Tokenizer {

    /** Words that make a following two-part number a version, compared ignoring case. */
    public static final List<String> VERSION_CUES = List.of("version", "versions", "release", "releases", "java", "jdk",
            "gradle", "python", "kotlin", "node");

    /** The clitics that a word ends in and that become tokens of their own (the owner, 2026-11-05). */
    public static final List<String> CLITICS = List.of("'ll", "'re", "'ve", "'m", "'d", "'s");

    /** File extensions that make a name without a slash a PATH. */
    public static final List<String> FILE_EXTENSIONS = List.of("java", "kt", "md", "txt", "json", "jsonl", "xml", "yml",
            "yaml", "toml", "properties", "gradle", "py", "sh", "js", "ts", "html", "css", "csv", "log");

    /**
     * Capitalised words that start a sentence, which make {@code word.Word} two words and a period when they follow
     * the period with no space ({@code passed.Then}).
     */
    public static final List<String> SENTENCE_STARTS = List.of("The", "Then", "This", "That", "These", "Those", "It",
            "Its", "We", "They", "There", "Here", "A", "An", "In", "On", "At", "If", "When", "While", "But", "And",
            "So", "Now", "Next", "After", "Before", "Also", "No", "All", "Each", "Our");

    /**
     * Top-level domains of a host without a scheme. A host is {@code localhost}, an IPv4 address (not after one of the
     * {@link #VERSION_CUES}), or two or more lower-case labels of letters, digits and inner hyphens joined by periods,
     * the last one of these and the first not ({@code example.org} and {@code api.example.com} are hosts,
     * {@code org.example} is a package).
     */
    public static final List<String> HOST_DOMAINS = List.of("com", "org", "net", "io", "ai", "dev", "co");

    /** The provenance of every token. */
    public static final Provenance PROVENANCE = Provenance.rule("tokenizer");

    private final Abbreviations abbreviations;
    private static final String LETTER = "[\\p{L}\\p{M}]";
    private static final String ID = "[\\p{L}_$][\\p{L}\\p{M}\\p{N}_$]*";
    private static final Pattern URL_PATTERN = Pattern.compile(
            "[a-zA-Z][a-zA-Z0-9+.-]*://[^\\p{javaWhitespace}]+");
    private static final Pattern PATH_PATTERN = Pattern.compile("[\\p{L}\\p{M}\\p{N}_.~$@+*{}/-]+");
    private static final Pattern MEMBER_PATTERN = Pattern.compile(ID + "(?:#" + ID + "|(?:\\." + ID + ")+)");
    private static final Pattern FILE_LINE_PATTERN = Pattern.compile(
            "[\\p{L}\\p{M}\\p{N}_.~$@+*{}/-]+\\.[\\p{L}][\\p{L}\\p{N}]*:[0-9]+(?:-[0-9]+)?");
    private static final Pattern FLAG_PATTERN = Pattern.compile(
            "--?[\\p{L}][\\p{L}\\p{M}\\p{N}.-]*(?:=[^\\p{javaWhitespace}]+)?");
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?:[0-9]+(?:\\.[0-9]+){2,}|v[0-9]+(?:\\.[0-9]+)*"
                    + "|[\\p{L}][\\p{L}\\p{N}_-]*[0-9]+(?:\\.[0-9]+)+(?:-[\\p{L}\\p{N}]+)?)");
    private static final Pattern TWO_PART_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+");
    private static final Pattern HASH_PATTERN = Pattern.compile("[a-f0-9]{7,40}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "[+-]?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?[kKMB]?");
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_]*(?:-[\\p{L}\\p{N}_]+)*");
    private static final Pattern ORDINAL_PATTERN = Pattern.compile("[0-9]+(?:st|nd|rd|th)");
    private static final Pattern WORD_PATTERN = Pattern.compile(
            "[\\p{L}]" + LETTER + "*(?:['\u2019-][\\p{L}]" + LETTER + "*)*");
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "localhost|[0-9]+(?:\\.[0-9]+){3}|[a-z0-9]+(?:-[a-z0-9]+)*"
                    + "(?:\\.[a-z0-9]+(?:-[a-z0-9]+)*)+");
    private static final Pattern FILE_LINE_COLUMN_PATTERN = Pattern.compile(
            "[\\p{L}\\p{M}\\p{N}_.~$@+*{}/-]+\\.[\\p{L}][\\p{L}\\p{N}]*:[0-9]+(?:-[0-9]+)?(?::[0-9]+)?");

    /** A tokenizer that keeps these abbreviations in one token each. */
    public Tokenizer(Abbreviations abbreviations) {
        this.abbreviations = abbreviations;
    }

    /** A tokenizer with the standard abbreviations. */
    public static Tokenizer standard() {
        return new Tokenizer(Abbreviations.standard());
    }

    /** The tokens of {@code text}, in text order. */
    public List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            int cp = text.codePointAt(at);
            if (Character.isWhitespace(cp)) {
                at += Character.charCount(cp);
                continue;
            }
            int end = at;
            Shape best = Shape.SYMBOL;
            for (Shape shape : Shape.values()) {
                int candidate = candidateEnd(shape, text, at);
                if (candidate > end) {
                    end = candidate;
                    best = shape;
                }
            }
            while (end < text.length() && mark(text.codePointAt(end))) {
                end += Character.charCount(text.codePointAt(end));
            }
            int cliticStart = end;
            if (best == Shape.WORD) {
                String normalized = text.substring(at, end).toLowerCase(Locale.ROOT).replace('\u2019', '\'');
                for (String clitic : CLITICS) {
                    if (normalized.endsWith(clitic)) {
                        int candidate = end - clitic.length();
                        if (candidate > at && Character.isLetter(text.codePointBefore(candidate))) {
                            cliticStart = candidate;
                            break;
                        }
                    }
                }
            }
            if (cliticStart < end) {
                Shape ownerShape = shapeOf(text.substring(at, cliticStart));
                tokens.add(new Token("t" + (tokens.size() + 1), new TextRange(at, cliticStart), ownerShape,
                        List.of(), false, PROVENANCE));
                tokens.add(new Token("t" + (tokens.size() + 1), new TextRange(cliticStart, end), Shape.WORD,
                        List.of(), false, PROVENANCE));
            } else {
                tokens.add(new Token("t" + (tokens.size() + 1), new TextRange(at, end), best,
                        List.of(), false, PROVENANCE));
            }
            at = end;
        }
        return List.copyOf(tokens);
    }

    /**
     * The tokens between a CODE_SPAN token's backticks, with ranges into the whole text and ids made of the span's id,
     * a period and a number ({@code t3.1}, {@code t3.2}, ...); an empty list for a token of any other shape.
     */
    public List<Token> inside(String text, Token codeSpan) {
        if (codeSpan.shape() != Shape.CODE_SPAN) {
            return List.of();
        }
        int start = codeSpan.range().start();
        int end = codeSpan.range().end();
        while (end > start && mark(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        int width = 0;
        while (start + width < end && text.charAt(start + width) == '`') {
            width++;
        }
        int offset = start + width;
        List<Token> result = new ArrayList<>();
        for (Token token : tokenize(text.substring(offset, end - width))) {
            TextRange range = new TextRange(offset + token.range().start(), offset + token.range().end());
            result.add(new Token(codeSpan.id() + "." + (result.size() + 1), range, token.shape(),
                    token.candidates(), token.guessed(), token.provenance()));
        }
        return List.copyOf(result);
    }

    /** The shape of {@code text} when the tokenizer reads all of it as one token, or null when it does not. */
    public Shape shapeOf(String text) {
        List<Token> tokens = tokenize(text);
        if (tokens.size() != 1) {
            return null;
        }
        Token token = tokens.get(0);
        return token.range().start() == 0 && token.range().end() == text.length() ? token.shape() : null;
    }

    private static int matchedEnd(Pattern pattern, String text, int at) {
        Matcher matcher = pattern.matcher(text);
        matcher.region(at, text.length());
        return matcher.lookingAt() ? matcher.end() : at;
    }

    private static boolean wordBefore(String text, int at) {
        if (at == 0) {
            return false;
        }
        int cp = text.codePointBefore(at);
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    private static boolean mark(int cp) {
        int kind = Character.getType(cp);
        return kind == Character.NON_SPACING_MARK || kind == Character.COMBINING_SPACING_MARK
                || kind == Character.ENCLOSING_MARK;
    }

    private static boolean quote(int cp) {
        return cp == '\'' || cp == '"' || cp == '\u2018' || cp == '\u2019'
                || cp == '\u201c' || cp == '\u201d' || cp == '\u00ab' || cp == '\u00bb';
    }

    private static int trimEnd(String text, int start, int end) {
        while (end > start) {
            if (end - start >= 4 && text.startsWith("&gt;", end - 4)) {
                if (angleBalance(text, start, end) < 0) {
                    end -= 4;
                    continue;
                }
                break;
            }
            int cp = text.codePointBefore(end);
            boolean remove = ".,:;?!".indexOf(cp) >= 0 || quote(cp);
            int opening = cp == ')' ? '(' : cp == ']' ? '[' : cp == '}' ? '{' : -1;
            if (cp == '>') {
                remove = angleBalance(text, start, end) < 0;
            } else if (opening >= 0) {
                int balance = 0;
                for (int i = start; i < end;) {
                    int current = text.codePointAt(i);
                    if (current == opening) {
                        balance++;
                    } else if (current == cp) {
                        balance--;
                    }
                    i += Character.charCount(current);
                }
                remove = balance < 0;
            }
            if (!remove) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return end;
    }

    private static int pathEnd(String text, int at) {
        if (wordBefore(text, at)) {
            return at;
        }
        int end = trimEnd(text, at, matchedEnd(PATH_PATTERN, text, at));
        String value = text.substring(at, end);
        if (!value.codePoints().anyMatch(Character::isLetter)) {
            return at;
        }
        int slash = value.lastIndexOf('/');
        int dot = value.lastIndexOf('.');
        if (slash < 0) {
            if (dot < 0 || !FILE_EXTENSIONS.contains(value.substring(dot + 1))) {
                return at;
            }
            boolean capitalisedJs = value.substring(dot + 1).equals("js")
                    && Character.isUpperCase(value.codePointAt(0));
            return capitalisedJs ? at : end;
        }
        boolean extension = dot > slash + 1 && dot + 1 < value.length()
                && value.substring(dot + 1).codePoints().allMatch(Character::isLetterOrDigit);
        boolean leading = value.startsWith("/") || value.startsWith("./")
                || value.startsWith("../") || value.startsWith("~/");
        return leading || value.indexOf('/') != slash || extension ? end : at;
    }

    private static int memberEnd(String text, int at) {
        int end = matchedEnd(MEMBER_PATTERN, text, at);
        if (end == at) {
            return at;
        }
        String value = text.substring(at, end);
        String[] parts = value.split("\\.");
        if (parts.length == 2 && parts[1].equals("js")
                && Character.isUpperCase(parts[0].codePointAt(0))) {
            return at;
        }
        if (parts.length == 2 && Character.isLowerCase(parts[0].codePointAt(0))
                && SENTENCE_STARTS.contains(parts[1])) {
            return at;
        }
        boolean accepted = value.indexOf('#') >= 0;
        for (String part : parts) {
            if (!part.isEmpty() && Character.isUpperCase(part.codePointAt(0))) {
                accepted = true;
            }
        }
        if (!accepted) {
            return at;
        }
        if (end < text.length() && text.charAt(end) == '(') {
            int depth = 0;
            for (int i = end; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    break;
                }
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && --depth == 0) {
                    return i + 1;
                }
            }
        }
        return end;
    }

    private static boolean versionCue(String text, int at) {
        int end = at;
        while (end > 0) {
            int cp = text.codePointBefore(end);
            if (cp == '\n' || cp == '\r') {
                return false;
            }
            if (!Character.isWhitespace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        if (end == at) {
            return false;
        }
        int start = end;
        while (start > 0 && Character.isLetter(text.codePointBefore(start))) {
            start -= Character.charCount(text.codePointBefore(start));
        }
        if (wordBefore(text, start)) {
            return false;
        }
        String cue = text.substring(start, end);
        for (String known : VERSION_CUES) {
            if (known.equalsIgnoreCase(cue)) {
                return true;
            }
        }
        return false;
    }

    private static int hashEnd(String text, int at) {
        if (wordBefore(text, at)) {
            return at;
        }
        int end = matchedEnd(HASH_PATTERN, text, at);
        if (end == at || end < text.length()
                && (Character.isLetterOrDigit(text.codePointAt(end)) || text.charAt(end) == '_')) {
            return at;
        }
        String value = text.substring(at, end);
        return value.codePoints().anyMatch(Character::isDigit)
                && value.codePoints().anyMatch(Character::isLetter) ? end : at;
    }

    private static int numberEnd(String text, int at) {
        int cp = text.codePointAt(at);
        if ((cp == '+' || cp == '-') && at > 0) {
            int before = text.codePointBefore(at);
            if (Character.isLetterOrDigit(before) || ")]プ".indexOf(before) >= 0 || before == '}') {
                return at;
            }
        }
        return matchedEnd(NUMBER_PATTERN, text, at);
    }

    private static int identEnd(String text, int at) {
        int end = matchedEnd(NAME_PATTERN, text, at);
        String value = text.substring(at, end);
        if (value.isEmpty() || ORDINAL_PATTERN.matcher(value).matches()) {
            return at;
        }
        boolean letter = false;
        boolean digit = false;
        boolean camel = false;
        int previous = -1;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            letter |= Character.isLetter(cp);
            digit |= Character.isDigit(cp);
            camel |= previous >= 0 && Character.isLowerCase(previous) && Character.isUpperCase(cp);
            previous = cp;
            i += Character.charCount(cp);
        }
        return value.indexOf('_') >= 0 || letter && digit || camel ? end : at;
    }

    private static int codeSpanEnd(String text, int at) {
        if (text.charAt(at) != '`') {
            return at;
        }
        int openingEnd = at;
        while (openingEnd < text.length() && text.charAt(openingEnd) == '`') {
            openingEnd++;
        }
        int width = openingEnd - at;
        if (width > 2) {
            return at;
        }
        for (int i = openingEnd; i < text.length();) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') {
                return at;
            }
            if (ch != '`') {
                i++;
                continue;
            }
            int run = i;
            while (i < text.length() && text.charAt(i) == '`') {
                i++;
            }
            if (i - run == width) {
                return i;
            }
        }
        return at;
    }

    private static int punctEnd(String text, int at) {
        int cp = text.codePointAt(at);
        if (cp == '.' || cp == '-' || cp == '`') {
            int end = at + 1;
            while (end < text.length() && text.charAt(end) == cp) {
                end++;
            }
            return end;
        }
        boolean punctuation = ",;:!?()[]{}\"'".indexOf(cp) >= 0 || quote(cp)
                || cp == '\u2026' || Character.getType(cp) == Character.DASH_PUNCTUATION;
        return punctuation ? at + Character.charCount(cp) : at;
    }

    private static int symbolEnd(String text, int at) {
        String operators = "+-*/=<>!&|^~%";
        int end = at;
        while (end < text.length() && operators.indexOf(text.codePointAt(end)) >= 0) {
            end += Character.charCount(text.codePointAt(end));
        }
        return end - at >= 2 ? end : at + Character.charCount(text.codePointAt(at));
    }

    private int candidateEnd(Shape shape, String text, int at) {
        switch (shape) {
            case URL:
                return urlEnd(text, at);
            case PATH:
                return pathEnd(text, at);
            case MEMBER_REF:
                return memberEnd(text, at);
            case FILE_LINE:
                return matchedEnd(FILE_LINE_COLUMN_PATTERN, text, at);
            case FLAG:
                return wordBefore(text, at) ? at : trimEnd(text, at, matchedEnd(FLAG_PATTERN, text, at));
            case VERSION:
                int version = matchedEnd(VERSION_PATTERN, text, at);
                return versionCue(text, at) ? Math.max(version, matchedEnd(TWO_PART_PATTERN, text, at)) : version;
            case HASH:
                return hashEnd(text, at);
            case NUMBER:
                return numberEnd(text, at);
            case IDENT:
                return identEnd(text, at);
            case CODE_SPAN:
                return codeSpanEnd(text, at);
            case WORD:
                int word = Math.max(matchedEnd(WORD_PATTERN, text, at), matchedEnd(ORDINAL_PATTERN, text, at));
                word = Math.max(word, at + abbreviations.matchAt(text, at));
                int product = matchedEnd(MEMBER_PATTERN, text, at);
                if (product > at) {
                    String value = text.substring(at, product);
                    int dot = value.indexOf('.');
                    if (dot > 0 && value.substring(dot + 1).equals("js")
                            && Character.isUpperCase(value.codePointAt(0))) {
                        word = Math.max(word, product);
                    }
                }
                return word;
            case PUNCT:
                return punctEnd(text, at);
            case SYMBOL:
                return symbolEnd(text, at);
            default:
                return at;
        }
    }

    private static boolean hostAccepted(String host, String text, int at) {
        if (host.equals("localhost")) {
            return true;
        }
        String[] labels = host.split("\\.");
        boolean numeric = host.codePoints().allMatch(cp -> cp == '.' || cp >= '0' && cp <= '9');
        if (numeric) {
            if (labels.length != 4 || versionCue(text, at)) {
                return false;
            }
            for (String label : labels) {
                if (label.length() > 3 || Integer.parseInt(label) > 255) {
                    return false;
                }
            }
            return true;
        }
        return labels.length >= 2 && !HOST_DOMAINS.contains(labels[0])
                && HOST_DOMAINS.contains(labels[labels.length - 1]);
    }

    private static int urlEnd(String text, int at) {
        int scheme = matchedEnd(URL_PATTERN, text, at);
        if (scheme > at) {
            return trimEnd(text, at, scheme);
        }
        if (wordBefore(text, at) || at > 0 && "./@".indexOf(text.codePointBefore(at)) >= 0) {
            return at;
        }
        int end = matchedEnd(HOST_PATTERN, text, at);
        if (end == at || !hostAccepted(text.substring(at, end), text, at)) {
            return at;
        }
        if (end < text.length() && text.charAt(end) == ':') {
            int port = end + 1;
            while (port < text.length() && text.charAt(port) >= '0' && text.charAt(port) <= '9') {
                port++;
            }
            if (port > end + 1) {
                end = port;
            }
        }
        if (end < text.length()) {
            int next = text.codePointAt(end);
            if (Character.isLetterOrDigit(next) || mark(next) || next == '_') {
                return at;
            }
            if (next == '.' && end + 1 < text.length()) {
                int following = text.codePointAt(end + 1);
                if (Character.isLetterOrDigit(following) || following == '_') {
                    return at;
                }
            }
            if (next == '/') {
                while (end < text.length() && !Character.isWhitespace(text.codePointAt(end))) {
                    end += Character.charCount(text.codePointAt(end));
                }
            }
        }
        return trimEnd(text, at, end);
    }

    private static int angleBalance(String text, int start, int end) {
        int balance = 0;
        for (int i = start; i < end;) {
            if (i + 4 <= end && text.startsWith("&lt;", i)) {
                balance++;
                i += 4;
            } else if (i + 4 <= end && text.startsWith("&gt;", i)) {
                balance--;
                i += 4;
            } else {
                int cp = text.codePointAt(i);
                if (cp == '<') {
                    balance++;
                } else if (cp == '>') {
                    balance--;
                }
                i += Character.charCount(cp);
            }
        }
        return balance;
    }
}
