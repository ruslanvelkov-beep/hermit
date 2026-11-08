package hermit.text.model;

import java.util.List;

/**
 * The optional layers of schema 1.1 (the version number stays 1): markers, clauses, frames, coreference, dates
 * ({@link DateResolution}) and the layout ({@link Layout}, the owner, 2026-11-06). A layer that is null did not run; an
 * empty list ran and found nothing.
 */
public record Layers(List<Marker> markers, List<Clause> clauses, List<Frame> frames, List<Coreference> coreference,
        List<DateResolution> dates, Layout layout) {

    /** No layer ran, as in a document of schema version 1. */
    public static final Layers NONE = new Layers(null, null, null, null, null, null);

    /** The layers with no layout layer. */
    public Layers(List<Marker> markers, List<Clause> clauses, List<Frame> frames, List<Coreference> coreference,
            List<DateResolution> dates) {
        this(markers, clauses, frames, coreference, dates, null);
    }

    /** The layers with no date layer and no layout layer. */
    public Layers(List<Marker> markers, List<Clause> clauses, List<Frame> frames, List<Coreference> coreference) {
        this(markers, clauses, frames, coreference, null, null);
    }
}
