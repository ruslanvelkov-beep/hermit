package hermit.text.lex;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hermit.text.model.Layout;
import hermit.text.model.Sentence;
import hermit.text.model.Tag;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The layout rules (the owner, 2026-11-06): a text's lines are read before any token, as a lexer settles its contexts
 * first, and each line gets its tags ({@link Layout.LineTag}) and its blocks. A line's text is the text between two
 * line feeds, without a carriage return at its end; it is blank when it holds only whitespace; its indentation counts
 * columns, a tab to the next multiple of 4; it is free while it is not blank and has no tag yet. The stages run in
 * order, each on the lines that are still free, unless it says otherwise:
 * <ol>
 *   <li>{@link #binary}: a text that is no text is BINARY as a whole, and nothing else runs;</li>
 *   <li>{@link #contexts}: front matter, fenced code, HTML comments;</li>
 *   <li>{@link #code}: code without a fence;</li>
 *   <li>{@link #markers}: page breaks, columns, headings, rules, tables, quotes;</li>
 *   <li>{@link #lists}: bulleted and numbered list items and their text;</li>
 *   <li>{@link #titles}: the titles that no marker makes certain;</li>
 *   <li>{@link #paragraphs}: the other lines, as paragraphs;</li>
 *   <li>{@link #blocks}: the blocks of the titles, list items, table rows, quotes, rules and page breaks.</li>
 * </ol>
 * Blocks come ordered by their first line, then the longer first, then in the order the stages made them; their ids
 * are {@code b1}, {@code b2}, ... in that order. After POS tagging, {@link #settle} settles the lines that may be a
 * title or part of a paragraph, and {@link #lowerCaseReadings} gives the title words read in lower case.
 */
public final class LayoutAnalyzer {

    /** Words a title in title case may write in lower case, in lower case. */
    public static final Set<String> SMALL_WORDS = Set.of("a", "an", "the", "of", "for", "in", "on", "at", "to", "and",
            "or", "but", "nor", "with", "by", "from", "vs", "vs.", "via", "as", "into", "per", "than", "up", "off",
            "over");
    /** A Roman numeral in capitals, not empty. */
    public static final String ROMAN = "(?=[MDCLXVI])(?:M{0,3}(?:CM|CD|D?C{0,3})(?:XC|XL|L?X{0,3})(?:IX|IV|V?I{0,3}))";
    /** A code fence: three or more backticks or tildes, then an optional language. */
    public static final Pattern FENCE = Pattern.compile("(?U)^ {0,3}(`{3,}|~{3,})\\s*([\\w+#.-]*)");
    /** An ATX heading: one to six {@code #}, then whitespace or the line's end. */
    public static final Pattern ATX = Pattern.compile("(?U)^ {0,3}(#{1,6})(?:\\s+|$)");
    /** A setext underline: a line of {@code =} or of {@code -} only. */
    public static final Pattern SETEXT = Pattern.compile("(?U)^ {0,3}(=+|-+)\\s*$");
    /** A horizontal rule: three or more of one of {@code - * _ =}, spaces allowed between. */
    public static final Pattern RULE = Pattern.compile("(?U)^ {0,3}([-*_=])(?:\\s*\\1){2,}\\s*$");
    /** A bullet: {@code -}, {@code *}, {@code +}, or a bullet character, then whitespace and text. */
    public static final Pattern BULLET = Pattern.compile("(?U)^(\\s*)([-*+•◦‣▪])\\s+(?=\\S)");
    /**
     * A label: a number ({@code 7}, {@code 7.1}), a Roman numeral in capitals or in lower case, or one letter; then
     * optionally {@code .} or {@code )}; then whitespace and text.
     */
    public static final Pattern LABEL = Pattern.compile("(?U)^(\\s*)(\\d{1,3}(?:\\.\\d{1,3})*|" + ROMAN + "|"
            + ROMAN.toLowerCase(Locale.ROOT) + "|[A-Za-z])([.)])?(\\s+)(?=\\S)");
    /** A line that is all bold: {@code **...**} or {@code __...__}, an optional colon after it. */
    public static final Pattern BOLD_LINE = Pattern.compile("(?U)^\\s*(\\*\\*|__)[^*_].*?\\1:?\\s*$");
    /** A table row: a line starting with {@code |}, or a grid table's border. */
    public static final Pattern TABLE = Pattern.compile("(?U)^\\s*(\\||\\+[-=+]{3,})");
    /** A quotation line: starting with {@code >}. */
    public static final Pattern QUOTE = Pattern.compile("(?U)^\\s*>");
    /** A line that starts like code. */
    public static final Pattern CODE_START = Pattern.compile("(?U)^\\s*(import |from \\S+ import |package |public "
            + "|private |protected |static |class |interface |def |return\\b|if \\(|for \\(|while \\(|} *else"
            + "|try *\\{|catch *\\(|function |const |let |var |#include|#!/|fn |func |val |SELECT |INSERT |CREATE "
            + "|UPDATE |DELETE |@\\w+|\\$ |\\./\\S|[A-Za-z_][\\w.]*\\(.*\\);\\s*$)");
    /** A comment line of code: {@code //}, {@code -- }, {@code /*}, {@code * }, a closing comment, or {@code #}. */
    public static final Pattern COMMENT_LINE = Pattern.compile("(?U)^\\s*(//|--\\s|/\\*|\\*\\s|\\*/|#(?!#)\\s?\\S)");
    /** A field line: a key and a colon at its start, or two spaces between two characters. */
    public static final Pattern FIELD = Pattern.compile("(?U)^\\s*[^\\s:]+:\\s|\\S {2}\\s*\\S");
    /** A page's footer: a line ending in {@code [Page N]}. */
    public static final Pattern PAGE_FOOTER = Pattern.compile("(?U)\\[Page \\d+\\]\\s*$");
    /** A page's running header: three columns apart by three or more spaces, or a line starting {@code RFC N}. */
    public static final Pattern PAGE_HEADER = Pattern.compile("(?U)\\S\\s{3,}\\S.*\\s{3,}\\S|^RFC \\d+");
    /** What makes a text Markdown: a {@code #} heading, a fence, a link, bold text or a bullet. */
    public static final Pattern MARKDOWN = Pattern.compile(
            "(?mU)^ {0,3}(#{1,6} |```|~~~)|\\[[^\\]]+\\]\\([^)]+\\)|\\*\\*[^*]+\\*\\*|^\\s*[-*+] \\S");
    /** A sentence's end at a line's end: {@code .}, {@code !} or {@code ?}, then closing quotes or brackets. */
    public static final Pattern SENTENCE_END = Pattern.compile("(?U)[.!?][\"')\\]]*$");
    /** The languages {@link #guessLanguage} knows, in order, each with the pattern whose matches count for it. */
    public static final List<Map.Entry<String, Pattern>> LANGUAGES = List.of(
            Map.entry("java", Pattern.compile(
                    "(?mU)\\b(public|private) (static |final )*(class|void|record|interface)\\b|System\\.out"
                            + "|import java\\.")),
            Map.entry("python", Pattern.compile(
                    "(?mU)^\\s*(def \\w+\\(|from \\S+ import|import \\w+$|class \\w+(\\(.*\\))?:)")),
            Map.entry("javascript", Pattern.compile(
                    "(?mU)\\b(function\\s*\\w*\\(|const \\w+ =|let \\w+ =|=>|require\\()")),
            Map.entry("shell", Pattern.compile(
                    "(?mU)^\\s*(\\$ |sudo |cd |export |echo |git |npm |pip |uv |curl |\\./)")),
            Map.entry("json", Pattern.compile("(?mU)^\\s*[\\[{]\\s*$|^\\s*\"[^\"]+\"\\s*:")),
            Map.entry("yaml", Pattern.compile("(?mU)^\\s*[\\w-]+:\\s+\\S|^\\s*- \\w+:")),
            Map.entry("xml", Pattern.compile("(?mU)^\\s*<[\\w?!/]")),
            Map.entry("sql", Pattern.compile("(?mU)\\b(SELECT|INSERT INTO|CREATE TABLE)\\b")),
            Map.entry("asn1", Pattern.compile("(?mU)::=")),
            Map.entry("c", Pattern.compile("(?mU)#include\\s*<")),
            Map.entry("go", Pattern.compile("(?mU)^\\s*func \\w+\\(|^package \\w+$")),
            Map.entry("rust", Pattern.compile("(?mU)\\bfn \\w+\\(|\\blet mut\\b")));

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final LayoutAnalyzer STANDARD = new LayoutAnalyzer();

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    static String trim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && space(s.charAt(start))) {
            start++;
        }
        while (end > start && space(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean cased(String word, boolean upper) {
        boolean any = false;
        for (int at = 0; at < word.length(); at++) {
            char c = word.charAt(at);
            if (Character.isUpperCase(c) || Character.isLowerCase(c)) {
                any = true;
                if (upper ? Character.isLowerCase(c) : Character.isUpperCase(c)) {
                    return false;
                }
            }
        }
        return any;
    }

    private static int mostCommon(List<Integer> values) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        values.forEach(value -> counts.merge(value, 1, Integer::sum));
        int best = 0;
        int most = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > most) {
                most = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /** {@code regex} compiled with {@code flags} and Unicode character classes ({@code \\s} holds a no-break space). */
    private static Pattern pattern(String regex, int flags) {
        return Pattern.compile(regex, flags | Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static Pattern pattern(String regex) {
        return pattern(regex, 0);
    }

    /** The indentation of {@code line} in columns: a space is one, a tab goes to the next multiple of 4. */
    public static int indentOf(String line) {
        int indent = 0;
        int offset = 0;
        while (offset < line.length()) {
            char next = line.charAt(offset++);
            if (next == '\t') {
                indent = (indent / 4 + 1) * 4;
            } else if (next == ' ') {
                indent++;
            } else {
                break;
            }
        }
        return indent;
    }

    /**
     * Whether {@code text} is no text: it holds a NUL character, or control characters other than tab, line feed,
     * carriage return and form feed make more than 2% of it.
     */
    public static boolean binary(String text) {
        if (text.indexOf(0) >= 0) {
            return true;
        }
        long controls = 0;
        for (int at = 0; at < text.length(); at++) {
            char value = text.charAt(at);
            if (Character.isISOControl(value) && value != '\t' && value != '\n'
                    && value != '\r' && value != '\f') {
                controls++;
            }
        }
        return controls * 100 > (long) text.length() * 2;
    }

    /**
     * Whether {@code line} is structure, never code: one of {@link #ATX}, {@link #RULE}, {@link #SETEXT},
     * {@link #TABLE}, {@link #BULLET}, {@link #QUOTE} and {@link #BOLD_LINE} matches from the start of the line as
     * written (so a {@code #} indented 4 or more columns is no heading).
     */
    public static boolean structural(String line) {
        return ATX.matcher(line).lookingAt() || RULE.matcher(line).lookingAt() || SETEXT.matcher(line).lookingAt()
                || TABLE.matcher(line).lookingAt() || BULLET.matcher(line).lookingAt()
                || QUOTE.matcher(line).lookingAt() || BOLD_LINE.matcher(line).lookingAt();
    }

    /**
     * Whether {@code line} is code-like. It is not when it is blank or {@link #structural}. It is when its trimmed text
     * starts like code ({@link #CODE_START}). Else, with each code span ({@code `...`}) read as one letter (prose's
     * inline code): when it ends in {@code ;} and holds one of {@code ( ) { } [ ] = < >} or has at most 3 words (runs
     * of ASCII letters); ends in <code>{</code> or <code>}</code>, or is <code>)</code>, <code>]</code> or
     * <code>},</code>; holds {@code ::=} or is {@code BEGIN} or {@code END} or ends in {@code " BEGIN"}; holds
     * {@code ==}, {@code !=}, {@code =>}, {@code ->}, {@code ::}, {@code &&}, {@code ||}, {@code ()}, {@code []} or a
     * call on a member ({@code a.b(}) among four or more symbols (characters that are no letter, digit or
     * whitespace); or is longer than 8 characters, of which symbols are more than 35%, with fewer than 4 words.
     */
    public static boolean codeLike(String line) {
        String body = trim(line);
        if (body.isEmpty() || structural(line)) {
            return false;
        }
        if (CODE_START.matcher(body).find()) {
            return true;
        }
        body = body.replaceAll("`[^`]*`", "x");
        Matcher word = pattern("[A-Za-z]+").matcher(body);
        int words = 0;
        while (word.find()) {
            words++;
        }
        long symbols = body.codePoints().filter(value -> !Character.isLetterOrDigit(value)
                && !Character.isWhitespace(value)).count();
        if (body.endsWith(";") && (words <= 3 || pattern("[(){}\\[\\]=<>]").matcher(body).find())) {
            return true;
        }
        if (body.endsWith("{") || body.endsWith("}") || body.equals(")") || body.equals("]")
                || body.equals("},") || body.contains("::=") || body.equals("BEGIN") || body.equals("END")
                || body.endsWith(" BEGIN")) {
            return true;
        }
        boolean operator = false;
        for (String token : List.of("==", "!=", "=>", "->", "::", "&&", "||", "()", "[]")) {
            operator |= body.contains(token);
        }
        boolean memberCall = pattern("[A-Za-z_$][\\w$]*\\.[A-Za-z_$][\\w$]*\\(").matcher(body).find();
        return symbols >= 4 && (operator || memberCall)
                || body.length() > 8 && symbols * 100 > (long) body.length() * 35 && words < 4;
    }

    /**
     * The language of {@code lines} of code: the entry of {@link #LANGUAGES} whose pattern matches most often in the
     * lines joined by line feeds (the patterns read {@code ^} and {@code $} at each line), the first such entry on a
     * tie; {@code unknown} when none matches.
     */
    public static String guessLanguage(List<String> lines) {
        String text = String.join("\n", lines);
        String language = "unknown";
        int best = 0;
        for (Map.Entry<String, Pattern> entry : LANGUAGES) {
            int matches = 0;
            Matcher matcher = entry.getValue().matcher(text);
            while (matcher.find()) {
                matches++;
            }
            if (matches > best) {
                best = matches;
                language = entry.getKey();
            }
        }
        return language;
    }

    /** The value of a Roman numeral in capitals or in lower case ({@code VII} 7, {@code xiv} 14); null when invalid. */
    public static Integer romanValue(String numeral) {
        if (numeral.isEmpty()) {
            return null;
        }
        boolean lower = numeral.equals(numeral.toLowerCase(Locale.ROOT));
        boolean upper = numeral.equals(numeral.toUpperCase(Locale.ROOT));
        String capitals = numeral.toUpperCase(Locale.ROOT);
        if ((!lower && !upper) || !capitals.matches(ROMAN)) {
            return null;
        }
        int value = 0;
        int previous = 0;
        for (int index = capitals.length() - 1; index >= 0; index--) {
            int current = switch (capitals.charAt(index)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            value += current < previous ? -current : current;
            previous = current;
        }
        return value;
    }

    /**
     * Whether {@code words} are in title case: the words not in {@link #SMALL_WORDS} (compared in lower case) are at
     * least one, each starts with an upper-case letter, and one of them of two or more characters has only lower-case
     * letters after its first (and at least one).
     */
    public static boolean titleCase(List<String> words) {
        boolean significant = false;
        boolean ordinary = false;
        for (String word : words) {
            if (SMALL_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                continue;
            }
            significant = true;
            if (word.isEmpty() || !Character.isUpperCase(word.charAt(0))) {
                return false;
            }
            if (word.length() >= 2 && cased(word.substring(1), false)) {
                ordinary = true;
            }
        }
        return significant && ordinary;
    }

    /** Whether {@code words} are in capitals: one or more of them have two or more characters, and all of those do. */
    public static boolean allCapitals(List<String> words) {
        boolean found = false;
        for (String word : words) {
            if (word.length() < 2) {
                continue;
            }
            found = true;
            if (!cased(word, true)) {
                return false;
            }
        }
        return found;
    }

    /**
     * The typical width of {@code lines}: of their non-blank lines' lengths (right-trimmed) in ascending order, the one
     * at 90% of the way (the index {@code (int) (0.9 * (n - 1))}); 0 without non-blank lines.
     */
    public static int typicalWidth(List<String> lines) {
        List<Integer> widths = new ArrayList<>();
        for (String line : lines) {
            int end = line.length();
            while (end > 0 && space(line.charAt(end - 1))) {
                end--;
            }
            if (!trim(line).isEmpty()) {
                widths.add(end);
            }
        }
        Collections.sort(widths);
        return widths.isEmpty() ? 0 : widths.get((int) (0.9 * (widths.size() - 1)));
    }

    /**
     * Whether {@code lines} are double-spaced: they have 6 or more non-blank lines, and of the steps from one non-blank
     * line to the next, 80% or more pass exactly one blank line.
     */
    public static boolean doubleSpaced(List<String> lines) {
        int nonBlank = 0;
        int steps = 0;
        int separated = 0;
        int previous = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (trim(lines.get(index)).isEmpty()) {
                continue;
            }
            nonBlank++;
            if (previous >= 0) {
                steps++;
                if (index == previous + 2 && trim(lines.get(previous + 1)).isEmpty()) {
                    separated++;
                }
            }
            previous = index;
        }
        return nonBlank >= 6 && separated * 10 >= steps * 8;
    }

    /** One reading of a label: its kind, depth, parent and value. */
    record Reading(String kind, int depth, String parent, int value) {}

    /** A line's label for {@link #lists}: its readings, the characters before it, and its text. */
    record Label(List<Reading> readings, int indent, String text) {

        static Label of(String line) {
            Matcher matcher = LABEL.matcher(line);
            if (!matcher.find()) {
                return null;
            }
            String label = matcher.group(2);
            String punctuation = matcher.group(3);
            List<Reading> readings = new ArrayList<>();
            if (label.chars().allMatch(Character::isDigit) || label.indexOf('.') >= 0) {
                String[] parts = label.split("\\.");
                int value = Integer.parseInt(parts[parts.length - 1]);
                String parent = parts.length == 1 ? "" : String.join(".", Arrays.copyOf(parts, parts.length - 1));
                readings.add(new Reading("arabic", parts.length, parent, value));
            } else {
                Integer roman = romanValue(label);
                boolean punctuated = punctuation != null;
                boolean letter = label.length() == 1 && punctuated;
                boolean numeral = roman != null && (label.length() > 1 || punctuated);
                String suffix = Character.isUpperCase(label.charAt(0)) ? "upper" : "lower";
                Reading letterReading = new Reading("letter-" + suffix, 1, "",
                        Character.toLowerCase(label.charAt(0)) - 'a' + 1);
                Reading romanReading = new Reading("roman-" + suffix, 1, "", roman == null ? 0 : roman);
                if (letter && numeral && !label.equalsIgnoreCase("i")) {
                    readings.add(letterReading);
                    readings.add(romanReading);
                } else {
                    if (numeral) {
                        readings.add(romanReading);
                    }
                    if (letter) {
                        readings.add(letterReading);
                    }
                }
            }
            if (readings.isEmpty()) {
                return null;
            }
            return new Label(List.copyOf(readings), matcher.group(1).length(), label);
        }
    }

    /** A block while the stages run. */
    private record Draft(Layout.Kind kind, int first, int last, ObjectNode attributes) {}

    /** A line while the stages run. */
    static final class Row {
        final int index;
        final int start;
        final String text;
        final String trimmed;
        final boolean blank;
        final int indent;
        final Set<Layout.LineTag> tags = EnumSet.noneOf(Layout.LineTag.class);
        final ObjectNode attributes = JSON.objectNode();
        Label label;
        Integer item;
        int itemIndent;

        Row(int index, int start, String text) {
            this.index = index;
            this.start = start;
            this.text = text;
            this.trimmed = trim(text);
            this.blank = trimmed.isEmpty();
            this.indent = indentOf(text);
        }

        boolean free() {
            return tags.isEmpty() && !blank;
        }
    }

    /** The lines and the blocks while the stages run. */
    static final class Page {
        final String text;
        final List<Row> rows = new ArrayList<>();
        final List<Draft> drafts = new ArrayList<>();

        Page(String text) {
            this.text = text;
            int start = 0;
            String[] raw = text.split("\n", -1);
            for (int index = 0; index < raw.length; index++) {
                String line = raw[index];
                int end = line.length();
                while (end > 0 && line.charAt(end - 1) == '\r') {
                    end--;
                }
                rows.add(new Row(index, start, line.substring(0, end)));
                start += line.length() + 1;
            }
        }

        void tag(int first, int last, Layout.LineTag tag) {
            for (int index = first; index <= last; index++) {
                rows.get(index).tags.add(tag);
            }
        }

        void block(Layout.Kind kind, int first, int last, ObjectNode attributes) {
            drafts.add(new Draft(kind, first, last, attributes));
        }

        List<String> texts(int first, int last) {
            List<String> texts = new ArrayList<>();
            for (int index = first; index <= last && index < rows.size(); index++) {
                texts.add(rows.get(index).text);
            }
            return texts;
        }

        Layout layout() {
            List<Layout.Line> lines = new ArrayList<>();
            for (Row row : rows) {
                lines.add(new Layout.Line(row.index, new TextRange(row.start, row.start + row.text.length()),
                        row.indent, Collections.unmodifiableSet(EnumSet.copyOf(row.tags))));
            }
            List<Draft> ordered = new ArrayList<>(drafts);
            ordered.sort(Comparator.comparingInt(Draft::first)
                    .thenComparing(Comparator.comparingInt(Draft::last).reversed()));
            List<Layout.Block> blocks = new ArrayList<>();
            for (Draft draft : ordered) {
                blocks.add(new Layout.Block("b" + (blocks.size() + 1), draft.kind(),
                        new TextRange(lines.get(draft.first()).range().start(), lines.get(draft.last()).range().end()),
                        draft.first(), draft.last(), draft.attributes()));
            }
            return new Layout(List.copyOf(lines), List.copyOf(blocks));
        }
    }

    /**
     * The contexts, top to bottom, as a lexer reads strings and comments: front matter (a first line {@code ---} up
     * to the next line {@code ---} or {@code ...}) is METADATA; a fenced code block (from a line whose trimmed text
     * {@link #FENCE} matches whole, the fence and an optional language and nothing else, at any indentation, up to the
     * next line whose trimmed text is that fence character only, at least as long as the fence, or the text's end) is
     * CODE, its block's attributes
     * {@code language} (the fence's language, else {@link #guessLanguage} of the lines between the fences),
     * {@code fenced} true and {@code closed}; an HTML comment (a line starting {@code <!--} up to the line holding
     * {@code -->}, or the text's end) is COMMENT.
     */
    static void contexts(Page page) {
        int index = 0;
        if (!page.rows.isEmpty() && page.rows.get(0).trimmed.equals("---")) {
            int last = 1;
            while (last < page.rows.size() && !page.rows.get(last).trimmed.equals("---")
                    && !page.rows.get(last).trimmed.equals("...")) {
                last++;
            }
            if (last < page.rows.size()) {
                page.tag(0, last, Layout.LineTag.METADATA);
                page.block(Layout.Kind.METADATA, 0, last, JSON.objectNode());
                index = last + 1;
            }
        }
        while (index < page.rows.size()) {
            Row row = page.rows.get(index);
            Matcher fence = FENCE.matcher(row.trimmed);
            if (row.tags.isEmpty() && fence.matches()) {
                char character = row.trimmed.charAt(0);
                int length = 0;
                while (length < row.trimmed.length() && row.trimmed.charAt(length) == character) {
                    length++;
                }
                String language = trim(row.trimmed.substring(length));
                int last = index + 1;
                boolean closed = false;
                while (last < page.rows.size()) {
                    String candidate = page.rows.get(last).trimmed;
                    int count = 0;
                    while (count < candidate.length() && candidate.charAt(count) == character) {
                        count++;
                    }
                    if (count >= length && candidate.substring(count).trim().isEmpty()) {
                        closed = true;
                        break;
                    }
                    last++;
                }
                if (!closed) {
                    last = page.rows.size() - 1;
                }
                if (language.isEmpty()) {
                    language = guessLanguage(page.texts(index + 1, closed ? last - 1 : last));
                }
                ObjectNode attributes = JSON.objectNode();
                attributes.put("language", language);
                attributes.put("fenced", true);
                attributes.put("closed", closed);
                page.tag(index, last, Layout.LineTag.CODE);
                page.block(Layout.Kind.CODE, index, last, attributes);
                index = last + 1;
            } else if (row.tags.isEmpty() && row.trimmed.startsWith("<!--")) {
                int last = index;
                while (last < page.rows.size() && !page.rows.get(last).text.contains("-->")) {
                    last++;
                }
                last = Math.min(last, page.rows.size() - 1);
                page.tag(index, last, Layout.LineTag.COMMENT);
                page.block(Layout.Kind.COMMENT, index, last, JSON.objectNode());
                index = last + 1;
            } else {
                index++;
            }
        }
    }

    /**
     * Code without a fence, a context too, so before any marker; every block is CODE with the attributes
     * {@code language} ({@link #guessLanguage} of its lines) and {@code fenced} false:
     * <ul>
     *   <li>a comment line is one that {@link #COMMENT_LINE} matches from the start of the line as written and that is
     *       not {@link #structural};</li>
     *   <li>when there are three or more free lines and {@link #codeLike} or comment lines make 60% or more of them,
     *       every line with no tag (blank lines too) is CODE, one block over all the lines with {@code whole} true;
     *       </li>
     *   <li>a reST literal block: after a free line ending in {@code ::} (not {@code :::}) that is no directive (does
     *       not start with {@code ..}), past blank lines, the lines up to the first non-blank line indented no deeper
     *       than that line, without the blank lines at the end ({@code literal} true);</li>
     *   <li>chunks, the runs of free lines between other lines (blank or tagged). A chunk is code when at least one
     *       of its lines is code-like and: it has two or more lines, of which the lines that are code-like or comment
     *       lines make 60% or more; or it has one line, which starts like code ({@link #CODE_START}). In Markdown
     *       ({@link #MARKDOWN}), where code without a fence is indented, a chunk is code instead when at least one of
     *       its lines is code-like, a blank line comes before it, each of its lines is indented 4 or more columns
     *       deeper than the most common indentation of the free lines (counted after the first rule and before the
     *       literal blocks; the first counted wins a tie), and, with two or more lines, the lines that are code-like
     *       or comment lines make 60% or more. Then, until nothing changes, a chunk whose lines are all code-like or
     *       comment lines and that is one blank line away from a code chunk is code too. Code chunks one blank line
     *       apart make one block, whose non-blank lines are CODE.</li>
     * </ul>
     */
    static void code(Page page, boolean markdown) {
        int free = 0;
        int codeLines = 0;
        for (Row row : page.rows) {
            if (row.free()) {
                free++;
                if (codeLike(row.text) || commentLine(row)) {
                    codeLines++;
                }
            }
        }
        if (free >= 3 && codeLines * 10 >= free * 6) {
            for (Row row : page.rows) {
                if (row.tags.isEmpty()) {
                    row.tags.add(Layout.LineTag.CODE);
                }
            }
            ObjectNode attributes = JSON.objectNode();
            attributes.put("language", guessLanguage(page.texts(0, page.rows.size() - 1)));
            attributes.put("fenced", false);
            attributes.put("whole", true);
            page.block(Layout.Kind.CODE, 0, page.rows.size() - 1, attributes);
            return;
        }
        List<Integer> indents = new ArrayList<>();
        for (Row row : page.rows) {
            if (row.free()) {
                indents.add(row.indent);
            }
        }
        int base = mostCommon(indents);
        for (int index = 0; index < page.rows.size(); index++) {
            Row lead = page.rows.get(index);
            if (!lead.free() || !lead.trimmed.endsWith("::") || lead.trimmed.endsWith(":::")
                    || lead.trimmed.startsWith("..")) {
                continue;
            }
            int first = index + 1;
            while (first < page.rows.size() && page.rows.get(first).blank) {
                first++;
            }
            if (first >= page.rows.size() || page.rows.get(first).indent <= lead.indent) {
                continue;
            }
            int last = first;
            while (last + 1 < page.rows.size()
                    && (page.rows.get(last + 1).blank || page.rows.get(last + 1).indent > lead.indent)) {
                last++;
            }
            while (last >= first && page.rows.get(last).blank) {
                last--;
            }
            page.tag(first, last, Layout.LineTag.CODE);
            ObjectNode attributes = JSON.objectNode();
            attributes.put("language", guessLanguage(page.texts(first, last)));
            attributes.put("fenced", false);
            attributes.put("literal", true);
            page.block(Layout.Kind.CODE, first, last, attributes);
            index = last;
        }
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<Boolean> selected = new ArrayList<>();
        int index = 0;
        while (index < page.rows.size()) {
            while (index < page.rows.size() && !page.rows.get(index).free()) {
                index++;
            }
            int first = index;
            while (index < page.rows.size() && page.rows.get(index).free()) {
                index++;
            }
            int last = index - 1;
            if (first > last) {
                continue;
            }
            int like = 0;
            boolean hasCodeLike = false;
            for (int line = first; line <= last; line++) {
                Row row = page.rows.get(line);
                boolean lineCodeLike = codeLike(row.text);
                hasCodeLike |= lineCodeLike;
                if (lineCodeLike || commentLine(row)) {
                    like++;
                }
            }
            boolean accepted = hasCodeLike && (last > first
                    ? like * 10 >= (last - first + 1) * 6
                    : CODE_START.matcher(page.rows.get(first).trimmed).find());
            if (markdown) {
                boolean indented = first > 0 && page.rows.get(first - 1).blank;
                for (int line = first; line <= last; line++) {
                    indented &= page.rows.get(line).indent >= base + 4;
                }
                boolean enough = last == first || like * 10 >= (last - first + 1) * 6;
                accepted = hasCodeLike && indented && enough;
            }
            starts.add(first);
            ends.add(last);
            selected.add(accepted);
        }
        boolean changed;
        do {
            changed = false;
            for (int chunk = 0; chunk < starts.size(); chunk++) {
                if (selected.get(chunk)) {
                    continue;
                }
                boolean allLike = true;
                for (int line = starts.get(chunk); line <= ends.get(chunk); line++) {
                    Row row = page.rows.get(line);
                    allLike &= codeLike(row.text) || commentLine(row);
                }
                boolean beside = chunk > 0 && selected.get(chunk - 1)
                        && starts.get(chunk) == ends.get(chunk - 1) + 2;
                beside |= chunk + 1 < starts.size() && selected.get(chunk + 1)
                        && starts.get(chunk + 1) == ends.get(chunk) + 2;
                if (allLike && beside) {
                    selected.set(chunk, true);
                    changed = true;
                }
            }
        } while (changed);
        for (int chunk = 0; chunk < starts.size(); chunk++) {
            if (!selected.get(chunk)) {
                continue;
            }
            int first = starts.get(chunk);
            int last = ends.get(chunk);
            while (chunk + 1 < starts.size() && selected.get(chunk + 1)
                    && starts.get(chunk + 1) == last + 2) {
                chunk++;
                last = ends.get(chunk);
            }
            for (int line = first; line <= last; line++) {
                if (!page.rows.get(line).blank) {
                    page.rows.get(line).tags.add(Layout.LineTag.CODE);
                }
            }
            ObjectNode attributes = JSON.objectNode();
            attributes.put("language", guessLanguage(page.texts(first, last)));
            attributes.put("fenced", false);
            page.block(Layout.Kind.CODE, first, last, attributes);
        }
    }

    /**
     * The markers of certain structure, in three passes over the free lines:
     * <ol>
     *   <li>a page break: a line holding a form feed or matching {@link #PAGE_FOOTER} is PAGE_BREAK, and so are the
     *       lines after it that are blank and hold a form feed (blank lines without one are passed), and the first line
     *       after them when it is free and matches {@link #PAGE_HEADER} (the owner, 2026-11-06: a page break is a block
     *       of its own, which ends the text block before it);</li>
     *   <li>columns: a line whose trimmed text has two or more runs of text apart by three or more spaces, and a
     *       neighbouring non-blank line with such columns too, is a TABLE_ROW with {@code columns} true, unless it is
     *       {@link #codeLike};</li>
     *   <li>the first that applies, each pattern matched from the start of the line as written (so a {@code #}
     *       indented 4 or more columns is no heading): {@link #ATX} is a TITLE with its {@code level} (the number of
     *       {@code #}) and {@code certain} true; a line of at most 80 characters (trimmed) that is no bullet and no
     *       rule, above a free line matching {@link #SETEXT}, is a TITLE of level 1 ({@code =}) or 2 ({@code -}) with
     *       {@code certain} true, and the underline a RULE; {@link #RULE} is a RULE; {@link #TABLE} a TABLE_ROW;
     *       {@link #QUOTE} a QUOTE; a {@link #BOLD_LINE} of at most 100 characters (trimmed) a TITLE with
     *       {@code certain} true.</li>
     * </ol>
     */
    static void markers(Page page) {
        boolean afterBreak = false;
        for (Row row : page.rows) {
            boolean pageBreak = row.tags.isEmpty()
                    && (row.text.indexOf('\f') >= 0 || PAGE_FOOTER.matcher(row.trimmed).find());
            if (pageBreak) {
                row.tags.add(Layout.LineTag.PAGE_BREAK);
                afterBreak = true;
            } else if (afterBreak && row.blank) {
                continue;
            } else if (afterBreak && row.free() && PAGE_HEADER.matcher(row.trimmed).find()) {
                row.tags.add(Layout.LineTag.PAGE_BREAK);
                afterBreak = false;
            } else if (!row.blank) {
                afterBreak = false;
            }
        }
        for (int index = 0; index < page.rows.size(); index++) {
            Row row = page.rows.get(index);
            boolean columns = row.trimmed.matches(".*\\S {3,}\\S.*");
            if (!row.free() || !columns || codeLike(row.text)) {
                continue;
            }
            boolean neighbour = index > 0 && !page.rows.get(index - 1).blank
                    && page.rows.get(index - 1).trimmed.matches(".*\\S {3,}\\S.*");
            neighbour |= index + 1 < page.rows.size() && !page.rows.get(index + 1).blank
                    && page.rows.get(index + 1).trimmed.matches(".*\\S {3,}\\S.*");
            if (neighbour) {
                row.tags.add(Layout.LineTag.TABLE_ROW);
                row.attributes.put("columns", true);
            }
        }
        for (int index = 0; index < page.rows.size(); index++) {
            Row row = page.rows.get(index);
            if (!row.free()) {
                continue;
            }
            Matcher atx = ATX.matcher(row.text);
            if (atx.lookingAt()) {
                row.tags.add(Layout.LineTag.TITLE);
                row.attributes.put("level", atx.group(1).length());
                row.attributes.put("certain", true);
            } else if (row.trimmed.length() <= 80 && !BULLET.matcher(row.text).lookingAt()
                    && !RULE.matcher(row.text).lookingAt() && index + 1 < page.rows.size()
                    && page.rows.get(index + 1).free()
                    && SETEXT.matcher(page.rows.get(index + 1).text).lookingAt()) {
                Row underline = page.rows.get(index + 1);
                row.tags.add(Layout.LineTag.TITLE);
                row.attributes.put("level", underline.trimmed.charAt(0) == '=' ? 1 : 2);
                row.attributes.put("certain", true);
                underline.tags.add(Layout.LineTag.RULE);
            } else if (RULE.matcher(row.text).lookingAt()) {
                row.tags.add(Layout.LineTag.RULE);
            } else if (TABLE.matcher(row.text).lookingAt()) {
                row.tags.add(Layout.LineTag.TABLE_ROW);
            } else if (QUOTE.matcher(row.text).lookingAt()) {
                row.tags.add(Layout.LineTag.QUOTE);
            } else if (row.trimmed.length() <= 100 && BOLD_LINE.matcher(row.text).lookingAt()) {
                row.tags.add(Layout.LineTag.TITLE);
                row.attributes.put("certain", true);
            }
        }
    }

    /**
     * The list items (the owner, 2026-11-06), on the free lines:
     * <ul>
     *   <li>a {@link #BULLET} line is a LIST_ITEM, {@code kind} {@code bullet} with its {@code marker} and
     *       {@code indent} (the characters before the marker);</li>
     *   <li>a {@link #LABEL} line has a label with one or two readings, each a kind, a depth, a parent and a value: a
     *       number is {@code arabic} (its depth the count of its parts, its value the last part, its parent the parts
     *       before); a valid Roman numeral ({@link #romanValue}) longer than one letter or followed by {@code .} or
     *       {@code )} is {@code roman-upper} or {@code roman-lower} with the numeral's value; one letter followed by
     *       {@code .} or {@code )} is {@code letter-upper} or {@code letter-lower} with its place in the alphabet. A
     *       letter that is also a Roman numeral ({@code c)}, {@code v.}) has both readings, the letter's first except
     *       for {@code i};</li>
     *   <li>a labelled line is a LIST_ITEM when one of its readings continues a neighbour ({@link #neighbour}): the
     *       nearest label above has a reading of the same kind and parent with the value one less, or the nearest below
     *       one with the value one more (its readings in order, above before below for each); or when its first
     *       reading is a first subsection ({@code 7.1}) whose parent is the label of the nearest number above it one
     *       level up, past deeper numbers, blank lines, page breaks and indented lines. Its attributes: {@code kind}
     *       and {@code number} of that reading, {@code label} and {@code indent}, and {@code indentMismatch} true when
     *       the label it continues is indented otherwise. A labelled line that is no item keeps its label for
     *       {@link #titles};</li>
     *   <li>an item's text, as LIST_ITEM_TEXT: the free lines right under it up to a blank line, and after blank lines
     *       the next free line when it is indented deeper than the item's {@code indent} (and so on), none of them a
     *       bullet or a line whose label has a reading (so {@code a consistent state} is text: one letter needs
     *       {@code .} or {@code )}).</li>
     * </ul>
     */
    static void lists(Page page) {
        Map<Integer, Label> labels = new TreeMap<>();
        for (Row row : page.rows) {
            if (row.free()) {
                row.label = Label.of(row.text);
                if (row.label != null) {
                    labels.put(row.index, row.label);
                }
            }
        }
        for (Row row : page.rows) {
            if (!row.free()) {
                continue;
            }
            Matcher bullet = BULLET.matcher(row.text);
            if (bullet.find()) {
                row.attributes.put("kind", "bullet");
                row.attributes.put("marker", bullet.group(2));
                row.attributes.put("indent", bullet.group(1).length());
                row.item = row.index;
                row.itemIndent = bullet.group(1).length();
                page.tag(row.index, row.index, Layout.LineTag.LIST_ITEM);
                continue;
            }
            Label label = labels.get(row.index);
            if (label == null) {
                continue;
            }
            Reading selected = null;
            Integer continued = null;
            for (Reading reading : label.readings()) {
                for (int step : List.of(-1, 1)) {
                    Integer candidate = neighbour(page, labels, row.index, step);
                    if (candidate == null) {
                        continue;
                    }
                    for (Reading other : labels.get(candidate).readings()) {
                        int expected = reading.value() + step;
                        if (reading.kind().equals(other.kind()) && reading.parent().equals(other.parent())
                                && other.value() == expected) {
                            selected = reading;
                            continued = candidate;
                            break;
                        }
                    }
                    if (selected != null) {
                        break;
                    }
                }
                if (selected != null) {
                    break;
                }
            }
            if (selected == null) {
                Reading first = label.readings().get(0);
                if (first.kind().equals("arabic") && first.depth() > 1 && first.value() == 1) {
                    for (int index = row.index - 1; index >= 0; index--) {
                        Row candidateRow = page.rows.get(index);
                        Label candidate = labels.get(index);
                        if (candidate != null) {
                            Reading number = candidate.readings().get(0);
                            if (number.kind().equals("arabic") && number.depth() == first.depth() - 1
                                    && candidate.text().equals(first.parent())) {
                                selected = first;
                                continued = index;
                            } else if (number.kind().equals("arabic") && number.depth() < first.depth()) {
                                break;
                            }
                        } else if (!candidateRow.blank
                                && !candidateRow.tags.contains(Layout.LineTag.PAGE_BREAK)
                                && candidateRow.indent <= label.indent()) {
                            break;
                        }
                        if (selected != null) {
                            break;
                        }
                    }
                }
            }
            if (selected != null) {
                row.attributes.put("kind", selected.kind());
                row.attributes.put("label", label.text());
                row.attributes.put("number", selected.value());
                row.attributes.put("indent", label.indent());
                if (continued != null && labels.get(continued).indent() != label.indent()) {
                    row.attributes.put("indentMismatch", true);
                }
                row.item = row.index;
                row.itemIndent = label.indent();
                page.tag(row.index, row.index, Layout.LineTag.LIST_ITEM);
            }
        }
        for (Row item : page.rows) {
            if (item.item == null || item.item != item.index) {
                continue;
            }
            boolean blank = false;
            for (int index = item.index + 1; index < page.rows.size(); index++) {
                Row row = page.rows.get(index);
                if (row.blank) {
                    blank = true;
                    continue;
                }
                if (!row.free() || labels.containsKey(index) || BULLET.matcher(row.text).find()) {
                    break;
                }
                if (blank && row.indent <= item.itemIndent) {
                    break;
                }
                page.tag(index, index, Layout.LineTag.LIST_ITEM_TEXT);
                row.item = item.index;
                row.itemIndent = item.itemIndent;
                blank = false;
            }
        }
    }

    /**
     * The nearest label of the same style as line {@code i}'s, above it ({@code step} -1) or below it (1): the search
     * passes blank lines, page breaks, and lines that are no TITLE, CODE or TABLE_ROW and are indented deeper than the
     * label, or wrap an item (going down, the lines right under the label before a blank line; going up, a line with a
     * label above it and no blank line between). Any other line ends it; null when none is found.
     */
    static Integer neighbour(Page page, Map<Integer, Label> labels, int i, int step) {
        Label origin = labels.get(i);
        int index = i + step;
        boolean blank = false;
        while (index >= 0 && index < page.rows.size()) {
            Row row = page.rows.get(index);
            Label candidate = labels.get(index);
            if (candidate != null) {
                for (Reading reading : origin.readings()) {
                    for (Reading other : candidate.readings()) {
                        if (reading.kind().equals(other.kind()) && reading.parent().equals(other.parent())) {
                            return index;
                        }
                    }
                }
            }
            if (row.blank) {
                blank = true;
                index += step;
                continue;
            }
            if (row.tags.contains(Layout.LineTag.PAGE_BREAK)) {
                index += step;
                continue;
            }
            boolean barrier = row.tags.contains(Layout.LineTag.TITLE)
                    || row.tags.contains(Layout.LineTag.CODE)
                    || row.tags.contains(Layout.LineTag.TABLE_ROW);
            if (!barrier && row.indent > origin.indent()) {
                index += step;
                continue;
            }
            if (!barrier && !blank && step > 0) {
                index += step;
                continue;
            }
            if (!barrier && step < 0) {
                for (int above = index - 1; above >= 0 && !page.rows.get(above).blank; above--) {
                    if (labels.containsKey(above)) {
                        index += step;
                        break;
                    }
                }
                if (index != row.index) {
                    continue;
                }
            }
            return null;
        }
        return null;
    }

    /**
     * The titles that no marker makes certain, line by line on the free lines. A line is apart above when the line
     * before it is blank, a RULE, a TITLE, CODE or METADATA, or it is the first; apart below when the line after it is
     * blank, a RULE, or not blank and indented otherwise, or it is the last. Its body is its trimmed text, without its
     * label when {@link #lists} left it one; its words are the runs of a letter and then letters, apostrophes and
     * hyphens. It is no title when its body is a link with no space, or starts with {@code $}, {@code >>>} or a
     * markup tag, or holds a sentence's end followed by more text ({@code .}, {@code !} or {@code ?}, closing quotes or
     * brackets, whitespace, then a character: {@code The loop ran. It stopped.}), or ends in {@code :} above (past one
     * blank line) a LIST_ITEM or CODE, or above nothing (a lead-in).
     * Its cues:
     * {@code numbered} (a label), {@code title-case} ({@link #titleCase}), {@code caps} ({@link #allCapitals}),
     * {@code centred} (the typical width ({@link #typicalWidth}) is 50 or more, the line is indented 8 or more, and its
     * middle is within 6 columns of the width's middle), {@code outdented} (indented less than the most common
     * indentation of the free lines longer than 40 characters, when that is above 0). Its strong cues are the cues,
     * without {@code centred} when it is not apart below. When the line is apart above, has words, has 1 to 10 words in
     * its body (split by whitespace), is at most 60 characters or 70% of the typical width long, does not end in
     * {@code .}, {@code ,} or {@code ;} before closing quotes or brackets (a numbered body of at most 8 words ending in
     * one period may, when it is in title case or has at most 3 words), and is apart below or has a strong cue: it is a
     * TITLE with its strong cues ({@code cues}) when it is apart below and has one, else TITLE and PART_OF_PARAGRAPH
     * with its strong cues, or {@code short} when it has none.
     */
    static void titles(Page page) {
        int width = typicalWidth(page.texts(0, page.rows.size() - 1));
        List<Integer> longIndents = new ArrayList<>();
        for (Row row : page.rows) {
            if (row.free() && row.trimmed.length() > 40) {
                longIndents.add(row.indent);
            }
        }
        int commonIndent = mostCommon(longIndents);
        Pattern wordsPattern = pattern("\\p{L}[\\p{L}'’-]*");
        Pattern innerEnd = pattern("[.!?][\\\"')\\]]*\\s+.");
        Pattern trailingPunctuation = pattern("[.,;][\\\"')\\]]*$");
        for (int index = 0; index < page.rows.size(); index++) {
            Row row = page.rows.get(index);
            if (!row.free()) {
                continue;
            }
            boolean above = index == 0 || page.rows.get(index - 1).blank;
            if (index > 0) {
                Set<Layout.LineTag> prior = page.rows.get(index - 1).tags;
                above |= prior.contains(Layout.LineTag.RULE) || prior.contains(Layout.LineTag.TITLE)
                        || prior.contains(Layout.LineTag.CODE) || prior.contains(Layout.LineTag.METADATA);
            }
            boolean below = index == page.rows.size() - 1 || page.rows.get(index + 1).blank;
            if (index + 1 < page.rows.size()) {
                Row next = page.rows.get(index + 1);
                below |= next.tags.contains(Layout.LineTag.RULE) || !next.blank && next.indent != row.indent;
            }
            String body = row.trimmed;
            boolean numbered = row.label != null;
            if (numbered) {
                Matcher label = LABEL.matcher(row.text);
                if (label.find()) {
                    body = trim(row.text.substring(label.end()));
                }
            }
            boolean leadIn = body.endsWith(":");
            if (leadIn) {
                int next = index + 1;
                if (next < page.rows.size() && page.rows.get(next).blank) {
                    next++;
                }
                leadIn = next >= page.rows.size() || page.rows.get(next).tags.contains(Layout.LineTag.LIST_ITEM)
                        || page.rows.get(next).tags.contains(Layout.LineTag.CODE);
            }
            if (body.matches("\\S+://\\S+") || body.startsWith("$") || body.startsWith(">>>")
                    || body.matches("<[^>]+>.*") || innerEnd.matcher(body).find() || leadIn) {
                continue;
            }
            List<String> words = new ArrayList<>();
            Matcher matcher = wordsPattern.matcher(body);
            while (matcher.find()) {
                words.add(matcher.group());
            }
            boolean inTitleCase = titleCase(words);
            boolean capitals = allCapitals(words);
            boolean centred = width >= 50 && row.indent >= 8
                    && Math.abs(row.indent + row.trimmed.length() / 2.0 - width / 2.0) <= 6;
            boolean outdented = commonIndent > 0 && row.indent < commonIndent;
            List<String> cues = new ArrayList<>();
            if (numbered) {
                cues.add("numbered");
            }
            if (inTitleCase) {
                cues.add("title-case");
            }
            if (capitals) {
                cues.add("caps");
            }
            if (centred && below) {
                cues.add("centred");
            }
            if (outdented) {
                cues.add("outdented");
            }
            int splitWords = body.isEmpty() ? 0 : body.split("\\s+").length;
            boolean shortEnough = body.length() <= 60 || width > 0 && body.length() * 10 <= width * 7;
            boolean punctuation = trailingPunctuation.matcher(body).find();
            boolean numberedPeriod = numbered && body.endsWith(".") && splitWords <= 8
                    && (inTitleCase || splitWords <= 3);
            if (!above || words.isEmpty() || splitWords > 10 || !shortEnough
                    || punctuation && !numberedPeriod || !below && cues.isEmpty()) {
                continue;
            }
            row.tags.add(Layout.LineTag.TITLE);
            ArrayNode cueArray = row.attributes.putArray("cues");
            if (below && !cues.isEmpty()) {
                cues.forEach(cueArray::add);
            } else {
                row.tags.add(Layout.LineTag.PART_OF_PARAGRAPH);
                if (cues.isEmpty()) {
                    cueArray.add("short");
                } else {
                    cues.forEach(cueArray::add);
                }
            }
        }
    }

    /**
     * The paragraphs: every line still free is PART_OF_PARAGRAPH. A paragraph starts at a PART_OF_PARAGRAPH line (one
     * that is also a TITLE only when a non-blank line follows it) and takes the next lines while they are
     * PART_OF_PARAGRAPH and no TITLE. It ends:
     * <ul>
     *   <li>at blank lines, unless the text is double-spaced ({@link #doubleSpaced}) and there is one;</li>
     *   <li>before a line indented deeper than the paragraph's last line, when the text is double-spaced, or when the
     *       paragraph has two or more lines, no blank line comes between, and the last line ends a sentence
     *       ({@link #SENTENCE_END});</li>
     *   <li>after a last line that ends a sentence and is shorter than 70% of the typical width
     *       ({@link #typicalWidth}, when that is 50 or more), when no blank line comes between or the text is
     *       double-spaced, and the paragraph's lines are hard-wrapped: two or more, of which half or more
     *       (right-trimmed) reach within 15 columns of the width.</li>
     * </ul>
     * Its block is a PARAGRAPH with {@code doubleSpaced}.
     */
    static void paragraphs(Page page) {
        List<String> texts = page.texts(0, page.rows.size() - 1);
        boolean spaced = doubleSpaced(texts);
        int width = typicalWidth(texts);
        for (Row row : page.rows) {
            if (row.free()) {
                row.tags.add(Layout.LineTag.PART_OF_PARAGRAPH);
            }
        }
        int index = 0;
        while (index < page.rows.size()) {
            Row firstRow = page.rows.get(index);
            boolean starts = firstRow.tags.contains(Layout.LineTag.PART_OF_PARAGRAPH);
            if (starts && firstRow.tags.contains(Layout.LineTag.TITLE)) {
                starts = index + 1 < page.rows.size() && !page.rows.get(index + 1).blank;
            }
            if (!starts) {
                index++;
                continue;
            }
            int first = index;
            int last = index;
            int hard = firstRow.text.stripTrailing().length() >= width - 15 ? 1 : 0;
            while (last + 1 < page.rows.size()) {
                int nextIndex = last + 1;
                boolean blank = false;
                if (page.rows.get(nextIndex).blank) {
                    if (!spaced || nextIndex + 1 >= page.rows.size()) {
                        break;
                    }
                    blank = true;
                    nextIndex++;
                }
                Row next = page.rows.get(nextIndex);
                if (!next.tags.contains(Layout.LineTag.PART_OF_PARAGRAPH)
                        || next.tags.contains(Layout.LineTag.TITLE)) {
                    break;
                }
                Row previous = page.rows.get(last);
                int count = last - first + 1;
                boolean sentence = SENTENCE_END.matcher(previous.trimmed).find();
                if (next.indent > previous.indent && (spaced || count >= 2 && !blank && sentence)) {
                    break;
                }
                boolean wrapped = count >= 2 && hard * 2 >= count;
                boolean shortSentence = width >= 50 && previous.text.stripTrailing().length() * 10 < width * 7
                        && sentence && (!blank || spaced) && wrapped;
                if (shortSentence) {
                    break;
                }
                last = nextIndex;
                if (next.text.stripTrailing().length() >= width - 15) {
                    hard++;
                }
            }
            ObjectNode attributes = JSON.objectNode();
            attributes.put("doubleSpaced", spaced);
            page.block(Layout.Kind.PARAGRAPH, first, last, attributes);
            index = last + 1;
        }
    }

    /**
     * The blocks of single lines and list items, line by line: a TITLE line gets a TITLE block (its {@code level},
     * {@code certain} and {@code cues}); else a LIST_ITEM line a LIST_ITEM block over it and its LIST_ITEM_TEXT lines
     * (its {@code kind}, {@code marker}, {@code label}, {@code number}, {@code indent} and {@code indentMismatch});
     * else a line that is a PAGE_BREAK, QUOTE, RULE or TABLE_ROW a block of the first of these it is, a TABLE_ROW of
     * columns with {@code columns} true. An attribute the line does not have is left out.
     */
    static void blocks(Page page) {
        for (int index = 0; index < page.rows.size(); index++) {
            Row row = page.rows.get(index);
            Layout.Kind kind = null;
            int last = index;
            if (row.tags.contains(Layout.LineTag.TITLE)) {
                kind = Layout.Kind.TITLE;
            } else if (row.tags.contains(Layout.LineTag.LIST_ITEM)) {
                kind = Layout.Kind.LIST_ITEM;
                while (last + 1 < page.rows.size()
                        && page.rows.get(last + 1).tags.contains(Layout.LineTag.LIST_ITEM_TEXT)) {
                    last++;
                }
            } else if (row.tags.contains(Layout.LineTag.PAGE_BREAK)) {
                kind = Layout.Kind.PAGE_BREAK;
            } else if (row.tags.contains(Layout.LineTag.QUOTE)) {
                kind = Layout.Kind.QUOTE;
            } else if (row.tags.contains(Layout.LineTag.RULE)) {
                kind = Layout.Kind.RULE;
            } else if (row.tags.contains(Layout.LineTag.TABLE_ROW)) {
                kind = Layout.Kind.TABLE_ROW;
            }
            if (kind != null) {
                ObjectNode attributes = JSON.objectNode();
                attributes.setAll(row.attributes);
                page.block(kind, index, last, attributes);
                index = last;
            }
        }
    }

    /** The analyzer with the rules of the class javadoc. */
    public static LayoutAnalyzer standard() {
        return STANDARD;
    }

    /** The layout of {@code text}: the stages of the class javadoc, in order. */
    public Layout analyze(String text) {
        Page page = new Page(text);
        if (binary(text)) {
            for (Row row : page.rows) {
                row.tags.add(Layout.LineTag.BINARY);
            }
            page.block(Layout.Kind.BINARY, 0, page.rows.size() - 1, JSON.objectNode());
            return page.layout();
        }
        contexts(page);
        code(page, MARKDOWN.matcher(text).find());
        markers(page);
        lists(page);
        titles(page);
        paragraphs(page);
        blocks(page);
        return page.layout();
    }

    /**
     * The layout after POS tagging (the owner, 2026-11-06): each line that is both TITLE and PART_OF_PARAGRAPH settled
     * by its tokens, {@code tokens} the tagged tokens of {@code text} (the text this layout was found in, or its flow
     * text); a token is on a line when its range lies within the line's. The line's next line is the line after it
     * in the PARAGRAPH block that starts at it, if any; it goes on in lower case when its first letter is lower case.
     * A line has a finite verb when a WORD token on it, whose text in lower case does not end in {@code ing}, is tagged
     * VERB or AUX (its first candidate), or starts with an upper-case letter and holds VERB with the rule
     * {@code word-forms} (a verb read as a name for its capital: {@code We Ran Two Tests}). The words of the line are
     * the runs of a letter and then letters, apostrophes and hyphens in its text; it is a field ({@link #FIELD}) when
     * it starts with a key and a colon or holds two spaces between two characters ({@code Name: LAPACK},
     * {@code SYNTAX  Counter}). The line is:
     * <ul>
     *   <li>PART_OF_PARAGRAPH and no TITLE, when its next line goes on in lower case and it has a finite verb; its
     *       TITLE block is dropped;</li>
     *   <li>TITLE and no PART_OF_PARAGRAPH, when its words are in title case ({@link #titleCase}), it is no field, and
     *       its next line does not go on in lower case and has a finite verb (prose follows the title); the PARAGRAPH
     *       block that starts at it starts at the next line, and its TITLE block gets {@code settled} true;</li>
     *   <li>else as it is.</li>
     * </ul>
     * The blocks keep the order of the class javadoc (a changed block keeps its place among those of its first line)
     * and get their ids again in that order. When no line changes, the result is {@code layout} itself.
     */
    public static Layout settle(Layout layout, String text, List<Token> tokens) {
        List<Layout.Line> lines = new ArrayList<>(layout.lines());
        List<Layout.Block> blocks = new ArrayList<>(layout.blocks());
        boolean changed = false;
        for (Layout.Line line : layout.lines()) {
            if (!line.tags().contains(Layout.LineTag.TITLE)
                    || !line.tags().contains(Layout.LineTag.PART_OF_PARAGRAPH)) {
                continue;
            }
            Layout.Block paragraph = blocks.stream()
                    .filter(block -> block.kind() == Layout.Kind.PARAGRAPH
                            && block.firstLine() == line.index() && block.lastLine() > line.index())
                    .findFirst().orElse(null);
            if (paragraph == null) {
                continue;
            }
            Layout.Line next = lines.get(line.index() + 1);
            String nextText = text.substring(next.range().start(), next.range().end());
            boolean lower = startsWithLowerCaseLetter(nextText);
            String lineText = text.substring(line.range().start(), line.range().end());
            EnumSet<Layout.LineTag> tags = EnumSet.copyOf(line.tags());
            if (lower && finiteVerb(line, text, tokens)) {
                tags.remove(Layout.LineTag.TITLE);
                blocks.removeIf(block -> block.kind() == Layout.Kind.TITLE
                        && block.firstLine() == line.index());
            } else if (!lower && finiteVerb(next, text, tokens) && !FIELD.matcher(lineText).find()
                    && titleCase(words(lineText))) {
                tags.remove(Layout.LineTag.PART_OF_PARAGRAPH);
                int paragraphIndex = blocks.indexOf(paragraph);
                Layout.Line first = lines.get(paragraph.firstLine() + 1);
                blocks.set(paragraphIndex, new Layout.Block(paragraph.id(), paragraph.kind(),
                        new TextRange(first.range().start(), paragraph.range().end()), paragraph.firstLine() + 1,
                        paragraph.lastLine(), paragraph.attributes()));
                for (int index = 0; index < blocks.size(); index++) {
                    Layout.Block block = blocks.get(index);
                    if (block.kind() == Layout.Kind.TITLE && block.firstLine() == line.index()) {
                        ObjectNode attributes = block.attributes().deepCopy();
                        attributes.put("settled", true);
                        blocks.set(index, new Layout.Block(block.id(), block.kind(), block.range(), block.firstLine(),
                                block.lastLine(), attributes));
                    }
                }
            } else {
                continue;
            }
            lines.set(line.index(), new Layout.Line(line.index(), line.range(), line.indent(), Set.copyOf(tags)));
            changed = true;
        }
        if (!changed) {
            return layout;
        }
        blocks.sort(Comparator.comparingInt(Layout.Block::firstLine)
                .thenComparing(Comparator.comparingInt(
                        (Layout.Block block) -> block.lastLine() - block.firstLine()).reversed()));
        List<Layout.Block> numbered = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            Layout.Block block = blocks.get(index);
            numbered.add(new Layout.Block("b" + (index + 1), block.kind(), block.range(), block.firstLine(),
                    block.lastLine(), block.attributes()));
        }
        return new Layout(List.copyOf(lines), List.copyOf(numbered));
    }

    /**
     * The ids of the tokens that the rules reading a capital as a name's sign read in lower case (the owner,
     * 2026-11-06: a title's capitals are its style), {@code tokens} the tagged tokens of {@code text} (the text
     * {@code layout} was found in, or its flow text) and {@code sentences} its sentences; a token is on a line when its
     * range lies within the line's. They are the WORD tokens on a line that is TITLE and no PART_OF_PARAGRAPH whose
     * text is an upper-case letter and then only lower-case letters, one or more ({@code Objects}), unless the
     * document's prose writes the word so: a line that is PART_OF_PARAGRAPH, LIST_ITEM or LIST_ITEM_TEXT and no TITLE
     * holds a WORD token of the same text that is not the first WORD token of its sentence.
     */
    public static Set<String> lowerCaseReadings(Layout layout, String text, List<Token> tokens,
            List<Sentence> sentences) {
        Set<String> firstWords = new HashSet<>();
        for (Sentence sentence : sentences) {
            for (String tokenId : sentence.tokenIds()) {
                Token token = tokens.stream().filter(candidate -> candidate.id().equals(tokenId))
                        .findFirst().orElse(null);
                if (token != null && token.shape() == Token.Shape.WORD) {
                    firstWords.add(token.id());
                    break;
                }
            }
        }
        Set<String> proseWords = new HashSet<>();
        for (Token token : tokens) {
            if (token.shape() != Token.Shape.WORD || firstWords.contains(token.id())) {
                continue;
            }
            Layout.Line line = lineOf(layout, token);
            if (line == null || line.tags().contains(Layout.LineTag.TITLE)) {
                continue;
            }
            boolean prose = line.tags().contains(Layout.LineTag.PART_OF_PARAGRAPH)
                    || line.tags().contains(Layout.LineTag.LIST_ITEM)
                    || line.tags().contains(Layout.LineTag.LIST_ITEM_TEXT);
            if (prose) {
                proseWords.add(token.range().surfaceIn(text));
            }
        }
        Set<String> readings = new HashSet<>();
        for (Token token : tokens) {
            if (token.shape() != Token.Shape.WORD) {
                continue;
            }
            Layout.Line line = lineOf(layout, token);
            if (line == null || !line.tags().contains(Layout.LineTag.TITLE)
                    || line.tags().contains(Layout.LineTag.PART_OF_PARAGRAPH)) {
                continue;
            }
            String surface = token.range().surfaceIn(text);
            if (surface.length() < 2 || !Character.isUpperCase(surface.charAt(0))) {
                continue;
            }
            boolean lower = true;
            for (int index = 1; index < surface.length(); index++) {
                if (!Character.isLowerCase(surface.charAt(index))) {
                    lower = false;
                    break;
                }
            }
            if (lower && !proseWords.contains(surface)) {
                readings.add(token.id());
            }
        }
        return Set.copyOf(readings);
    }

    private static boolean commentLine(Row row) {
        return COMMENT_LINE.matcher(row.text).lookingAt() && !structural(row.text);
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = pattern("\\p{L}[\\p{L}'’-]*").matcher(text);
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    private static boolean finiteVerb(Layout.Line line, String text, List<Token> tokens) {
        for (Token token : tokens) {
            if (token.shape() != Token.Shape.WORD || token.range().start() < line.range().start()
                    || token.range().end() > line.range().end()) {
                continue;
            }
            String surface = token.range().surfaceIn(text);
            if (surface.toLowerCase(Locale.ROOT).endsWith("ing")) {
                continue;
            }
            if (token.tag() == Tag.AUX || token.tag() == Tag.VERB) {
                return true;
            }
            if (!surface.isEmpty() && Character.isUpperCase(surface.charAt(0))
                    && token.candidates().stream().anyMatch(candidate -> candidate.tag() == Tag.VERB
                            && candidate.rule().equals("word-forms"))) {
                return true;
            }
        }
        return false;
    }

    private static Layout.Line lineOf(Layout layout, Token token) {
        for (Layout.Line line : layout.lines()) {
            if (token.range().start() >= line.range().start() && token.range().end() <= line.range().end()) {
                return line;
            }
        }
        return null;
    }

    private static boolean startsWithLowerCaseLetter(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isLetter(character)) {
                return Character.isLowerCase(character);
            }
        }
        return false;
    }
}
