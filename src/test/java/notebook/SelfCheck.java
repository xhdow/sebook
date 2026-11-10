package notebook;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import notebook.crypto.Vault;
import notebook.crypto.VaultException;
import notebook.tree.NodeTree;
import notebook.tree.NodeTreeException;

/**
 * Minimal self-check without a test framework. Run with:
 * {@code java -cp "out;lib/*" notebook.SelfCheck}
 */
public final class SelfCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        cryptoRoundTrip();
        cryptoWrongPassword();
        cryptoTamper();
        treeBasics();
        treePropagation();
        treeNoOpSetKeepsTs();
        treeFuzzy();
        treePathNavigation();
        treeJsonRoundTrip();
        treeReservedKey();
        passwordGenerator();
        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------- helpers ----------------

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS " + name);
        } else {
            failed++;
            System.out.println("FAIL " + name + " " + detail);
        }
    }

    private static void checkThrows(String name, Class<? extends Exception> type, ThrowingRunnable r) {
        try {
            r.run();
            check(name + " (expected " + type.getSimpleName() + ")", false);
        } catch (Exception e) {
            check(name + " -> " + e.getClass().getSimpleName(), type.isInstance(e));
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---------------- crypto ----------------

    private static void cryptoRoundTrip() throws Exception {
        char[] pwd = "correct horse battery staple".toCharArray();
        byte[] plain = "{\"hello\":\"世界\"}".getBytes(StandardCharsets.UTF_8);
        // small params for speed in this check
        byte[] enc = Vault.encrypt(plain, pwd, 8192, 1, 1);
        check("ciphertext differs from plaintext", !Arrays.equals(enc, plain));
        check("magic detected", Vault.isVaultFile(enc));
        byte[] dec = Vault.decrypt(enc, pwd);
        check("round trip", Arrays.equals(plain, dec));
        byte[] enc2 = Vault.encrypt(plain, pwd, 8192, 1, 1);
        check("salt/nonce randomise output", !Arrays.equals(enc, enc2));
    }

    private static void cryptoWrongPassword() throws Exception {
        byte[] enc = Vault.encrypt("secret".getBytes(StandardCharsets.UTF_8),
                "pw".toCharArray(), 8192, 1, 1);
        checkThrows("wrong password rejected", VaultException.class,
                () -> Vault.decrypt(enc, "nope".toCharArray()));
    }

    private static void cryptoTamper() throws Exception {
        char[] pwd = "pw".toCharArray();
        byte[] enc = Vault.encrypt("secret".getBytes(StandardCharsets.UTF_8), pwd, 8192, 1, 1);
        byte[] mod = enc.clone();
        mod[mod.length - 1] ^= 0x01;
        checkThrows("tampered ciphertext rejected", VaultException.class,
                () -> Vault.decrypt(mod, pwd));
        byte[] mod2 = enc.clone();
        mod2[27] ^= 0x01; // inside the salt, part of the AAD
        checkThrows("tampered header rejected", VaultException.class,
                () -> Vault.decrypt(mod2, pwd));
        byte[] mod3 = enc.clone();
        // memory cost bytes (offset 6..9) set absurdly high
        mod3[6] = (byte) 0x7F;
        mod3[7] = (byte) 0xFF;
        mod3[8] = (byte) 0xFF;
        mod3[9] = (byte) 0xFF;
        checkThrows("absurd KDF params rejected", VaultException.class,
                () -> Vault.decrypt(mod3, pwd));
    }

    // ---------------- tree ----------------

    private static void treeBasics() throws Exception {
        NodeTree t = new NodeTree();
        check("root pwd", t.pwd().equals("/"));
        t.mkdir("work");
        t.cd("work");
        t.mkdir("deep");
        t.set("title", JsonNodeFactory.instance.textNode("proj"));
        t.set("count", JsonNodeFactory.instance.numberNode(3));
        t.set("done", JsonNodeFactory.instance.booleanNode(true));
        t.set("tags", JsonNodeFactory.instance.arrayNode().add("a").add("b"));
        check("pwd after cd", t.pwd().equals("/work"));
        check("ls has deep", t.ls().equals(java.util.List.of("deep")));
        check("keys", t.keys().equals(java.util.List.of("title", "count", "done", "tags")),
                "got=" + t.keys());
        check("dirty", t.isDirty());
        checkThrows("mkdir duplicate", NodeTreeException.class, () -> t.mkdir("deep"));
        checkThrows("set over child", NodeTreeException.class,
                () -> t.set("deep", JsonNodeFactory.instance.textNode("x")));
        t.cd("..");
        check("cd ..", t.pwd().equals("/"));
        t.rmdir("work");
        check("rmdir", t.ls().isEmpty());
    }

    private static void treePropagation() throws Exception {
        NodeTree t = new NodeTree();
        t.mkdir("a");
        t.cd("a");
        t.mkdir("b");
        t.cd("b");
        t.mkdir("c");
        t.cd("c");
        long beforeRoot = t.flatten().get("/").path("ts").asLong();
        long beforeA = t.flatten().get("/a").path("ts").asLong();
        Thread.sleep(2);
        t.set("x", JsonNodeFactory.instance.textNode("v"));
        long want = System.currentTimeMillis();
        check("root ts updated", t.flatten().get("/").path("ts").asLong() > beforeRoot);
        check("ancestor a ts updated", t.flatten().get("/a").path("ts").asLong() > beforeA);
        check("root ts == leaf change ts (approx)",
                Math.abs(t.flatten().get("/").path("ts").asLong() - want) < 5);
    }

    private static void treeNoOpSetKeepsTs() throws Exception {
        NodeTree t = new NodeTree();
        t.set("k", JsonNodeFactory.instance.textNode("v"));
        long ts = t.ts();
        t.set("k", JsonNodeFactory.instance.textNode("v"));
        check("no-op set keeps ts", t.ts() == ts);
    }

    private static void treeFuzzy() throws Exception {
        NodeTree t = new NodeTree();
        t.mkdir("work");
        t.mkdir("worknotes");
        t.mkdir("home");
        t.cd("wrk");
        check("fuzzy unique", t.pwd().equals("/work"));
        t.cd("/");
        check("exact wins", t.match("work").matched().equals("work"));
        checkThrows("ambiguous", NodeTreeException.class, () -> t.cd("wor"));
        checkThrows("no match", NodeTreeException.class, () -> t.cd("zzzz"));
        check("fuzzy none", t.match("zzzz").kind() == notebook.tree.MatchKind.NONE);
        t.cd("HOME".toLowerCase());
        check("case-insensitive exact", t.pwd().equals("/home"));
    }

    private static void treePathNavigation() throws Exception {
        NodeTree t = new NodeTree();
        t.mkdir("a");
        t.cd("a");
        t.mkdir("b");
        t.cd("/a/b");
        check("absolute cd", t.pwd().equals("/a/b"));
        t.cd("..");
        check("parent", t.pwd().equals("/a"));
        t.cd(".");
        check("dot", t.pwd().equals("/a"));
        check("resolve abs", String.join("/", t.resolve("/a/b")).equals("a/b"));
    }

    private static void treeJsonRoundTrip() throws Exception {
        NodeTree t = new NodeTree();
        t.mkdir("work");
        t.cd("work");
        t.set("title", JsonNodeFactory.instance.textNode("hi"));
        String json = t.toJson();
        NodeTree t2 = NodeTree.fromJson(json);
        check("json round trip same json", t2.toJson().equals(json));
        checkThrows("bad top-level", NodeTreeException.class, () -> NodeTree.fromJson("[1,2]"));
        checkThrows("bad json", NodeTreeException.class, () -> NodeTree.fromJson("{oops"));
        checkThrows("missing ts in child", NodeTreeException.class,
                () -> NodeTree.fromJson("{\"ts\":1,\"child\":{\"x\":1}}"));
        NodeTree rootNoTs = NodeTree.fromJson("{\"a\":{\"ts\":1}}");
        check("root ts auto-filled", rootNoTs.ts() > 0);
    }

    private static void treeReservedKey() throws Exception {
        NodeTree t = new NodeTree();
        checkThrows("set ts forbidden", NodeTreeException.class,
                () -> t.set("ts", JsonNodeFactory.instance.numberNode(1)));
        checkThrows("mkdir ts forbidden", NodeTreeException.class, () -> t.mkdir("ts"));
        checkThrows("blank key forbidden", NodeTreeException.class,
                () -> t.set("  ", JsonNodeFactory.instance.textNode("x")));
    }

    private static void passwordGenerator() {
        for (int len : new int[] {4, 8, 16, 20, 64}) {
            String pw = notebook.cli.NotebookCli.generatePassword(len);
            boolean size = pw.length() == len;
            boolean categories = pw.chars().anyMatch(Character::isLowerCase)
                    && pw.chars().anyMatch(Character::isUpperCase)
                    && pw.chars().anyMatch(Character::isDigit)
                    && pw.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
            check("password len=" + len + " has all categories", size && categories, "got=" + pw);
        }
        boolean distinct = true;
        for (int i = 0; i < 200; i++) {
            if (notebook.cli.NotebookCli.generatePassword(24).equals(
                    notebook.cli.NotebookCli.generatePassword(24))) {
                distinct = false;
                break;
            }
        }
        check("passwords are not constant", distinct);
    }
}
