package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainLexiconTest {

    private static final DomainLexicon DOMAIN = DomainLexicon.standard();

    /** The word's candidate tags in the lexicon, in order. */
    private static List<Tag> tags(DomainLexicon lexicon, String word) {
        return lexicon.candidates(word).stream().map(Candidate::tag).toList();
    }

    @Test
    void softwareWordsAreAmbiguousOnPurpose() {
        for (String word : List.of("test", "build", "commit", "run", "call", "return", "throw")) {
            assertEquals(List.of(Tag.NOUN, Tag.VERB), tags(DOMAIN, word), word);
        }
    }

    @Test
    void verbsAndNounsHaveOneCandidateEachNamingTheDomainRule() {
        assertEquals(List.of(Tag.VERB), tags(DOMAIN, "deploy"));
        assertEquals(List.of(Tag.VERB), tags(DOMAIN, "enable"));
        assertEquals(List.of(Tag.NOUN), tags(DOMAIN, "repository"));
        assertEquals(List.of(Tag.NOUN), tags(DOMAIN, "classpath"));
        assertEquals(List.of(new Candidate(Tag.NOUN, "domain"), new Candidate(Tag.VERB, "domain")),
                DOMAIN.candidates("merge"));
    }

    @Test
    void aFinalSOrEsKeepsTheEntrysCandidates() {
        assertEquals(List.of(Tag.NOUN, Tag.VERB), tags(DOMAIN, "runs"));
        assertEquals(List.of(Tag.NOUN, Tag.VERB), tags(DOMAIN, "fixes"));
        assertEquals(List.of(Tag.NOUN), tags(DOMAIN, "classes"));
        assertEquals(List.of(Tag.VERB), tags(DOMAIN, "fails"));
        assertEquals(List.of(Tag.NOUN, Tag.VERB), tags(DOMAIN, "caches"), "cach is no entry, cache is");
        assertEquals(List.of(), tags(DOMAIN, "testing"), "other endings are the suffix rules' work");
    }

    @Test
    void wordsMatchInLowerCase() {
        assertEquals(List.of(Tag.NOUN, Tag.VERB), tags(DOMAIN, "Commit"));
        assertEquals(List.of(Tag.NOUN), tags(DOMAIN, "PARSER"));
    }

    @Test
    void toolNamesArePropnExactlyAsWritten() {
        assertEquals(List.of(new Candidate(Tag.PROPN, "domain")), DOMAIN.candidates("Bash"));
        assertEquals(List.of(Tag.PROPN), tags(DOMAIN, "TreeEdit"));
        assertEquals(List.of(), tags(DOMAIN, "bash"));
        assertEquals(List.of(), tags(DOMAIN, "BASH"));
    }

    @Test
    void namesFromTheCodeArePropnAndComeBeforeTheWordLists() {
        DomainLexicon lexicon = DomainLexicon.withNames(List.of("RepeatGuard", "Index", "gpt-oss-20b"));
        assertEquals(List.of(Tag.PROPN), tags(lexicon, "RepeatGuard"));
        assertEquals(List.of(Tag.PROPN), tags(lexicon, "Index"));
        assertEquals(List.of(Tag.PROPN), tags(lexicon, "gpt-oss-20b"));
        assertEquals(List.of(Tag.NOUN, Tag.VERB), tags(lexicon, "index"));
        assertEquals(List.of(Tag.PROPN), tags(lexicon, "Bash"), "the standard names stay");
        assertEquals(List.of(), tags(DOMAIN, "RepeatGuard"));
    }

    @Test
    void aBlankOrSpacedNameIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DomainLexicon.withNames(List.of("")));
        assertThrows(IllegalArgumentException.class, () -> DomainLexicon.withNames(List.of("Repeat Guard")));
    }

    @Test
    void declaredNamesFindsTypesAndMethodsInOrderEachOnce() {
        String source = """
                package demo;

                /** A class that reads plans. */
                public final class PlanReader {
                    private final Map<String, Plan> cache = new HashMap<>();

                    public PlanReader(Path root) {
                    }

                    public static List<Plan> readAll(Path dir) throws IOException {
                        return List.of(read(dir));
                    }

                    record Entry(String id) {}

                    enum Kind { A, B }

                    private Plan read(Path file) {
                        if (file == null) {
                            return null;
                        }
                        return parse(Files.readString(file));
                    }
                }
                """;
        assertEquals(List.of("PlanReader", "readAll", "Entry", "Kind", "read"), DomainLexicon.declaredNames(source));
    }

    @Test
    void declaredNamesSkipCommentsCallsAndFields() {
        String source = """
                // a class in a comment, and a call: run(x)
                 * record Ghost(int x)
                @Override public String toString() {
                private static final Pattern P = Pattern.compile("(a)");
                interface Port {
                """;
        assertEquals(List.of("Port"), DomainLexicon.declaredNames(source));
    }
}
