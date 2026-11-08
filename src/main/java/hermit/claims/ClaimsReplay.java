package hermit.claims;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.evidence.Evidence;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps a labelled set of claims with the claim inspector, one JSON line per claim, so its decisions can be scored against the
 * labels. Usage: {@code ClaimsReplay <claims.json: [{id, claim, ...}]> <out.jsonl> [id ...]}; the inspector is the session's
 * (the same -Dhermit.* properties as a run).
 */
public final class ClaimsReplay {

    private ClaimsReplay() {}

    public static void main(String[] args) throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode claims = json.readTree(Path.of(args[0]).toFile());
        Set<String> only = new HashSet<>(Arrays.asList(args).subList(2, args.length));
        ClaimInspector inspector = ClaimSession.inspector();
        try (BufferedWriter out = Files.newBufferedWriter(
                Path.of(args[1]), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (JsonNode node : claims) {
                String id = node.path("id").asText();
                if (!only.isEmpty() && !only.contains(id)) {
                    continue;
                }
                int logged = inspector.log().size();
                long start = System.nanoTime();
                Claim claim = inspector.map(new Claim(id, node.path("claim").asText(), Claim.Origin.REQUEST, 0));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("truth", node.has("truth") ? node.get("truth").asBoolean() : null);
                row.put("status", claim.status.name());
                row.put("seconds", Math.round((System.nanoTime() - start) / 1e8) / 10.0);
                row.put("hypothesis", claim.hypothesis);
                Evidence p = claim.decidingEvidence();
                if (p != null) {
                    row.put("kind", p.kind.name());
                    row.put("summary", p.summary);
                    row.put("anchors", p.anchors);
                    row.put("callChain", p.callChain);
                    row.put("test", p.test);
                    row.put("events", p.events == null ? null : ClaimLedger.cap(p.events, 3000));
                }
                row.put("undecided", claim.undecidedReason);
                row.put("proofs", claim.evidence.size());
                row.put("log", inspector.log().subList(logged, inspector.log().size()));
                out.write(json.writeValueAsString(row));
                out.newLine();
                out.flush();
                System.out.println(id + " " + claim.status + " (" + row.get("seconds") + "s)"
                        + (p == null ? "" : " " + p.kind + ": " + ClaimLedger.cap(p.summary, 160)));
            }
        }
        System.exit(0);
    }
}
