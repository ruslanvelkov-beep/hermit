package hermit.answer;

import java.util.Map;

/**
 * Turns a SUCCESSFUL structured tool result into a finished, user-facing answer
 * WITHOUT going through the model. This is the "code disposes" counterpart to the
 * final-answer execution gate: the gate detects when the model ignored a result;
 * a formatter then reports that result deterministically, so for structured skills
 * (e.g. GitHub) the real values always reach the user regardless of how weak the
 * synthesising model is.
 */
public interface ResultFormatter {

    /** Whether this formatter can produce an answer from the given tool's result. */
    boolean canFormat(String toolName, Map<String, Object> result);

    /** A complete, human-readable answer built deterministically from the result. */
    String format(String toolName, Map<String, Object> result);
}
