package hermit.text.semantic;

import hermit.text.model.AnnotationDocument;
import hermit.text.model.Clause;
import hermit.text.model.Marker;
import hermit.text.model.Sentence;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Clauses (milestone A4 of the annotation plan, section 6.3, and section 3 of the G1 labelling guide), by rules over
 * the tagged tokens of each sentence. A VERB or AUX token is a verb below; a word is a token's text, compared in lower
 * case unless the rule shows capitals.
 * <ol>
 *   <li><b>Table rows.</b> A sentence with two or more {@code |} tokens is cut into cells: scanning left to right, a
 *       {@code |} closes the cell when the cell holds a token other than {@code |} (so the first cell keeps the
 *       opening {@code |}); left-over tokens that are all {@code |} join the last cell, other left-over tokens are a
 *       cell of their own. A cell with a verb is of the type CELL, else VERBLESS.</li>
 *   <li><b>Top-level clauses.</b> Any other sentence is cut into parts, scanning left to right:
 *     <ul>
 *       <li>after a {@code ;};</li>
 *       <li>after an arrow (U+2192 or {@code ->}) when the part so far or the rest of the sentence up to its next
 *           {@code ;} has a verb;</li>
 *       <li>after a colon, or a dash (U+2014, U+2013 or a {@code -} token), when the part so far has a verb, the
 *           rest up to the next {@code ;} has one, and that rest starts with a subject and a verb
 *           ({@link #subjectAndVerb});</li>
 *       <li>before a conjunction, one of {@link #CONJUNCTIONS} tagged CCONJ, or {@code so} right after a comma, when
 *           the sentence's first token does not start with {@code #}, the part so far has a verb, the tokens after the
 *           conjunction start a clause (a verb first, an ADV and then a verb, or a subject and a verb), and, unless a
 *           comma comes right before the conjunction, it is not the case that a verb follows the conjunction directly
 *           while the part so far holds {@code that}, {@code which}, {@code who} or a token tagged SCONJ.</li>
 *     </ul>
 *       No cut is made after the sentence's last token.</li>
 * </ol>
 * The nested clauses, the types, heads and the rest are in {@link #segment}.
 */
public final class ClauseSegmenter {

    /** Conjunctions, tagged CCONJ, that join two clauses. */
    public static final List<String> CONJUNCTIONS = List.of("and", "but", "or");

    /** Words tagged SCONJ that open no subordinate clause by that tag ({@code that} has rules of its own). */
    public static final List<String> NOT_OPENING = List.of("so", "than", "like", "that");

    /** Relative pronouns. */
    public static final List<String> RELATIVE_PRONOUNS = List.of("which", "who", "whom", "whose", "that", "where");

    /** Free relatives: each opens a RELATIVE clause wherever it stands ({@code whatever was requested}). */
    public static final List<String> FREE_RELATIVES = List.of("whatever", "whoever");

    /** Question words, which open a subordinate clause after a verb or an ADP ({@code describe what exists}). */
    public static final List<String> QUESTION_WORDS = List.of("what", "why", "how", "whether");

    /** Personal pronouns, which open a relative clause after a NOUN and before a verb ({@code the ones I did}). */
    public static final List<String> PERSONAL_PRONOUNS = List.of("i", "we", "you", "they", "it", "he", "she");

    /** Determiners that open a relative clause with no pronoun ({@code vectors this provider returns}). */
    public static final List<String> RELATIVE_DETERMINERS = List.of("a", "an", "the", "this", "these", "those");

    /**
     * The clauses of {@code input}, as the class javadoc describes them; {@code markers} are its markers. After the
     * table rows and the top-level clauses:
     * <ol>
     *   <li><b>Nested clauses</b> inside each part and each cell (the outer clause), scanning its tokens left to right:
     *       a token that opens one ({@link #opener}, with the outer clause's first and last tokens as its bounds)
     *       starts a SUBORDINATE or RELATIVE clause, except a relative {@code that} with no verb among the five tokens
     *       after it, a clause with no verb after its opener, and a clause that would cover the whole outer clause.
     *       It ends:
     *     <ul>
     *       <li>when only PUNCT or SYM tokens (or {@code -}, {@code *}, {@code **}) come before the opener in the outer
     *           clause: at the first comma after the opener, comma included, or at the outer clause's end;</li>
     *       <li>else, when the opener is tagged SCONJ and a VERB ending in {@code ing}, {@code ed} or {@code en}
     *           follows it directly: at the first {@code ,}, {@code ;}, {@code .}, U+2014 or {@code :} after it
     *           (included), or before the first {@code (}, {@code )} or token tagged SCONJ after it, whichever comes
     *           first;</li>
     *       <li>else, when the outer clause has no verb of its own (outside the clauses nested in it so far) before the
     *           opener, and a verb group after the opener's first one does not start right after a token tagged CCONJ:
     *           before the first such group (the outer clause's verb);</li>
     *       <li>else at the outer clause's end, unless one of these comes first: a token tagged SCONJ other than
     *           {@code that}, not right after the opener (the clause ends before it); after the end of the clause's
     *           first verb group, a {@code ;}, {@code :}, {@code (}, {@code |} or U+2014 (it ends before it), or a
     *           comma that is not followed by a verb or by a token tagged CCONJ and a verb (it ends before the comma).
     *           A clause that reaches the outer clause's end leaves out a last {@code ;}, {@code :}, {@code |} or
     *           {@code ,}.</li>
     *     </ul>
     *       A verb group starts at a verb and goes on, while its last token is not tagged VERB, over a next verb, or a
     *       next ADV or PART that a verb follows. Nested clauses are looked for again inside each nested clause (after
     *       its opener), and the scan of the outer clause goes on after the nested clause's end. The clause a nested
     *       clause lies in is its parent.</li>
     *   <li><b>Types</b> of the parts: VERBLESS when the part has no verb outside its nested clauses; else MAIN for the
     *       first such part of the sentence and for a part cut after a colon, COORDINATE for the others.</li>
     *   <li><b>Head</b>: among the clause's own tokens (those outside the clauses nested in it), its first VERB, else
     *       the last of the AUX tokens that follow its first AUX directly (the copula before a predicate, after a
     *       modal: {@code must be}); none for a VERBLESS clause. <b>Finite</b>: false for a
     *       VERBLESS clause and for one whose first token is tagged SCONJ and is followed by a VERB ending in
     *       {@code ing}, {@code ed} or {@code en}; true otherwise.</li>
     *   <li><b>Asserted</b>: QUOTED when a QUOTATION marker's range holds the clause's range; else ATTRIBUTED for a
     *       SUBORDINATE clause that starts with {@code that} right after a word of {@link MarkerRecognizer#SAYING};
     *       else ASSERTED.</li>
     * </ol>
     * A clause's tokens are all the tokens of its range, its nested clauses' included. Clauses come by their first
     * token, then the longer first; their ids are {@code c1}, {@code c2} and so on in that order.
     */
    public List<Clause> segment(ConceptRecognizer.Input input, List<Marker> markers) {
        String text = input.text();
        List<Token> tokens = input.tokens();
        List<Draft> drafts = new ArrayList<>();
        for (Sentence sentence : input.sentences()) {
            if (sentence.tokenIds().isEmpty()) {
                continue;
            }
            int start = sentenceStart(tokens, sentence);
            int end = start + sentence.tokenIds().size() - 1;
            int firstDraft = drafts.size();
            int pipes = 0;
            for (int at = start; at <= end; at++) {
                if (word(text, tokens, at).equals("|")) {
                    pipes++;
                }
            }
            if (pipes >= 2) {
                int cellStart = start;
                boolean content = false;
                for (int at = start; at <= end; at++) {
                    String current = word(text, tokens, at);
                    if (current.equals("|") && content) {
                        drafts.add(new Draft(cellStart, at, true, false, drafts.size() == firstDraft));
                        cellStart = at + 1;
                        content = false;
                    } else if (!current.equals("|")) {
                        content = true;
                    }
                }
                if (cellStart <= end) {
                    if (!content && !drafts.isEmpty()) {
                        Draft last = drafts.remove(drafts.size() - 1);
                        drafts.add(new Draft(last.start, end, true, false, last.firstInSentence));
                    } else {
                        drafts.add(new Draft(cellStart, end, true, false, drafts.size() == firstDraft));
                    }
                }
                continue;
            }
            int partStart = start;
            boolean afterColon = false;
            for (int at = start; at <= end; at++) {
                String current = word(text, tokens, at);
                int semicolon = nextSemicolon(text, tokens, at + 1, end);
                boolean cutAfter = current.equals(";");
                boolean arrow = current.equals("\u2192") || current.equals("->");
                if (arrow) {
                    cutAfter = hasVerb(tokens, partStart, at) || at < end && hasVerb(tokens, at + 1, semicolon);
                }
                boolean colon = current.equals(":");
                boolean dash = current.equals("\u2014") || current.equals("\u2013") || current.equals("-");
                if ((colon || dash) && at < end) {
                    cutAfter = hasVerb(tokens, partStart, at) && hasVerb(tokens, at + 1, semicolon)
                            && subjectAndVerb(text, tokens, at + 1, semicolon);
                }
                if (cutAfter && at < end) {
                    drafts.add(new Draft(partStart, at, false, afterColon, drafts.size() == firstDraft));
                    partStart = at + 1;
                    afterColon = colon;
                    continue;
                }
                String lower = current.toLowerCase();
                boolean conjunction = tokens.get(at).tag().name().equals("CCONJ") && CONJUNCTIONS.contains(lower);
                conjunction |= lower.equals("so") && at > partStart && word(text, tokens, at - 1).equals(",");
                if (conjunction && at > partStart && !word(text, tokens, start).startsWith("#")
                        && hasVerb(tokens, partStart, at - 1) && clauseStart(text, tokens, at + 1, end)) {
                    boolean comma = word(text, tokens, at - 1).equals(",");
                    boolean directVerb = at < end && isVerb(tokens.get(at + 1));
                    boolean blocked = false;
                    if (!comma && directVerb) {
                        for (int before = partStart; before < at; before++) {
                            String seen = word(text, tokens, before).toLowerCase();
                            if (seen.equals("that") || seen.equals("which") || seen.equals("who")
                                    || tokens.get(before).tag().name().equals("SCONJ")) {
                                blocked = true;
                                break;
                            }
                        }
                    }
                    if (!blocked) {
                        drafts.add(new Draft(partStart, at - 1, false, afterColon,
                                drafts.size() == firstDraft));
                        partStart = at;
                        afterColon = false;
                    }
                }
            }
            if (partStart <= end) {
                drafts.add(new Draft(partStart, end, false, afterColon, drafts.size() == firstDraft));
            }
        }
        List<Clause> clauses = new ArrayList<>();
        int id = 1;
        boolean verbalSeen = false;
        for (Draft draft : drafts) {
            if (draft.firstInSentence) {
                verbalSeen = false;
            }
            List<Nested> nested = recursiveNestedClauses(text, tokens, draft.start, draft.end);
            List<Nested> outerChildren = directChildren(null, nested);
            boolean verbal;
            Clause.Type type;
            if (draft.table) {
                verbal = hasVerb(tokens, draft.start, draft.end);
                type = verbal ? Clause.Type.CELL : Clause.Type.VERBLESS;
            } else {
                verbal = ownedHasVerb(tokens, draft.start, draft.end, outerChildren);
                if (!verbal) {
                    type = Clause.Type.VERBLESS;
                } else if (!verbalSeen || draft.afterColon) {
                    type = Clause.Type.MAIN;
                } else {
                    type = Clause.Type.COORDINATE;
                }
            }
            if (verbal && !draft.table) {
                verbalSeen = true;
            }
            List<String> tokenIds = new ArrayList<>();
            for (int at = draft.start; at <= draft.end; at++) {
                tokenIds.add(tokens.get(at).id());
            }
            String clauseHead = type == Clause.Type.VERBLESS ? null
                    : ownedHead(tokens, draft.start, draft.end, outerChildren);
            TextRange range = new TextRange(tokens.get(draft.start).range().start(),
                    tokens.get(draft.end).range().end());
            String outerId = "c" + id;
            id++;
            clauses.add(new Clause(outerId, range, tokenIds, type, clauseHead, type != Clause.Type.VERBLESS
                    && !participialOpener(text, tokens, draft.start, draft.end), null,
                    asserted(text, tokens, draft.start, draft.end, type, markers)));
            IdentityHashMap<Nested, String> ids = new IdentityHashMap<>();
            for (Nested child : nested) {
                ids.put(child, "c" + id);
                id++;
            }
            for (Nested child : nested) {
                List<String> childTokenIds = new ArrayList<>();
                for (int at = child.start; at <= child.end; at++) {
                    childTokenIds.add(tokens.get(at).id());
                }
                TextRange childRange = new TextRange(tokens.get(child.start).range().start(),
                        tokens.get(child.end).range().end());
                String parentId = child.parent == null ? outerId : ids.get(child.parent);
                String childHead = ownedHead(tokens, child.start, child.end, directChildren(child, nested));
                boolean finite = !participialOpener(text, tokens, child.start, child.end);
                clauses.add(new Clause(ids.get(child), childRange, childTokenIds, child.type, childHead,
                        finite, parentId,
                        asserted(text, tokens, child.start, child.end, child.type, markers)));
            }
        }
        return clauses;
    }

    /** The clauses of {@code text}, from the standard input, concept spans and markers for it. */
    public static List<Clause> clausesOf(String text, AnnotationDocument.Context context) {
        ConceptRecognizer.Input input = ConceptRecognizers.input(text, context);
        List<Marker> markers = new MarkerRecognizer().recognize(input, ConceptRecognizers.standard().recognize(input));
        return new ClauseSegmenter().segment(input, markers);
    }

    /**
     * Whether the tokens from index {@code from} to index {@code end} start with a subject and a verb: after an
     * optional first ADV, one to six tokens tagged DET, ADJ, NOUN, PROPN, PRON or NUM or of the shape CODE_SPAN, then a
     * VERB or AUX token. A group in parentheses and the double quotation marks ({@code "}, U+201C, U+201D) are passed
     * over, and so is an ADV after the first such token that comes right before the verb. False when the tokens run
     * out first.
     */
    public static boolean subjectAndVerb(String text, List<Token> tokens, int from, int end) {
        int at = from;
        if (at <= end && tokens.get(at).tag().name().equals("ADV")) {
            at++;
        }
        int subjects = 0;
        while (at <= end) {
            String word = tokens.get(at).range().surfaceIn(text);
            if (word.equals("\"") || word.equals("“") || word.equals("”")) {
                at++;
                continue;
            }
            if (word.equals("(")) {
                int depth = 1;
                at++;
                while (at <= end && depth > 0) {
                    String nested = tokens.get(at).range().surfaceIn(text);
                    if (nested.equals("(")) {
                        depth++;
                    } else if (nested.equals(")")) {
                        depth--;
                    }
                    at++;
                }
                if (depth > 0) {
                    return false;
                }
                continue;
            }
            String tag = tokens.get(at).tag().name();
            if ((tag.equals("VERB") || tag.equals("AUX")) && subjects >= 1) {
                return true;
            }
            if (tag.equals("ADV") && subjects >= 1 && at < end) {
                String next = tokens.get(at + 1).tag().name();
                if (next.equals("VERB") || next.equals("AUX")) {
                    at++;
                    continue;
                }
            }
            boolean nominal = tag.equals("DET") || tag.equals("ADJ") || tag.equals("NOUN")
                    || tag.equals("PROPN") || tag.equals("PRON") || tag.equals("NUM")
                    || tokens.get(at).shape() == Token.Shape.CODE_SPAN;
            if (!nominal || subjects == 6) {
                return false;
            }
            subjects++;
            at++;
        }
        return false;
    }

    /**
     * The type of clause the token at index {@code at} opens in an outer clause from index {@code start} to index
     * {@code end}, or null when it opens none. The token before it and the tokens after it are read only inside the
     * outer clause; the first rule that holds:
     * <ol>
     *   <li>a token tagged SCONJ that is not one of {@link #NOT_OPENING}: SUBORDINATE;</li>
     *   <li>{@code that} tagged SCONJ: RELATIVE after a NOUN or PROPN, else SUBORDINATE;</li>
     *   <li>one of {@link #RELATIVE_PRONOUNS} tagged PRON, DET or ADV, after a NOUN, PROPN or NUM, a CODE_SPAN, a comma
     *       or a {@code )}: RELATIVE;</li>
     *   <li>one of {@link #FREE_RELATIVES}: RELATIVE;</li>
     *   <li>{@code without} followed by a VERB ending in {@code ing} or by a PRON: SUBORDINATE;</li>
     *   <li>one of {@link #QUESTION_WORDS} after a verb or an ADP: SUBORDINATE;</li>
     *   <li>one of {@link #PERSONAL_PRONOUNS} tagged PRON, after a NOUN and followed by a verb: RELATIVE;</li>
     *   <li>one of {@link #RELATIVE_DETERMINERS} tagged DET after a NOUN, when among the three tokens after it one or
     *       more ADJ or NOUN tokens, the last a NOUN, are followed by a VERB that does not end in {@code ing}:
     *       RELATIVE.</li>
     * </ol>
     */
    public static Clause.Type opener(String text, List<Token> tokens, int at, int start, int end) {
        Token token = tokens.get(at);
        String word = token.range().surfaceIn(text).toLowerCase();
        String tag = token.tag().name();
        String previousTag = at > start ? tokens.get(at - 1).tag().name() : "";
        String previousWord = at > start ? tokens.get(at - 1).range().surfaceIn(text) : "";
        if (tag.equals("SCONJ") && !NOT_OPENING.contains(word)) {
            return Clause.Type.SUBORDINATE;
        }
        if (tag.equals("SCONJ") && word.equals("that")) {
            if (previousTag.equals("NOUN") || previousTag.equals("PROPN")) {
                return Clause.Type.RELATIVE;
            }
            return Clause.Type.SUBORDINATE;
        }
        boolean relativeTag = tag.equals("PRON") || tag.equals("DET") || tag.equals("ADV");
        boolean relativePredecessor = previousTag.equals("NOUN") || previousTag.equals("PROPN")
                || previousTag.equals("NUM") || previousWord.equals(",") || previousWord.equals(")")
                || at > start && tokens.get(at - 1).shape() == Token.Shape.CODE_SPAN;
        if (RELATIVE_PRONOUNS.contains(word) && relativeTag && relativePredecessor) {
            return Clause.Type.RELATIVE;
        }
        if (FREE_RELATIVES.contains(word)) {
            return Clause.Type.RELATIVE;
        }
        if (word.equals("without") && at < end) {
            String nextWord = tokens.get(at + 1).range().surfaceIn(text).toLowerCase();
            String nextTag = tokens.get(at + 1).tag().name();
            if (nextTag.equals("PRON") || nextTag.equals("VERB") && nextWord.endsWith("ing")) {
                return Clause.Type.SUBORDINATE;
            }
        }
        boolean previousVerb = previousTag.equals("VERB") || previousTag.equals("AUX");
        if (QUESTION_WORDS.contains(word) && (previousVerb || previousTag.equals("ADP"))) {
            return Clause.Type.SUBORDINATE;
        }
        if (PERSONAL_PRONOUNS.contains(word) && tag.equals("PRON") && previousTag.equals("NOUN") && at < end) {
            String nextTag = tokens.get(at + 1).tag().name();
            if (nextTag.equals("VERB") || nextTag.equals("AUX")) {
                return Clause.Type.RELATIVE;
            }
        }
        if (RELATIVE_DETERMINERS.contains(word) && tag.equals("DET") && previousTag.equals("NOUN")) {
            int lastNoun = -1;
            int limit = Math.min(end, at + 3);
            for (int i = at + 1; i <= limit; i++) {
                String candidate = tokens.get(i).tag().name();
                if (candidate.equals("ADJ") || candidate.equals("NOUN")) {
                    if (candidate.equals("NOUN")) {
                        lastNoun = i;
                    }
                } else {
                    break;
                }
            }
            if (lastNoun >= 0 && lastNoun < end) {
                Token verb = tokens.get(lastNoun + 1);
                String verbWord = verb.range().surfaceIn(text).toLowerCase();
                if (verb.tag().name().equals("VERB") && !verbWord.endsWith("ing")) {
                    return Clause.Type.RELATIVE;
                }
            }
        }
        return null;
    }

    private static boolean isVerb(Token token) {
        String tag = token.tag().name();
        return tag.equals("VERB") || tag.equals("AUX");
    }

    private static boolean hasVerb(List<Token> tokens, int start, int end) {
        for (int at = start; at <= end; at++) {
            if (isVerb(tokens.get(at))) {
                return true;
            }
        }
        return false;
    }

    private static int verbGroupEnd(List<Token> tokens, int start, int end) {
        int at = start;
        while (at < end && !tokens.get(at).tag().name().equals("VERB")) {
            Token next = tokens.get(at + 1);
            if (isVerb(next)) {
                at++;
            } else if ((next.tag().name().equals("ADV") || next.tag().name().equals("PART"))
                    && at + 2 <= end && isVerb(tokens.get(at + 2))) {
                at += 2;
            } else {
                break;
            }
        }
        return at;
    }

    private static boolean participialOpener(String text, List<Token> tokens, int at, int end) {
        if (at >= end || !tokens.get(at).tag().name().equals("SCONJ")
                || !tokens.get(at + 1).tag().name().equals("VERB")) {
            return false;
        }
        String word = tokens.get(at + 1).range().surfaceIn(text).toLowerCase();
        return word.endsWith("ing") || word.endsWith("ed") || word.endsWith("en");
    }

    private static final class Draft {
        private final int start;
        private final int end;
        private final boolean table;
        private final boolean afterColon;
        private final boolean firstInSentence;

        private Draft(int start, int end, boolean table, boolean afterColon, boolean firstInSentence) {
            this.start = start;
            this.end = end;
            this.table = table;
            this.afterColon = afterColon;
            this.firstInSentence = firstInSentence;
        }
    }

    private static int sentenceStart(List<Token> tokens, Sentence sentence) {
        String first = sentence.tokenIds().get(0);
        for (int at = 0; at < tokens.size(); at++) {
            if (tokens.get(at).id().equals(first)) {
                return at;
            }
        }
        throw new IllegalArgumentException("sentence token is absent: " + first);
    }

    private static String word(String text, List<Token> tokens, int at) {
        return tokens.get(at).range().surfaceIn(text);
    }

    private static int nextSemicolon(String text, List<Token> tokens, int from, int end) {
        for (int at = from; at <= end; at++) {
            if (word(text, tokens, at).equals(";")) {
                return at;
            }
        }
        return end;
    }

    private static boolean clauseStart(String text, List<Token> tokens, int from, int end) {
        if (from > end) {
            return false;
        }
        if (isVerb(tokens.get(from))) {
            return true;
        }
        if (tokens.get(from).tag().name().equals("ADV") && from < end && isVerb(tokens.get(from + 1))) {
            return true;
        }
        return subjectAndVerb(text, tokens, from, end);
    }

    private static final class Nested {
        private final int start;
        private final int end;
        private final Clause.Type type;
        private final Nested parent;

        private Nested(int start, int end, Clause.Type type) {
            this(start, end, type, null);
        }

        private Nested(int start, int end, Clause.Type type, Nested parent) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.parent = parent;
        }
    }

    private static boolean insideNested(int at, List<Nested> nested) {
        for (Nested child : nested) {
            if (child.start <= at && at <= child.end) {
                return true;
            }
        }
        return false;
    }

    private static boolean ownedHasVerb(List<Token> tokens, int start, int end, List<Nested> nested) {
        for (int at = start; at <= end; at++) {
            if (!insideNested(at, nested) && isVerb(tokens.get(at))) {
                return true;
            }
        }
        return false;
    }

    private static String ownedHead(List<Token> tokens, int start, int end, List<Nested> nested) {
        int firstAux = -1;
        int lastAux = -1;
        for (int at = start; at <= end; at++) {
            if (insideNested(at, nested)) {
                continue;
            }
            String tag = tokens.get(at).tag().name();
            if (tag.equals("VERB")) {
                return tokens.get(at).id();
            }
            if (tag.equals("AUX")) {
                if (firstAux < 0) {
                    firstAux = at;
                    lastAux = at;
                } else if (at == lastAux + 1) {
                    lastAux = at;
                }
            }
        }
        return firstAux < 0 ? null : tokens.get(lastAux).id();
    }

    private static Clause.Asserted asserted(String text, List<Token> tokens, int start, int end,
            Clause.Type type, List<Marker> markers) {
        int rangeStart = tokens.get(start).range().start();
        int rangeEnd = tokens.get(end).range().end();
        for (Marker marker : markers) {
            if (marker.markerClass() == Marker.Class.QUOTATION
                    && marker.range().start() <= rangeStart && rangeEnd <= marker.range().end()) {
                return Clause.Asserted.QUOTED;
            }
        }
        if (type == Clause.Type.SUBORDINATE && word(text, tokens, start).equalsIgnoreCase("that")
                && start > 0 && MarkerRecognizer.SAYING.contains(word(text, tokens, start - 1).toLowerCase())) {
            return Clause.Asserted.ATTRIBUTED;
        }
        return Clause.Asserted.ASSERTED;
    }

    private static List<Nested> directChildren(Nested parent, List<Nested> nested) {
        List<Nested> children = new ArrayList<>();
        for (Nested child : nested) {
            if (child.parent == parent) {
                children.add(child);
            }
        }
        return children;
    }

    private static List<Nested> recursiveNestedClauses(String text, List<Token> tokens, int start, int end) {
        List<Nested> nested = new ArrayList<>();
        collectNested(text, tokens, start, end, null, nested);
        nested.sort((left, right) -> left.start != right.start
                ? Integer.compare(left.start, right.start) : Integer.compare(right.end, left.end));
        return nested;
    }

    private static void collectNested(String text, List<Token> tokens, int start, int end, Nested parent,
            List<Nested> nested) {
        int scanStart = parent == null ? start : start + 1;
        for (Nested found : nestedClausesFrom(text, tokens, start, scanStart, end)) {
            Nested child = new Nested(found.start, found.end, found.type, parent);
            nested.add(child);
            collectNested(text, tokens, child.start, child.end, child, nested);
        }
    }

    private static List<Nested> nestedClausesFrom(String text, List<Token> tokens, int outerStart,
            int scanStart, int end) {
        List<Nested> nested = new ArrayList<>();
        int at = scanStart;
        while (at <= end) {
            Clause.Type type = opener(text, tokens, at, outerStart, end);
            if (type == null || type == Clause.Type.RELATIVE && word(text, tokens, at).equalsIgnoreCase("that")
                    && !hasVerb(tokens, at + 1, Math.min(end, at + 5)) || !hasVerb(tokens, at + 1, end)) {
                at++;
                continue;
            }
            int nestedEnd = end;
            boolean fourthRule = false;
            boolean punctuationOnly = true;
            for (int before = outerStart; before < at; before++) {
                String beforeWord = word(text, tokens, before);
                String beforeTag = tokens.get(before).tag().name();
                if (!beforeTag.equals("PUNCT") && !beforeTag.equals("SYM") && !beforeWord.equals("-")
                        && !beforeWord.equals("*") && !beforeWord.equals("**")) {
                    punctuationOnly = false;
                    break;
                }
            }
            if (punctuationOnly) {
                for (int scan = at + 1; scan <= end; scan++) {
                    if (word(text, tokens, scan).equals(",")) {
                        nestedEnd = scan;
                        break;
                    }
                }
            } else if (participialOpener(text, tokens, at, end)) {
                for (int scan = at + 2; scan <= end; scan++) {
                    String current = word(text, tokens, scan);
                    if (current.equals("(") || current.equals(")")
                            || tokens.get(scan).tag().name().equals("SCONJ")) {
                        nestedEnd = scan - 1;
                        break;
                    }
                    if (current.equals(",") || current.equals(";") || current.equals(".")
                            || current.equals("—") || current.equals(":")) {
                        nestedEnd = scan;
                        break;
                    }
                }
            } else {
                int firstVerb = -1;
                for (int scan = at + 1; scan <= end; scan++) {
                    if (isVerb(tokens.get(scan))) {
                        firstVerb = scan;
                        break;
                    }
                }
                int firstGroupEnd = firstVerb < 0 ? at : verbGroupEnd(tokens, firstVerb, end);
                boolean thirdRule = false;
                if (firstVerb >= 0 && !ownedHasVerb(tokens, outerStart, at - 1, nested)) {
                    int scan = firstGroupEnd + 1;
                    while (scan <= end) {
                        if (!isVerb(tokens.get(scan))) {
                            scan++;
                            continue;
                        }
                        int groupEnd = verbGroupEnd(tokens, scan, end);
                        if (!tokens.get(scan - 1).tag().name().equals("CCONJ")) {
                            nestedEnd = scan - 1;
                            thirdRule = true;
                            break;
                        }
                        scan = groupEnd + 1;
                    }
                }
                if (!thirdRule) {
                    fourthRule = true;
                    for (int scan = at + 2; scan <= end; scan++) {
                        String current = word(text, tokens, scan);
                        String tag = tokens.get(scan).tag().name();
                        if (tag.equals("SCONJ") && !current.equalsIgnoreCase("that")) {
                            nestedEnd = scan - 1;
                            break;
                        }
                        if (scan <= firstGroupEnd) {
                            continue;
                        }
                        if (current.equals(";") || current.equals(":") || current.equals("(")
                                || current.equals("|") || current.equals("—")) {
                            nestedEnd = scan - 1;
                            break;
                        }
                        if (current.equals(",")) {
                            boolean followedByVerb = scan < end && isVerb(tokens.get(scan + 1));
                            boolean followedByConjoinedVerb = scan + 2 <= end
                                    && tokens.get(scan + 1).tag().name().equals("CCONJ")
                                    && isVerb(tokens.get(scan + 2));
                            if (!followedByVerb && !followedByConjoinedVerb) {
                                nestedEnd = scan - 1;
                                break;
                            }
                        }
                    }
                }
            }
            if (fourthRule && nestedEnd == end) {
                String last = word(text, tokens, nestedEnd);
                if (last.equals(";") || last.equals(":") || last.equals("|") || last.equals(",")) {
                    nestedEnd--;
                }
            }
            if (nestedEnd >= at && (at > outerStart || nestedEnd < end)) {
                nested.add(new Nested(at, nestedEnd, type));
                at = nestedEnd + 1;
            } else {
                at++;
            }
        }
        return nested;
    }
}
