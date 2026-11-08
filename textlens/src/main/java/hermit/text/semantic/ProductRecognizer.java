package hermit.text.semantic;

import hermit.text.lex.Tokenizer;
import hermit.text.model.Span;
import hermit.text.model.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Products and names, conservatively (milestone A3 of the annotation plan, section 6.1).
 * <ul>
 *   <li>PRODUCT (rule {@code product}): an entry of {@link #PRODUCTS}, its words as written and the tokens apart only
 *       by spaces, valued with the entry; a VERSION token with a letter in it other than a leading {@code v}
 *       ({@code Qwen3.8-27B}), or an IDENT token that is a {@link #MODEL_ID} ({@code GPT-OSS-20B},
 *       {@code gpt-oss-120b}), valued with the token as written; a CODE_SPAN token whose inside text (between the
 *       backticks) is an entry of {@link #PRODUCTS}, is read by the standard tokenizer as such a VERSION or IDENT
 *       token, or is a {@link #MODEL_NAME}, valued with the inside text; a WORD token that is a
 *       {@link #SCRIPT_NAME} ({@code Node.js}), valued as written.</li>
 *   <li>NAME (rule {@code name}): an entry of {@link #ORGANISATIONS}, matched the same way. No other name is guessed: a
 *       capitalised word that no list holds is not a name. Where the text uses the organisation for its service, the
 *       span keeps the alternative {@code {"concept": "PRODUCT", "value": "<the entry>"}}: when the token after it is
 *       one of {@link #SERVICE_NOUNS} or the token before it one of {@link #SERVICE_PREPOSITIONS}, in any case
 *       ({@code every OpenAI call}, {@code via Groq}).</li>
 * </ul>
 * An entry's last word may carry a possessive ending, {@code 's} with an ASCII apostrophe or with U+2019
 * ({@code Hermit's}, {@code Hugging Face's}): the span covers the ending, and the value is the entry. The longest entry
 * wins where two start at the same token. Spans come in text order, ids {@code p1}, {@code p2}, ...
 */
public final class ProductRecognizer implements ConceptRecognizer {

    /** Products, services, languages, libraries, models and data sets by name. */
    public static final List<String> PRODUCTS = List.of("Java", "Kotlin", "Python", "Gradle", "Maven", "JUnit",
            "Jackson", "Mockito", "AssertJ", "SQLite", "Git", "GitHub", "Codex", "ChatGPT", "Hermit", "Sol", "Luna",
            "Astra", "DeepSeek", "DeepSeek Flash", "Qwen", "GPT-OSS", "LM Studio", "UD English EWT",
            "Universal Dependencies");
    /** Organisations by name. */
    public static final List<String> ORGANISATIONS = List.of("OpenAI", "Anthropic", "Hugging Face", "DeepInfra", "Groq",
            "Cerebras", "Google", "Microsoft", "Oracle", "Apache");
    /** Nouns after an organisation's name that make it name the organisation's service, in lower case. */
    public static final List<String> SERVICE_NOUNS = List.of("call", "calls", "router", "api", "apis", "model",
            "models", "endpoint", "endpoints", "service", "services", "server", "servers", "key", "keys", "account");
    /** Prepositions before an organisation's name that make it name the organisation's service, in lower case. */
    public static final List<String> SERVICE_PREPOSITIONS = List.of("through", "via", "on");
    /** A model id: letters with hyphens, then a size in billions ({@code GPT-OSS-20B}, {@code gpt-oss-120b}). */
    public static final Pattern MODEL_ID = Pattern.compile("[A-Za-z]+(?:-[A-Za-z]+)*-[0-9]+[bB]");
    /**
     * A model's name as a provider lists it: an optional owner and a slash, a model family in any case, then a digit,
     * after an optional hyphen ({@code gpt-6-astra}, {@code zai-glm-4.7}, {@code qwen2.5-coder-7b-instruct},
     * {@code Qwen/Qwen3.8-27B:preferred}).
     */
    public static final Pattern MODEL_NAME = Pattern.compile("(?:[A-Za-z0-9-]+/)?"
            + "(?i:gpt|qwen|glm|zai-glm|deepseek|llama|gemma|mistral|claude|kimi|phi)-?[0-9][A-Za-z0-9.:_-]*");
    /** A product named as a script: a capitalised name, a period and {@code js} ({@code Node.js}, {@code Vue.js}). */
    public static final Pattern SCRIPT_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*\\.js");
    /** Any letter. */
    private static final Pattern VERSION_WITH_LETTER = Pattern.compile(".*[A-Za-z].*");

    /** The spans of {@code input}, as the class javadoc describes them. */
    @Override
    public List<Span> recognize(Input input) {
        List<Span> spans = new ArrayList<>();
        Tokenizer tokenizer = Tokenizer.standard();
        int size = input.tokens().size();
        for (int i = 0; i < size;) {
            String best = null;
            Span.Concept bestConcept = null;
            int bestEnd = -1;
            for (String product : PRODUCTS) {
                String[] words = product.split(" ");
                int end = i + words.length - 1;
                if (end < size) {
                    String surface = Spans.surface(input, i, end);
                    String stem = CodeRefRecognizer.possessiveStem(surface);
                    if ((surface.equals(product) || product.equals(stem))
                            && (best == null || product.length() > best.length())) {
                        best = product;
                        bestConcept = Span.Concept.PRODUCT;
                        bestEnd = end;
                    }
                }
            }
            for (String organisation : ORGANISATIONS) {
                String[] words = organisation.split(" ");
                int end = i + words.length - 1;
                if (end < size) {
                    String surface = Spans.surface(input, i, end);
                    String stem = CodeRefRecognizer.possessiveStem(surface);
                    if ((surface.equals(organisation) || organisation.equals(stem))
                            && (best == null || organisation.length() > best.length())) {
                        best = organisation;
                        bestConcept = Span.Concept.NAME;
                        bestEnd = end;
                    }
                }
            }
            if (best != null) {
                String rule = bestConcept == Span.Concept.PRODUCT ? "product" : "name";
                String following = Spans.lower(input, bestEnd + 1);
                String preceding = Spans.lower(input, i - 1);
                boolean service = bestConcept == Span.Concept.NAME
                        && ((following != null && SERVICE_NOUNS.contains(following.toLowerCase(Locale.ROOT)))
                                || (preceding != null
                                && SERVICE_PREPOSITIONS.contains(preceding.toLowerCase(Locale.ROOT))));
                spans.add(Spans.over(input, i, bestEnd, "p" + (spans.size() + 1), bestConcept,
                        Spans.JSON.textNode(best), service
                                ? List.of(Spans.alternative(Span.Concept.PRODUCT, Spans.JSON.textNode(best)))
                                : List.of(), rule));
                i = bestEnd + 1;
                continue;
            }
            Token token = input.tokens().get(i);
            String text = Spans.surface(input, i, i);
            String value = text;
            Token.Shape shape = token.shape();
            if (shape == Token.Shape.CODE_SPAN) {
                value = text.substring(1, text.length() - 1);
                shape = tokenizer.shapeOf(value);
            }
            boolean catalogue = PRODUCTS.contains(value);
            boolean productVersion = shape == Token.Shape.VERSION
                    && VERSION_WITH_LETTER.matcher(value.startsWith("v") ? value.substring(1) : value).matches();
            boolean modelId = shape == Token.Shape.IDENT && MODEL_ID.matcher(value).matches();
            boolean modelName = token.shape() == Token.Shape.CODE_SPAN && MODEL_NAME.matcher(value).matches();
            boolean scriptName = token.shape() == Token.Shape.WORD && SCRIPT_NAME.matcher(value).matches();
            if (catalogue || productVersion || modelId || modelName || scriptName) {
                spans.add(Spans.over(input, i, i, "p" + (spans.size() + 1), Span.Concept.PRODUCT,
                        Spans.JSON.textNode(value), List.of(), "product"));
            }
            i++;
        }
        return List.copyOf(spans);
    }
}
