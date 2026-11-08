package hermit.tool.claims;

import hermit.claims.Claim;
import hermit.claims.ClaimLedger;
import hermit.claims.ClaimSession;
import hermit.evidence.Evidence;
import hermit.tool.api.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The planner's handle on the claim ledger: list the claims, add the claims a text makes, and map a claim to code - CodeGraph
 * (declarations, call paths), a Hindsight recording of a test that reaches it, and only then a blind review of the lines.
 */
public class ClaimInspectorTool implements Tool {

    @Override
    public String getDocumentation() {
        return "ClaimInspector: maps a claim about the code to the code itself and decides it on evidence only — CodeGraph "
        + "(declarations, call paths), a Hindsight recording of a test that reaches it (a test is written in a scratch "
        + "worktree when none does), and only then a review of the cited lines. Parameters: {\"action\": \"status\" | "
        + "\"extract\" | \"map\", \"id\": \"<claim id, e.g. C3, or 'next' for the next OPEN claim>\" (map), \"text\": "
        + "\"<text whose claims to add>\" (extract), \"origin\": \"request\" | \"answer\" | \"reviewer\" | \"plan\" (extract)}. "
        + "Every claim an answer makes must be in the ledger and decided before the answer can stand.";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String action = String.valueOf(parameters.getOrDefault("action", "status")).toLowerCase(Locale.ROOT);
        List<Claim> ledger = ClaimSession.ledger();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", action);
        switch (action) {
            case "extract" -> {
                String text = String.valueOf(parameters.getOrDefault("text", ""));
                Claim.Origin origin = origin(String.valueOf(parameters.getOrDefault("origin", "plan")));
                List<Claim> added = ClaimSession.inspector().extract(ledger, text, origin, 0, ClaimSession.maxClaims());
                out.put("status", "Success");
                out.put("added", added.stream().map(c -> c.id + ": " + c.text).toList());
            }
            case "map" -> {
                String id = String.valueOf(parameters.getOrDefault("id", "next"));
                Claim claim = find(ledger, id);
                if (claim == null) {
                    out.put("status", "Error");
                    out.put("message", "no claim " + id + " in the ledger"
                            + (ClaimLedger.open(ledger).isEmpty() ? " (none is OPEN)" : ""));
                    return out;
                }
                ClaimSession.inspector().map(claim);
                out.put("status", "Success");
                out.put("claim", claim.id + " [" + claim.status + "] " + claim.text);
                out.put("proofs", describe(claim));
            }
            default -> out.put("status", "Success");
        }
        out.put("ledger", ClaimLedger.render(ledger));
        return out;
    }

    static Claim find(List<Claim> ledger, String id) {
        if (id == null || id.isBlank() || id.equalsIgnoreCase("next")) {
            List<Claim> open = ClaimLedger.open(ledger);
            return open.isEmpty() ? null : open.get(0);
        }
        for (Claim c : ledger) {
            if (c.id.equalsIgnoreCase(id.strip())) {
                return c;
            }
        }
        return null;
    }

    static List<String> describe(Claim claim) {
        List<String> out = new ArrayList<>();
        for (Evidence p : claim.evidence) {
            StringBuilder sb = new StringBuilder(p.kind.name()).append(p.decides ? " (decides " + p.verdict + "): " : " (open): ")
                    .append(p.summary);
            if (p.test != null) {
                sb.append(" — test ").append(p.test);
            }
            if (!p.anchors.isEmpty()) {
                sb.append(" — ").append(String.join(", ", p.anchors));
            }
            if (!p.callChain.isEmpty()) {
                sb.append(" — path ").append(String.join(" -> ", p.callChain));
            }
            out.add(sb.toString());
        }
        if (claim.status == Claim.Status.UNDECIDED && claim.undecidedReason != null) {
            out.add("undecided: " + claim.undecidedReason);
        }
        return out;
    }

    static Claim.Origin origin(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "request", "ticket" -> Claim.Origin.REQUEST;
            case "answer" -> Claim.Origin.ANSWER;
            case "reviewer" -> Claim.Origin.REVIEWER;
            default -> Claim.Origin.PLAN;
        };
    }
}
