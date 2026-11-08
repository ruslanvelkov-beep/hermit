package hermit.text.lex;

import hermit.text.model.Layout;
import hermit.text.model.Sentence;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Splits a text into sentences over its tokens (milestone A1 of the annotation plan, section 4). Every token lies in
 * exactly one sentence; sentences are in text order, each runs from its first token's start to its last token's end,
 * and their ids are {@code s1}, {@code s2}, ... in text order.
 *
 * <p>The blocks come first, from the text's layout ({@link LayoutAnalyzer}, the owner, 2026-11-06), in text order:
 * <ul>
 *   <li>a CODE, METADATA, COMMENT or BINARY block is one CODE sentence, UNCERTAIN when it is a fenced block that is
 *       never closed;</li>
 *   <li>a TITLE block whose line is no PART_OF_PARAGRAPH (a title the layout is sure of) is one HEADING sentence; a
 *       TITLE block whose line is PART_OF_PARAGRAPH too is part of its PARAGRAPH block, or, with none, a block whose
 *       sentences are PROSE; a TABLE_ROW block is one TABLE_ROW sentence;</li>
 *   <li>a LIST_ITEM block (the item and its text) is a block whose sentences are LIST_ITEMs;</li>
 *   <li>a PARAGRAPH block, a run of QUOTE blocks on neighbouring lines, a RULE block and a PAGE_BREAK block are each a
 *       block whose sentences are PROSE;</li>
 *   <li>the tokens that no such block holds make, line by line, PROSE blocks.</li>
 * </ul>
 *
 * <p>Inside a prose or list block, in the flow text (where a paragraph's or an item's lines are one line), a sentence
 * ends after a PUNCT token that is {@code ?}, {@code !}, an ellipsis or periods only, together with any closing
 * brackets and quotes right after it, when the next token is on a later line, or (skipping opening brackets and
 * quotes) is a word with an upper-case first letter or a token of any shape but WORD, PUNCT and SYMBOL. A {@code :}
 * ends a sentence only when the next token is on a later line. No sentence ends while a parenthesis, bracket, brace or
 * double quote opened in the block is still open, nor at a list item's own marker. A period inside a token (a path, a
 * version, a number, a known abbreviation) is no PUNCT token, so it never ends a sentence.
 *
 * <p>A sentence is UNCERTAIN when a known abbreviation in it is followed by a word with an upper-case first letter (the
 * abbreviation may have ended a sentence), or when its block ends while a bracket or double quote is still open; every
 * other sentence is CERTAIN.
 */
public final class SentenceSplitter {

    private final Abbreviations abbreviations;

    /** A splitter that knows these abbreviations. */
    public SentenceSplitter(Abbreviations abbreviations) {
        this.abbreviations = abbreviations;
    }

    /** A splitter with the standard abbreviations. */
    public static SentenceSplitter standard() {
        return new SentenceSplitter(Abbreviations.standard());
    }

    /**
     * The sentences of {@code text} over {@code tokens}, the text's tokens in text order: the blocks of the text's
     * layout ({@link LayoutAnalyzer#standard}), split in its flow text ({@link Layout#flow}).
     */
    public List<Sentence> split(String text, List<Token> tokens) {
        Layout layout = LayoutAnalyzer.standard().analyze(text);
        return split(layout.flow(text), tokens, layout);
    }

