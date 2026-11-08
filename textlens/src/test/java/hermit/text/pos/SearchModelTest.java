package hermit.text.pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.text.model.Tag;
import hermit.text.model.Token.Candidate;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchModelTest {

    static final String MODEL = String.join("\n",
            "{\"kind\": \"meta\", \"source\": \"test\", \"licences\": \"none\"}",
            "{\"kind\": \"weight\", \"feature\": \"w=run\", \"weights\": {\"VERB\": 1.5, \"NOUN\": -0.5, "
                    + "\"FOO\": 9.0}}",
            "",
            "{\"kind\": \"word\", \"word\": \"run\", \"shares\": {\"VERB\": 0.6, \"NOUN\": 0.36, \"ADJ\": 0.04}}",
            "{\"kind\": \"word\", \"word\": \"tie\", \"shares\": {\"VERB\": 0.45, \"NOUN\": 0.45, \"ADV\": 0.1}}",
            "{\"kind\": \"word\", \"word\": \"don't\", \"shares\": {\"AUX\": 1.0}}",
            "{\"kind\": \"lemmas\", \"pos\": \"noun\", \"lemmas\": [\"box\", \"run\", \"set\", \"man\", \"church\", "
                    + "\"look_up\"]}",
            "{\"kind\": \"lemmas\", \"pos\": \"verb\", \"lemmas\": [\"run\", \"set\", \"make\", \"box\", \"look_up\"]}",
            "{\"kind\": \"lemmas\", \"pos\": \"adj\", \"lemmas\": [\"fine\", \"set\"]}",
            "{\"kind\": \"lemmas\", \"pos\": \"adv\", \"lemmas\": [\"fast\"]}",
            "{\"kind\": \"exceptions\", \"pos\": \"verb\", \"forms\": {\"ran\": [\"run\"]}}",
            "{\"kind\": \"exceptions\", \"pos\": \"noun\", \"forms\": {\"men\": [\"man\"]}}") + "\n";

    /** The model of {@link #MODEL}, written gzip-compressed into {@code dir}. */
    static SearchModel model(Path dir) throws IOException {
        Path file = dir.resolve("search.jsonl.gz");
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(file))) {
            output.write(MODEL.getBytes(StandardCharsets.UTF_8));
        }
        return SearchModel.load(file).orElseThrow();
    }

    @Test
    void aMissingFileIsNoModel(@TempDir Path dir) throws IOException {
        assertEquals(Optional.empty(), SearchModel.load(dir.resolve("missing.jsonl")));
        String before = System.getProperty(SearchModel.MODEL_PROPERTY);
        try {
            System.clearProperty(SearchModel.MODEL_PROPERTY);
            assertEquals(Optional.empty(), SearchModel.fromSystemProperties(), "no property");
            System.setProperty(SearchModel.MODEL_PROPERTY, dir.resolve("absent.jsonl").toString());
            assertEquals(Optional.empty(), SearchModel.fromSystemProperties(), "a property naming no file");
        } finally {
            if (before == null) {
                System.clearProperty(SearchModel.MODEL_PROPERTY);
            } else {
                System.setProperty(SearchModel.MODEL_PROPERTY, before);
            }
        }
    }

    @Test
    void theFileHoldsWeightsAndSharesInTheirOrder(@TempDir Path dir) throws IOException {
        SearchModel model = model(dir);
        assertEquals(1.5, model.weight("w=run", Tag.VERB));
        assertEquals(-0.5, model.weight("w=run", Tag.NOUN));
        assertEquals(0.0, model.weight("w=run", Tag.ADJ), "a tag without a weight");
        assertEquals(0.0, model.weight("w=walk", Tag.VERB), "a feature without weights");
        assertEquals(List.of(Tag.VERB, Tag.NOUN, Tag.ADJ), List.copyOf(model.shares("Run").keySet()));
        assertEquals(0.36, model.shares("RUN").get(Tag.NOUN));
        assertEquals(Map.of(Tag.AUX, 1.0), model.shares("Don’t"), "a typographic apostrophe");
        assertEquals(Map.of(), model.shares("walk"));
        assertEquals("don't", SearchModel.normalize("Don’t"));
    }

    @Test
    void wordNetReadsAFormByItsMorphology(@TempDir Path dir) throws IOException {
        SearchModel model = model(dir);
        assertEquals(EnumSet.of(Tag.NOUN, Tag.VERB), model.wordNetTags("boxes"), "xes for a noun, es for a verb");
        assertEquals(Set.of(Tag.VERB), model.wordNetTags("Ran"), "an exception");
        assertEquals(Set.of(Tag.NOUN), model.wordNetTags("men"), "an exception and men to man");
        assertEquals(Set.of(Tag.NOUN), model.wordNetTags("churches"));
        assertEquals(Set.of(Tag.VERB), model.wordNetTags("making"), "ing to e");
        assertEquals(Set.of(Tag.ADJ), model.wordNetTags("finer"), "er to e");
        assertEquals(Set.of(Tag.ADV), model.wordNetTags("fast"));
        assertEquals(EnumSet.of(Tag.NOUN, Tag.VERB), model.wordNetTags("look-up"), "a hyphen as an underscore");
        assertEquals(EnumSet.of(Tag.NOUN, Tag.VERB, Tag.ADJ), model.wordNetTags("Set"));
        assertTrue(model.wordNetTags("s").isEmpty(), "an ending is detached only from a longer word");
        assertTrue(model.wordNetTags("walked").isEmpty());
    }

    @Test
    void theExtrasFollowWordNetTheDictionaryAndCapitals(@TempDir Path dir) throws IOException {
        SearchModel model = model(dir);
        assertEquals(List.of(new Candidate(Tag.ADJ, "x:wn"), new Candidate(Tag.VERB, "x:wn"),
                        new Candidate(Tag.PROPN, "x:cap")),
                model.extras("Set", List.of(new Candidate(Tag.NOUN, "word-forms")), false));
        assertEquals(List.of(new Candidate(Tag.NOUN, "x:wn")),
                model.extras("run", List.of(new Candidate(Tag.VERB, "word-forms")), false),
                "a share below 5% is no extra");
        assertEquals(List.of(new Candidate(Tag.VERB, "x:dict"), new Candidate(Tag.NOUN, "x:dict"),
                        new Candidate(Tag.ADV, "x:dict")),
                model.extras("tie", List.of(), false), "the larger share first, the dictionary's order on a tie");
        assertEquals(List.of(new Candidate(Tag.VERB, "x:dict"), new Candidate(Tag.ADV, "x:dict")),
                model.extras("Tie", List.of(new Candidate(Tag.NOUN, "suffix:ie")), true),
                "no capital at the start of a sentence");
        assertEquals(List.of(new Candidate(Tag.VERB, "x:dict"), new Candidate(Tag.NOUN, "x:dict"),
                        new Candidate(Tag.ADV, "x:dict")),
                model.extras("Tie", List.of(new Candidate(Tag.PROPN, "word-forms")), false),
                "no capital when the word holds PROPN");
    }
}
