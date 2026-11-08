package hermit.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.memory.EmbeddingProvider.Mode;
import org.junit.jupiter.api.Test;

/** The deterministic embedder must be unit-length and rank shared-vocabulary text higher. */
class HashingEmbedderTest {

    private final HashingEmbedder e = new HashingEmbedder();

    @Test
    void vectorsAreUnitLength() {
        float[] v = e.embed("fetch the current bitcoin price in usd", Mode.DOCUMENT);
        double n = 0;
        for (float x : v) {
            n += (double) x * x;
        }
        assertEquals(1.0, Math.sqrt(n), 1e-5);
    }

    @Test
    void deterministic() {
        float[] a = e.embed("read a file line by line", Mode.DOCUMENT);
        float[] b = e.embed("read a file line by line", Mode.DOCUMENT);
        assertEquals(EmbeddingProvider.cosine(a, a), EmbeddingProvider.cosine(a, b), 1e-9);
    }

    @Test
    void relatedTextScoresHigherThanUnrelated() {
        float[] q = e.embed("fetch current bitcoin price in usd", Mode.QUERY);
        float[] related = e.embed("get the current bitcoin price in usd from an api", Mode.DOCUMENT);
        float[] unrelated = e.embed("sort a list of integers with quicksort", Mode.DOCUMENT);
        double simRelated = EmbeddingProvider.cosine(q, related);
        double simUnrelated = EmbeddingProvider.cosine(q, unrelated);
        assertTrue(simRelated > simUnrelated,
                "related=" + simRelated + " should beat unrelated=" + simUnrelated);
        assertTrue(simRelated > 0.4, "related sim too low: " + simRelated);
    }
}
