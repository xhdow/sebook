package notebook.crypto;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Strong, password-based encryption of an arbitrary byte payload into a
 * single self-contained binary blob.
 *
 * <h2>Algorithms</h2>
 * <ul>
 *   <li><b>KDF:</b> Argon2id (memory-hard, resists GPU/ASIC attacks). The
 *       parameters are stored in the file header and authenticated as AEAD
 *       associated data, so they cannot be downgraded unnoticed.</li>
 *   <li><b>Encryption:</b> AES-256-GCM (authenticated encryption, detects any
 *       tampering of ciphertext).</li>
 * </ul>
 *
 * <h2>File layout (big endian)</h2>
 * <pre>
 *   padLen     1 byte   0..{@value #MAX_PAD_LEN}; number of random padding bytes
 *   padBytes   padLen    random noise, contents ignored
 *   version    1 byte   (currently 1); also acts as the format discriminator
 *   kdf        1 byte   (0x13 = Argon2id, 0x00 = legacy Argon2i, 0x01 = Argon2d)
 *   memKiB     4 byte   Argon2 memory cost in KiB
 *   iterations 4 byte   Argon2 time cost
 *   parallelism 1 byte  Argon2 lanes
 *   keyLen     1 byte   derived key length in bytes
 *   saltLen    1 byte
 *   salt       saltLen bytes
 *   nonce      12 bytes
 *   ciphertext rest      AES-GCM output, includes the 16 byte auth tag
 * </pre>
 *
 * <p>There is deliberately no magic signature: the file starts with random
 * noise of a random length, so no fixed signature or fixed-size prefix can be
 * used to identify the file or fingerprint its format. The header fields are
 * themselves random or high-entropy values. Everything above the ciphertext
 * (that is, the whole header) is fed to AES-GCM as associated data, so the
 * header cannot be modified or downgraded.</p>
 *
 * <p>The padding length is not authenticated by the tag in a meaningful way:
 * modifying it only makes decryption fail, which is indistinguishable from a
 * wrong password.</p>
 *
 * <h2>Memory hygiene</h2>
 * <p>This class never writes plaintext to disk. Intermediate key and plaintext
 * buffers are wiped with {@link Arrays#fill} as soon as they are no longer
 * needed. Callers passing a {@code char[]} password are responsible for wiping
 * it (see {@link #wipe(char[])}); {@code String} passwords cannot be wiped and
 * should be avoided.</p>
 *
 * <p>This class is thread safe.</p>
 */
public final class Vault {

    /**
     * Legacy signature used by files written before the header was randomised.
     * New files never contain it; it is kept only so old vaults can be detected
     * and rejected with a clear message.
     */
    @Deprecated
    public static final byte[] LEGACY_MAGIC = {'N', 'B', 'T', '1'};
    public static final int VERSION = 1;

    /** Maximum number of random padding bytes placed at the start of a file. */
    public static final int MAX_PAD_LEN = 255;

    public static final int KDF_ARGON2D = 0x01;
    public static final int KDF_ARGON2I = 0x00;
    public static final int KDF_ARGON2ID = 0x13;

    public static final int DEFAULT_MEMORY_KIB = 64 * 1024; // 64 MiB
    public static final int DEFAULT_ITERATIONS = 3;
    public static final int DEFAULT_PARALLELISM = 1;

    public static final int KEY_LEN = 32;   // 256-bit AES key
    public static final int SALT_LEN = 16;
    public static final int NONCE_LEN = 12;
    public static final int TAG_BITS = 128;

    /** Lower bound for accepted Argon2 parameters when reading a file (anti-DoS / anti-downgrade). */
    private static final int MIN_MEMORY_KIB = 8 * 1024;
    /** Upper bound so a crafted header cannot exhaust memory (anti-DoS). */
    private static final int MAX_MEMORY_KIB = 1024 * 1024; // 1 GiB
    private static final int MAX_ITERATIONS = 1_000;
    private static final int MAX_PARALLELISM = 64;
    private static final int MIN_ITERATIONS = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Vault() {
        // utility class
    }

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /** Derives a key from {@code password} using default Argon2id parameters. */
    public static byte[] deriveKey(char[] password, byte[] salt) {
        return deriveKey(password, salt, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM);
    }

    /**
     * Derives a key with explicit Argon2id parameters.
     *
     * @return a fresh {@link #KEY_LEN}-byte array; the caller should wipe it.
     */
    public static byte[] deriveKey(char[] password, byte[] salt, int memoryKib, int iterations, int parallelism) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        if (salt == null || salt.length < SALT_LEN) {
            throw new IllegalArgumentException("salt must be at least " + SALT_LEN + " bytes");
        }
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] key = new byte[KEY_LEN];
        byte[] pwdBytes = toUtf8(password);
        try {
            generator.generateBytes(pwdBytes, key);
        } finally {
            Arrays.fill(pwdBytes, (byte) 0);
        }
        return key;
    }

    /** Encrypts {@code plaintext} with default parameters and returns the vault file bytes. */
    public static byte[] encrypt(byte[] plaintext, char[] password) throws VaultException {
        return encrypt(plaintext, password, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM);
    }

    /**
     * Encrypts {@code plaintext} and returns the complete vault file content.
     * No file is touched.
     */
    public static byte[] encrypt(byte[] plaintext, char[] password,
                                 int memoryKib, int iterations, int parallelism) throws VaultException {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        byte[] salt = randomBytes(SALT_LEN);
        byte[] nonce = randomBytes(NONCE_LEN);
        byte[] padding = randomBytes(RANDOM.nextInt(MAX_PAD_LEN + 1));
        byte[] header = buildHeader(padding, salt, nonce, memoryKib, iterations, parallelism);

        byte[] key = deriveKey(password, salt, memoryKib, iterations, parallelism);
        try {
            byte[] ciphertext = crypt(true, key, nonce, header, plaintext);
            return concat(header, ciphertext);
        } finally {
            Arrays.fill(padding, (byte) 0);
            wipe(key);
        }
    }

    /** Decrypts vault file bytes with default-compatible header parameters. */
    public static byte[] decrypt(byte[] fileBytes, char[] password) throws VaultException {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new VaultException("vault data is empty");
        }
        Header h = parseHeader(fileBytes);
        if (h.kdf != KDF_ARGON2ID) {
            throw new VaultException("unsupported KDF id: 0x" + Integer.toHexString(h.kdf & 0xff));
        }
        if (h.memoryKib < MIN_MEMORY_KIB || h.memoryKib > MAX_MEMORY_KIB
                || h.iterations < MIN_ITERATIONS || h.iterations > MAX_ITERATIONS
                || h.parallelism < 1 || h.parallelism > MAX_PARALLELISM) {
            throw new VaultException("vault header contains KDF parameters outside the accepted range");
        }
        byte[] header = Arrays.copyOfRange(fileBytes, 0, h.headerLength);
        byte[] ciphertext = Arrays.copyOfRange(fileBytes, h.headerLength, fileBytes.length);

        byte[] key = deriveKey(password, h.salt, h.memoryKib, h.iterations, h.parallelism);
        try {
            return crypt(false, key, h.nonce, header, ciphertext);
        } finally {
            wipe(key);
        }
    }

    /** Encrypts and atomically replaces {@code file}. */
    public static void encryptToFile(Path file, byte[] plaintext, char[] password) throws VaultException {
        encryptToFile(file, plaintext, password, DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM);
    }

    /** Encrypts and atomically replaces {@code file} using the given KDF parameters. */
    public static void encryptToFile(Path file, byte[] plaintext, char[] password,
                                     int memoryKib, int iterations, int parallelism) throws VaultException {
        byte[] data = encrypt(plaintext, password, memoryKib, iterations, parallelism);
        try {
            writeAtomically(file, data);
        } catch (IOException e) {
            throw new VaultException("cannot write vault file: " + e.getMessage(), e);
        } finally {
            wipe(data);
        }
    }

    /** Reads and decrypts a vault file. Never leaves a plaintext file behind. */
    public static byte[] decryptFromFile(Path file, char[] password) throws VaultException {
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new VaultException("cannot read vault file: " + e.getMessage(), e);
        }
        try {
            return decrypt(fileBytes, password);
        } finally {
            wipe(fileBytes);
        }
    }

    /**
     * Weak structural check: could {@code data} plausibly be a vault file?
     *
     * <p>Since the format has no magic signature, this only verifies the minimum
     * length and that the data does not start with the deprecated {@code NBT1}
     * signature. It is not a substitute for {@link #decrypt}.</p>
     */
    public static boolean isVaultFile(byte[] data) {
        return data != null
                && data.length >= BASE_HEADER_LEN + NONCE_LEN
                && !startsWith(data, LEGACY_MAGIC);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes {@code data} to {@code target} through a sibling temp file and an
     * atomic rename, so an interrupted write never corrupts an existing file.
     * The temp file only ever contains ciphertext.
     */
    public static void writeAtomically(Path target, byte[] data) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null) {
            dir = Path.of(".").toAbsolutePath();
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, ".vault-", ".tmp");
        boolean moved = false;
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** Overwrites a password buffer with NUL characters. */
    public static void wipe(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    /** Overwrites a byte buffer with zeroes. */
    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private record Header(int kdf, int memoryKib, int iterations, int parallelism,
                          int keyLen, byte[] salt, byte[] nonce, int headerLength) {

        Header {
            salt = salt.clone();
            nonce = nonce.clone();
        }

        @Override
        public byte[] salt() {
            return salt.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }

    private static byte[] buildHeader(byte[] padding, byte[] salt, byte[] nonce,
                                      int memoryKib, int iterations, int parallelism) {
        if (memoryKib < MIN_MEMORY_KIB) {
            throw new IllegalArgumentException("memoryKib must be >= " + MIN_MEMORY_KIB);
        }
        if (iterations < MIN_ITERATIONS) {
            throw new IllegalArgumentException("iterations must be >= " + MIN_ITERATIONS);
        }
        if (parallelism < 1 || parallelism > 255) {
            throw new IllegalArgumentException("parallelism out of range");
        }
        if (padding.length > MAX_PAD_LEN) {
            throw new IllegalArgumentException("padding too long");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(padding.length);
            out.write(padding);
            out.writeByte(VERSION);
            out.writeByte(KDF_ARGON2ID);
            out.writeInt(memoryKib);
            out.writeInt(iterations);
            out.writeByte(parallelism);
            out.writeByte(KEY_LEN);
            out.writeByte(salt.length);
            out.write(salt);
            out.write(nonce);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory header build failed", e);
        }
    }

    /**
     * Fixed cost of the header without padding: padLen(1) + version(1) + kdf(1)
     * + mem(4) + iter(4) + par(1) + keyLen(1) + saltLen(1) + salt + nonce.
     */
    private static final int BASE_HEADER_LEN =
            1 + 1 + 1 + 4 + 4 + 1 + 1 + 1 + SALT_LEN + NONCE_LEN;

    private static Header parseHeader(byte[] data) throws VaultException {
        if (data.length < BASE_HEADER_LEN + NONCE_LEN) {
            throw new VaultException("not a vault file (too short)");
        }
        if (startsWith(data, LEGACY_MAGIC)) {
            throw new VaultException("legacy vault format (NBT1) is not supported; "
                    + "re-encrypt it with an older build first");
        }
        int p = 0;
        int padLen = data[p++] & 0xff;
        if (data.length < p + padLen + BASE_HEADER_LEN) {
            throw new VaultException("corrupt vault header");
        }
        p += padLen;
        int version = data[p++] & 0xff;
        if (version != VERSION) {
            throw new VaultException("unsupported vault version: " + version);
        }
        int kdf = data[p++] & 0xff;
        int memoryKib = readInt(data, p);
        p += 4;
        int iterations = readInt(data, p);
        p += 4;
        int parallelism = data[p++] & 0xff;
        int keyLen = data[p++] & 0xff;
        int saltLen = data[p++] & 0xff;
        if (keyLen != KEY_LEN) {
            throw new VaultException("unsupported key length: " + keyLen);
        }
        if (saltLen < SALT_LEN || data.length < p + saltLen + NONCE_LEN) {
            throw new VaultException("corrupt vault header");
        }
        byte[] salt = Arrays.copyOfRange(data, p, p + saltLen);
        p += saltLen;
        byte[] nonce = Arrays.copyOfRange(data, p, p + NONCE_LEN);
        p += NONCE_LEN;
        if (data.length <= p) {
            throw new VaultException("vault contains no ciphertext");
        }
        return new Header(kdf, memoryKib, iterations, parallelism, keyLen, salt, nonce, p);
    }

    private static byte[] crypt(boolean encrypt, byte[] key, byte[] nonce, byte[] aad, byte[] input)
            throws VaultException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, nonce);
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), spec);
            cipher.updateAAD(aad);
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            if (encrypt) {
                throw new VaultException("encryption failed: " + e.getMessage(), e);
            }
            // GCM authentication failure: wrong password, or the file was modified.
            throw new VaultException("wrong password or corrupted vault", e);
        }
    }

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static byte[] randomBytes(int len) {
        byte[] b = new byte[len];
        RANDOM.nextBytes(b);
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] toUtf8(char[] chars) {
        byte[] bytes = new String(chars).getBytes(StandardCharsets.UTF_8);
        return bytes;
    }
}
