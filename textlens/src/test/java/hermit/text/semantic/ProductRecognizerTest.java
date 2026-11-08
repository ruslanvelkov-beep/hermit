package hermit.text.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import hermit.text.model.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductRecognizerTest {

    private static List<Span> spans(String text) {
        return new ProductRecognizer().recognize(ConceptRecognizers.input(text, null));
    }

    private static List<String> found(String text) {
        return spans(text).stream()
                .map(s -> s.concept() + " " + text.substring(s.range().start(), s.range().end()) + " = " + s.value())
                .toList();
    }

    @Test
    void catalogueProductsAsWritten() {
        assertEquals(List.of("PRODUCT Gradle = \"Gradle\"", "PRODUCT JUnit = \"JUnit\"", "PRODUCT Codex = \"Codex\""),
                found("Gradle runs JUnit, and Codex plans."));
        assertEquals(List.of(), found("The gradle file and the codex folder."), "as written, capitals included");
        assertEquals("product", spans("Ask Sol.").get(0).provenance().rule());
        assertEquals("p1", spans("Ask Sol.").get(0).id());
    }

    @Test
    void scriptNamesAreProducts() {
        assertEquals(List.of("PRODUCT Node.js = \"Node.js\"", "PRODUCT Vue.js = \"Vue.js\""),
                found("The Node.js client talks to a Vue.js page."));
        assertEquals(List.of(), found("Edit app.js and the .js files."));
    }

    @Test
    void theLongestEntryWins() {
        assertEquals(List.of("PRODUCT DeepSeek Flash = \"DeepSeek Flash\"", "PRODUCT DeepSeek = \"DeepSeek\""),
                found("DeepSeek Flash answered, then DeepSeek alone."));
        assertEquals(List.of("PRODUCT UD English EWT = \"UD English EWT\""), found("Train on UD English EWT later."));
    }

    @Test
    void modelNamesWithTheirSizeOrVersion() {
        assertEquals(List.of("PRODUCT Qwen3.8-27B = \"Qwen3.8-27B\"", "PRODUCT GPT-OSS-20B = \"GPT-OSS-20B\"",
                "PRODUCT gpt-oss-120b = \"gpt-oss-120b\""),
                found("Compare Qwen3.8-27B, GPT-OSS-20B and gpt-oss-120b."));
    }

    @Test
    void organisationsAreNames() {
        assertEquals(List.of("NAME OpenAI = \"OpenAI\"", "NAME Hugging Face = \"Hugging Face\""),
                found("OpenAI and Hugging Face serve them."));
        assertEquals("name", spans("Ask Groq.").get(0).provenance().rule());
    }

    @Test
    void noOtherCapitalisedWordIsAName() {
        assertEquals(List.of(), found("Zephyr met Quillon at Brightwater on Monday."));
    }

    @Test
    void testLibrariesAndDatabasesAreProducts() {
        assertEquals(List.of("PRODUCT Mockito = \"Mockito\"", "PRODUCT AssertJ = \"AssertJ\"",
                "PRODUCT SQLite = \"SQLite\""), found("Mockito mocks, AssertJ asserts, SQLite stores."));
    }

    @Test
    void aPossessiveEndingIsATokenOfItsOwn() {
        assertEquals(List.of("PRODUCT Hermit = \"Hermit\"", "NAME Hugging Face = \"Hugging Face\""),
                found("Hermit's suite uses Hugging Face\u2019s router."));
        assertEquals(List.of(), found("The hermit's cave."));
    }

    @Test
    void aCodeSpanNamingAProductIsAProduct() {
        assertEquals(List.of("PRODUCT `gpt-6-astra` = \"gpt-6-astra\"", "PRODUCT `zai-glm-4.7` = \"zai-glm-4.7\"",
                "PRODUCT `Qwen/Qwen3.8-27B:preferred` = \"Qwen/Qwen3.8-27B:preferred\"",
                "PRODUCT `Qwen3.8-27B` = \"Qwen3.8-27B\"", "PRODUCT `Gradle` = \"Gradle\""),
                found("Use `gpt-6-astra`, `zai-glm-4.7`, `Qwen/Qwen3.8-27B:preferred`, `Qwen3.8-27B` or `Gradle`."));
        assertEquals(List.of(), found("Call `RepeatGuard` with `2.21.1` there."));
    }

    @Test
    void modelNamesAsProvidersListThem() {
        assertTrue(ProductRecognizer.MODEL_NAME.matcher("qwen2.5-coder-7b-instruct").matches());
        assertTrue(ProductRecognizer.MODEL_NAME.matcher("glm-4.7").matches());
        assertTrue(ProductRecognizer.MODEL_NAME.matcher("Qwen/Qwen3.8-27B").matches());
        assertFalse(ProductRecognizer.MODEL_NAME.matcher("qwen").matches());
        assertFalse(ProductRecognizer.MODEL_NAME.matcher("phil-2").matches());
    }

    @Test
    void anOrganisationUsedForItsServiceKeepsAProductReading() {
        List<Span> names = spans("Every Groq call failed, then we went via Groq; Groq itself said nothing.");
        JsonNode product = Spans.alternative(Span.Concept.PRODUCT, Spans.JSON.textNode("Groq"));
        assertEquals(List.of(List.of(product), List.of(product), List.of()),
                names.stream().map(Span::alternatives).toList());
        assertEquals(Span.Concept.NAME, names.get(0).concept());
    }
}
