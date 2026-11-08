package hermit.text.model;

import java.util.List;

/** Which spans cover a token; a token that no span covers is residual text, still input to the claim splitter. */
public record Coverage(String tokenId, List<String> spanIds) {

    /** Whether no span covers the token. */
    public boolean residual() {
        return spanIds.isEmpty();
    }
}
