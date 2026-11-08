package hermit.tool.codehost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hermit.model.ChatClient;
import hermit.tool.api.Tool;
import hermit.workspace.RemoteExec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A SPECIALIZED SKILL (not a thin tool wrapper) for reading GitHub pull requests
 * and issues via the authenticated {@code gh} CLI. It embodies the "LLM proposes,
 * code disposes" split we settled on: the Planner only decides "inspect this
 * GitHub thing"; everything hard-and-error-prone is deterministic code here -
 *
 *   1. REFERENCE RESOLUTION: parse a PR/issue reference out of whatever the
 *      planner passed (a full URL - even one with a trailing /changes or /files -,
 *      an owner/repo#123, or a bare number + repo), or, failing that, extract the
 *      first github.com reference from a supplied context string. Only if BOTH
 *      deterministic paths fail does it make ONE confined LLM extraction call,
 *      and that result is still validated against the source text (a hallucinated
 *      URL whose repo/number do not appear verbatim in the input is rejected).
 *   2. INTENT -> COMMAND: a fixed lookup (viewPR/diffPR/viewIssue), or inferred
 *      from the URL shape (/pull/ vs /issues/). No free-form command generation.
 *   3. EXECUTION: gh is invoked as an argument array (never `bash -c`), so there
 *      is no shell-injection surface and the command is identical every run.
 *   4. FALLBACK LADDER: --json -> plain view; auth failure and "gh not installed"
 *      are turned into actionable messages; a genuine 404 from the AUTHENTICATED
 *      tool is reported as truly-not-found (the only case where the link is wrong).
 *
 * Because gh carries the user's credentials, this NEVER 404s on a login-only repository the
 * way an unauthenticated curl does - which was the exact failure this skill exists
 * to remove.
 */
public class CodeHostSkill implements Tool {

    private static final int TIMEOUT_SEC = 60;
    private static final int BODY_CAP = 4000;
    private static final int DIFF_CAP = 12_000;
    private static final int MAX_FILES_LISTED = 25;
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getDocumentation() {
        return "GitHub: read a GitHub pull request or issue through the authenticated gh CLI. "
                + "PREFER THIS over raw Bash/gh/curl for anything on github.com — it resolves the "
                + "PR/issue reference for you, picks the right gh command, and (unlike curl) works on "
                + "LOGIN-ONLY repositories because gh is logged in. Parameters: {\"url\": \"<PR or issue URL>\" "
                + "(or \"ref\": \"owner/repo#123\", or \"repo\": \"owner/repo\" + \"number\": <n>), "
                + "\"intent\": \"viewPR\"|\"diffPR\"|\"viewIssue\"|\"auto\" (optional, default auto — "
                + "inferred from the URL), \"context\": \"<text to pull the reference from if url is "
                + "missing>\" (optional), \"remote\": \"user@host\" (optional — set ONLY when told to run "
                + "gh on a remote machine over SSH, e.g. a login-only repository authed only there)}. Returns the "
                + "PR/issue title, state, author, body, changed files (or the raw diff for diffPR).";
    }

    // ---- deterministic reference model -------------------------------------

    /** A resolved GitHub reference. kind is "pull" or "issues" (gh URL segment). */
    record Ref(String owner, String repo, String kind, int number, String canonicalUrl) {}

    // Full github.com URL: captures owner, repo, pull|issues, number (ignores trailing /files, /changes, #anchor).
    private static final Pattern URL_RE = Pattern.compile(
            "https?://github\\.com/([\\w.-]+)/([\\w.-]+)/(pull|issues)/(\\d+)");
    // Shorthand owner/repo#123
    private static final Pattern SHORT_RE = Pattern.compile("([\\w.-]+)/([\\w.-]+)#(\\d+)");

    /**
     * Parse a reference from an explicit value, optionally using a repo hint for a
     * bare number. Returns null if nothing usable is found. Pure/deterministic.
     */
    static Ref parseRef(String raw, String repoHint, Integer bareNumber) {
        if (raw != null && !raw.isBlank()) {
            Matcher u = URL_RE.matcher(raw);
            if (u.find()) {
                String owner = u.group(1), repo = u.group(2), kind = u.group(3);
                int n = Integer.parseInt(u.group(4));
                return new Ref(owner, repo, kind, n, canonical(owner, repo, kind, n));
            }
            Matcher s = SHORT_RE.matcher(raw);
            if (s.find()) {
                String owner = s.group(1), repo = s.group(2);
                int n = Integer.parseInt(s.group(3));
                // shorthand doesn't say pull vs issue; default to pull, gh will redirect issues too
                return new Ref(owner, repo, "pull", n, canonical(owner, repo, "pull", n));
            }
        }
        // repo hint + a bare number (from a "number" param or a lone integer in raw)
        Integer n = bareNumber;
        if (n == null && raw != null) {
            Matcher d = Pattern.compile("^#?(\\d+)$").matcher(raw.trim());
            if (d.find()) {
                n = Integer.parseInt(d.group(1));
            }
        }
        if (n != null && repoHint != null && repoHint.matches("[\\w.-]+/[\\w.-]+")) {
            String[] p = repoHint.split("/");
            return new Ref(p[0], p[1], "pull", n, canonical(p[0], p[1], "pull", n));
        }
        return null;
    }

    /** Extract the first GitHub PR/issue reference appearing in a block of text. */
    static Ref extractFromContext(String context) {
        if (context == null || context.isBlank()) {
            return null;
        }
        Matcher u = URL_RE.matcher(context);
        if (u.find()) {
            String owner = u.group(1), repo = u.group(2), kind = u.group(3);
            int n = Integer.parseInt(u.group(4));
            return new Ref(owner, repo, kind, n, canonical(owner, repo, kind, n));
        }
        Matcher s = SHORT_RE.matcher(context);
        if (s.find()) {
            String owner = s.group(1), repo = s.group(2);
            int n = Integer.parseInt(s.group(3));
            return new Ref(owner, repo, "pull", n, canonical(owner, repo, "pull", n));
        }
        return null;
    }

    private static String canonical(String owner, String repo, String kind, int n) {
        return "https://github.com/" + owner + "/" + repo + "/" + kind + "/" + n;
    }

    /** Resolve the effective intent: explicit if valid, else inferred from the ref's kind. */
    static String inferIntent(String explicit, Ref ref) {
        if (explicit != null) {
            String e = explicit.trim();
            if (e.equalsIgnoreCase("viewPR") || e.equalsIgnoreCase("diffPR") || e.equalsIgnoreCase("viewIssue")) {
                return e.substring(0, 1).toLowerCase() + e.substring(1); // normalise leading case
            }
        }
        return "issues".equals(ref.kind()) ? "viewIssue" : "viewPR";
    }

    static boolean looksLikeAuthError(String stderr) {
        if (stderr == null) {
            return false;
        }
        String s = stderr.toLowerCase();
        return s.contains("gh auth login") || s.contains("not logged into") || s.contains("http 401")
                || s.contains("bad credentials") || s.contains("authentication");
    }

    static boolean looksLikeNotFound(String stderr) {
        if (stderr == null) {
            return false;
        }
        String s = stderr.toLowerCase();
        return s.contains("could not resolve") || s.contains("not found") || s.contains("http 404")
                || s.contains("no pull requests") || s.contains("no issues");
    }

    /** True only if the model-extracted ref is actually grounded in the source text. */
    static boolean grounded(Ref ref, String sourceText) {
        if (ref == null || sourceText == null) {
            return false;
        }
        return sourceText.contains(ref.repo()) && sourceText.contains(String.valueOf(ref.number()));
    }

    // ---- execution ---------------------------------------------------------

    @Override
    public Map<String, Object> execute(Map<String, Object> parameters) {
        String raw = firstNonBlank(parameters, "url", "ref", "target", "pr", "issue", "link");
        String repoHint = str(parameters.get("repo"));
        String context = str(parameters.get("context"));
        Integer number = asInt(parameters.get("number"));

        // 1) deterministic resolution from explicit args
        Ref ref = parseRef(raw, repoHint, number);
        // 2) deterministic resolution from a supplied context blob
        if (ref == null) {
            ref = extractFromContext(context);
        }
        // 3) confined LLM fallback - ONE call, and only if code couldn't find it;
        //    its output is re-parsed AND grounded against the source text.
        String source = ((raw == null ? "" : raw) + "\n" + (context == null ? "" : context)).trim();
        if (ref == null && !source.isBlank()) {
            Ref guess = llmExtract(source);
            if (grounded(guess, source)) {
                ref = guess;
            }
        }
        if (ref == null) {
            return err("No GitHub PR/issue reference found. Pass a github.com PR/issue URL in 'url', "
                    + "an 'owner/repo#123' in 'ref', or 'repo'+'number'.");
        }

        // gh + its auth live on the BRAIN host (where Hermit runs), so gh runs LOCALLY by default even when
        // a global -Dhermit.exec.host default sends the code/build tools to another machine. It goes remote
        // ONLY if the call explicitly carries a "remote" param (specExplicit ignores the global default).
        RemoteExec.Spec spec = RemoteExec.specExplicit(parameters, null);
        String intent = inferIntent(str(parameters.get("intent")), ref);
        return switch (intent) {
            case "diffPR" -> diffPR(ref, spec);
            case "viewIssue" -> viewItem(ref, "issue", spec);
            default -> viewItem(ref, "pr", spec);
        };
    }

    /** Gh pr/issue view with --json, falling back to a plain human view, with actionable errors. */
    private Map<String, Object> viewItem(Ref ref, String kind, RemoteExec.Spec spec) {
        String fields = "pr".equals(kind)
                ? "number,title,state,author,body,url,files,additions,deletions,baseRefName,headRefName"
                : "number,title,state,author,body,url";
        Exec e = run(spec, kind, "view", ref.canonicalUrl(), "--json", fields);
        if (!e.launched) {
            return err("The 'gh' CLI is not installed or not on PATH. Install GitHub CLI (gh) to read "
                    + "GitHub PRs/issues, or fall back to Bash.");
        }
        if (e.exit == 0 && !e.out.isBlank()) {
            return presentJson(ref, kind, e.out);
        }
        // ladder: auth / not-found get specific guidance
        if (looksLikeAuthError(e.err)) {
            return err("gh is not authenticated — run `gh auth login` (this is NOT a bad URL). Ref: "
                    + ref.canonicalUrl());
        }
        // retry: plain human-readable view (handles unknown-field / json edge cases)
        Exec plain = run(spec, kind, "view", ref.canonicalUrl());
        if (plain.exit == 0 && !plain.out.isBlank()) {
            Map<String, Object> r = ok(ref, kind + "View");
            r.put("view", cap(plain.out, BODY_CAP + DIFF_CAP));
            return r;
        }
        if (looksLikeNotFound(e.err) || looksLikeNotFound(plain.err)) {
            return err("Authenticated gh could not find " + ref.canonicalUrl()
                    + " — the reference is genuinely wrong or does not exist (not merely a login-only "
                    + "repository access issue). gh said: " + tail(firstNonBlankStr(plain.err, e.err), 300));
        }
        return err("gh " + kind + " view failed (exit " + e.exit + "): " + tail(firstNonBlankStr(e.err, plain.err), 400));
    }

    private Map<String, Object> diffPR(Ref ref, RemoteExec.Spec spec) {
        Exec e = run(spec, "pr", "diff", ref.canonicalUrl());
        if (!e.launched) {
            return err("The 'gh' CLI is not installed or not on PATH.");
        }
        if (e.exit == 0) {
            Map<String, Object> r = ok(ref, "diffPR");
            r.put("diff", cap(e.out, DIFF_CAP));
            return r;
        }
        if (looksLikeAuthError(e.err)) {
            return err("gh is not authenticated — run `gh auth login`. Ref: " + ref.canonicalUrl());
        }
        if (looksLikeNotFound(e.err)) {
            return err("Authenticated gh could not find " + ref.canonicalUrl() + " — the reference is wrong.");
        }
        return err("gh pr diff failed (exit " + e.exit + "): " + tail(e.err, 400));
    }

    /** Parse gh's --json output into a compact, planner-friendly structure. */
    private Map<String, Object> presentJson(Ref ref, String kind, String json) {
        try {
            JsonNode n = mapper.readTree(json);
            Map<String, Object> r = ok(ref, ("pr".equals(kind) ? "pr" : "issue") + "View");
            r.put("number", n.path("number").asInt());
            r.put("title", n.path("title").asText(""));
            r.put("state", n.path("state").asText(""));
            r.put("author", n.path("author").path("login").asText(""));
            r.put("url", n.path("url").asText(ref.canonicalUrl()));
            String body = n.path("body").asText("");
            r.put("body", cap(body, BODY_CAP));
            if ("pr".equals(kind)) {
                r.put("additions", n.path("additions").asInt());
                r.put("deletions", n.path("deletions").asInt());
                r.put("baseRef", n.path("baseRefName").asText(""));
                r.put("headRef", n.path("headRefName").asText(""));
                List<String> files = new ArrayList<>();
                JsonNode fs = n.path("files");
                int total = fs.isArray() ? fs.size() : 0;
                if (fs.isArray()) {
                    for (int i = 0; i < fs.size() && i < MAX_FILES_LISTED; i++) {
                        files.add(fs.get(i).path("path").asText());
                    }
                }
                r.put("filesChanged", total);
                r.put("files", files);
            }
            return r;
        } catch (Exception ex) {
            // JSON unexpectedly unparseable - hand back the raw, still a success.
            Map<String, Object> r = ok(ref, kind + "View");
            r.put("raw", cap(json, BODY_CAP));
            return r;
        }
    }

    // ---- confined LLM fallback extractor -----------------------------------

    private Ref llmExtract(String source) {
        try {
            String prompt = "From the text below, output ONLY the GitHub pull-request or issue reference "
                    + "as a github.com URL and NOTHING else (no words). If there is none, output exactly NONE.\n\n"
                    + source;
            String ans = new ChatClient().getResponse(prompt);
            if (ans == null || ans.isBlank() || ans.contains("NONE")) {
                return null;
            }
            return parseRef(ans.trim(), null, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ---- gh process exec (argument array; no shell) ------------------------

    private static final class Exec {
        final boolean launched;
        final int exit;
        final String out;
        final String err;
        Exec(boolean launched, int exit, String out, String err) {
            this.launched = launched;
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    private Exec run(RemoteExec.Spec spec, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("gh");
        for (String a : args) {
            cmd.add(a);
        }
        // Local, or `gh …` on the remote host over SSH when spec is remote. stderr kept SEPARATE from
        // stdout (ssh preserves both streams) so the auth/not-found ladder still works.
        ProcessBuilder pb = RemoteExec.command(cmd, spec);
        try {
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread to = drain(p.getInputStream(), out);
            Thread te = drain(p.getErrorStream(), err);
            to.start();
            te.start();
            boolean finished = p.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                to.join(1000);
                te.join(1000);
                return new Exec(true, -1, out.toString(), "gh timed out after " + TIMEOUT_SEC + "s");
            }
            to.join(2000);
            te.join(2000);
            return new Exec(true, p.exitValue(), out.toString(), err.toString());
        } catch (IOException notInstalled) {
            return new Exec(false, -1, "", notInstalled.getMessage());
        } catch (Exception e) {
            return new Exec(true, -1, "", e.getMessage());
        }
    }

    private Thread drain(InputStream in, StringBuilder sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sink) {
                        sink.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        return t;
    }

    // ---- small helpers -----------------------------------------------------

    private Map<String, Object> ok(Ref ref, String action) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "Success");
        r.put("action", action);
        r.put("ref", ref.canonicalUrl());
        return r;
    }

    private Map<String, Object> err(String message) {
        return Map.of("status", "Error", "message", message);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String firstNonBlank(Map<String, Object> params, String... keys) {
        for (String k : keys) {
            Object v = params.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static String firstNonBlankStr(String... ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n...(" + (s.length() - max) + " more chars)";
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
