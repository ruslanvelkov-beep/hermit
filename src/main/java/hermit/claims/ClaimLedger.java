package hermit.claims;

import hermit.evidence.Evidence;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The claims of a turn and their evidence. Every claim - from the request, an answer, a reviewer - is mapped to code
 * and ends SUPPORTED or REFUTED on evidence, or UNDECIDED with the reason. The ledger is rendered into every planner
 * and answer prompt, so a proven claim stays in front of the model until the turn ends.
 */
public final class ClaimLedger {

    private ClaimLedger() {}

    /** The overlap of content words above which two texts state the same claim. */
    static final double SAME_CLAIM = 0.75;

    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on",
            "for", "is", "are", "be", "by", "with", "that", "this", "it", "its", "as", "at", "from",
            "not", "no", "does", "do", "can", "cannot", "will", "would", "which", "when", "if", "than",
            "then", "so", "but", "has", "have", "was", "were", "their", "they");

    /** The content words of a text, lower-cased (negations kept: they flip a claim). */
    static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^a-z0-9_#.]+")) {
            String t = w.replaceAll("^[.#]+|[.#]+$", "");
            if (t.length() > 1 && (!STOP.contains(t) || t.equals("not") || t.equals("no") || t.equals("cannot"))) {
                out.add(t);
            }
        }
        return out;
    }

    /** Jaccard overlap of the content words of two texts. */
    static double overlap(String a, String b) {
        Set<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(?:not|no|never|none|cannot)\\b|n['’]t\\b");

    /** True when the text negates an odd number of times: "cannot delete" and "can delete" are opposite claims. */
    static boolean negated(String text) {
        Matcher m = NEGATION.matcher(text == null ? "" : text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n % 2 == 1;
    }

    /**
     * The ledger claim that states the same as {@code text}, or null;
     * a claim and its negation are never the same.
     */
    public static Claim match(List<Claim> ledger, String text) {
        Claim best = null;
        double bestScore = SAME_CLAIM;
        boolean negated = negated(text);
        for (Claim c : ledger) {
            if (negated(c.text) != negated) {
                continue;
            }
            double s = overlap(c.text, text);
            if (s >= bestScore) {
                best = c;
                bestScore = s;
            }
        }
        return best;
    }

    /** Adds a claim unless the ledger already holds the same one; returns the ledger's claim either way. */
    public static Claim add(List<Claim> ledger, String text, Claim.Origin origin, int iteration, String parentId) {
        Claim same = match(ledger, text);
        if (same != null) {
            return same;
        }
        Claim c = new Claim("C" + (ledger.size() + 1), text.strip(), origin, iteration);
        c.parentId = parentId;
        ledger.add(c);
        return c;
    }

    /** The claims that are still open, in ledger order. */
    public static List<Claim> open(List<Claim> ledger) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : ledger) {
            if (c.status == Claim.Status.OPEN) {
                out.add(c);
            }
        }
        return out;
    }

    /** True when every claim of the request has been mapped (decided, or undecided with a reason). */
    public static boolean requestClaimsMapped(List<Claim> ledger) {
        for (Claim c : ledger) {
            if (c.origin == Claim.Origin.REQUEST && c.status == Claim.Status.OPEN) {
                return false;
            }
        }
        return true;
    }

    /** The CLAIMS block of the planner and answer prompts; empty when there are no claims. */
    public static String render(List<Claim> ledger) {
        if (ledger == null || ledger.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(
                "\n--- CLAIMS: every claim is mapped to code and decided on evidence only. A SUPPORTED "
                + "claim may be stated; a REFUTED claim may only be stated as refuted; an UNDECIDED one only as undecided; "
                + "an OPEN one must be inspected (the claim inspector action=map) before any answer rests on it ---\n");
        for (Claim c : ledger) {
            sb.append(c.id).append(" [").append(c.status).append("] (")
                    .append(c.origin.name().toLowerCase(Locale.ROOT)).append(") ")
                    .append(cap(c.text, 240));
            Evidence p = c.decidingEvidence();
            if (p != null) {
                sb.append("\n    evidence (").append(p.kind).append("): ").append(cap(p.summary, 320));
                if (!p.anchors.isEmpty()) {
                    sb.append(" [")
                            .append(String.join(", ", p.anchors.subList(0, Math.min(4, p.anchors.size()))))
                            .append("]");
                }
            } else if (c.status == Claim.Status.UNDECIDED && c.undecidedReason != null) {
                sb.append("\n    undecided: ").append(cap(c.undecidedReason, 240));
            } else if (c.status == Claim.Status.OPEN) {
                sb.append("\n    TO MAP");
                if (!c.notes.isEmpty()) {
                    sb.append(" — disputed: ").append(cap(c.notes.get(c.notes.size() - 1), 200));
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Collapses whitespace and caps text at the given length. */
    static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
