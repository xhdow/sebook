package sebook.tree;

import java.util.List;

/**
 * Thrown when a fuzzy path segment matches several children and the caller must
 * choose one.
 */
public class AmbiguousPathException extends NodeTreeException {

    private final List<String> candidates;

    public AmbiguousPathException(String message, List<String> candidates) {
        super(message + " " + candidates);
        this.candidates = List.copyOf(candidates);
    }

    public List<String> candidates() {
        return candidates;
    }
}