    /**
     * The sentences of {@code text} (a flow text) over {@code tokens}, its tokens in text order, in the blocks of
     * {@code layout} (the layout of the text the flow text was made from), as the class javadoc describes.
     */
    public List<Sentence> split(String text, List<Token> tokens, Layout layout) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        Set<Integer> paragraphLines = new HashSet<>();
        for (Layout.Block block : layout.blocks()) {
            if (block.kind() == Layout.Kind.PARAGRAPH) {
                for (int line = block.firstLine(); line <= block.lastLine(); line++) {
                    paragraphLines.add(line);
                }
            }
        }
        List<Block> blocks = new ArrayList<>();
        int quoteStart = -1;
        int quoteEnd = -1;
        int quoteLine = -2;
        for (Layout.Block block : layout.blocks()) {
            if (block.kind() == Layout.Kind.QUOTE && block.firstLine() == quoteLine + 1) {
                quoteEnd = block.range().end();
                quoteLine = block.lastLine();
                continue;
            }
            if (quoteStart >= 0) {
                blocks.add(new Block(quoteStart, quoteEnd, Sentence.Kind.PROSE, false, false));
                quoteStart = -1;
            }
            if (block.kind() == Layout.Kind.QUOTE) {
                quoteStart = block.range().start();
                quoteEnd = block.range().end();
                quoteLine = block.lastLine();
                continue;
            }
            int start = block.range().start();
            int end = block.range().end();
            switch (block.kind()) {
                case CODE, METADATA, COMMENT, BINARY -> blocks.add(new Block(start, end, Sentence.Kind.CODE,
                        block.attributes().path("fenced").asBoolean(false)
                                && !block.attributes().path("closed").asBoolean(true), false));
                case TITLE -> {
                    if (!paragraphLines.contains(block.firstLine())) {
                        boolean sure = !layout.lines().get(block.firstLine()).tags()
                                .contains(Layout.LineTag.PART_OF_PARAGRAPH);
                        blocks.add(new Block(start, end, sure ? Sentence.Kind.HEADING : Sentence.Kind.PROSE, false,
                                false));
                    }
                }
                case TABLE_ROW -> blocks.add(new Block(start, end, Sentence.Kind.TABLE_ROW, false, false));
                case LIST_ITEM -> blocks.add(new Block(start, end, Sentence.Kind.LIST_ITEM, false, true));
                default -> blocks.add(new Block(start, end, Sentence.Kind.PROSE, false, false));
            }
        }
        if (quoteStart >= 0) {
            blocks.add(new Block(quoteStart, quoteEnd, Sentence.Kind.PROSE, false, false));
        }
        List<Block> held = List.copyOf(blocks);
        int lastLine = -1;
        for (Token token : tokens) {
            int at = token.range().start();
            if (held.stream().noneMatch(block -> block.start() <= at && token.range().end() <= block.end())
                    && at >= lastLine) {
                int start = text.lastIndexOf('\n', at - 1) + 1;
                lastLine = lineEnd(text, at);
                blocks.add(new Block(start, lastLine, Sentence.Kind.PROSE, false, false));
            }
        }
        blocks.sort(Comparator.comparingInt(Block::start));
        List<Sentence> out = new ArrayList<>();
        for (Block block : blocks) {
            out.addAll(splitBlock(text, tokens, block, out.size() + 1));
        }
        return List.copyOf(out);
    }

    private record Block(int start, int end, Sentence.Kind kind, boolean uncertain, boolean listMarker) {}

    private static int lineEnd(String text, int start) {
        int end = text.indexOf('\n', start);
        return end < 0 ? text.length() : end;
    }

    private static int firstTokenAtOrAfter(List<Token> tokens, int offset) {
        int at = 0;
        while (at < tokens.size() && tokens.get(at).range().end() <= offset) {
            at++;
        }
        return at;
    }

    private static int firstTokenAfter(List<Token> tokens, int offset) {
        int at = 0;
        while (at < tokens.size() && tokens.get(at).range().start() < offset) {
            at++;
        }
        return at;
    }

    private static String surface(String text, Token token) {
        return text.substring(token.range().start(), token.range().end());
    }

    private static boolean closing(String value) {
        return value.equals(")") || value.equals("]") || value.equals("}") || value.equals("\"")
                || value.equals("'") || value.equals("\u2019") || value.equals("\u201d")
                || value.equals("\u00bb");
    }

    private static boolean opening(String value) {
        return value.equals("(") || value.equals("[") || value.equals("{") || value.equals("\"")
                || value.equals("'") || value.equals("\u2018") || value.equals("\u201c")
                || value.equals("\u00ab");
    }

    private static boolean terminator(String value) {
        if (value.equals("?") || value.equals("!") || value.equals("\u2026")) {
            return true;
        }
        if (value.isEmpty()) {
            return false;
        }
        for (int at = 0; at < value.length(); at++) {
            if (value.charAt(at) != '.') {
                return false;
            }
        }
        return true;
    }

    private static boolean upperWord(String text, Token token) {
        if (token.shape() != Token.Shape.WORD) {
            return false;
        }
        String value = surface(text, token);
        return !value.isEmpty() && Character.isUpperCase(value.codePointAt(0));
    }

    private static boolean startsSentence(String text, Token token) {
        if (upperWord(text, token)) {
            return true;
        }
        return token.shape() != Token.Shape.WORD && token.shape() != Token.Shape.PUNCT
                && token.shape() != Token.Shape.SYMBOL;
    }

    private boolean abbreviationBeforeCapital(String text, List<Token> tokens, int from, int to) {
        for (int at = from; at + 1 < to; at++) {
            if (abbreviations.contains(surface(text, tokens.get(at))) && upperWord(text, tokens.get(at + 1))) {
                return true;
            }
        }
        return false;
    }

    private static Sentence sentence(String text, List<Token> tokens, int from, int to, Sentence.Kind kind,
            Sentence.Boundary boundary, int number) {
        int start = tokens.get(from).range().start();
        int end = tokens.get(to - 1).range().end();
        List<String> tokenIds = tokens.subList(from, to).stream().map(Token::id).toList();
        return new Sentence("s" + number, TextRange.of(start, end, text.length()), kind, boundary, tokenIds);
    }

    private List<Sentence> splitBlock(String text, List<Token> tokens, Block block, int firstNumber) {
        List<Sentence> out = new ArrayList<>();
        int from = firstTokenAtOrAfter(tokens, block.start());
        int limit = firstTokenAfter(tokens, block.end());
        if (from >= limit) {
            return out;
        }
        if (block.kind() == Sentence.Kind.CODE || block.kind() == Sentence.Kind.HEADING
                || block.kind() == Sentence.Kind.TABLE_ROW) {
            Sentence.Boundary boundary = block.uncertain() ? Sentence.Boundary.UNCERTAIN : Sentence.Boundary.CERTAIN;
            out.add(sentence(text, tokens, from, limit, block.kind(), boundary, firstNumber));
            return out;
        }
        int round = 0;
        int square = 0;
        int brace = 0;
        boolean quoted = false;
        for (int at = from; at < limit; at++) {
            String value = surface(text, tokens.get(at));
            if (value.equals("(")) {
                round++;
            } else if (value.equals(")") && round > 0) {
                round--;
            } else if (value.equals("[")) {
                square++;
            } else if (value.equals("]") && square > 0) {
                square--;
            } else if (value.equals("{")) {
                brace++;
            } else if (value.equals("}") && brace > 0) {
                brace--;
            } else if (value.equals("\"") || value.equals("\u201c") || value.equals("\u201d")) {
                quoted = !quoted;
            }
            boolean colon = value.equals(":");
            if (!terminator(value) && !colon) {
                continue;
            }
            if (block.listMarker() && at == from + 1 && value.equals(".")) {
                continue;
            }
            int end = at + 1;
            while (end < limit && closing(surface(text, tokens.get(end)))) {
                end++;
            }
            int remainingRound = round;
            int remainingSquare = square;
            int remainingBrace = brace;
            boolean remainingQuoted = quoted;
            for (int close = at + 1; close < end; close++) {
                String closingValue = surface(text, tokens.get(close));
                if (closingValue.equals(")") && remainingRound > 0) {
                    remainingRound--;
                } else if (closingValue.equals("]") && remainingSquare > 0) {
                    remainingSquare--;
                } else if (closingValue.equals("}") && remainingBrace > 0) {
                    remainingBrace--;
                } else if (closingValue.equals("\"") || closingValue.equals("\u201c")
                        || closingValue.equals("\u201d")) {
                    remainingQuoted = !remainingQuoted;
                }
            }
            if (remainingRound > 0 || remainingSquare > 0 || remainingBrace > 0 || remainingQuoted) {
                continue;
            }
            boolean laterLine = end < limit && text.substring(tokens.get(at).range().end(),
                    tokens.get(end).range().start()).indexOf('\n') >= 0;
            if (colon && !laterLine) {
                continue;
            }
            int next = end;
            while (next < limit && opening(surface(text, tokens.get(next)))) {
                next++;
            }
            boolean endHere = end == limit || laterLine || next < limit && startsSentence(text, tokens.get(next));
            if (!endHere) {
                continue;
            }
            Sentence.Boundary boundary = abbreviationBeforeCapital(text, tokens, from, end)
                    ? Sentence.Boundary.UNCERTAIN : Sentence.Boundary.CERTAIN;
            out.add(sentence(text, tokens, from, end, block.kind(), boundary, firstNumber + out.size()));
            round = 0;
            square = 0;
            brace = 0;
            quoted = false;
            from = end;
            at = end - 1;
        }
        if (from < limit) {
            boolean open = round > 0 || square > 0 || brace > 0 || quoted;
            boolean uncertain = block.uncertain() || open || abbreviationBeforeCapital(text, tokens, from, limit);
            Sentence.Boundary boundary = uncertain ? Sentence.Boundary.UNCERTAIN : Sentence.Boundary.CERTAIN;
            out.add(sentence(text, tokens, from, limit, block.kind(), boundary, firstNumber + out.size()));
        }
        return out;
    }
}
