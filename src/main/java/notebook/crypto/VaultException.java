package notebook.crypto;

/**
 * Thrown by {@link Vault} for any cryptographic or file-format problem.
 *
 * <p>The message is always safe to show to a human. It never contains plaintext
 * or key material.</p>
 */
public class VaultException extends Exception {

    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
