package hermit.text.pos;

import hermit.text.model.Token.Candidate;
import java.util.List;

/** A word list that proposes tag candidates for WORD tokens (milestone A2 of the annotation plan, section 5). */
public interface Lexicon {

    /**
     * The word's candidates in order, each naming this lexicon's rule; an empty list when the lexicon does not know the
     * word.
     */
    List<Candidate> candidates(String word);
}
