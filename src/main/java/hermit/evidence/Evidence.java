package hermit.evidence;

import hermit.claims.Claim;
import java.util.ArrayList;
import java.util.List;

/**
 * Evidence for or against a claim. Static evidence is what the code graph and source show. Runtime evidence is
 * what a recording of a test shows. Logic is a blind review over cited lines, used only when the other two
 * cannot decide.
 */
public class Evidence {

    public enum Kind { STATIC, RUNTIME, LOGIC }

    public Kind kind;
    /** SUPPORTED, REFUTED or null when this evidence does not decide the claim. */
    public Claim.Status verdict;
    public boolean decides;
    /** One to three lines a reader can check: what was looked at and what it showed. */
    public String summary;
    /** file:line anchors the summary rests on. */
    public List<String> anchors = new ArrayList<>();
    /** A call chain from the code graph, entry first. */
    public List<String> callChain = new ArrayList<>();
    /** The test that ran (Class#method), and the recording directory. */
    public String test;
    public String recording;
    /** Excerpt of recorded events (calls, arguments, returns, conditions) the verdict rests on. */
    public String events;

    public Evidence() {}

    public Evidence(Kind kind, Claim.Status verdict, boolean decides, String summary) {
        this.kind = kind;
        this.verdict = verdict;
        this.decides = decides;
        this.summary = summary;
    }
}
