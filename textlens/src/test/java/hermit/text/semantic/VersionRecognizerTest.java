package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.text.model.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionRecognizerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Span> spans(String text) {
        return new VersionRecognizer().recognize(ConceptRecognizers.input(text, null));
    }

    private static List<String> found(String text) {
        return spans(text).stream()
                .map(s -> s.concept() + " " + text.substring(s.range().start(), s.range().end()) + " = " + s.value())
                .toList();
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Test
    void versionsAsWritten() {
        assertEquals(List.of("VERSION 2.21.1 = \"2.21.1\"", "VERSION v3 = \"v3\"", "VERSION v1.2 = \"v1.2\""),
                found("Jackson 2.21.1 replaced v3 and v1.2."));
        assertEquals("version", spans("Use v3 now.").get(0).provenance().rule());
        assertEquals("v1", spans("Use v3 now.").get(0).id());
    }

    @Test
    void decimalVsVersionKeepsBoth() {
        Span s = spans("Upgrade Gradle 8.10 first.").get(0);
        assertEquals(json("\"8.10\""), s.value());
        assertEquals(List.of(json("{\"concept\": \"NUMBER\", \"value\": \"8.1\"}")), s.alternatives());
        assertEquals(List.of(), spans("Upgrade to 2.21.1 first.").get(0).alternatives());
    }

    @Test
    void aPlainDecimalWithoutACueIsNoVersionHere() {
        assertEquals(List.of(), found("The score was 1.10 here."));
    }

    @Test
    void aProductsVersionIsLeftToTheProducts() {
        assertEquals(List.of(), found("Ask Qwen3.8-27B again."));
    }

    @Test
    void aVersionInACodeSpan() {
        assertEquals(List.of("VERSION `2.21.1` = \"2.21.1\"", "VERSION `v3` = \"v3\"", "VERSION `1.10` = \"1.10\""),
                found("Pin `2.21.1`, `v3` and `1.10`, not `3` or `v3.x`."));
        Span decimal = spans("Pin `1.10` now.").get(0);
        assertEquals(List.of(json("{\"concept\": \"NUMBER\", \"value\": \"1.1\"}")), decimal.alternatives());
        assertEquals("version", decimal.provenance().rule());
    }
}
