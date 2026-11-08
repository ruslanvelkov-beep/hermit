package hermit.text.lex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AbbreviationsTest {

    @Test
    void theStandardListHoldsThePlansEntries() {
        Abbreviations standard = Abbreviations.standard();
        for (String entry : List.of("e.g.", "i.e.", "etc.", "vs.", "cf.", "Fig.", "No.", "Oct.", "Sept.")) {
            assertTrue(standard.contains(entry), entry);
        }
        assertFalse(standard.contains("May."), "May is never abbreviated");
        assertFalse(standard.contains("etc"), "an entry keeps its final period");
    }

    @Test
    void aMatchTakesTheLongestEntry() {
        Abbreviations months = Abbreviations.of(List.of("Sep.", "Sept."));
        assertEquals(5, months.matchAt("Sept. 3", 0));
        assertEquals(4, months.matchAt("Sep. 3", 0));
        assertEquals(0, months.matchAt("Sept 3", 0));
    }

    @Test
    void anEntryCountsOnlyAsAWholeWord() {
        Abbreviations standard = Abbreviations.standard();
        assertEquals(4, standard.matchAt("(e.g. a list)", 1));
        assertEquals(0, standard.matchAt("bike.g. a list", 3), "a letter before it");
        assertEquals(0, standard.matchAt("e.g.x", 0), "a letter after its period");
    }

    @Test
    void entriesAreCaseSensitive() {
        Abbreviations standard = Abbreviations.standard();
        assertEquals(3, standard.matchAt("No. 5 failed", 0));
        assertEquals(0, standard.matchAt("no. Then", 0), "a sentence that ends with no");
    }

    @Test
    void anEntryWithoutItsFinalPeriodIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Abbreviations.of(List.of("etc")));
        assertThrows(IllegalArgumentException.class, () -> Abbreviations.of(List.of("e. g.")));
        assertThrows(IllegalArgumentException.class, () -> Abbreviations.of(List.of(" ")));
    }
}
