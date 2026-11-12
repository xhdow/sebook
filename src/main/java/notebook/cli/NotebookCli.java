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
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import notebook.crypto.Vault;
import notebook.crypto.VaultException;
import notebook.tree.AmbiguousPathException;
import notebook.tree.Match;
import notebook.tree.MatchKind;
import notebook.tree.MergeStats;
import notebook.tree.NodeTree;
import notebook.tree.NodeTreeException;

/**
 * Interactive command-line front end for an encrypted personal notebook.
 *
 * <p>Data lives in memory as a {@link NodeTree}; it is encrypted with
 * {@link Vault} and written to a single binary file only on {@code .save}.
 * Plaintext reaches the disk only through an explicit {@code .export}.</p>
 */
public final class NotebookCli {

    private static final String DEFAULT_VAULT = "notebook.vault";
    private static final int MAX_PASSWORD_ATTEMPTS = 3;

    private final Console console = System.console();
    private final BufferedReader stdin = console == null
            ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
            : null;
    private final PrintStream out = System.out;

    private Path vaultPath;
    private NodeTree tree;

    public static void main(String[] args) {
        Path path = Path.of(args.length > 0 ? args[0] : DEFAULT_VAULT);
        try {
            new NotebookCli().run(path);
        } catch (Exception e) {
            System.err.println("fatal: " + e.getMessage());
            System.exit(1);
        }
    }

    private void run(Path path) throws IOException, VaultException, NodeTreeException {
        this.vaultPath = path.toAbsolutePath();

        out.println("== encrypted notebook ==");
        out.println("vault: " + vaultPath);

        if (Files.exists(vaultPath)) {
            if (!loadExisting()) {
                return;
            }
        } else {
            if (!createNew()) {
                return;
            }
            out.println("new vault created in memory. use .save to write it to disk.");
        }

        out.println("type .help for commands.");
        out.println(tree.pwd());
        list(false, false);
        loop();
    }

    // ---------------------------------------------------------------------
    // Startup
    // ---------------------------------------------------------------------

    private boolean loadExisting() throws VaultException, IOException, NodeTreeException {
        if (!Files.isRegularFile(vaultPath)) {
            throw new VaultException("not a regular file: " + vaultPath);
        }
        for (int attempt = 1; attempt <= MAX_PASSWORD_ATTEMPTS; attempt++) {
            char[] password = readPassword("password: ");
            if (password == null) {
                out.println("cancelled.");
                return false;
            }
            try {
                byte[] plain = Vault.decryptFromFile(vaultPath, password);
                try {
                    tree = NodeTree.fromJson(new String(plain, StandardCharsets.UTF_8));
                } finally {
                    Vault.wipe(plain);
                }
                out.println("vault unlocked.");
                return true;
            } catch (VaultException e) {
                out.println("cannot unlock: " + e.getMessage());
            } finally {
                Vault.wipe(password);
            }
        }
        out.println("too many failed attempts.");
        return false;
    }

