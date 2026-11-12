package sebook.tree;

import java.util.List;

/**
 * Result of a fuzzy lookup against the children of a node.
 *
 * @param kind       how the lookup resolved
 * @param query      the name that was looked up
 * @param matched    the resolved child name for {@link MatchKind#EXACT} and
 *                   {@link MatchKind#FUZZY_UNIQUE}; {@code null} otherwise
 * @param candidates candidates for {@link MatchKind#FUZZY_AMBIGUOUS} (sorted
 *                   best first), empty otherwise
 */
public record Match(MatchKind kind, String query, String matched, List<String> candidates) {

    public Match {
        candidates = List.copyOf(candidates);
    }

    public boolean isResolved() {
        return kind == MatchKind.EXACT || kind == MatchKind.FUZZY_UNIQUE;
    }
}
