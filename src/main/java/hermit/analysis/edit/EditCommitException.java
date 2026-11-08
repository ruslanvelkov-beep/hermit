package hermit.analysis.edit;

import java.util.List;

/**
 * Thrown when a tree edit's commit finds that the edited tree is NOT a correct program: the rendered source failed to
 * re-parse, or names, members or arities no longer resolve. Carries the positioned problem list (line:column against
 * the RENDERED source), the feedback an iterating caller needs, and the rendered source itself for inspection. The
 * editor's state is left as it is, so the caller can fix forward or roll back.
 */
public class EditCommitException extends RuntimeException {

    private final List<String> problems;
    private final String renderedSource;

    public EditCommitException(List<String> problems, String renderedSource) {
        super("commit rejected: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
        this.renderedSource = renderedSource;
    }

    /** The provable problems, each positioned as {@code [line:col] message} in the rendered source. */
    public List<String> problems() {
        return problems;
    }

    /** The full rendered source the problems refer to. */
    public String renderedSource() {
        return renderedSource;
    }
}
