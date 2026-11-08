package hermit.text.semantic;

import hermit.text.model.*;
import hermit.text.model.AnnotationDocument;
import hermit.text.model.Marker;
import hermit.text.model.Provenance;
import hermit.text.model.Span;
import hermit.text.model.Tag;
import hermit.text.model.TextRange;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * The logical markers of a text (milestone A4 of the annotation plan, section 6.2). A marker covers WORD tokens, so a
 * word in a code span ({@code may} in a table of modal verbs) is never one, nor is a WORD that alone fills a
 * quotation whose marks touch it ({@code 'not'}). Words are compared in lower case; "next" and "before" mean the
 * neighbouring token of the same sentence. Two readings of the same words are two markers.
 * <ul>
 *   <li><b>NEGATION</b>: {@code not} and {@code n't} (but not in {@code not only}), {@code never},
 *       {@code cannot}, {@code nowhere}, {@code no longer} and {@code fails to} or {@code failed to} are
 *       {@code clausal}; {@code no} followed by a noun phrase ({@link #nounPhraseEnd}) or by a token tagged VERB,
 *       and {@code neither} tagged DET followed by a noun phrase are
 *       {@code determiner}; {@code none}, {@code nothing}, {@code nobody} and any other {@code neither} are
 *       {@code pronoun}; {@code without} is {@code prepositional}.</li>
 *   <li><b>QUANTIFIER</b>: each negative determiner or pronoun above ({@code no}, {@code neither}, {@code none},
 *       {@code nothing}, {@code nobody}) is also {@code negative}; {@code every}, {@code each}, {@code all},
 *       {@code both}, {@code everything}, {@code everyone} and {@code everybody} are {@code universal}; {@code any}
 *       is {@code universal}, or {@code existential} when a NEGATION marker comes before it in its sentence;
 *       {@code some} and {@code there is}, {@code there are}, {@code there was} or {@code there were} are
 *       {@code existential}; {@code most}, {@code many} and {@code few} followed by a noun phrase are
 *       {@code proportional}; {@code only} tagged ADV is {@code exclusive}, except after {@code not}, {@code n't} or
 *       {@code if} and before {@code if}.</li>
 *   <li><b>MODALITY</b>, for a word tagged AUX: {@code can} and {@code cannot} are {@code ability}; {@code could} is
 *       {@code ability} before {@code not} or {@code n't}, else {@code possibility}; {@code may}, {@code might} and
 *       {@code would} are {@code possibility}; {@code must} and {@code shall} are {@code necessity}; {@code should} is
 *       {@code advice}. Besides, {@code need}, {@code needs}, {@code have}, {@code has} or {@code had} before
 *       {@code to} is {@code necessity} over both words, and a form of {@code be} ({@code be}, {@code is},
 *       {@code are}, {@code was}, {@code were}, {@code been}) before {@code able to} is {@code ability}, before
 *       {@code allowed to} {@code permission}, over the three words. {@code will} is not a marker.</li>
 *   <li><b>FREQUENCY</b>: {@code never} ({@code never}), {@code always} ({@code always}), {@code often} and
 *       {@code usually} ({@code often}), {@code sometimes}, {@code occasionally}, {@code rarely} and {@code seldom}
 *       ({@code sometimes}), and {@code again}, {@code twice} and any other {@code once} ({@code count}).</li>
 *   <li><b>TEMPORAL</b>: {@code until}, {@code while}, {@code during}, {@code then} tagged ADV, {@code when}, and
 *       {@code once} followed by a token tagged DET, PRON, NOUN or PROPN (the subject of its clause); {@code before}
 *       and {@code after} unless they name a place: followed by a pronoun that no VERB or AUX follows
 *       ({@code before it.}), or by a noun phrase whose head is one of {@link #PLACE_NOUNS}
 *       ({@code after the colon}); and {@code since} as below.</li>
 *   <li><b>{@code since}</b>: when one of the next two tokens is a HASH, NUMBER or VERSION token or starts a DATE or
 *       TIME span (the tokens before it tagged PROPN or NOUN), it is TEMPORAL, and also CAUSAL when the token after
 *       that one is tagged VERB or AUX ({@code since 4f2a9c1 moved ...}); otherwise it is CAUSAL when it is the first
 *       word of its sentence or follows a comma, a semicolon or a dash, and a token tagged VERB or AUX comes after it
 *       before the next PUNCT token; otherwise TEMPORAL.</li>
 * </ul>
 * The other classes, the scopes and the order of the markers are in {@link #recognize}.
 */
public final class MarkerRecognizer {

    /** Verbs of saying that report a quotation or a {@code that} clause, in lower case. */
    public static final List<String> SAYING = List.of("say", "says", "said", "claim", "claims", "claimed", "report",
            "reports", "reported", "note", "notes", "noted", "state", "states", "stated", "answer", "answers",
            "answered", "reply", "replies", "replied", "think", "thinks", "thought", "believe", "believes", "believed",
            "argue", "argues", "argued");

    /** Possessive pronouns, which may stand in a noun phrase as determiners do, in lower case. */
    public static final List<String> POSSESSIVES = List.of("its", "their", "his", "her", "our", "your", "my");

    /** Nouns of a position in a text or in code: {@code before} or {@code after} one of them is no TEMPORAL. */
    public static final List<String> PLACE_NOUNS = List.of("line", "lines", "column", "colon", "comma", "semicolon",
            "brace", "bracket", "parenthesis", "comment", "comments", "member", "members", "token", "tokens", "word",
            "words", "character", "characters", "dot", "cell", "field", "header", "tag");

    /**
     * The markers of {@code input}, whose concept spans are {@code spans}: those of the class javadoc, and these:
     * <ul>
     *   <li><b>CAUSAL</b>: {@code because}, {@code therefore}, {@code hence}, {@code thus}, {@code due to},
     *       {@code so that}, and {@code so} tagged CCONJ or SCONJ.</li>
     *   <li><b>CONDITIONAL</b>: {@code if}, {@code unless}, {@code whenever}, {@code only if}, {@code in case};
     *       {@code otherwise} as the first word of its sentence or after a comma, a semicolon or {@code or};
     *       {@code else} unless it follows a word such as {@code nothing}, {@code something} or {@code what}; and
     *       {@code when} that states a condition: not the first word of its sentence, and followed by a token tagged
     *       DET, PRON, NOUN, PROPN or ADJ (its clause's subject).</li>
     *   <li><b>ATTRIBUTION</b>: {@code according to}, and a verb of saying ({@link #SAYING}) tagged VERB whose next
     *       token, or the one after a {@code with}, a colon or a comma, opens a quotation or is {@code that} tagged
     *       SCONJ.</li>
     *   <li><b>HEDGE</b>: {@code probably}, {@code likely}, {@code perhaps}, {@code possibly}, {@code roughly},
     *       {@code approximately}, {@code seems}, {@code seem} and {@code appears to}; {@code about} and {@code around}
     *       when the next token starts a NUMBER, QUANTITY, PERCENT or DURATION span; and a {@code ~} that touches the
     *       next token.</li>
     *   <li><b>QUOTATION</b> ({@code quote}): a pair of double quotation marks ({@code "}, or U+201C then U+201D) in
     *       one sentence, or of single ones ({@code '}, or U+2018 then U+2019) whose opening mark follows whitespace,
     *       the text's start or an opening bracket and touches the next token, and whose closing mark touches the token
     *       before it and is followed by whitespace, a punctuation mark or the text's end. The marker covers both
     *       marks; a pair with nothing between the marks is none.</li>
     * </ul>
     * <p>Scope (schema 1.1), as a token list:
     * <ul>
     *   <li>a determiner NEGATION or QUANTIFIER, and every QUANTIFIER followed by a noun phrase except one tagged PRON:
     *       from itself to the end of that phrase ({@code every plan}); {@code many} and the like start the scope one
     *       token earlier after {@code that}, {@code so}, {@code too}, {@code how} or {@code as};</li>
     *   <li>{@code without} followed by a noun phrase (not by a VERB, nor by a pronoun and a VERB or AUX): itself and
     *       the phrase;</li>
     *   <li>{@code only}: itself and the noun phrase after it; with none after it, and before the sentence's end or a
     *       PUNCT or SYM token, the run of NOUN, PROPN, ADJ and DET tokens before it that ends in a NOUN or PROPN;</li>
     *   <li>{@code none}, and a universal or existential QUANTIFIER with no noun phrase after it: itself;
     *       {@code everything} and the like: itself, and a following {@code else};</li>
     *   <li>{@code there is} and the like: the noun phrase after the verb;</li>
     *   <li>a MODALITY: the verb it governs ({@link #governedVerb}), after its last word;</li>
     *   <li>a QUOTATION: the tokens between its marks; an ATTRIBUTION: the quotation it introduces, marks
     *       included;</li>
     *   <li>a clausal NEGATION or a FREQUENCY marker inside a quotation, with no other scope: the quotation's tokens
     *       between its marks.</li>
     * </ul>
     * Every other scope is a clause, which needs the clause layer (task a4r2): until then the scope is a Scope whose
     * five parts are null. So is a scope above whose phrase or verb is not found.
     * <p>Markers come in text order: by their first token, then the longer first, then in the order of
     * {@link Marker.Class}. Their ids are {@code m1}, {@code m2} and so on in that order, and each provenance is
     * {@link Provenance#rule} of {@code marker:} and the rule's name.
     */
    public List<Marker> recognize(ConceptRecognizer.Input input, List<Span> spans) {
        String text = input.text();
        List<Token> tokens = input.tokens();
        List<FoundMarker> found = new LinkedList<>();
        for (Sentence sentence : input.sentences()) {
            if (sentence.tokenIds().isEmpty() || sentence.kind() == Sentence.Kind.CODE) {
                continue;
            }
            int start = -1;
            int end = -1;
            for (int j = 0; j < tokens.size(); j++) {
                if (tokens.get(j).id().equals(sentence.tokenIds().get(0))) {
                    start = j;
                }
                if (tokens.get(j).id().equals(sentence.tokenIds().get(sentence.tokenIds().size() - 1))) {
                    end = j;
                }
            }
            if (start < 0 || end < start) {
                continue;
            }
            List<int[]> quotes = new LinkedList<>();
            for (int i = start; i <= end; i++) {
                String opening = markerWord(text, tokens, i);
                if (!List.of("\"", "'", "“", "‘").contains(opening)) {
                    continue;
                }
                boolean single = opening.equals("'") || opening.equals("‘");
                int offset = tokens.get(i).range().start();
                if (single && (i == end || tokens.get(i).range().end() != tokens.get(i + 1).range().start()
                        || offset > 0 && !Character.isWhitespace(text.charAt(offset - 1))
                        && "([{“.".indexOf(text.charAt(offset - 1)) < 0)) {
                    continue;
                }
                String closing = opening.equals("“") ? "”" : opening.equals("‘") ? "’" : opening;
                for (int j = i + 1; j <= end; j++) {
                    if (!markerWord(text, tokens, j).equals(closing)) {
                        continue;
                    }
                    int after = tokens.get(j).range().end();
                    boolean boundary = after == text.length() || Character.isWhitespace(text.charAt(after));
                    if (!boundary) {
                        int category = Character.getType(text.charAt(after));
                        boundary = category == Character.CONNECTOR_PUNCTUATION
                                || category == Character.DASH_PUNCTUATION
                                || category == Character.START_PUNCTUATION
                                || category == Character.END_PUNCTUATION
                                || category == Character.INITIAL_QUOTE_PUNCTUATION
                                || category == Character.FINAL_QUOTE_PUNCTUATION
                                || category == Character.OTHER_PUNCTUATION;
                    }
                    if (single && (!boundary
                            || tokens.get(j - 1).range().end() != tokens.get(j).range().start())) {
                        continue;
                    }
                    if (j > i + 1) {
                        quotes.add(new int[] {i, j});
                        collectMarker(found, tokens, i, j, Marker.Class.QUOTATION, "quote", i + 1, j - 1,
                                "quote");
                    }
                    i = j;
                    break;
                }
            }
            boolean precedingNegation = false;
            boolean wordSeen = false;
            for (int i = start; i <= end; i++) {
                Token token = tokens.get(i);
                String word = markerWord(text, tokens, i);
                String previous = i > start ? markerWord(text, tokens, i - 1) : "";
                String next = i < end ? markerWord(text, tokens, i + 1) : "";
                Tag nextTag = i < end ? tokens.get(i + 1).tag() : Tag.X;
                boolean firstWord = !wordSeen;
                if (token.shape() == Token.Shape.WORD) {
                    wordSeen = true;
                }
                int quoteFirst = -1;
                int quoteLast = -1;
                boolean mentioned = false;
                for (int[] quote : quotes) {
                    if (quote[0] < i && i < quote[1]) {
                        quoteFirst = quote[0] + 1;
                        quoteLast = quote[1] - 1;
                        mentioned = quote[1] == quote[0] + 2
                                && tokens.get(quote[0]).range().end() == token.range().start()
                                && token.range().end() == tokens.get(quote[1]).range().start();
                    }
                }
                if (word.equals("~") && i < end && token.shape() != Token.Shape.CODE_SPAN
                        && token.range().end() == tokens.get(i + 1).range().start()) {
                    collectMarker(found, tokens, i, i, Marker.Class.HEDGE, null, -1, -1, word);
                }
                if (token.shape() != Token.Shape.WORD || mentioned) {
                    continue;
                }
                int phraseEnd = nounPhraseEnd(text, tokens, i + 1, end);
                String negation = null;
                int negationLast = i;
                if (List.of("not", "n't").contains(word) && !next.equals("only")
                        || List.of("never", "cannot", "nowhere").contains(word)) {
                    negation = "clausal";
                } else if (word.equals("no") && next.equals("longer")
                        || List.of("fails", "failed").contains(word) && next.equals("to")) {
                    negation = "clausal";
                    negationLast++;
                } else if (word.equals("no") && (phraseEnd >= i + 1 || nextTag == Tag.VERB)) {
                    negation = "determiner";
                } else if (word.equals("neither")) {
                    negation = token.tag() == Tag.DET && phraseEnd >= i + 1 ? "determiner" : "pronoun";
                } else if (List.of("none", "nothing", "nobody").contains(word)) {
                    negation = "pronoun";
                } else if (word.equals("without")) {
                    negation = "prepositional";
                }
                if (negation != null) {
                    int sf = -1;
                    int sl = -1;
                    boolean pronounClause = nextTag == Tag.PRON && i + 2 <= end
                            && List.of(Tag.VERB, Tag.AUX).contains(tokens.get(i + 2).tag());
                    if (phraseEnd >= i + 1 && (negation.equals("determiner")
                            || word.equals("without") && nextTag != Tag.VERB && !pronounClause)) {
                        sf = i;
                        sl = phraseEnd;
                    } else if (word.equals("none")) {
                        sf = i;
                        sl = i;
                    } else if (negation.equals("clausal")) {
                        sf = quoteFirst;
                        sl = quoteLast;
                    }
                    collectMarker(found, tokens, i, negationLast, Marker.Class.NEGATION, negation, sf, sl,
                            negationLast == i ? word : word + " " + next);
                }
                String quantifier = null;
                int quantifierLast = i;
                if (negation != null && List.of("determiner", "pronoun").contains(negation)) {
                    quantifier = "negative";
                } else if (List.of("every", "each", "all", "both", "everything", "everyone", "everybody")
                        .contains(word)) {
                    quantifier = "universal";
                } else if (word.equals("any")) {
                    quantifier = precedingNegation ? "existential" : "universal";
                } else if (word.equals("some")) {
                    quantifier = "existential";
                } else if (word.equals("there") && List.of("is", "are", "was", "were").contains(next)) {
                    quantifier = "existential";
                    quantifierLast++;
                } else if (List.of("most", "many", "few").contains(word) && phraseEnd >= i + 1) {
                    quantifier = "proportional";
                } else if (word.equals("only") && token.tag() == Tag.ADV
                        && !List.of("not", "n't", "if").contains(previous) && !next.equals("if")) {
                    quantifier = "exclusive";
                }
                if (quantifier != null) {
                    int sf = -1;
                    int sl = -1;
                    if (quantifierLast > i) {
                        sl = nounPhraseEnd(text, tokens, quantifierLast + 1, end);
                        sf = sl >= quantifierLast + 1 ? quantifierLast + 1 : -1;
                    } else if (word.equals("none")) {
                        sf = i;
                        sl = i;
                    } else if (List.of("everything", "everyone", "everybody").contains(word)) {
                        sf = i;
                        sl = next.equals("else") ? i + 1 : i;
                    } else if (phraseEnd >= i + 1 && token.tag() != Tag.PRON) {
                        sf = i;
                        sl = phraseEnd;
                        if (quantifier.equals("proportional")
                                && List.of("that", "so", "too", "how", "as").contains(previous)) {
                            sf--;
                        }
                    } else if (word.equals("only") && i > start
                            && (i == end || nextTag == Tag.PUNCT || nextTag == Tag.SYM)
                            && List.of(Tag.NOUN, Tag.PROPN).contains(tokens.get(i - 1).tag())) {
                        sf = i - 1;
                        sl = i - 1;
                        while (sf > start && List.of(Tag.NOUN, Tag.PROPN, Tag.ADJ, Tag.DET)
                                .contains(tokens.get(sf - 1).tag())) {
                            sf--;
                        }
                    } else if (List.of("universal", "existential").contains(quantifier)) {
                        sf = i;
                        sl = i;
                    }
                    collectMarker(found, tokens, i, quantifierLast, Marker.Class.QUANTIFIER, quantifier, sf, sl,
                            quantifierLast == i ? word : word + " " + next);
                }
                precedingNegation |= negation != null;
                String modality = null;
                int modalLast = i;
                if (token.tag() == Tag.AUX) {
                    modality = switch (word) {
                        case "can", "cannot" -> "ability";
                        case "could" -> List.of("not", "n't").contains(next) ? "ability" : "possibility";
                        case "may", "might", "would" -> "possibility";
                        case "must", "shall" -> "necessity";
                        case "should" -> "advice";
                        default -> null;
                    };
                }
                if (List.of("need", "needs", "have", "has", "had").contains(word) && next.equals("to")) {
                    modality = "necessity";
                    modalLast++;
                } else if (List.of("be", "is", "are", "was", "were", "been").contains(word)
                        && List.of("able", "allowed").contains(next) && i + 2 <= end
                        && markerWord(text, tokens, i + 2).equals("to")) {
                    modality = next.equals("able") ? "ability" : "permission";
                    modalLast += 2;
                }
                if (modality != null) {
                    int verb = governedVerb(text, tokens, modalLast, start, end);
                    collectMarker(found, tokens, i, modalLast, Marker.Class.MODALITY, modality, verb, verb, word);
                }
                boolean onceClause = word.equals("once")
                        && List.of(Tag.DET, Tag.PRON, Tag.NOUN, Tag.PROPN).contains(nextTag);
                String frequency = switch (word) {
                    case "never" -> "never";
                    case "always" -> "always";
                    case "often", "usually" -> "often";
                    case "sometimes", "occasionally", "rarely", "seldom" -> "sometimes";
                    case "again", "twice" -> "count";
                    case "once" -> onceClause ? null : "count";
                    default -> null;
                };
                if (frequency != null) {
                    collectMarker(found, tokens, i, i, Marker.Class.FREQUENCY, frequency,
                            quoteFirst, quoteLast, word);
                }
                boolean temporal = List.of("until", "while", "during", "when").contains(word)
                        || word.equals("then") && token.tag() == Tag.ADV || onceClause;
                boolean causal = false;
                if (List.of("before", "after").contains(word)) {
                    boolean pronounPlace = nextTag == Tag.PRON && !(i + 2 <= end
                            && List.of(Tag.VERB, Tag.AUX).contains(tokens.get(i + 2).tag()));
                    boolean nounPlace = phraseEnd >= i + 1
                            && PLACE_NOUNS.contains(markerWord(text, tokens, phraseEnd));
                    temporal = !pronounPlace && !nounPlace;
                }
                if (word.equals("since")) {
                    int point = -1;
                    for (int j = i + 1; j <= Math.min(end, i + 2); j++) {
                        Token candidate = tokens.get(j);
                        boolean time = List.of(Token.Shape.HASH, Token.Shape.NUMBER, Token.Shape.VERSION)
                                .contains(candidate.shape());
                        for (Span span : spans) {
                            time |= span.range().start() == candidate.range().start()
                                    && List.of(Span.Concept.DATE, Span.Concept.TIME).contains(span.concept());
                        }
                        if (time) {
                            point = j;
                            break;
                        }
                        if (!List.of(Tag.PROPN, Tag.NOUN).contains(candidate.tag())) {
                            break;
                        }
                    }
                    if (point >= 0) {
                        temporal = true;
                        causal = point < end
                                && List.of(Tag.VERB, Tag.AUX).contains(tokens.get(point + 1).tag());
                    } else {
                        boolean verb = false;
                        for (int j = i + 1; j <= end && tokens.get(j).tag() != Tag.PUNCT; j++) {
                            verb |= List.of(Tag.VERB, Tag.AUX).contains(tokens.get(j).tag());
                        }
                        causal = verb && (firstWord || List.of(",", ";", "-", "—", "–").contains(previous));
                        temporal = !causal;
                    }
                }
                if (temporal) {
                    collectMarker(found, tokens, i, i, Marker.Class.TEMPORAL, null, -1, -1, word);
                }
                int causalLast = i;
                if (word.equals("due") && next.equals("to") || word.equals("so") && next.equals("that")) {
                    causal = true;
                    causalLast++;
                }
                causal |= List.of("because", "therefore", "hence", "thus").contains(word)
                        || word.equals("so") && List.of(Tag.CCONJ, Tag.SCONJ).contains(token.tag());
                if (causal) {
                    collectMarker(found, tokens, i, causalLast, Marker.Class.CAUSAL, null, -1, -1, word);
                }
                boolean conditional = List.of("unless", "whenever").contains(word)
                        || word.equals("if") && !previous.equals("only")
                        || word.equals("otherwise") && (firstWord || List.of(",", ";", "or").contains(previous))
                        || word.equals("when") && !firstWord
                        && List.of(Tag.DET, Tag.PRON, Tag.NOUN, Tag.PROPN, Tag.ADJ).contains(nextTag);
                if (word.equals("else") && !List.of("nothing", "something", "anything", "everything", "what",
                        "nobody", "somebody", "anybody", "everybody", "someone", "anyone", "everyone", "who")
                        .contains(previous)) {
                    conditional = true;
                }
                int conditionalLast = i;
                if (word.equals("only") && next.equals("if") || word.equals("in") && next.equals("case")) {
                    conditional = true;
                    conditionalLast++;
                }
                if (conditional) {
                    collectMarker(found, tokens, i, conditionalLast, Marker.Class.CONDITIONAL, null, -1, -1, word);
                }
                boolean hedge = List.of("probably", "likely", "perhaps", "possibly", "roughly", "approximately",
                        "seems", "seem").contains(word);
                int hedgeLast = i;
                if (word.equals("appears") && next.equals("to")) {
                    hedge = true;
                    hedgeLast++;
                }
                if (List.of("about", "around").contains(word) && i < end) {
                    for (Span span : spans) {
                        hedge |= span.range().start() == tokens.get(i + 1).range().start()
                                && List.of(Span.Concept.NUMBER, Span.Concept.QUANTITY, Span.Concept.PERCENT,
                                        Span.Concept.DURATION).contains(span.concept());
                    }
                }
                if (hedge) {
                    collectMarker(found, tokens, i, hedgeLast, Marker.Class.HEDGE, null, -1, -1, word);
                }
                if (word.equals("according") && next.equals("to")) {
                    collectMarker(found, tokens, i, i + 1, Marker.Class.ATTRIBUTION, null, -1, -1, "according to");
                } else if (SAYING.contains(word) && token.tag() == Tag.VERB) {
                    int introduced = i + (List.of("with", ":", ",").contains(next) ? 2 : 1);
                    boolean attribution = introduced <= end
                            && markerWord(text, tokens, introduced).equals("that")
                            && tokens.get(introduced).tag() == Tag.SCONJ;
                    int sf = -1;
                    int sl = -1;
                    for (int[] quote : quotes) {
                        if (quote[0] == introduced) {
                            attribution = true;
                            sf = quote[0];
                            sl = quote[1];
                            break;
                        }
                    }
                    if (attribution) {
                        collectMarker(found, tokens, i, i, Marker.Class.ATTRIBUTION, null, sf, sl, word);
                    }
                }
            }
        }
        found.sort((left, right) -> {
            int order = Integer.compare(left.first(), right.first());
            if (order == 0) {
                order = Integer.compare(right.last(), left.last());
            }
            return order == 0 ? left.markerClass().compareTo(right.markerClass()) : order;
        });
        List<Marker> result = new LinkedList<>();
        for (FoundMarker marker : found) {
            Token first = tokens.get(marker.first());
            Token last = tokens.get(marker.last());
            result.add(new Marker("m" + (result.size() + 1),
                    new TextRange(first.range().start(), last.range().end()),
                    tokens.subList(marker.first(), marker.last() + 1).stream().map(Token::id).toList(),
                    marker.markerClass(), marker.kind(), marker.scope(), Provenance.rule("marker:" + marker.rule())));
        }
        return result;
    }

    /** The markers of {@code text}, from the standard input and concept spans for it. */
    public static List<Marker> markersOf(String text, AnnotationDocument.Context context) {
        ConceptRecognizer.Input input = ConceptRecognizers.input(text, context);
        return new MarkerRecognizer().recognize(input, ConceptRecognizers.standard().recognize(input));
    }

    /**
     * The index of the last token of the noun phrase that starts at {@code from}, or -1 when none starts there. A
     * phrase is a run of tokens tagged DET, ADJ, NOUN, PROPN or NUM, or possessive pronouns ({@link #POSSESSIVES}),
     * whose last NOUN, PROPN or NUM token is its head and its end. It goes on over a coordination: {@code and} or
     * {@code or} followed by another such run that does not start with a DET, or a comma followed by such runs
     * separated by commas up to an {@code and} or {@code or} and one more run. Only tokens up to {@code sentenceEnd}
     * (the index of the sentence's last token) are read.
     */
    public static int nounPhraseEnd(String text, List<Token> tokens, int from, int sentenceEnd) {
        if (from < 0 || from >= tokens.size() || from > sentenceEnd) {
            return -1;
        }
        int limit = Math.min(sentenceEnd, tokens.size() - 1);
        int index = from;
        int head = -1;
        while (index <= limit) {
            Token token = tokens.get(index);
            String tag = token.tag().name();
            String word = text.substring(token.range().start(), token.range().end()).toLowerCase(Locale.ROOT);
            if (!(tag.equals("DET") || tag.equals("ADJ") || tag.equals("NOUN") || tag.equals("PROPN")
                    || tag.equals("NUM") || tag.equals("PRON") && POSSESSIVES.contains(word))) {
                break;
            }
            if (tag.equals("NOUN") || tag.equals("PROPN") || tag.equals("NUM")) {
                head = index;
            }
            index++;
        }
        if (head < 0) {
            return -1;
        }
        int result = head;
        if (index > limit) {
            return result;
        }
        String separator = text.substring(tokens.get(index).range().start(), tokens.get(index).range().end())
                .toLowerCase(Locale.ROOT);
        if (separator.equals("and") || separator.equals("or")) {
            int next = index + 1;
            if (next <= limit && !tokens.get(next).tag().name().equals("DET")) {
                int coordinatedEnd = nounPhraseEnd(text, tokens, next, limit);
                if (coordinatedEnd >= next) {
                    result = coordinatedEnd;
                }
            }
            return result;
        }
        if (!separator.equals(",")) {
            return result;
        }
        int scan = index + 1;
        while (scan <= limit) {
            if (tokens.get(scan).tag().name().equals("DET")) {
                return result;
            }
            int run = scan;
            int runHead = -1;
            while (run <= limit) {
                Token token = tokens.get(run);
                String tag = token.tag().name();
                String word = text.substring(token.range().start(), token.range().end()).toLowerCase(Locale.ROOT);
                if (!(tag.equals("ADJ") || tag.equals("NOUN") || tag.equals("PROPN") || tag.equals("NUM")
                        || tag.equals("PRON") && POSSESSIVES.contains(word))) {
                    break;
                }
                if (tag.equals("NOUN") || tag.equals("PROPN") || tag.equals("NUM")) {
                    runHead = run;
                }
                run++;
            }
            if (runHead < scan || run > limit) {
                return result;
            }
            String nextSeparator = text.substring(tokens.get(run).range().start(), tokens.get(run).range().end())
                    .toLowerCase(Locale.ROOT);
            if (nextSeparator.equals(",")) {
                scan = run + 1;
                continue;
            }
            if (!nextSeparator.equals("and") && !nextSeparator.equals("or")) {
                return result;
            }
            int finalStart = run + 1;
            if (finalStart > limit || tokens.get(finalStart).tag().name().equals("DET")) {
                return result;
            }
            int finalEnd = nounPhraseEnd(text, tokens, finalStart, limit);
            return finalEnd >= finalStart ? finalEnd : result;
        }
        return result;
    }

    /**
     * The index of the verb a modal at {@code modal} governs, or -1: the first token after it tagged VERB, past tokens
     * tagged ADV, PART or AUX and the words {@code no} and {@code longer}, and, when no WORD token of its sentence
     * comes before the modal (a question), past its subject too (tokens tagged PRON, DET, NOUN, PROPN or ADJ). When
     * another token comes first, it is the last AUX passed over (a copula, as in {@code must be in}). At most six
     * tokens are read, up to {@code sentenceEnd}; {@code sentenceStart} is the index of the sentence's first token.
     */
    public static int governedVerb(String text, List<Token> tokens, int modal, int sentenceStart, int sentenceEnd) {
        if (modal < 0 || modal >= tokens.size() || modal >= sentenceEnd) {
            return -1;
        }
        int limit = Math.min(Math.min(sentenceEnd, tokens.size() - 1), modal + 6);
        boolean question = true;
        for (int i = Math.max(0, sentenceStart); i < modal; i++) {
            String tag = tokens.get(i).tag().name();
            if (!tag.equals("PUNCT") && !tag.equals("SYM")) {
                question = false;
                break;
            }
        }
        int lastAuxiliary = -1;
        for (int i = modal + 1; i <= limit; i++) {
            Token token = tokens.get(i);
            String tag = token.tag().name();
            String word = text.substring(token.range().start(), token.range().end()).toLowerCase(Locale.ROOT);
            if (tag.equals("VERB")) {
                return i;
            }
            if (tag.equals("AUX")) {
                lastAuxiliary = i;
                continue;
            }
            if (tag.equals("ADV") || tag.equals("PART") || word.equals("no") || word.equals("longer")) {
                continue;
            }
            if (question && (tag.equals("PRON") || tag.equals("DET") || tag.equals("NOUN")
                    || tag.equals("PROPN") || tag.equals("ADJ"))) {
                continue;
            }
            return lastAuxiliary;
        }
        return lastAuxiliary;
    }

    private record FoundMarker(int first, int last, Marker.Class markerClass, String kind, Marker.Scope scope,
            String rule) {}

    private static String markerWord(String text, List<Token> tokens, int index) {
        return tokens.get(index).range().surfaceIn(text).toLowerCase(Locale.ROOT);
    }

    private static Marker.Scope markerScope(List<Token> tokens, int first, int last) {
        List<String> ids = null;
        if (first >= 0 && last >= first && last < tokens.size()) {
            ids = tokens.subList(first, last + 1).stream().map(Token::id).toList();
        }
        return new Marker.Scope(null, ids, null, null, null);
    }

    private static void collectMarker(List<FoundMarker> found, List<Token> tokens, int first, int last,
            Marker.Class markerClass, String kind, int scopeFirst, int scopeLast, String rule) {
        found.add(new FoundMarker(first, last, markerClass, kind,
                markerScope(tokens, scopeFirst, scopeLast), rule));
    }
}
