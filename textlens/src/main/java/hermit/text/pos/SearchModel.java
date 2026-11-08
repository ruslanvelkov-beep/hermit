package hermit.text.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * The model of the sentence search ({@link SentenceSearch}; the owner, 2026-11-06): the weights of its averaged
 * perceptron, the share of each tag among a word's uses in a dictionary, and WordNet's lemmas and exceptions by part of
 * speech. The weights and the dictionary are trained outside this code on UD English EWT and our own labelled text, a
 * derived work under CC BY-SA 4.0; the lemmas and exceptions are WordNet 3.1's, under the WordNet licence. They are
 * kept as a separate file and loaded only when one is named ({@link #MODEL_PROPERTY}).
 *
 * <p>The model file is UTF-8 text, gzip-compressed when its name ends in {@code .gz}, one JSON object a line, read by
 * its {@code "kind"}:
 * <ul>
 *   <li>{@code "weight"}: a feature's weights by tag ({@code {"kind": "weight", "feature": "w=run", "weights":
 *       {"VERB": 1.25, "NOUN": -0.5}}});</li>
 *   <li>{@code "word"}: a word's tag shares ({@code {"kind": "word", "word": "run", "shares": {"VERB": 0.75, "NOUN":
 *       0.25}}}), the word as {@link #normalize} gives it, the tags in the order of the line;</li>
 *   <li>{@code "lemmas"}: the lemmas of a part of speech ({@code {"kind": "lemmas", "pos": "noun", "lemmas": ["box",
 *       "run"]}}), a lemma's spaces written {@code _};</li>
 *   <li>{@code "exceptions"}: the irregular forms of a part of speech and their lemmas ({@code {"kind": "exceptions",
 *       "pos": "verb", "forms": {"ran": ["run"]}}}).</li>
 * </ul>
 * A part of speech is one of {@link #PARTS_OF_SPEECH}. Any other line (the header, with the source and the licences)
 * is skipped, and so is a weight or a share for a tag that {@link Tag} does not name.
 */
public final class SearchModel {

    /** The system property that names the model file. */
    public static final String MODEL_PROPERTY = "hermit.pos.search";

    /** WordNet's parts of speech, in the order they are tried, and the tag of each. */
    public static final Map<String, Tag> PARTS_OF_SPEECH = Collections.unmodifiableMap(new LinkedHashMap<>(Map.of(
            "noun", Tag.NOUN, "verb", Tag.VERB, "adj", Tag.ADJ, "adv", Tag.ADV)));

    /** The share of a word's uses that makes a dictionary tag one of the word's extra candidates. */
    public static final double DICTIONARY_SHARE = 0.05;

    /** An ending that WordNet's morphology detaches from a form, and what it puts back. */
    public record Detachment(String ending, String replacement) {}

    /** WordNet's detachments by part of speech, in the order they are tried. */
    public static final Map<String, List<Detachment>> DETACHMENTS = Map.of(
            "noun", List.of(new Detachment("s", ""), new Detachment("ses", "s"), new Detachment("xes", "x"),
                    new Detachment("zes", "z"), new Detachment("ches", "ch"), new Detachment("shes", "sh"),
                    new Detachment("men", "man"), new Detachment("ies", "y")),
            "verb", List.of(new Detachment("s", ""), new Detachment("ies", "y"), new Detachment("es", "e"),
                    new Detachment("es", ""), new Detachment("ed", "e"), new Detachment("ed", ""),
                    new Detachment("ing", "e"), new Detachment("ing", "")),
            "adj", List.of(new Detachment("er", ""), new Detachment("est", ""), new Detachment("er", "e"),
                    new Detachment("est", "e")),
            "adv", List.of());

    private final Map<String, Map<Tag, Double>> weights;
    private final Map<String, Map<Tag, Double>> shares;
    private final Map<String, Set<String>> lemmas;
    private final Map<String, Map<String, Set<String>>> exceptions;
    private static final Map<String, Optional<SearchModel>> LOADED = new HashMap<>();

    /**
     * A model with these weights (feature, then tag, then weight), tag shares (word, then tag, then share, the tags in
     * their order), lemmas and exceptions (part of speech, then form, then lemmas); a part of speech that is missing
     * has none.
     */
    public SearchModel(Map<String, Map<Tag, Double>> weights, Map<String, Map<Tag, Double>> shares,
            Map<String, Set<String>> lemmas, Map<String, Map<String, Set<String>>> exceptions) {
        Map<String, Map<Tag, Double>> weightCopy = new HashMap<>();
        weights.forEach((feature, byTag) -> weightCopy.put(feature, Map.copyOf(byTag)));
        this.weights = Map.copyOf(weightCopy);
        Map<String, Map<Tag, Double>> shareCopy = new HashMap<>();
        shares.forEach((word, byTag) -> shareCopy.put(word, Collections.unmodifiableMap(new LinkedHashMap<>(byTag))));
        this.shares = Map.copyOf(shareCopy);
        Map<String, Set<String>> lemmaCopy = new HashMap<>();
        lemmas.forEach((pos, words) -> lemmaCopy.put(pos, Set.copyOf(words)));
        this.lemmas = Map.copyOf(lemmaCopy);
        Map<String, Map<String, Set<String>>> exceptionCopy = new HashMap<>();
        exceptions.forEach((pos, forms) -> {
            Map<String, Set<String>> formCopy = new HashMap<>();
            forms.forEach((form, bases) -> formCopy.put(form, Set.copyOf(bases)));
            exceptionCopy.put(pos, Map.copyOf(formCopy));
        });
        this.exceptions = Map.copyOf(exceptionCopy);
    }

    /** The model of the file at {@code path}, read as the class javadoc says; empty when there is none. */
    public static Optional<SearchModel> load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        Map<String, Map<Tag, Double>> weights = new HashMap<>();
        Map<String, Map<Tag, Double>> shares = new HashMap<>();
        Map<String, Set<String>> lemmas = new HashMap<>();
        Map<String, Map<String, Set<String>>> exceptions = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream file = Files.newInputStream(path);
                InputStream input = path.getFileName().toString().endsWith(".gz")
                        ? new GZIPInputStream(file) : file;
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode object = mapper.readTree(line);
                String kind = object.path("kind").textValue();
                if ("weight".equals(kind)) {
                    JsonNode featureNode = object.get("feature");
                    JsonNode values = object.get("weights");
                    if (featureNode == null || values == null || !values.isObject()) {
                        continue;
                    }
                    Map<Tag, Double> byTag = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        try {
                            byTag.put(Tag.valueOf(field.getKey()), field.getValue().doubleValue());
                        } catch (IllegalArgumentException ignored) {
                            // A weight for a tag outside this tag set is ignored.
                        }
                    }
                    weights.put(featureNode.textValue(), byTag);
                } else if ("word".equals(kind)) {
                    JsonNode wordNode = object.get("word");
                    JsonNode values = object.get("shares");
                    if (wordNode == null || values == null || !values.isObject()) {
                        continue;
                    }
                    Map<Tag, Double> byTag = new LinkedHashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        try {
                            byTag.put(Tag.valueOf(field.getKey()), field.getValue().doubleValue());
                        } catch (IllegalArgumentException ignored) {
                            // A share for a tag outside this tag set is ignored.
                        }
                    }
                    shares.put(wordNode.textValue(), byTag);
                } else if ("lemmas".equals(kind)) {
                    String pos = object.path("pos").textValue();
                    JsonNode values = object.get("lemmas");
                    if (!PARTS_OF_SPEECH.containsKey(pos) || values == null || !values.isArray()) {
                        continue;
                    }
                    Set<String> words = new HashSet<>();
                    values.forEach(value -> words.add(value.textValue()));
                    lemmas.put(pos, words);
                } else if ("exceptions".equals(kind)) {
                    String pos = object.path("pos").textValue();
                    JsonNode values = object.get("forms");
                    if (!PARTS_OF_SPEECH.containsKey(pos) || values == null || !values.isObject()) {
                        continue;
                    }
                    Map<String, Set<String>> forms = new HashMap<>();
                    Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        Set<String> bases = new HashSet<>();
                        field.getValue().forEach(value -> bases.add(value.textValue()));
                        forms.put(field.getKey(), bases);
                    }
                    exceptions.put(pos, forms);
                }
            }
        }
        return Optional.of(new SearchModel(weights, shares, lemmas, exceptions));
    }

    /**
     * The model of the file that {@link #MODEL_PROPERTY} names, loaded once for each file and kept; empty when the
     * property is not set, the file does not exist or it cannot be read.
     */
    public static Optional<SearchModel> fromSystemProperties() {
        String named = System.getProperty(MODEL_PROPERTY);
        if (named == null) {
            return Optional.empty();
        }
        synchronized (LOADED) {
            if (LOADED.containsKey(named)) {
                return LOADED.get(named);
            }
            Optional<SearchModel> model;
            try {
                model = load(Path.of(named));
            } catch (IOException | RuntimeException ignored) {
                model = Optional.empty();
            }
            LOADED.put(named, model);
            return model;
        }
    }

    /**
     * A word as the dictionary and the features read it: in lower case, with a typographic apostrophe (U+2019) read
     * as {@code '}.
     */
    public static String normalize(String word) {
        return word.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
    }

    /** The weight of {@code feature} for {@code tag}; 0 when the model has none. */
    public double weight(String feature, Tag tag) {
        return weights.getOrDefault(feature, Map.of()).getOrDefault(tag, 0.0);
    }

    /** The tag shares of {@code word} ({@link #normalize}d), in the dictionary's order; empty when it is not there. */
    public Map<Tag, Double> shares(String word) {
        return shares.getOrDefault(normalize(word), Map.of());
    }

    /**
     * The tags of the parts of speech whose lemmas hold a base of {@code word}, by WordNet's morphology: the word
     * {@link #normalize}d with {@code -} read as {@code _}; its bases for a part of speech are the word itself, the
     * lemmas its exceptions give it, and, for each detachment of {@link #DETACHMENTS} whose ending the word has and is
     * shorter than, the word without the ending and with the replacement.
     */
    public Set<Tag> wordNetTags(String word) {
        String form = normalize(word).replace('-', '_');
        Set<Tag> result = EnumSet.noneOf(Tag.class);
        for (Map.Entry<String, Tag> part : PARTS_OF_SPEECH.entrySet()) {
            String pos = part.getKey();
            Set<String> bases = new HashSet<>();
            bases.add(form);
            bases.addAll(exceptions.getOrDefault(pos, Map.of()).getOrDefault(form, Set.of()));
            for (Detachment detachment : DETACHMENTS.getOrDefault(pos, List.of())) {
                if (form.length() > detachment.ending().length() && form.endsWith(detachment.ending())) {
                    String stem = form.substring(0, form.length() - detachment.ending().length());
                    bases.add(stem + detachment.replacement());
                }
            }
            if (!Collections.disjoint(lemmas.getOrDefault(pos, Set.of()), bases)) {
                result.add(part.getValue());
            }
        }
        return result;
    }

    /**
     * The extra candidates of a WORD token, {@code word}, whose candidates are {@code candidates}, each tag once and
     * none that the candidates hold, in this order: the {@link #wordNetTags} in the order of {@link Tag}, naming the
     * rule {@code x:wn}; the dictionary's tags of a share of at least {@link #DICTIONARY_SHARE}, the larger share
     * first and the dictionary's order on a tie, naming {@code x:dict}; and PROPN, naming {@code x:cap}, when the word
     * does not start its sentence and its first character is upper case.
     */
    public List<Candidate> extras(String word, List<Candidate> candidates, boolean startsSentence) {
        Set<Tag> held = EnumSet.noneOf(Tag.class);
        candidates.forEach(candidate -> held.add(candidate.tag()));
        List<Candidate> result = new ArrayList<>();
        for (Tag tag : wordNetTags(word)) {
            if (held.add(tag)) {
                result.add(new Candidate(tag, "x:wn"));
            }
        }
        List<Map.Entry<Tag, Double>> dictionary = new ArrayList<>(shares(word).entrySet());
        dictionary.sort((left, right) -> Double.compare(right.getValue(), left.getValue()));
        for (Map.Entry<Tag, Double> entry : dictionary) {
            if (entry.getValue() >= DICTIONARY_SHARE && held.add(entry.getKey())) {
                result.add(new Candidate(entry.getKey(), "x:dict"));
            }
        }
        if (!startsSentence && !word.isEmpty() && Character.isUpperCase(word.charAt(0)) && held.add(Tag.PROPN)) {
            result.add(new Candidate(Tag.PROPN, "x:cap"));
        }
        return List.copyOf(result);
    }
}
