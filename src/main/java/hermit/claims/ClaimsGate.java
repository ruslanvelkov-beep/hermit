package hermit.claims;

import hermit.plan.RepeatGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The claims gate's text checks: the part of a request that states the claims to check, the claims a reviewer disputes,
 * the note on a refuted claim, and the undecided claims an answer states as established.
 */
public final class ClaimsGate {

    private ClaimsGate() {
    }

    /** The part of a request that states the claims to check: a ticket's finding, else the whole request. */
    public static String claimsTextOf(String request) {
        if (request == null) {
            return "";
        }
        int a = request.indexOf("THE FINDING");
        int b = a < 0 ? -1 : request.indexOf("YOUR TASK", a);
        return a >= 0 ? request.substring(a, b > a ? b : request.length()) : request;
    }

    /** The disputed claims of a reviewer's issue with their reasons: {claim, reason}. */
    public static List<String[]> disputesOf(String issue) {
        List<String[]> out = new ArrayList<>();
        String marker = "CONTRADICTS them: ";
        int at = issue == null ? -1 : issue.indexOf(marker);
        if (at < 0) {
            return out;
        }
        String body = issue.substring(at + marker.length());
        int end = body.indexOf(". Drop each such claim");
        int semi = body.indexOf("; the verdict does not follow");
        if (semi >= 0 && (end < 0 || semi < end)) {
            end = semi;
        }
        if (end >= 0) {
            body = body.substring(0, end);
        }
        for (String part : body.split(" \\| ")) {
            String[] two = part.split("\\s+[—–-]\\s+", 2);
            if (two[0].strip().length() >= 12) {
                out.add(new String[] {two[0].strip(), two.length > 1 ? two[1].strip() : ""});
            }
        }
        return out;
    }

    /** How a refusal names a refuted claim the answer asserts: the answer's own sentence, the proof, what to do. */
    public static String refutedNote(Claim c, String said) {
        return c.id + (said == null ? "" : " — your sentence \"" + RepeatGuard.excerpt(said, 200) + "\"")
                + " (refuted: " + RepeatGuard.excerpt(c.decidingEvidence() == null ? "" : c.decidingEvidence().summary, 200)
                + ") — say precisely what the proof leaves standing, or drop it";
    }

    private static final Pattern UNDECIDED_WORDING = Pattern.compile(
            "(?i)\\b(undecided|unproven|not (?:shown|established|proven|proved|confirmed|verified)|unverified|cannot be (?:confirmed|shown|verified)|no line shows)\\b");
    /** "undecided … but supported", "-> validated": the paragraph names the status and then overrides it. */
    private static final Pattern OVERRIDDEN = Pattern.compile(
            "(?is)\\b(undecided|unproven)\\b.{0,200}?(\\bbut\\b|\\bhowever\\b|->|→).{0,120}?\\b(supported|proven|proved|validated|confirmed|established|shown)\\b");

    /**
     * The UNDECIDED claims an answer states as established: every paragraph that cites the claim's id or restates it
     * says nothing of its being undecided.
     */
    public static List<String> undecidedStatedAsFact(String answer, List<Claim> answerClaims) {
        List<String> out = new ArrayList<>();
        String[] paragraphs = (answer == null ? "" : answer).split("\\n\\s*\\n|\\n(?=\\s*(?:\\d+\\.|[-*])\\s)");
        for (Claim c : answerClaims) {
            if (c.status != Claim.Status.UNDECIDED) {
                continue;
            }
            boolean mentioned = false, qualified = false;
            for (String p : paragraphs) {
                boolean here = Pattern.compile("\\b" + c.id + "\\b").matcher(p).find();
                for (String sentence : p.split("(?<=[.!?])\\s+")) {   // a restatement is a sentence, not a paragraph
                    here |= ClaimLedger.match(List.of(c), sentence) != null;
                }
                if (here) {
                    mentioned = true;
                    qualified |= UNDECIDED_WORDING.matcher(p).find() && !OVERRIDDEN.matcher(p).find();
                }
            }
            if (mentioned && !qualified) {
                out.add(c.id);
            }
        }
        return out;
    }
}
