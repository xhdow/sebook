package notebook.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

import notebook.crypto.Vault;
import notebook.crypto.VaultException;

/**
 * Stand-alone command-line tool for encrypting and decrypting arbitrary files,
 * and for showing a vault file's cleartext metadata.
 *
 * <pre>
 *   java -cp ... notebook.cli.FileToolCli encrypt &lt;in&gt; [&lt;out&gt;] [--force]
 *   java -cp ... notebook.cli.FileToolCli decrypt &lt;in&gt; [&lt;out&gt;] [--force]
 *   java -cp ... notebook.cli.FileToolCli info    &lt;file&gt;
 * </pre>
 *
 * <p>{@code info} never asks for a password and never decrypts anything: it
 * only reports the structural header fields, including the salt and nonce as
 * hex.</p>
 *
 * <p>Passwords are read interactively (twice for encryption). They are never
 * accepted as command-line arguments, which would leak them into the process
 * list and shell history.</p>
 */
public final class FileToolCli {

    private final Console console = System.console();
    private final BufferedReader stdin = console == null
            ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
            : null;
    private final PrintStream out = System.out;
    private final PrintStream err = System.err;

    private static final String DEFAULT_ENC_SUFFIX = ".enc";
    private static final String DEFAULT_DEC_SUFFIX = ".dec";

