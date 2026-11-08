package hermit.text.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.lex.WordCategories.Category;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The noun categories from WordNet 3.1 (the owner, 2026-11-05). */
class WordCategoriesTest {

    @Test
    void nounsAndTheirPluralsHaveTheirCategories() {
        WordCategories standard = WordCategories.standard();
        assertEquals(Set.of(Category.TIME), standard.ofNoun("meeting"));
        assertEquals(Set.of(Category.TIME), standard.ofNoun("Meetings"));
        assertEquals(Set.of(Category.TIME), standard.ofNoun("deadline"));
        assertEquals(Set.of(Category.TIME), standard.ofNoun("events"));
        assertEquals(Set.of(Category.TIME), standard.ofNoun("holidays"));
        assertEquals(Set.of(Category.UNIT, Category.TIME), standard.ofNoun("seconds"), "a unit and a moment");
        assertEquals(Set.of(Category.UNIT), standard.ofNoun("bytes"));
        assertEquals(Set.of(), standard.ofNoun("objects"));
        assertEquals(Set.of(), standard.ofNoun("files"));
    }

    @Test
    void theAddedNounsOfTheDomainAreTimeNouns() {
        for (String word : WordCategories.ADDED_TIME_NOUNS) {
            assertTrue(WordCategories.standard().ofNoun(word).contains(Category.TIME), word);
        }
        assertEquals(Set.of(Category.TIME), WordCategories.standard().ofNoun("releases"));
    }

    @Test
    void theResourceCarriesWordNetsLicence() throws IOException {
        try (InputStream in = WordCategories.class.getResourceAsStream(WordCategories.RESOURCE)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("WordNet 3.1 Copyright 2011 by Princeton University."));
            assertTrue(text.contains("[time]") && text.contains("[unit]"));
        }
    }

    @Test
    void readTakesHeadingsCommentsAndWords() throws IOException {
        WordCategories read = WordCategories.read(new BufferedReader(new StringReader(
                "# a comment\n[time]\ngala soiree\n\n[unit]\nfurlong gala\n")));
        assertEquals(Set.of(Category.TIME, Category.UNIT), read.ofNoun("galas"));
        assertEquals(Set.of(Category.UNIT), read.ofNoun("furlongs"));
        assertEquals(Set.of(Category.TIME), read.ofNoun("release"));
        assertEquals(Set.of(), read.ofNoun("comment"));
        assertThrows(IllegalArgumentException.class,
                () -> WordCategories.read(new BufferedReader(new StringReader("gala\n[time]\n"))));
    }
}
