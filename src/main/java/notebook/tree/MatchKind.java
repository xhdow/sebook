package notebook.tree;

/**
 * How a fuzzy name lookup ended up.
 */
public enum MatchKind {
    /** The name matched exactly. */
    EXACT,
    /** The name did not match exactly, but exactly one candidate was close enough. */
    FUZZY_UNIQUE,
    /** Several candidates were close enough; the caller must choose. */
    FUZZY_AMBIGUOUS,
    /** Nothing matched. */
    NONE
}
