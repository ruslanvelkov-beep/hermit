package hermit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The owner, 2026-10-26: the router's providers of a model failed one after another (Groq refused, DeepInfra and nscale
 * were paused, Novita overloaded) while Hermit asked for one provider only. A call now tries the providers in order,
 * cheapest first by default or fastest first, and moves to the next one when a provider fails.
 */
class ProviderRouteTest {

    private static final String ROUTER = "https://router.huggingface.co/v1";
    private static final String CATALOGUE = "{\"data\": [{\"id\": \"openai/gpt-oss-120b\", \"providers\": ["
            + "{\"provider\": \"groq\", \"status\": \"live\", \"pricing\": {\"input\": 0.15, \"output\": 0.75}, \"throughput\": 396},"
            + "{\"provider\": \"deepinfra\", \"status\": \"live\", \"pricing\": {\"input\": 0.037, \"output\": 0.17}, \"throughput\": 31},"
            + "{\"provider\": \"baseten\", \"status\": \"live\", \"pricing\": {\"input\": 0.1, \"output\": 0.5}, \"throughput\": 209},"
            + "{\"provider\": \"cerebras\", \"status\": \"live\", \"pricing\": {\"input\": 0.35, \"output\": 0.75}, \"throughput\": 982},"
            + "{\"provider\": \"featherless-ai\", \"status\": \"live\"},"
            + "{\"provider\": \"oldone\", \"status\": \"staging\", \"pricing\": {\"input\": 0.01, \"output\": 0.01}}]}]}";

    @BeforeEach
    void setUp() {
        ProviderRoute.useCatalogue(() -> CATALOGUE);
    }

    @AfterEach
    void tearDown() {
        ProviderRoute.useCatalogue(null);
        System.clearProperty("hermit.hf.route");
    }

    @Test
    void theCheapestLiveProviderComesFirstAndAPricelessOneLast() {
        assertEquals(List.of("openai/gpt-oss-120b:deepinfra", "openai/gpt-oss-120b:baseten", "openai/gpt-oss-120b:groq",
                "openai/gpt-oss-120b:cerebras", "openai/gpt-oss-120b:featherless-ai"),
                ProviderRoute.candidates("openai/gpt-oss-120b:preferred"));
    }

    @Test
    void theFastestOrderIsAProperty() {
        System.setProperty("hermit.hf.route", "fastest");
        assertEquals(List.of("openai/gpt-oss-120b:cerebras", "openai/gpt-oss-120b:groq", "openai/gpt-oss-120b:baseten",
                "openai/gpt-oss-120b:deepinfra", "openai/gpt-oss-120b:featherless-ai"),
                ProviderRoute.candidates("openai/gpt-oss-120b"));
    }

    @Test
    void aNamedProviderGoesFirstAndTheOthersFollowInOrder() {
        assertEquals(List.of("openai/gpt-oss-120b:groq", "openai/gpt-oss-120b:deepinfra", "openai/gpt-oss-120b:baseten",
                "openai/gpt-oss-120b:cerebras", "openai/gpt-oss-120b:featherless-ai"),
                ProviderRoute.candidates("openai/gpt-oss-120b:groq"));
    }

    @Test
    void theRoutersOwnChoiceIsKeptFirstWhenAskedFor() {
        System.setProperty("hermit.hf.route", "preferred");
        assertEquals("openai/gpt-oss-120b:preferred", ProviderRoute.candidates("openai/gpt-oss-120b:preferred").get(0));
        assertEquals("openai/gpt-oss-120b:deepinfra", ProviderRoute.candidates("openai/gpt-oss-120b:preferred").get(1));
    }

    @Test
    void aModelTheCatalogueDoesNotListIsSentAsGiven() {
        assertEquals(List.of("some/model:preferred"), ProviderRoute.candidates("some/model:preferred"));
        ProviderRoute.useCatalogue(() -> {
            throw new IllegalStateException("the router did not answer");
        });
        assertEquals(List.of("openai/gpt-oss-120b:preferred"), ProviderRoute.candidates("openai/gpt-oss-120b:preferred"));
    }

