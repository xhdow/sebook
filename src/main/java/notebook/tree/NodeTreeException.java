package notebook.tree;

/**
 * Thrown when a tree operation cannot be completed: bad path, reserved key,
 * missing field, invalid JSON, and so on.
 */
public class NodeTreeException extends Exception {

    public NodeTreeException(String message) {
        super(message);
    }

    public NodeTreeException(String message, Throwable cause) {
        super(message, cause);
    }
}
