package hermit.text;

import java.util.Set;

/**
 * Extracts a fenced code block (```lang … ```) from an LLM response. This is the
 * out-of-band channel for programs: the model keeps the plan JSON tiny and puts
 * the actual program in a fenced block AFTER it, so the code - full of quotes and
 * newlines - never has to be escaped into a JSON string (the escaping the small
 * model kept mangling, which broke the parser and looped the turn).
 */
public final class CodeFence {

    /** The first fenced PROGRAM block found, plus the response with that block removed. */
    public record Extracted(String language, String code, String textWithoutBlock) {}

    // Only these fenced languages are treated as an out-of-band PROGRAM. A ```json
    // (or plain ```) block is the plan itself - never mistaken for code, so it is
    // left in place for the JSON parser.
    private static final Set<String> PROGRAM_LANGS = Set.of(
            "python", "py", "bash", "sh", "shell", "zsh", "java", "javascript", "js",
            "typescript", "ts", "ruby", "rb", "go", "rust", "rs", "c", "cpp", "c++",
            "perl", "php", "kotlin", "kt", "scala", "groovy");

    private CodeFence() {}

    public static Extracted first(String raw) {
        if (raw == null) {
            return new Extracted(null, null, null);
        }
        int search = 0;
        while (true) {
            int open = raw.indexOf("```", search);
            if (open < 0) {
                return new Extracted(null, null, raw);
            }
            int langStart = open + 3;
            int nl = raw.indexOf('\n', langStart);
            if (nl < 0) {
                return new Extracted(null, null, raw);
            }
            String lang = raw.substring(langStart, nl).trim().toLowerCase();
            int close = raw.indexOf("```", nl + 1);
            if (close < 0) {
                return new Extracted(null, null, raw);       // unterminated fence - treat as no block
            }
            if (PROGRAM_LANGS.contains(lang)) {
                String code = raw.substring(nl + 1, close);
                if (code.endsWith("\n")) {
                    code = code.substring(0, code.length() - 1);
                }
                String without = raw.substring(0, open) + raw.substring(close + 3);
                return new Extracted(lang, code, without);
            }
            search = close + 3;                              // e.g. a ```json plan - skip, keep looking
        }
    }
}
