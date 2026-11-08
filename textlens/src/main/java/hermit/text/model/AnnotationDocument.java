package hermit.text.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One annotated text, schema version 1: the unchanged text, the clock captured for it, the sentences, tokens, spans,
 * relations, checks and coverage over it, and the optional layers of schema 1.1 ({@link Layers}: markers, clauses,
 * frames, coreference and dates), which keep the version number 1. Offsets are half-open UTF-16 indices (see
 * {@link TextRange}); the text is never normalized, and whatever lies between tokens is whitespace.
 */
public record AnnotationDocument(int schemaVersion, String ruleset, String text, Context context,
        String codeIndexRevision, List<Sentence> sentences, List<Token> tokens, List<Span> spans,
        List<Relation> relations, List<Check> checks, List<Coverage> coverage, Layers layers) {

    /** The schema version this code writes, and the only one it reads. */
    public static final int SCHEMA_VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A document of schema version 1's eleven components, with no layers ({@link Layers#NONE}). */
    public AnnotationDocument(int schemaVersion, String ruleset, String text, Context context,
            String codeIndexRevision, List<Sentence> sentences, List<Token> tokens, List<Span> spans,
            List<Relation> relations, List<Check> checks, List<Coverage> coverage) {
        this(schemaVersion, ruleset, text, context, codeIndexRevision, sentences, tokens, spans, relations, checks,
                coverage, Layers.NONE);
    }

    /** The clock captured once for the document, its zone, and the text's locale as a language tag. */
    public record Context(Instant instant, ZoneId zone, String locale) {}

    /**
     * This document, refused with an IllegalArgumentException that names the first problem unless:
     * <ul>
     *   <li>the schema version is 1;</li>
     *   <li>every range lies within the text, and neither its start nor its end falls inside a surrogate pair;</li>
     *   <li>token ids are unique, and tokens are non-empty, in text order and apart, with only whitespace between
     *       them and around them;</li>
     *   <li>each sentence names existing tokens, in text order;</li>
     *   <li>span ids are unique, relations name existing spans, and checks target existing spans or relations;</li>
     *   <li>coverage has one record per token, in token order, naming existing spans;</li>
     *   <li>in the layers that are present: marker ids are unique, and so are clause ids; the ranges of markers and
     *       clauses hold as above; every token list of a marker, its scope, a clause, a frame's phrase and a
     *       coreference is non-empty, names existing tokens and is in text order, and every head names an existing
     *       token; a clause's parent, a frame's clause and object clause, and the clauses a marker's scope names
     *       exist; an antecedent's span exists, and the antecedent's last token comes before its anaphor's first;</li>
     *   <li>when the date layer is present, each entry names an existing span that is a DATE span or has a DATE
     *       alternative, no span has two entries, and every candidate has a month from 1 to 12 and a day other than
     *       0;</li>
     *   <li>when the clause layer is present, every token of every sentence belongs to at least one clause.</li>
     * </ul>
     */
    public AnnotationDocument validated() {
        if (schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema version must be " + SCHEMA_VERSION + ", was " + schemaVersion());
        }
        String text = text();
        List<Token> tokens = tokens();
        Map<String, Integer> tokenIndex = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokenIndex.putIfAbsent(tokens.get(i).id(), i) != null) {
                throw new IllegalArgumentException("duplicate token id: " + tokens.get(i).id());
            }
        }
        Set<String> tokenIds = tokenIndex.keySet();
        for (Token t : tokens) {
            String kind = "token '" + t.id() + "'";
            TextRange range = t.range();
            if (!validRange(range, text.length())) {
                throw new IllegalArgumentException(kind + " has invalid range [" + range.start() + ", "
                        + range.end() + ")");
            }
            refuseSurrogateSplit(text, range, kind);
        }
        for (int i = 1; i < tokens.size(); i++) {
            Token prev = tokens.get(i - 1);
            Token curr = tokens.get(i);
            if (prev.range().end() > curr.range().start()) {
                throw new IllegalArgumentException("tokens overlap: '" + prev.id() + "' and '" + curr.id() + "'");
            }
        }
        if (!tokens.isEmpty()) {
            int first = tokens.get(0).range().start();
            for (int i = 0; i < first; i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    throw new IllegalArgumentException("non-whitespace text before token '" + tokens.get(0).id() + "'");
                }
            }
            for (int i = 1; i < tokens.size(); i++) {
                int from = tokens.get(i - 1).range().end();
                int to = tokens.get(i).range().start();
                for (int j = from; j < to; j++) {
                    if (!Character.isWhitespace(text.charAt(j))) {
                        throw new IllegalArgumentException("non-whitespace text between tokens '"
                                + tokens.get(i - 1).id() + "' and '" + tokens.get(i).id() + "'");
                    }
                }
            }
            int last = tokens.get(tokens.size() - 1).range().end();
            for (int i = last; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    throw new IllegalArgumentException("non-whitespace text after token '"
                            + tokens.get(tokens.size() - 1).id() + "'");
                }
            }
        } else {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    throw new IllegalArgumentException("non-whitespace text with no tokens");
                }
            }
        }
        for (Sentence s : sentences()) {
            int lastIndex = -1;
            for (String tokenId : s.tokenIds()) {
                Integer index = tokenIndex.get(tokenId);
                if (index == null) {
                    throw new IllegalArgumentException("sentence '" + s.id()
                            + "' names unknown token id: " + tokenId);
                }
                if (index <= lastIndex) {
                    throw new IllegalArgumentException("sentence '" + s.id()
                            + "' names tokens out of order: " + tokenId);
                }
                lastIndex = index;
            }
        }
        List<Span> spans = spans();
        Set<String> spanIds = new HashSet<>();
        for (Span sp : spans) {
            if (!spanIds.add(sp.id())) {
                throw new IllegalArgumentException("duplicate span id: " + sp.id());
            }
            String kind = "span '" + sp.id() + "'";
            TextRange range = sp.range();
            if (!validRange(range, text.length())) {
                throw new IllegalArgumentException(kind + " has invalid range [" + range.start() + ", "
                        + range.end() + ")");
            }
            refuseSurrogateSplit(text, range, kind);
        }
        Set<String> relationIds = new HashSet<>();
        for (Relation r : relations()) {
            if (r.id() != null) {
                relationIds.add(r.id());
            }
            for (String spanId : r.spanIds()) {
                if (!spanIds.contains(spanId)) {
                    throw new IllegalArgumentException("relation '" + r.id()
                            + "' names unknown span id: " + spanId);
                }
            }
        }
        for (Check c : checks()) {
            for (String targetId : c.targets()) {
                if (!spanIds.contains(targetId) && !relationIds.contains(targetId)) {
                    throw new IllegalArgumentException("check '" + c.checker()
                            + "' names unknown target id: " + targetId);
                }
            }
        }
        List<Coverage> coverage = coverage();
        if (coverage.size() != tokens.size()) {
            throw new IllegalArgumentException("coverage must have one record per token, was " + coverage.size());
        }
        for (int i = 0; i < tokens.size(); i++) {
            Coverage cov = coverage.get(i);
            if (!cov.tokenId().equals(tokens.get(i).id())) {
                throw new IllegalArgumentException("coverage record " + i + " names token '" + cov.tokenId()
                        + "', expected '" + tokens.get(i).id() + "'");
            }
            for (String spanId : cov.spanIds()) {
                if (!spanIds.contains(spanId)) {
                    throw new IllegalArgumentException("coverage for token '" + cov.tokenId()
                            + "' names unknown span id: " + spanId);
                }
            }
        }
        validateLayers(tokenIndex, spanIds);
        return this;
    }

    /**
     * Refuses the first problem of the layers that are present, as {@link #validated()} describes, given each token
     * id's index in the token list and the span ids.
     */
    private void validateLayers(Map<String, Integer> tokenIndex, Set<String> spanIds) {
        Layers layers = layers();
        Set<String> clauseIds = new HashSet<>();
        Set<String> markerIds = new HashSet<>();
        List<TextRange> ranges = new ArrayList<>();
        List<List<String>> tokenLists = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        List<String> clauseReferences = new ArrayList<>();
        if (layers.clauses() != null) {
            for (Clause clause : layers.clauses()) {
                if (!clauseIds.add(clause.id())) {
                    throw new IllegalArgumentException("duplicate clause id: " + clause.id());
                }
                ranges.add(clause.range());
                tokenLists.add(clause.tokenIds());
                if (clause.head() != null) {
                    heads.add(clause.head());
                }
                if (clause.parent() != null) {
                    clauseReferences.add(clause.parent());
                }
            }
        }
        if (layers.markers() != null) {
            for (Marker marker : layers.markers()) {
                if (!markerIds.add(marker.id())) {
                    throw new IllegalArgumentException("duplicate marker id: " + marker.id());
                }
                ranges.add(marker.range());
                tokenLists.add(marker.tokenIds());
                Marker.Scope scope = marker.scope();
                if (scope.tokenIds() != null) {
                    tokenLists.add(scope.tokenIds());
                }
                if (scope.clause() != null) {
                    clauseReferences.add(scope.clause());
                }
                if (scope.antecedent() != null) {
                    clauseReferences.add(scope.antecedent());
                }
                if (scope.consequent() != null) {
                    clauseReferences.add(scope.consequent());
                }
                if (scope.notAsserted() != null) {
                    clauseReferences.addAll(scope.notAsserted());
                }
            }
        }
        if (layers.frames() != null) {
            for (Frame frame : layers.frames()) {
                clauseReferences.add(frame.clause());
                if (frame.objectClause() != null) {
                    clauseReferences.add(frame.objectClause());
                }
                if (frame.subject() != null) {
                    tokenLists.add(frame.subject().tokenIds());
                    heads.add(frame.subject().head());
                }
                if (frame.verb() != null) {
                    tokenLists.add(frame.verb().tokenIds());
                    heads.add(frame.verb().head());
                }
                if (frame.object() != null) {
                    tokenLists.add(frame.object().tokenIds());
                    heads.add(frame.object().head());
                }
            }
        }
        if (layers.coreference() != null) {
            for (Coreference coreference : layers.coreference()) {
                tokenLists.add(coreference.tokenIds());
                if (coreference.antecedent() != null) {
                    tokenLists.add(coreference.antecedent().tokenIds());
                }
            }
        }
        for (TextRange range : ranges) {
            if (!validRange(range, text().length())) {
                throw new IllegalArgumentException("layer has invalid range [" + range.start() + ", "
                        + range.end() + ")");
            }
            refuseSurrogateSplit(text(), range, "layer");
        }
        for (List<String> tokenIds : tokenLists) {
            if (tokenIds == null || tokenIds.isEmpty()) {
                throw new IllegalArgumentException("layer token list must be non-empty");
            }
            int last = -1;
            for (String tokenId : tokenIds) {
                Integer index = tokenIndex.get(tokenId);
                if (index == null) {
                    throw new IllegalArgumentException("layer names unknown token id: " + tokenId);
                }
                if (index <= last) {
                    throw new IllegalArgumentException("layer names tokens out of order: " + tokenId);
                }
                last = index;
            }
        }
        for (String head : heads) {
            if (!tokenIndex.containsKey(head)) {
                throw new IllegalArgumentException("layer names unknown head token id: " + head);
            }
        }
        for (String clauseId : clauseReferences) {
            if (!clauseIds.contains(clauseId)) {
                throw new IllegalArgumentException("layer names unknown clause id: " + clauseId);
            }
        }
        if (layers.coreference() != null) {
            for (Coreference coreference : layers.coreference()) {
                Coreference.Antecedent antecedent = coreference.antecedent();
                if (antecedent != null) {
                    if (antecedent.spanId() != null && !spanIds.contains(antecedent.spanId())) {
                        throw new IllegalArgumentException("coreference antecedent names unknown span id: "
                                + antecedent.spanId());
                    }
                    int last = tokenIndex.get(antecedent.tokenIds().get(antecedent.tokenIds().size() - 1));
                    int first = tokenIndex.get(coreference.tokenIds().get(0));
                    if (last >= first) {
                        throw new IllegalArgumentException("coreference antecedent must precede its anaphor");
                    }
                }
            }
        }
        if (layers.clauses() != null) {
            Set<String> covered = new HashSet<>();
            for (Clause clause : layers.clauses()) {
                covered.addAll(clause.tokenIds());
            }
            for (Sentence sentence : sentences()) {
                for (String tokenId : sentence.tokenIds()) {
                    if (!covered.contains(tokenId)) {
                        throw new IllegalArgumentException("sentence token is not in a clause: " + tokenId);
                    }
                }
            }
        }
        if (layers.dates() != null) {
            Set<String> dateSpans = new HashSet<>();
            for (Span span : spans()) {
                if (span.concept() == Span.Concept.DATE || span.alternatives().stream()
                        .anyMatch(alternative -> "DATE".equals(alternative.path("concept").asText()))) {
                    dateSpans.add(span.id());
                }
            }
            Set<String> dated = new HashSet<>();
            for (DateResolution date : layers.dates()) {
                if (!dateSpans.contains(date.spanId())) {
                    throw new IllegalArgumentException("date names unknown DATE span id: " + date.spanId());
                }
                if (!dated.add(date.spanId())) {
                    throw new IllegalArgumentException("duplicate date entry for span id: " + date.spanId());
                }
                for (DateResolution.Candidate candidate : date.candidates()) {
                    if (candidate.month() < 1 || candidate.month() > 12 || candidate.day() < 1) {
                        throw new IllegalArgumentException("date candidate has no such month or day: " + date.spanId());
                    }
                }
            }
        }
        if (layers.layout() != null) {
            validateLayout(layers.layout());
        }
    }

    /**
     * Refuses the first problem of the layout layer: its lines are numbered from 0 in order, their ranges lie in the
     * text (a blank line's may be empty) in text order without overlapping, every block's first line is at most its
     * last, which is a line of the layout, its range runs from its first line's start to its last line's end, and
     * block ids are unique.
     */
    private void validateLayout(Layout layout) {
        int end = 0;
        for (int index = 0; index < layout.lines().size(); index++) {
            Layout.Line line = layout.lines().get(index);
            if (line.index() != index) {
                throw new IllegalArgumentException("layout line out of order: " + line.index());
            }
            TextRange range = line.range();
            if (range.start() < end || range.end() < range.start() || range.end() > text().length()) {
                throw new IllegalArgumentException("layout line has an invalid range: " + index);
            }
            end = range.end();
        }
        Set<String> blockIds = new HashSet<>();
        for (Layout.Block block : layout.blocks()) {
            if (!blockIds.add(block.id())) {
                throw new IllegalArgumentException("duplicate layout block id: " + block.id());
            }
            if (block.firstLine() < 0 || block.firstLine() > block.lastLine()
                    || block.lastLine() >= layout.lines().size()) {
                throw new IllegalArgumentException("layout block names no such lines: " + block.id());
            }
            if (block.range().start() != layout.lines().get(block.firstLine()).range().start()
                    || block.range().end() != layout.lines().get(block.lastLine()).range().end()) {
                throw new IllegalArgumentException("layout block range is not its lines': " + block.id());
            }
        }
    }

    /** The ranges of text that no token covers, in text order; empty gaps are left out. */
    public List<TextRange> gaps() {
        List<TextRange> result = new ArrayList<>();
        int start = 0;
        for (Token token : tokens()) {
            if (start < token.range().start()) {
                result.add(new TextRange(start, token.range().start()));
            }
            start = token.range().end();
        }
        if (start < text().length()) {
            result.add(new TextRange(start, text().length()));
        }
        return List.copyOf(result);
    }

    /** The other spans whose range contains the named span's range, in document order. */
    public List<Span> spansContaining(String spanId) {
        Span named = null;
        for (Span span : spans()) {
            if (span.id().equals(spanId)) {
                named = span;
                break;
            }
        }
        if (named == null) {
            throw new IllegalArgumentException("unknown span id: " + spanId);
        }
        List<Span> result = new ArrayList<>();
        for (Span span : spans()) {
            if (!span.id().equals(spanId) && span.range().contains(named.range())) {
                result.add(span);
            }
        }
        return List.copyOf(result);
    }

    /**
     * The document as compact JSON (no spaces between tokens), keys in this order: {@code schemaVersion},
     * {@code ruleset}, {@code text}, {@code context} ({@code instant} in ISO-8601, {@code zone}, {@code locale}),
     * {@code codeIndexRevision} (left out when null), then {@code sentences}, {@code tokens}, {@code spans},
     * {@code relations}, {@code checks} and {@code coverage}, each an array in the document's order, then the layers
     * that are present, in this order: {@code markers}, {@code clauses}, {@code frames}, {@code coreference},
     * {@code dates} and {@code layout}. An absent layer is left out; an empty one is an empty array.
     * <ul>
     *   <li>A range is a two-number array {@code [start,end]}.</li>
     *   <li>A sentence is {@code id, range, kind, boundary, tokens}.</li>
     *   <li>A token is {@code id, range, shape, tags, guessed, provenance}, each tag {@code tag, rule}.</li>
     *   <li>A span is {@code id, range, class, value, alternatives, provenance}.</li>
     *   <li>A relation is {@code id, kind, spans, provenance}.</li>
     *   <li>A check is {@code targets, checker, status, evidence, assumptions, reasons}, each assumption
     *       {@code kind, value, source}.</li>
     *   <li>A coverage record is {@code token, spans}.</li>
     *   <li>A provenance is {@code source, confidence}, then {@code rule} and {@code model} when not null.</li>
     *   <li>A marker is {@code id, range, tokenIds, class, kind, scope, provenance}; a scope holds, in this order,
     *       those of {@code clause, tokenIds, antecedent, consequent, notAsserted} that are not null.</li>
     *   <li>A clause is {@code id, range, tokenIds, type, head, finite, parent, asserted}.</li>
     *   <li>A frame is {@code clause, subject, verb, object, objectClause, implicit, polarity}, each phrase
     *       {@code head, tokenIds}.</li>
     *   <li>A coreference is {@code tokenIds, antecedent}, an antecedent {@code tokenIds, spanId}.</li>
     *   <li>A date is {@code spanId, year, month, day, weekday, candidates, state, date}, each candidate
     *       {@code year, month, day, derived, fit, time}, its {@code derived} an object of part names to sources, the
     *       names in alphabetical order.</li>
     *   <li>The layout is an object of {@code lines} and {@code blocks}: each line {@code index, range, indent, tags}
     *       (its tags in the order of {@link Layout.LineTag}), each block {@code id, kind, range, lines, attributes},
     *       its {@code lines} the first and the last line, its attributes an object.</li>
     * </ul>
     * In a layer, a null outside a scope is written as JSON null. Tags, shapes, concept classes and marker classes are
     * written as their names; every other enum value in lower case, with a hyphen for each underscore
     * ({@code list-item}, {@code rules}, {@code inconsistent}).
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(writeDocument(this));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON: " + exception.getMessage(), exception);
        }
    }

    /**
     * The document that a JSON text in the form {@link #toJson()} writes holds, validated. A schema version other than
     * 1, an unknown enum value, a missing key and an invalid range are refused with an IllegalArgumentException whose
     * message names them. A layer whose key is missing is absent, so a document of schema version 1 reads with
     * {@link Layers#NONE}; inside a layer, a key whose value may be null may also be missing.
     */
    public static AnnotationDocument fromJson(String json) {
        try {
            return readDocument(MAPPER.readTree(json)).validated();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("JSON: " + exception.getMessage(), exception);
        }
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static ArrayNode writeRange(TextRange range) {
        ArrayNode node = MAPPER.createArrayNode();
        node.add(range.start());
        node.add(range.end());
        return node;
    }

    private static ObjectNode writeProvenance(Provenance provenance) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("source", lower(provenance.source()));
        node.put("confidence", provenance.confidence());
        if (provenance.rule() != null) {
            node.put("rule", provenance.rule());
        }
        if (provenance.model() != null) {
            node.put("model", provenance.model());
        }
        return node;
    }

    private static JsonNode required(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        return value;
    }

    private static TextRange readRange(JsonNode node) {
        if (!node.isArray() || node.size() != 2) {
            throw new IllegalArgumentException("range: " + node);
        }
        return new TextRange(node.get(0).asInt(), node.get(1).asInt());
    }

    private static List<String> readStrings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static <E extends Enum<E>> E readNamedEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown " + type.getSimpleName() + ": " + value, exception);
        }
    }

    private static <E extends Enum<E>> E readLowerEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown " + type.getSimpleName() + ": " + value, exception);
        }
    }

    private static Provenance readProvenance(JsonNode node) {
        return new Provenance(readLowerEnum(Provenance.Source.class, required(node, "source").asText()),
                required(node, "confidence").asDouble(),
                node.has("rule") ? node.get("rule").asText() : null,
                node.has("model") ? node.get("model").asText() : null);
    }

    private static ObjectNode writeDocument(AnnotationDocument document) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", document.schemaVersion());
        root.put("ruleset", document.ruleset());
        root.put("text", document.text());
        ObjectNode context = root.putObject("context");
        context.put("instant", document.context().instant().toString());
        context.put("zone", document.context().zone().getId());
        context.put("locale", document.context().locale());
        if (document.codeIndexRevision() != null) {
            root.put("codeIndexRevision", document.codeIndexRevision());
        }
        ArrayNode sentences = root.putArray("sentences");
        for (Sentence sentence : document.sentences()) {
            ObjectNode node = sentences.addObject();
            node.put("id", sentence.id());
            node.set("range", writeRange(sentence.range()));
            node.put("kind", lower(sentence.kind()));
            node.put("boundary", lower(sentence.boundary()));
            ArrayNode tokens = node.putArray("tokens");
            sentence.tokenIds().forEach(tokens::add);
        }
        ArrayNode tokens = root.putArray("tokens");
        for (Token token : document.tokens()) {
            ObjectNode node = tokens.addObject();
            node.put("id", token.id());
            node.set("range", writeRange(token.range()));
            node.put("shape", token.shape().name());
            ArrayNode tags = node.putArray("tags");
            for (Token.Candidate candidate : token.candidates()) {
                ObjectNode tag = tags.addObject();
                tag.put("tag", candidate.tag().name());
                tag.put("rule", candidate.rule());
            }
            node.put("guessed", token.guessed());
            node.set("provenance", writeProvenance(token.provenance()));
        }
        ArrayNode spans = root.putArray("spans");
        for (Span span : document.spans()) {
            ObjectNode node = spans.addObject();
            node.put("id", span.id());
            node.set("range", writeRange(span.range()));
            node.put("class", span.concept().name());
            node.set("value", span.value());
            ArrayNode alternatives = node.putArray("alternatives");
            span.alternatives().forEach(alternatives::add);
            node.set("provenance", writeProvenance(span.provenance()));
        }
        ArrayNode relations = root.putArray("relations");
        for (Relation relation : document.relations()) {
            ObjectNode node = relations.addObject();
            if (relation.id() != null) {
                node.put("id", relation.id());
            }
            node.put("kind", lower(relation.kind()));
            ArrayNode spansForRelation = node.putArray("spans");
            relation.spanIds().forEach(spansForRelation::add);
            node.set("provenance", writeProvenance(relation.provenance()));
        }
        ArrayNode checks = root.putArray("checks");
        for (Check check : document.checks()) {
            ObjectNode node = checks.addObject();
            ArrayNode targets = node.putArray("targets");
            check.targets().forEach(targets::add);
            node.put("checker", check.checker());
            node.put("status", lower(check.status()));
            node.set("evidence", check.evidence());
            ArrayNode assumptions = node.putArray("assumptions");
            for (Check.Assumption assumption : check.assumptions()) {
                ObjectNode item = assumptions.addObject();
                item.put("kind", assumption.kind());
                item.set("value", assumption.value());
                item.put("source", assumption.source());
            }
            ArrayNode reasons = node.putArray("reasons");
            check.reasons().forEach(reasons::add);
        }
        ArrayNode coverage = root.putArray("coverage");
        for (Coverage item : document.coverage()) {
            ObjectNode node = coverage.addObject();
            node.put("token", item.tokenId());
            ArrayNode spansForToken = node.putArray("spans");
            item.spanIds().forEach(spansForToken::add);
        }
        writeLayers(root, document.layers());
        return root;
    }

    /** Writes the layers of {@code layers} that are present onto {@code root}, as {@link #toJson()} describes. */
    private static void writeLayers(ObjectNode root, Layers layers) {
        if (layers.markers() != null) {
            ArrayNode markers = root.putArray("markers");
            for (Marker marker : layers.markers()) {
                ObjectNode node = markers.addObject();
                node.put("id", marker.id());
                node.set("range", writeRange(marker.range()));
                ArrayNode tokenIds = node.putArray("tokenIds");
                marker.tokenIds().forEach(tokenIds::add);
                node.put("class", marker.markerClass().name());
                if (marker.kind() == null) {
                    node.putNull("kind");
                } else {
                    node.put("kind", marker.kind());
                }
                ObjectNode scope = node.putObject("scope");
                if (marker.scope().clause() != null) {
                    scope.put("clause", marker.scope().clause());
                }
                if (marker.scope().tokenIds() != null) {
                    ArrayNode scopeTokens = scope.putArray("tokenIds");
                    marker.scope().tokenIds().forEach(scopeTokens::add);
                }
                if (marker.scope().antecedent() != null) {
                    scope.put("antecedent", marker.scope().antecedent());
                }
                if (marker.scope().consequent() != null) {
                    scope.put("consequent", marker.scope().consequent());
                }
                if (marker.scope().notAsserted() != null) {
                    ArrayNode notAsserted = scope.putArray("notAsserted");
                    marker.scope().notAsserted().forEach(notAsserted::add);
                }
                node.set("provenance", writeProvenance(marker.provenance()));
            }
        }
        if (layers.clauses() != null) {
            ArrayNode clauses = root.putArray("clauses");
            for (Clause clause : layers.clauses()) {
                ObjectNode node = clauses.addObject();
                node.put("id", clause.id());
                node.set("range", writeRange(clause.range()));
                ArrayNode tokenIds = node.putArray("tokenIds");
                clause.tokenIds().forEach(tokenIds::add);
                node.put("type", lower(clause.type()));
                if (clause.head() == null) {
                    node.putNull("head");
                } else {
                    node.put("head", clause.head());
                }
                node.put("finite", clause.finite());
                if (clause.parent() == null) {
                    node.putNull("parent");
                } else {
                    node.put("parent", clause.parent());
                }
                node.put("asserted", lower(clause.asserted()));
            }
        }
        if (layers.frames() != null) {
            ArrayNode frames = root.putArray("frames");
            for (Frame frame : layers.frames()) {
                ObjectNode node = frames.addObject();
                node.put("clause", frame.clause());
                writePhrase(node, "subject", frame.subject());
                writePhrase(node, "verb", frame.verb());
                writePhrase(node, "object", frame.object());
                if (frame.objectClause() == null) {
                    node.putNull("objectClause");
                } else {
                    node.put("objectClause", frame.objectClause());
                }
                if (frame.implicit() == null) {
                    node.putNull("implicit");
                } else {
                    node.put("implicit", frame.implicit());
                }
                node.put("polarity", lower(frame.polarity()));
            }
        }
        if (layers.coreference() != null) {
            ArrayNode coreference = root.putArray("coreference");
            for (Coreference item : layers.coreference()) {
                ObjectNode node = coreference.addObject();
                ArrayNode tokenIds = node.putArray("tokenIds");
                item.tokenIds().forEach(tokenIds::add);
                if (item.antecedent() == null) {
                    node.putNull("antecedent");
                } else {
                    ObjectNode antecedent = node.putObject("antecedent");
                    ArrayNode antecedentTokens = antecedent.putArray("tokenIds");
                    item.antecedent().tokenIds().forEach(antecedentTokens::add);
                    if (item.antecedent().spanId() == null) {
                        antecedent.putNull("spanId");
                    } else {
                        antecedent.put("spanId", item.antecedent().spanId());
                    }
                }
            }
        }
        if (layers.dates() != null) {
            ArrayNode dates = root.putArray("dates");
            for (DateResolution date : layers.dates()) {
                ObjectNode node = dates.addObject();
                node.put("spanId", date.spanId());
                node.put("year", date.year());
                node.put("month", date.month());
                node.put("day", date.day());
                node.put("weekday", date.weekday());
                if (date.tense() == null) {
                    node.putNull("tense");
                } else {
                    node.putObject("tense").put("time", lower(date.tense().time())).put("cue", date.tense().cue());
                }
                ArrayNode candidates = node.putArray("candidates");
                for (DateResolution.Candidate candidate : date.candidates()) {
                    ObjectNode item = candidates.addObject();
                    item.put("year", candidate.year());
                    item.put("month", candidate.month());
                    item.put("day", candidate.day());
                    ObjectNode derived = item.putObject("derived");
                    new TreeMap<>(candidate.derived()).forEach(derived::put);
                    item.put("fit", lower(candidate.fit()));
                    if (candidate.time() == null) {
                        item.putNull("time");
                    } else {
                        item.put("time", lower(candidate.time()));
                    }
                    if (candidate.tense() == null) {
                        item.putNull("tense");
                    } else {
                        item.put("tense", lower(candidate.tense()));
                    }
                }
                node.put("state", lower(date.state()));
                node.put("date", date.date());
            }
        }
        if (layers.layout() != null) {
            writeLayout(root, layers.layout());
        }
    }

    private static AnnotationDocument readDocument(JsonNode root) {
        int schemaVersion = required(root, "schemaVersion").asInt();
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schemaVersion: " + schemaVersion);
        }
        String ruleset = required(root, "ruleset").asText();
        String text = required(root, "text").asText();
        JsonNode contextNode = required(root, "context");
        Context context = new Context(Instant.parse(required(contextNode, "instant").asText()),
                ZoneId.of(required(contextNode, "zone").asText()), required(contextNode, "locale").asText());
        String revision = root.has("codeIndexRevision") ? root.get("codeIndexRevision").asText() : null;
        List<Sentence> sentences = new ArrayList<>();
        for (JsonNode node : required(root, "sentences")) {
            sentences.add(new Sentence(required(node, "id").asText(), readRange(required(node, "range")),
                    readLowerEnum(Sentence.Kind.class, required(node, "kind").asText()),
                    readLowerEnum(Sentence.Boundary.class, required(node, "boundary").asText()),
                    readStrings(required(node, "tokens"))));
        }
        List<Token> tokens = new ArrayList<>();
        for (JsonNode node : required(root, "tokens")) {
            List<Token.Candidate> candidates = new ArrayList<>();
            for (JsonNode tag : required(node, "tags")) {
                candidates.add(new Token.Candidate(readNamedEnum(Tag.class, required(tag, "tag").asText()),
                        required(tag, "rule").asText()));
            }
            tokens.add(new Token(required(node, "id").asText(), readRange(required(node, "range")),
                    readNamedEnum(Token.Shape.class, required(node, "shape").asText()), List.copyOf(candidates),
                    required(node, "guessed").asBoolean(), readProvenance(required(node, "provenance"))));
        }
        List<Span> spans = new ArrayList<>();
        for (JsonNode node : required(root, "spans")) {
            List<JsonNode> alternatives = new ArrayList<>();
            required(node, "alternatives").forEach(alternatives::add);
            spans.add(new Span(required(node, "id").asText(), readRange(required(node, "range")),
                    readNamedEnum(Span.Concept.class, required(node, "class").asText()), required(node, "value"),
                    List.copyOf(alternatives), readProvenance(required(node, "provenance"))));
        }
        List<Relation> relations = new ArrayList<>();
        for (JsonNode node : required(root, "relations")) {
            String id = node.has("id") ? node.get("id").asText() : null;
            relations.add(new Relation(id, readLowerEnum(Relation.Kind.class, required(node, "kind").asText()),
                    readStrings(required(node, "spans")), readProvenance(required(node, "provenance"))));
        }
        List<Check> checks = new ArrayList<>();
        for (JsonNode node : required(root, "checks")) {
            List<Check.Assumption> assumptions = new ArrayList<>();
            for (JsonNode item : required(node, "assumptions")) {
                assumptions.add(new Check.Assumption(required(item, "kind").asText(), required(item, "value"),
                        required(item, "source").asText()));
            }
            checks.add(new Check(readStrings(required(node, "targets")), required(node, "checker").asText(),
                    readLowerEnum(Check.Status.class, required(node, "status").asText()), required(node, "evidence"),
                    List.copyOf(assumptions), readStrings(required(node, "reasons"))));
        }
        List<Coverage> coverage = new ArrayList<>();
        for (JsonNode node : required(root, "coverage")) {
            coverage.add(new Coverage(required(node, "token").asText(), readStrings(required(node, "spans"))));
        }
        return new AnnotationDocument(schemaVersion, ruleset, text, context, revision, List.copyOf(sentences),
                List.copyOf(tokens), List.copyOf(spans), List.copyOf(relations), List.copyOf(checks),
                List.copyOf(coverage), readLayers(root));
    }

    private static void writeLayout(ObjectNode root, Layout layout) {
        ObjectNode node = root.putObject("layout");
        ArrayNode lines = node.putArray("lines");
        for (Layout.Line line : layout.lines()) {
            ObjectNode item = lines.addObject();
            item.put("index", line.index());
            item.set("range", writeRange(line.range()));
            item.put("indent", line.indent());
            ArrayNode tags = item.putArray("tags");
            for (Layout.LineTag tag : Layout.LineTag.values()) {
                if (line.tags().contains(tag)) {
                    tags.add(lower(tag));
                }
            }
        }
        ArrayNode blocks = node.putArray("blocks");
        for (Layout.Block block : layout.blocks()) {
            ObjectNode item = blocks.addObject();
            item.put("id", block.id());
            item.put("kind", lower(block.kind()));
            item.set("range", writeRange(block.range()));
            item.putArray("lines").add(block.firstLine()).add(block.lastLine());
            item.set("attributes", block.attributes() == null ? MAPPER.createObjectNode() : block.attributes());
        }
    }

    private static Layout readLayout(JsonNode node) {
        List<Layout.Line> lines = new ArrayList<>();
        for (JsonNode item : required(node, "lines")) {
            Set<Layout.LineTag> tags = EnumSet.noneOf(Layout.LineTag.class);
            required(item, "tags").forEach(tag -> tags.add(readLowerEnum(Layout.LineTag.class, tag.asText())));
            lines.add(new Layout.Line(required(item, "index").asInt(), readRange(required(item, "range")),
                    required(item, "indent").asInt(), Collections.unmodifiableSet(tags)));
        }
        List<Layout.Block> blocks = new ArrayList<>();
        for (JsonNode item : required(node, "blocks")) {
            JsonNode span = required(item, "lines");
            blocks.add(new Layout.Block(required(item, "id").asText(),
                    readLowerEnum(Layout.Kind.class, required(item, "kind").asText()),
                    readRange(required(item, "range")), span.get(0).asInt(), span.get(1).asInt(),
                    required(item, "attributes")));
        }
        return new Layout(List.copyOf(lines), List.copyOf(blocks));
    }

    /** The layers that {@code root} holds, as {@link #fromJson} describes: a missing key is an absent layer. */
    private static Layers readLayers(JsonNode root) {
        List<Marker> markers = null;
        if (root.has("markers")) {
            List<Marker> values = new ArrayList<>();
            for (JsonNode node : root.get("markers")) {
                JsonNode scopeNode = required(node, "scope");
                Marker.Scope scope = new Marker.Scope(
                        scopeNode.has("clause") ? scopeNode.get("clause").asText() : null,
                        scopeNode.has("tokenIds") ? readStrings(scopeNode.get("tokenIds")) : null,
                        scopeNode.has("antecedent") ? scopeNode.get("antecedent").asText() : null,
                        scopeNode.has("consequent") ? scopeNode.get("consequent").asText() : null,
                        scopeNode.has("notAsserted") ? readStrings(scopeNode.get("notAsserted")) : null);
                values.add(new Marker(required(node, "id").asText(), readRange(required(node, "range")),
                        readStrings(required(node, "tokenIds")),
                        readNamedEnum(Marker.Class.class, required(node, "class").asText()),
                        node.hasNonNull("kind") ? node.get("kind").asText() : null, scope,
                        readProvenance(required(node, "provenance"))));
            }
            markers = List.copyOf(values);
        }
        List<Clause> clauses = null;
        if (root.has("clauses")) {
            List<Clause> values = new ArrayList<>();
            for (JsonNode node : root.get("clauses")) {
                values.add(new Clause(required(node, "id").asText(), readRange(required(node, "range")),
                        readStrings(required(node, "tokenIds")),
                        readLowerEnum(Clause.Type.class, required(node, "type").asText()),
                        node.hasNonNull("head") ? node.get("head").asText() : null,
                        required(node, "finite").asBoolean(),
                        node.hasNonNull("parent") ? node.get("parent").asText() : null,
                        readLowerEnum(Clause.Asserted.class, required(node, "asserted").asText())));
            }
            clauses = List.copyOf(values);
        }
        List<Frame> frames = null;
        if (root.has("frames")) {
            List<Frame> values = new ArrayList<>();
            for (JsonNode node : root.get("frames")) {
                values.add(new Frame(required(node, "clause").asText(), readPhrase(node, "subject"),
                        readPhrase(node, "verb"), readPhrase(node, "object"),
                        node.hasNonNull("objectClause") ? node.get("objectClause").asText() : null,
                        node.hasNonNull("implicit") ? node.get("implicit").asText() : null,
                        readLowerEnum(Frame.Polarity.class, required(node, "polarity").asText())));
            }
            frames = List.copyOf(values);
        }
        List<Coreference> coreference = null;
        if (root.has("coreference")) {
            List<Coreference> values = new ArrayList<>();
            for (JsonNode node : root.get("coreference")) {
                Coreference.Antecedent antecedent = null;
                if (node.hasNonNull("antecedent")) {
                    JsonNode antecedentNode = node.get("antecedent");
                    antecedent = new Coreference.Antecedent(readStrings(required(antecedentNode, "tokenIds")),
                            antecedentNode.hasNonNull("spanId") ? antecedentNode.get("spanId").asText() : null);
                }
                values.add(new Coreference(readStrings(required(node, "tokenIds")), antecedent));
            }
            coreference = List.copyOf(values);
        }
        List<DateResolution> dates = null;
        if (root.has("dates")) {
            List<DateResolution> values = new ArrayList<>();
            for (JsonNode node : root.get("dates")) {
                List<DateResolution.Candidate> candidates = new ArrayList<>();
                for (JsonNode item : required(node, "candidates")) {
                    Map<String, String> derived = new HashMap<>();
                    required(item, "derived").properties().forEach(e -> derived.put(e.getKey(), e.getValue().asText()));
                    candidates.add(new DateResolution.Candidate(required(item, "year").asInt(),
                            required(item, "month").asInt(), required(item, "day").asInt(), Map.copyOf(derived),
                            readLowerEnum(DateResolution.Fit.class, required(item, "fit").asText()),
                            item.hasNonNull("time")
                                    ? readLowerEnum(DateResolution.Time.class, item.get("time").asText()) : null,
                            item.hasNonNull("tense")
                                    ? readLowerEnum(DateResolution.TenseFit.class, item.get("tense").asText()) : null));
                }
                values.add(new DateResolution(required(node, "spanId").asText(),
                        node.hasNonNull("year") ? node.get("year").asInt() : null,
                        node.hasNonNull("month") ? node.get("month").asInt() : null,
                        node.hasNonNull("day") ? node.get("day").asInt() : null,
                        node.hasNonNull("weekday") ? node.get("weekday").asText() : null,
                        node.hasNonNull("tense") ? new DateResolution.Tense(readLowerEnum(DateResolution.Time.class,
                                required(node.get("tense"), "time").asText()),
                                required(node.get("tense"), "cue").asText()) : null, List.copyOf(candidates),
                        readLowerEnum(DateResolution.State.class, required(node, "state").asText()),
                        node.hasNonNull("date") ? node.get("date").asText() : null));
            }
            dates = List.copyOf(values);
        }
        return new Layers(markers, clauses, frames, coreference, dates,
                root.has("layout") ? readLayout(root.get("layout")) : null);
    }

    private static boolean validRange(TextRange range, int textLength) {
        return range.start() >= 0 && range.end() <= textLength && range.start() < range.end();
    }

    private static void refuseSurrogateSplit(String text, TextRange range, String kind) {
        int start = range.start();
        int end = range.end();
        if (start > 0 && Character.isHighSurrogate(text.charAt(start - 1))
                && Character.isLowSurrogate(text.charAt(start))) {
            throw new IllegalArgumentException(kind + " start falls inside a surrogate pair");
        }
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            throw new IllegalArgumentException(kind + " end falls inside a surrogate pair");
        }
    }

    private static void writePhrase(ObjectNode node, String name, Frame.Phrase phrase) {
        if (phrase == null) {
            node.putNull(name);
            return;
        }
        ObjectNode value = node.putObject(name);
        value.put("head", phrase.head());
        ArrayNode tokenIds = value.putArray("tokenIds");
        phrase.tokenIds().forEach(tokenIds::add);
    }

    private static Frame.Phrase readPhrase(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        return new Frame.Phrase(required(value, "head").asText(), readStrings(required(value, "tokenIds")));
    }
}
