package hermit.memory;

import java.util.Locale;

/**
 * Deterministic, dependency-free embedder: a hashed bag-of-words (+ char 3-grams for
 * partial-match credit) projected into a fixed-dimension vector, L2-normalized. Cosine
 * between two texts then rises with shared vocabulary - enough to drive and PROVE the
 * store→retrieve pipeline (ranking, top-k, similarity floor) without any model or
 * network. The real semantic quality comes from a model-backed embedder; this is the
 * default for tests and the offline fallback.
 */
public final class HashingEmbedder implements EmbeddingProvider {

    private final int dim;

    public HashingEmbedder() {
        this(256);
    }

    public HashingEmbedder(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(String text, Mode mode) {
        float[] v = new float[dim];
        if (text == null || text.isBlank()) {
            return v;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // 1) word tokens (alphanumeric runs) - the main signal
        for (String tok : lower.split("[^a-z0-9]+")) {
            if (tok.length() < 2) {
                continue;
            }
            add(v, "w:" + tok, 1.0f);
            // 2) char 3-grams within the token - partial credit (bitcoin ~ bitcoins)
            for (int i = 0; i + 3 <= tok.length(); i++) {
                add(v, "g:" + tok.substring(i, i + 3), 0.3f);
            }
        }
        normalize(v);
        return v;
    }

    private void add(float[] v, String feature, float weight) {
        int h = feature.hashCode();
        int idx = Math.floorMod(h, dim);
        // sign from a second hash so distinct features don't all pile up positively
        float sign = ((h >>> 31) == 0) ? 1f : -1f;
        v[idx] += sign * weight;
    }

    private static void normalize(float[] v) {
        double n = 0.0;
        for (float x : v) {
            n += (double) x * x;
        }
        n = Math.sqrt(n);
        if (n > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / n);
            }
        }
    }

    @Override
    public int dimension() {
        return dim;
    }

    @Override
    public String id() {
        return "hashing-v1-d" + dim;
    }
}
