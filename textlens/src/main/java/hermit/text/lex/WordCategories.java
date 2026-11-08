package hermit.text.lex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Categories of nouns (the owner, 2026-11-05), from WordNet 3.1 by the resource {@link #RESOURCE}, which carries
 * WordNet's licence: a TIME noun names something that happens at a point in time (an event, a gathering, a time period
 * or a point in time: {@code meeting}, {@code deadline}, {@code holiday}); a UNIT noun is a unit of time or of
 * measurement ({@code second}, {@code byte}). The resource's lines: a heading {@code [time]} or {@code [unit]}, then
 * that category's words, in lower case and separated by spaces, up to the next heading; a line starting with {@code #}
 * is a comment. {@link #ADDED_TIME_NOUNS} are TIME nouns too.
 */
public final class WordCategories {

    /** A noun's category. */
    public enum Category { TIME, UNIT }

    /** The resource the standard categories are read from, next to this class. */
    public static final String RESOURCE = "word-categories.txt";

    /**
     * Nouns that name something happening at a point in time, which WordNet does not list as TIME: the domain's events,
     * and {@code plot}, which has a time and a place as an event has (the owner, 2026-11-06).
     */
    public static final List<String> ADDED_TIME_NOUNS = List.of("release", "launch", "demo", "standup", "stand-up",
            "call", "review", "build", "deploy", "deployment", "rollout", "retro", "retrospective", "webinar", "sync",
            "sprint", "milestone", "kickoff", "cutover", "hackathon", "offsite", "workshop", "lecture", "exam", "trip",
            "flight", "interview", "plot");

    private static WordCategories standard;

    private final Map<String, Set<Category>> categories;

    private WordCategories(Map<String, Set<Category>> categories) {
        this.categories = categories;
    }

    /** The categories of the resource and the added TIME nouns, read once. */
    public static synchronized WordCategories standard() {
        if (standard == null) {
            try (InputStream in = WordCategories.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Missing resource " + RESOURCE);
                }
                standard = read(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return standard;
    }

    /**
     * The categories that {@code reader} holds in the resource's format, and the added TIME nouns; refused with an
     * IllegalArgumentException when a word comes before the first heading or a heading names no category.
     */
    public static WordCategories read(BufferedReader reader) throws IOException {
        Map<String, Set<Category>> categories = new HashMap<>();
        Category category = null;
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String name = line.substring(1, line.length() - 1).toUpperCase(Locale.ROOT);
                try {
                    category = Category.valueOf(name);
                } catch (IllegalArgumentException unknown) {
                    throw new IllegalArgumentException("Unknown category " + line, unknown);
                }
                continue;
            }
            if (category == null) {
                throw new IllegalArgumentException("Word before category heading: " + line);
            }
            for (String word : line.split("\\s+")) {
                categories.computeIfAbsent(word, ignored -> EnumSet.noneOf(Category.class)).add(category);
            }
        }
        for (String word : ADDED_TIME_NOUNS) {
            categories.computeIfAbsent(word, ignored -> EnumSet.noneOf(Category.class)).add(Category.TIME);
        }
        Map<String, Set<Category>> immutable = new HashMap<>();
        categories.forEach((word, values) -> immutable.put(word, Set.copyOf(values)));
        return new WordCategories(Map.copyOf(immutable));
    }

    /**
     * The categories of the noun {@code word}: those of the word in lower case (a typographic apostrophe read as
     * {@code '}), or when it has none, of its singular: the word without a final {@code ies} and with {@code y}
     * instead, then without a final {@code es}, then without a final {@code s} ({@code meetings}, {@code bytes}); an
     * empty set when none of these has any.
     */
    public Set<Category> ofNoun(String word) {
        String lower = word.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        Set<Category> found = categories.get(lower);
        if (found != null) {
            return found;
        }
        if (lower.endsWith("ies") && lower.length() > 3) {
            found = categories.get(lower.substring(0, lower.length() - 3) + "y");
            if (found != null) {
                return found;
            }
        }
        if (lower.endsWith("es") && lower.length() > 2) {
            found = categories.get(lower.substring(0, lower.length() - 2));
            if (found != null) {
                return found;
            }
        }
        if (lower.endsWith("s") && lower.length() > 1) {
            found = categories.get(lower.substring(0, lower.length() - 1));
            if (found != null) {
                return found;
            }
        }
        return Set.of();
    }
}
