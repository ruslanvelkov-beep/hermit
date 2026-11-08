package hermit.memory;

public interface EmbeddingProvider {

    enum Mode { DOCUMENT, QUERY }

    /** @return an L2-normalized float[] (unit length), so cosine == dot product. */
    float[] embed(String text, Mode mode);

    /** Dimensionality of vectors this provider returns. */
    int dimension();

    /** Short id (model name / scheme) stored with each vector so a switch is detectable. */
    String id();

    /** Cosine similarity of two unit vectors == dot product; guards length/`null`. */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