    public static void main(String[] args) {
        int code = new FileToolCli().run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    private int run(String[] args) {
        if (args.length == 0) {
            usage();
            return 2;
        }
        String cmd = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        try {
            return switch (cmd) {
                case "encrypt", "enc", "e" -> encrypt(rest);
                case "decrypt", "dec", "d" -> decrypt(rest);
                case "info", "i" -> info(rest);
                case "-h", "--help", "help" -> {
                    usage();
                    yield 0;
                }
                default -> {
                    err.println("unknown command: " + args[0]);
                    usage();
                    yield 2;
                }
            };
        } catch (VaultException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("i/o error: " + e.getMessage());
            return 1;
        }
    }

    private void usage() {
        err.println("""
                usage:
                  FileToolCli encrypt <input> [output] [--force]   encrypt any file
                  FileToolCli decrypt <input> [output] [--force]   decrypt a vault file
                  FileToolCli info <file>                          show header metadata,
                                                                     including salt/nonce hex
                                                                     (no password needed)

                defaults:
                  encrypt output = <input>.enc
                  decrypt output = <input> without .enc, or <input>.dec
                """);
    }

    // ---------------------------------------------------------------------
    // encrypt / decrypt
    // ---------------------------------------------------------------------

    private int encrypt(String[] args) throws IOException, VaultException {
        Options o = Options.parse(args);
        if (o.input == null) {
            usage();
            return 2;
        }
        Path in = Path.of(o.input);
        if (!Files.isRegularFile(in)) {
            err.println("not a regular file: " + in);
            return 1;
        }
        Path outPath = Path.of(o.output != null ? o.output : o.input + DEFAULT_ENC_SUFFIX);
        if (!checkOverwrite(outPath, o.force)) {
            return 1;
        }

        char[] pwd = readPasswordTwice();
        if (pwd == null) {
            return 1;
        }
        byte[] plain = null;
        byte[] encrypted = null;
        try {
            plain = Files.readAllBytes(in);
            encrypted = Vault.encrypt(plain, pwd);
            Vault.writeAtomically(outPath, encrypted);
            out.println("encrypted " + in + " -> " + outPath
                    + " (" + plain.length + " -> " + encrypted.length + " bytes)");
            return 0;
        } finally {
            Vault.wipe(plain);
            Vault.wipe(encrypted);
            Vault.wipe(pwd);
        }
    }

    private int decrypt(String[] args) throws IOException, VaultException {
        Options o = Options.parse(args);
        if (o.input == null) {
            usage();
            return 2;
        }
        Path in = Path.of(o.input);
        if (!Files.isRegularFile(in)) {
            err.println("not a regular file: " + in);
            return 1;
        }
        Path outPath = Path.of(o.output != null ? o.output : defaultDecryptName(o.input));
        if (!checkOverwrite(outPath, o.force)) {
            return 1;
        }

        char[] pwd = readPassword("password: ");
        if (pwd == null) {
            return 1;
        }
        byte[] plain = null;
        try {
            byte[] fileBytes = Files.readAllBytes(in);
            try {
                plain = Vault.decrypt(fileBytes, pwd);
            } finally {
                Vault.wipe(fileBytes);
            }
            Vault.writeAtomically(outPath, plain);
            out.println("decrypted " + in + " -> " + outPath + " (" + plain.length + " bytes)");
            return 0;
        } finally {
            Vault.wipe(plain);
            Vault.wipe(pwd);
        }
    }

    private static String defaultDecryptName(String input) {
        if (input.endsWith(DEFAULT_ENC_SUFFIX) && input.length() > DEFAULT_ENC_SUFFIX.length()) {
            return input.substring(0, input.length() - DEFAULT_ENC_SUFFIX.length());
        }
        return input + DEFAULT_DEC_SUFFIX;
    }

    private boolean checkOverwrite(Path target, boolean force) {
        if (Files.exists(target) && !force) {
            err.println("refusing to overwrite existing file: " + target + " (use --force)");
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // info
    // ---------------------------------------------------------------------

    private int info(String[] args) throws IOException, VaultException {
        Options o = Options.parse(args);
        if (o.input == null) {
            usage();
            return 2;
        }
        Path file = Path.of(o.input);
        if (!Files.isRegularFile(file)) {
            err.println("not a regular file: " + file);
            return 1;
        }
        byte[] data = Files.readAllBytes(file);
        try {
            Vault.HeaderInfo h = Vault.inspect(data);
            out.println("file           : " + file.toAbsolutePath());
            out.println("total size     : " + h.totalLen() + " bytes");
            out.println("format version : " + h.version());
            out.println("kdf            : " + h.kdfName());
            out.println("kdf memory     : " + h.memoryKib() + " KiB ("
                    + String.format("%.1f", h.memoryKib() / 1024.0) + " MiB)");
            out.println("kdf iterations : " + h.iterations());
            out.println("kdf parallelism: " + h.parallelism());
            out.println("key length     : " + h.keyLen() + " bytes");
            out.println("padding length : " + h.padLen() + " bytes");
            HexFormat hex = HexFormat.of();
            out.println("salt length    : " + h.saltLen() + " bytes");
            out.println("salt (hex)     : " + hex.formatHex(h.salt()));
            out.println("nonce length   : " + h.nonceLen() + " bytes");
            out.println("nonce (hex)    : " + hex.formatHex(h.nonce()));
            out.println("ciphertext+tag : " + h.ciphertextLen() + " bytes");
            out.println("overhead       : " + (h.totalLen() - h.ciphertextLen()) + " bytes");
            return 0;
        } finally {
            Vault.wipe(data);
        }
    }

    // ---------------------------------------------------------------------
    // Input helpers
    // ---------------------------------------------------------------------

    private char[] readPasswordTwice() throws IOException {
        char[] first = readPassword("password: ");
        if (first == null) {
            return null;
        }
        char[] second = readPassword("repeat password: ");
        if (second == null) {
            Vault.wipe(first);
            return null;
        }
        boolean same = Arrays.equals(first, second);
        Vault.wipe(second);
        if (!same) {
            Vault.wipe(first);
            err.println("passwords do not match.");
            return null;
        }
        if (first.length < 8) {
            Vault.wipe(first);
            err.println("password must be at least 8 characters.");
            return null;
        }
        return first;
    }

    private char[] readPassword(String prompt) throws IOException {
        if (console != null) {
            return console.readPassword(prompt);
        }
        out.print(prompt);
        out.flush();
        String line = stdin.readLine();
        return line == null ? null : line.toCharArray();
    }

    // ---------------------------------------------------------------------
    // Argument parsing
    // ---------------------------------------------------------------------

    private static final class Options {
        String input;
        String output;
        boolean force;

        static Options parse(String[] args) {
            Options o = new Options();
            for (String a : args) {
                if (a.equals("--force") || a.equals("-f")) {
                    o.force = true;
                } else if (a.startsWith("-")) {
                    // unknown option: ignore rather than mistake it for a path
                } else if (o.input == null) {
                    o.input = a;
                } else if (o.output == null) {
                    o.output = a;
                }
            }
            return o;
        }
    }
}