    private boolean createNew() throws IOException, NodeTreeException, VaultException {
        char[] first = readPassword("new password: ");
        if (first == null) {
            return false;
        }
        char[] second = readPassword("repeat password: ");
        if (second == null || !Arrays.equals(first, second)) {
            Vault.wipe(first);
            Vault.wipe(second);
            out.println("passwords do not match.");
            return false;
        }
        Vault.wipe(second);
        if (first.length < 8) {
            Vault.wipe(first);
            out.println("password must be at least 8 characters.");
            return false;
        }
        tree = new NodeTree();
        try {
            saveVerified(first);
        } finally {
            Vault.wipe(first);
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // REPL
    // ---------------------------------------------------------------------

    private void loop() throws IOException {
        while (true) {
            out.print(prompt() + "> ");
            out.flush();
            String line = readLine();
            if (line == null) {
                out.println();
                shutdown();
                return;
            }
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith(".")) {
                out.println("commands start with '.'; type .help for help.");
                continue;
            }
            boolean shouldExit;
            try {
                shouldExit = dispatch(line);
            } catch (NodeTreeException e) {
                out.println("error: " + e.getMessage());
                shouldExit = false;
            } catch (VaultException e) {
                out.println("vault error: " + e.getMessage());
                shouldExit = false;
            } catch (IOException e) {
                out.println("i/o error: " + e.getMessage());
                shouldExit = false;
            }
            if (shouldExit) {
                if (tree.isDirty()) {
                    out.println("WARNING: unsaved changes were discarded.");
                }
                return;
            }
        }
    }

    private boolean dispatch(String line) throws NodeTreeException, VaultException, IOException {
        String[] parts = tokenize(line);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        switch (cmd) {
            case ".help", ".h", ".?" -> { help(); return false; }
            case ".cd" -> {
                if (args.length == 0) {
                    tree.cd("/");
                    out.println(tree.pwd());
                    list(false, false);
                } else {
                    cdWithFuzzy(args[0]);
                }
                return false;
            }
            case ".pwd" -> { out.println(tree.pwd()); return false; }
            case ".ls", ".ll" -> { list(cmd.equals(".ll")); return false; }
            case ".get" -> { requireArgs(cmd, args, 1); doGet(args[0]); return false; }
            case ".set" -> { requireArgs(cmd, args, 2); doSet(args); return false; }
            case ".del", ".unset" -> { requireArgs(cmd, args, 1); doDel(args[0]); return false; }
            case ".mkdir" -> { requireArgs(cmd, args, 1); doMkdir(args[0]); return false; }
            case ".rm", ".rmdir" -> { requireArgs(cmd, args, 1); doRm(args); return false; }
            case ".ts" -> { out.println(tree.pwd() + "  ts=" + tree.ts() + "  (" + fmtTs(tree.ts()) + ")"); return false; }
            case ".pass" -> { doPass(args); return false; }
            case ".save" -> { doSave(); return false; }
            case ".export" -> { requireArgs(cmd, args, 1); doExport(Path.of(args[0])); return false; }
            case ".import" -> { requireArgs(cmd, args, 1); doImport(args[0]); return false; }
            case ".discard", ".reload" -> { doDiscard(); return false; }
            case ".exit", ".quit", ".q" -> { return confirmExit(); }
            default -> { out.println("unknown command: " + cmd + " (try .help)"); return false; }
        }
    }

    private void shutdown() throws IOException {
        if (tree != null && tree.isDirty()) {
            out.println("unsaved changes were discarded.");
        }
    }

    private boolean confirmExit() throws IOException {
        if (tree.isDirty()) {
            out.print("unsaved changes. exit anyway? [y/N] ");
            if (!isYes(readLine())) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------

    private void cdWithFuzzy(String target) throws NodeTreeException {
        try {
            tree.cd(target);
            out.println(tree.pwd());
            list(false, false);
        } catch (AmbiguousPathException e) {
            showAmbiguous(e);
        }
    }

    private void showAmbiguous(AmbiguousPathException e) {
        out.println("ambiguous name, candidates:");
        List<String> c = e.candidates();
        for (int i = 0; i < c.size(); i++) {
            out.println("  " + (i + 1) + ") " + c.get(i));
        }
        out.println("use the exact name or .cd with a longer path.");
    }

    /**
     * Generates a random password of the requested length, guaranteed to contain
     * at least one character from each category: lowercase, uppercase, digits and
     * symbols. Uses {@link java.security.SecureRandom}.
     */
    private void doPass(String[] args) throws NodeTreeException {
        String spec = args.length == 0 ? "16" : args[0];
        int length;
        try {
            length = Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            throw new NodeTreeException("usage: .pass <length>  (e.g. .pass 20)");
        }
        if (length < 4 || length > 256) {
            throw new NodeTreeException("password length must be between 4 and 256");
        }
        out.println(generatePassword(length));
    }

    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*()-_=+[]{};:,.?/";
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    public static String generatePassword(int length) {
        String[] pools = {LOWER, UPPER, DIGITS, SYMBOLS};
        String all = LOWER + UPPER + DIGITS + SYMBOLS;
        char[] result = new char[length];
        // one guaranteed character per category
        for (int i = 0; i < pools.length; i++) {
            result[i] = pick(pools[i]);
        }
        for (int i = pools.length; i < length; i++) {
            result[i] = pick(all);
        }
        // Fisher-Yates shuffle so the guaranteed characters are not always first
        for (int i = length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = result[i];
            result[i] = result[j];
            result[j] = tmp;
        }
        return new String(result);
    }

    private static char pick(String pool) {
        return pool.charAt(SECURE_RANDOM.nextInt(pool.length()));
    }

    private void doGet(String key) {
        JsonNode v = tree.get(key);
        if (v == null) {
            out.println("(missing)");
        } else if (v.isObject()) {
            out.println("'" + key + "' is a child node; use .cd " + key);
        } else {
            out.println(v.isTextual() ? v.asText() : v.toPrettyString());
        }
    }

    private void doSet(String[] args) throws NodeTreeException {
        String key = args[0];
        String raw = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        tree.set(key, parseValue(raw));
        out.println("set " + key + "  [dirty]");
    }

    private void doDel(String key) throws NodeTreeException {
        if (tree.clear(key)) {
            out.println("removed " + key + "  [dirty]");
        } else {
            out.println("no such value: " + key);
        }
    }

    private void doMkdir(String name) throws NodeTreeException {
        tree.mkdir(name);
        String base = tree.pwd().equals("/") ? "/" : tree.pwd() + "/";
        out.println("created node " + base + name + "  [dirty]");
    }

    private void doRm(String[] args) throws NodeTreeException, IOException {
        String name = args[0];
        boolean force = args.length > 1 && args[1].equals("--force");
        if (!tree.hasChild(name)) {
            if (tree.hasValue(name)) {
                out.println("'" + name + "' is a value; use .del " + name);
            } else {
                out.println("no such node: " + name);
            }
            return;
        }
        if (!force) {
            out.print("delete node '" + name + "' and everything below it? [y/N] ");
            if (!isYes(readLine())) {
                out.println("cancelled.");
                return;
            }
        }
        tree.rmdir(name);
        out.println("deleted " + name + "  [dirty]");
    }

    private void doSave() throws VaultException, IOException {
        char[] first = readPassword("password: ");
        if (first == null) {
            out.println("cancelled.");
            return;
        }
        try {
            char[] second = readPassword("repeat password: ");
            if (second == null) {
                out.println("cancelled.");
                return;
            }
            boolean same = Arrays.equals(first, second);
            Vault.wipe(second);
            if (!same) {
                out.println("passwords do not match, nothing saved.");
                return;
            }
            if (first.length == 0) {
                out.println("password must not be empty.");
                return;
            }
            saveVerified(first);
        } finally {
            Vault.wipe(first);
        }
    }

    /**
     * Encrypts the tree, decrypts it again in memory to prove the password can
     * reopen the data, and only then writes the file. On any failure nothing is
     * written.
     *
     * <p>Ownership of {@code password} stays with the caller; this method never
     * wipes it.</p>
     */
    private void saveVerified(char[] password) throws VaultException, IOException {
        byte[] plain = tree.toJson().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        byte[] verified = null;
        try {
            encrypted = Vault.encrypt(plain, password);
            verified = Vault.decrypt(encrypted, password);
            if (!Arrays.equals(plain, verified)) {
                throw new VaultException("verification failed, nothing saved");
            }
            Vault.writeAtomically(vaultPath, encrypted);
            tree.markClean();
            out.println("saved to " + vaultPath + " (encrypted, verified).");
        } finally {
            Vault.wipe(plain);
            Vault.wipe(encrypted);
            Vault.wipe(verified);
        }
    }

    private void doExport(Path target) throws IOException, VaultException {
        out.println("!! .export writes DECRYPTED JSON to disk: " + target.toAbsolutePath());
        out.print("type 'yes' to continue: ");
        String answer = readLine();
        if (answer == null || !answer.strip().equalsIgnoreCase("yes")) {
            out.println("cancelled.");
            return;
        }
        byte[] plain = tree.toJson().getBytes(StandardCharsets.UTF_8);
        try {
            Vault.writeAtomically(target.toAbsolutePath(), plain);
            out.println("exported plaintext JSON to " + target.toAbsolutePath());
            out.println("remember to delete it when you are done.");
        } finally {
            Vault.wipe(plain);
        }
    }

    /**
     * Merges a plaintext notebook JSON file into the working tree.
     *
     * <p>The import is atomic: it is merged into a copy first, so an invalid or
     * unreadable file leaves the current notebook untouched even halfway
     * through.</p>
     */
    private void doImport(String file) throws IOException, NodeTreeException {
        Path source = Path.of(file);
        if (!Files.isRegularFile(source)) {
            out.println("no such file: " + source.toAbsolutePath());
            return;
        }
        byte[] plain = Files.readAllBytes(source);
        NodeTree imported;
        try {
            imported = NodeTree.fromJson(new String(plain, StandardCharsets.UTF_8));
        } catch (NodeTreeException e) {
            throw new NodeTreeException("cannot import " + source.toAbsolutePath() + ": " + e.getMessage());
        } finally {
            Vault.wipe(plain);
        }
        NodeTree candidate = tree.copy();
        MergeStats stats = candidate.merge(imported);
        if (!stats.changed()) {
            out.println("import: nothing changed, the notebook already contains everything.");
            return;
        }
        tree = candidate;
        out.println("imported " + source.toAbsolutePath());
        out.println("  " + stats.describe());
        out.println("[dirty] use .save to write the merged notebook.");
    }

    private void doDiscard() throws IOException, NodeTreeException, VaultException {
        if (!tree.isDirty()) {
            out.println("no unsaved changes.");
            return;
        }
        out.print("discard all unsaved changes? [y/N] ");
        if (!isYes(readLine())) {
            out.println("cancelled.");
            return;
        }
        char[] password = readPassword("password: ");
        if (password == null) {
            out.println("cancelled.");
            return;
        }
        try {
            byte[] plain = Vault.decryptFromFile(vaultPath, password);
            try {
                tree = NodeTree.fromJson(new String(plain, StandardCharsets.UTF_8));
            } finally {
                Vault.wipe(plain);
            }
            out.println("reloaded from disk.");
        } finally {
            Vault.wipe(password);
        }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private void list(boolean longFormat) {
        list(longFormat, true);
    }

    private void list(boolean longFormat, boolean showHeader) {
        List<String> children = tree.ls();
        List<String> keys = tree.keys();
        if (showHeader) {
            out.println(tree.pwd() + "   ts=" + fmtTs(tree.ts()) + (tree.isDirty() ? "   [dirty]" : ""));
        }
        if (children.isEmpty() && keys.isEmpty()) {
            out.println("  (empty)");
            return;
        }
        for (String c : children) {
            ObjectNode child = (ObjectNode) tree.current().get(c);
            long ts = child.path(NodeTree.TS).asLong();
            out.println("  [dir ] " + c + "/" + (longFormat ? "   ts=" + fmtTs(ts) : ""));
        }
        for (String k : keys) {
            JsonNode v = tree.current().get(k);
            String preview = longFormat ? v.toPrettyString() : preview(v);
            out.println("  [val ] " + k + " = " + preview);
        }
    }

    private static String preview(JsonNode v) {
        String s = v.isTextual() ? v.asText() : v.toString();
        if (s.length() > 60) {
            s = s.substring(0, 57) + "...";
        }
        return s.replace("\n", "\\n");
    }

    /** Formats an epoch-millis timestamp in the machine's local time zone. */
    private static String fmtTs(long millis) {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.systemDefault());
        return fmt.format(java.time.Instant.ofEpochMilli(millis)) + " "
                + java.time.ZoneId.systemDefault().getRules()
                        .getOffset(java.time.Instant.ofEpochMilli(millis));
    }

    private void help() {
        out.println("""
                navigation
                  .cd <path|name>     change node and list its contents
                                      (fuzzy names allowed, / .. . supported)
                  .pwd                print current path
                  .ls                 list children and values of current node
                  .ll                 same as .ls with local timestamps and full values
                  .ts                 show current node timestamp
                editing
                  .get <key>          read a value
                  .set <key> <value>  set a value (numbers, true/false, JSON are auto-detected)
                  .del <key>          delete a value
                  .mkdir <name>       create a child node
                  .rm <name> [--force] delete a child node and everything below it
                persistence
                  .save               encrypt and write the vault file (asks for the
                                      password twice and verifies it before writing)
                  .export <file>      export DECRYPTED json (asks for confirmation)
                  .import <file>      merge a plaintext json notebook into this one
                                      (newer nodes win; nothing changes if the
                                      file cannot be read)
                  .discard            drop unsaved changes and reload from disk
                other
                  .pass <length>      generate a random password (letters, digits, symbols)
                  .help   .exit

                tip: quote values with spaces, e.g.  .set title "my long title"
                """);
    }

    // ---------------------------------------------------------------------
    // Input helpers
    // ---------------------------------------------------------------------

    private String prompt() {
        return tree == null ? "?" : tree.pwd();
    }

    private String readLine() throws IOException {
        if (console != null) {
            String s = console.readLine();
            return s;
        }
        return stdin.readLine();
    }

    private char[] readPassword(String prompt) throws IOException {
        if (console != null) {
            char[] c = console.readPassword(prompt);
            return c;
        }
        out.print(prompt);
        out.flush();
        String line = stdin.readLine();
        return line == null ? null : line.toCharArray();
    }

    private static boolean isYes(String s) {
        return s != null && (s.strip().equalsIgnoreCase("y") || s.strip().equalsIgnoreCase("yes"));
    }

    private static void requireArgs(String cmd, String[] args, int n) throws NodeTreeException {
        if (args.length < n) {
            throw new NodeTreeException("usage error for " + cmd + " (see .help)");
        }
    }

    /** Splits on whitespace, honouring double quotes. */
    static String[] tokenize(String line) {
        List<String> tokens = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        boolean hasToken = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                hasToken = true;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (hasToken) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                    hasToken = false;
                }
            } else {
                cur.append(c);
                hasToken = true;
            }
        }
        if (hasToken) {
            tokens.add(cur.toString());
        }
        return tokens.toArray(new String[0]);
    }

    /** Interprets a raw CLI value as JSON when possible, else as a string. */
    static JsonNode parseValue(String raw) {
        String t = raw.strip();
        if (t.equalsIgnoreCase("true")) {
            return JsonNodeFactory.instance.booleanNode(true);
        }
        if (t.equalsIgnoreCase("false")) {
            return JsonNodeFactory.instance.booleanNode(false);
        }
        if (t.equalsIgnoreCase("null")) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                JsonNode parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(t);
                if (parsed != null && parsed.isObject()) {
                    return JsonNodeFactory.instance.textNode(raw);
                }
                return parsed;
            } catch (Exception ignored) {
                // fall through to string
            }
        }
        try {
            if (t.matches("-?\\d+")) {
                return JsonNodeFactory.instance.numberNode(Long.parseLong(t));
            }
            if (t.matches("-?\\d+\\.\\d+([eE][-+]?\\d+)?")) {
                return JsonNodeFactory.instance.numberNode(Double.parseDouble(t));
            }
        } catch (NumberFormatException ignored) {
            // fall through to string
        }
        return JsonNodeFactory.instance.textNode(raw);
    }
}