    @Test
    void aFailingProviderHandsTheCallToTheNextOne() throws Exception {
        List<String> tried = new ArrayList<>();
        HttpResponse<String> r = ProviderRoute.send(ROUTER, "openai/gpt-oss-120b:preferred", model -> {
            tried.add(model);
            return model.endsWith(":deepinfra") ? response(503) : model.endsWith(":baseten") ? response(429) : response(200);
        });
        assertEquals(200, r.statusCode());
        assertEquals(List.of("openai/gpt-oss-120b:deepinfra", "openai/gpt-oss-120b:baseten", "openai/gpt-oss-120b:groq"), tried);
        // the two that failed wait at the back for a while
        assertEquals("openai/gpt-oss-120b:groq", ProviderRoute.candidates("openai/gpt-oss-120b").get(0));
    }

    // the owner, 2026-10-27: GPT-OSS-120B answered empty on 17% of Novita's calls and 30% of nscale's
    @Test
    void anAnswerTheCheckRefusesHandsTheCallToTheNextProvider() throws Exception {
        List<String> tried = new ArrayList<>();
        ProviderRoute.Attempt attempt = model -> {
            tried.add(model);
            return response(200);
        };
        HttpResponse<String> r = ProviderRoute.send(ROUTER, "openai/gpt-oss-120b", attempt,
                resp -> tried.size() < 3 ? "empty reply" : null);
        assertEquals(200, r.statusCode());
        assertEquals(List.of("openai/gpt-oss-120b:deepinfra", "openai/gpt-oss-120b:baseten", "openai/gpt-oss-120b:groq"), tried);
        assertEquals("openai/gpt-oss-120b:groq", ProviderRoute.candidates("openai/gpt-oss-120b").get(0),
                "the two that answered empty wait at the back");
        tried.clear();
        ProviderRoute.send(ROUTER, "openai/gpt-oss-120b", attempt, resp -> "empty reply");
        assertEquals(5, tried.size(), "the last provider's answer is returned as it is");
    }

    @Test
    void aRequestErrorIsNotAProviderFailure() throws Exception {
        List<String> tried = new ArrayList<>();
        HttpResponse<String> r = ProviderRoute.send(ROUTER, "openai/gpt-oss-120b", model -> {
            tried.add(model);
            return response(400);
        });
        assertEquals(400, r.statusCode());
        assertEquals(1, tried.size(), "a bad request fails on every provider alike");
    }

    @Test
    void aTimeoutMovesOnAndTheLastFailureIsThrown() {
        List<String> tried = new ArrayList<>();
        assertThrows(IOException.class, () -> ProviderRoute.send(ROUTER, "openai/gpt-oss-120b", model -> {
            tried.add(model);
            throw new HttpTimeoutException("timed out");
        }));
        assertEquals(5, tried.size());
    }

    @Test
    void anotherEndpointIsCalledOnceWithTheModelAsGiven() throws Exception {
        List<String> tried = new ArrayList<>();
        ProviderRoute.send("https://api.deepseek.com/v1", "deepseek-v4-flash", model -> {
            tried.add(model);
            return response(503);
        });
        assertEquals(List.of("deepseek-v4-flash"), tried);
    }

    // the owner, 2026-10-26: cheap providers write 30-40 tokens a second, as slow as a local model; a call's timeout
    // follows the provider's speed, from 4 minutes up to 15
    @Test
    void aSlowProviderGetsALongerTimeoutUpToFifteenMinutes() {
        Duration fallback = Duration.ofSeconds(120);
        assertEquals(240, ProviderRoute.timeoutFor(ROUTER, "openai/gpt-oss-120b:groq", 8192, fallback).toSeconds(), "fast: the floor");
        assertEquals(648, ProviderRoute.timeoutFor(ROUTER, "openai/gpt-oss-120b:deepinfra", 8192, fallback).toSeconds(),
                "8,192 tokens at 31 a second, twice over, and two minutes");
        assertEquals(900, ProviderRoute.timeoutFor(ROUTER, "openai/gpt-oss-120b:featherless-ai", 8192, fallback).toSeconds(),
                "no listed speed: the ceiling");
        assertEquals(120, ProviderRoute.timeoutFor("https://api.deepseek.com/v1", "deepseek-v4-flash", 8192, fallback).toSeconds(),
                "another endpoint keeps its own");
    }

    private static HttpResponse<String> response(int status) {
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return HttpRequest.newBuilder(URI.create(ROUTER)).build();
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (a, b) -> true);
            }

            @Override
            public String body() {
                return "{}";
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create(ROUTER);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
