package hermit.tool.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hermit.claims.Claim;
import hermit.claims.ClaimInspector;
import hermit.claims.ClaimSession;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The planner's handle on the claim ledger: extract adds claims, map decides the next open one, status shows the ledger. */
class ClaimInspectorToolTest {

    private final List<Claim> ledger = new ArrayList<>();

    @BeforeEach
    void session() {
        ClaimInspector.Code none = new ClaimInspector.Code() {
            @Override
            public List<ClaimInspector.Located> locate(String spec) {
                return List.of();
            }

            @Override
            public ClaimInspector.Reach reach(String from, String to) {
                return new ClaimInspector.Reach(false, List.of(), "unresolved");
            }

            @Override
            public List<String> tests(String target) {
                return List.of();
            }

            @Override
            public Path root() {
                return Path.of(".");
            }
        };
        ClaimSession.use(new ClaimInspector(prompt -> prompt.startsWith("Split the text")
                ? "{\"claims\": [\"Nothing in this repository calls the order service twice\"]}"
                : "{\"kind\": \"outside\", \"targets\": []}", null, none, null, null, 1));
        ClaimSession.bind(ledger);
    }

    @AfterEach
    void reset() {
        ClaimSession.use(null);
        ClaimSession.bind(new ArrayList<>());
    }

    @Test
    void extractThenMapLeavesEveryClaimDecidedOrUndecidedWithAReason() {
        ClaimInspectorTool tool = new ClaimInspectorTool();
        Map<String, Object> added = tool.execute(Map.of("action", "extract", "text", "an answer", "origin", "answer"));
        assertEquals(List.of("C1: Nothing in this repository calls the order service twice"), added.get("added"));
        assertEquals(Claim.Origin.ANSWER, ledger.get(0).origin);

        Map<String, Object> mapped = tool.execute(Map.of("action", "map", "id", "next"));
        assertEquals("Success", mapped.get("status"));
        assertEquals(Claim.Status.UNDECIDED, ledger.get(0).status);
        assertTrue(String.valueOf(mapped.get("ledger")).contains("C1 [UNDECIDED] (answer)"),
        String.valueOf(mapped.get("ledger")));

        Map<String, Object> none = tool.execute(Map.of("action", "map", "id", "next"));
        assertEquals("Error", none.get("status"));
        assertTrue(String.valueOf(none.get("message")).contains("none is OPEN"));
        assertEquals("Error", tool.execute(Map.of("action", "map", "id", "C9")).get("status"));
    }
}
