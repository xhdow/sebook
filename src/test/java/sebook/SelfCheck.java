package sebook;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import sebook.crypto.Vault;
import sebook.crypto.VaultException;
import sebook.tree.NodeTree;
import sebook.tree.NodeTreeException;

/**
 * Minimal self-check without a test framework. Run with:
 * {@code java -cp "out;lib/*" sebook.SelfCheck}
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
        treeValueNodes();
        mergeAddsNewPaths();
        mergeSiblingsIndependently();
        mergeValueNewerWins();
        mergeValueOlderLoses();
        mergeIntermediateNodeUnionsSubtree();
        mergeNeverDeletes();
        mergeFutureTsClamped();
        mergeKeptTreeIsNoOp();
        mergeIsAtomicAndPlain();
        passwordGenerator();
        vaultInspect();
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
        check("file is plausible vault", Vault.isVaultFile(enc));
        byte[] dec = Vault.decrypt(enc, pwd);
        check("round trip", Arrays.equals(plain, dec));
        byte[] enc2 = Vault.encrypt(plain, pwd, 8192, 1, 1);
        check("salt/nonce randomise output", !Arrays.equals(enc, enc2));

        // padding makes the file length vary
        boolean sawVariation = false;
        int baseline = -1;
        for (int i = 0; i < 40; i++) {
            byte[] probe = Vault.encrypt(plain, pwd, 8192, 1, 1);
            if (baseline == -1) {
                baseline = probe.length;
            } else if (probe.length != baseline) {
                sawVariation = true;
                break;
            }
        }
        check("padding varies file length", sawVariation);

        // padding bytes are noise, not a fixed prefix
        byte[][] samples = new byte[8][];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = Vault.encrypt(plain, pwd, 8192, 1, 1);
        }
        boolean sameFirstByte = true;
        for (byte[] s : samples) {
            if (s[0] != samples[0][0]) {
                sameFirstByte = false;
            }
        }
        check("first byte is not constant", !sameFirstByte);
    }

    /** Offset of the Argon2 memory-cost field for a given file. */
    private static int memoryFieldOffset(byte[] file) {
        int padLen = file[0] & 0xff;
        return 1 + padLen + 1 + 1; // padLen byte + padding + version + kdf
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

        int saltOff = memoryFieldOffset(enc) + 4 + 4 + 1 + 1 + 1; // mem, iter, par, keyLen, saltLen
        byte[] mod2 = enc.clone();
        mod2[saltOff] ^= 0x01; // inside the salt, part of the AAD
        checkThrows("tampered header rejected", VaultException.class,
                () -> Vault.decrypt(mod2, pwd));

        byte[] mod3 = enc.clone();
        int memOff = memoryFieldOffset(enc);
        // memory cost bytes set absurdly high
        mod3[memOff] = (byte) 0x7F;
        mod3[memOff + 1] = (byte) 0xFF;
        mod3[memOff + 2] = (byte) 0xFF;
        mod3[memOff + 3] = (byte) 0xFF;
        checkThrows("absurd KDF params rejected", VaultException.class,
                () -> Vault.decrypt(mod3, pwd));

        byte[] mod4 = enc.clone();
        mod4[0] = (byte) 0xFE; // bogus padLen, points past the real header
        checkThrows("tampered pad length rejected", VaultException.class,
                () -> Vault.decrypt(mod4, pwd));
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
        check("keys are sorted", t.keys().equals(java.util.List.of("count", "done", "tags", "title")),
                "got=" + t.keys());
        check("dirty", t.isDirty());
        checkThrows("mkdir duplicate", NodeTreeException.class, () -> t.mkdir("deep"));
        checkThrows("set over directory", NodeTreeException.class,
                () -> t.set("deep", JsonNodeFactory.instance.textNode("x")));
        check("value node shape", t.entry("title").path(NodeTree.NT).asText().equals(NodeTree.NT_VALUE)
                && t.entry("title").path(NodeTree.VAL).asText().equals("proj"), t.current().toPrettyString());
        check("directory node shape", t.entry("deep").path(NodeTree.NT).asText().equals(NodeTree.NT_DIR));
        t.cd("..");
        check("cd ..", t.pwd().equals("/"));
        t.rmdir("work");
        check("rmdir", t.ls().isEmpty());
    }

    private static void treeValueNodes() throws Exception {
        NodeTree t = new NodeTree();
        t.mkdir("a");
        t.cd("a");
        t.set("user", JsonNodeFactory.instance.textNode("tom"));
        t.set("password", JsonNodeFactory.instance.textNode("123"));
        long parentTs = t.ts();
        long userTs = t.entry("user").path(NodeTree.TS).asLong();
        check("value carries its own ts", userTs > 0 && userTs == parentTs);

        Thread.sleep(2);
        t.set("user", JsonNodeFactory.instance.textNode("tom2"));
        long newUserTs = t.entry("user").path(NodeTree.TS).asLong();
        long pwdTs = t.entry("password").path(NodeTree.TS).asLong();
        check("editing a value updates its own ts", newUserTs > userTs);
        check("editing a value leaves siblings' ts alone", pwdTs == userTs);

        check("get returns the payload", "tom2".equals(t.get("user").asText()));
        checkThrows("value is not a directory", NodeTreeException.class, () -> t.cd("user"));
        check("cd to value rejected, cwd unchanged", t.pwd().equals("/a"));

        // the model still allows any JSON payload inside "val"
        t.set("profile", new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"x\":1}"));
        check("object payload allowed", t.get("profile").isObject(), t.current().toPrettyString());

        check("clear removes a value", t.clear("profile"));
        check("clear ignores missing keys", !t.clear("profile"));

        // .set stores text verbatim: no number/boolean re-interpretation
        NodeTree text = new NodeTree();
        text.set("pwd", JsonNodeFactory.instance.textNode("000888"));
        check("leading zeros preserved", "000888".equals(text.get("pwd").asText()));
        text.set("pwd", JsonNodeFactory.instance.textNode("888"));
        check("000888 and 888 are different values", "888".equals(text.get("pwd").asText()));
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
        check("fuzzy none", t.match("zzzz").kind() == sebook.tree.MatchKind.NONE);
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
                () -> NodeTree.fromJson("{\"ts\":1,\"child\":{\"nt\":\"d\"}}"));
        NodeTree rootNoTs = NodeTree.fromJson("{\"a\":{\"ts\":1,\"nt\":\"d\"}}");
        check("root ts auto-filled", rootNoTs.ts() > 0);
        checkThrows("raw value in directory rejected", NodeTreeException.class,
                () -> NodeTree.fromJson("{\"ts\":1,\"a\":\"x\"}"));
        checkThrows("value node without payload rejected", NodeTreeException.class,
                () -> NodeTree.fromJson("{\"ts\":1,\"a\":{\"ts\":1,\"nt\":\"v\"}}"));
        checkThrows("directory with a payload rejected", NodeTreeException.class,
                () -> NodeTree.fromJson("{\"ts\":1,\"a\":{\"ts\":1,\"nt\":\"d\",\"val\":1}}"));
        NodeTree ok = NodeTree.fromJson(
                "{\"ts\":1,\"a\":{\"ts\":2,\"nt\":\"v\",\"val\":\"x\"},\"b\":{\"ts\":3,\"nt\":\"d\"}}");
        check("value payload parsed", "x".equals(ok.get("a").asText()));
    }

    private static void treeReservedKey() throws Exception {
        NodeTree t = new NodeTree();
        for (String key : new String[] {"ts", "nt", "val"}) {
            checkThrows("set " + key + " forbidden", NodeTreeException.class,
                    () -> t.set(key, JsonNodeFactory.instance.textNode("x")));
            checkThrows("mkdir " + key + " forbidden", NodeTreeException.class, () -> t.mkdir(key));
        }
        checkThrows("blank key forbidden", NodeTreeException.class,
                () -> t.set("  ", JsonNodeFactory.instance.textNode("x")));
    }

    // ---------------- merge / import ----------------

    private static NodeTree parse(String json) throws NodeTreeException {
        return NodeTree.fromJson(json);
    }

    /** A value node as it appears in JSON: {"ts":..,"nt":"v","val":..}. */
    private static String v(long ts, String payload) {
        return "{\"ts\":" + ts + ",\"nt\":\"v\",\"val\":" + payload + "}";
    }

    /** The payload of the value at {@code path}. */
    private static String val(NodeTree t, String path) throws NodeTreeException {
        return t.nodeAt(path).path(NodeTree.VAL).asText();
    }

    private static long tsOf(NodeTree t, String path) throws NodeTreeException {
        return t.nodeAt(path).path(NodeTree.TS).asLong();
    }

    private static void mergeAddsNewPaths() throws Exception {
        NodeTree c = parse("{\"ts\":1000,\"work\":{\"ts\":1000,\"title\":" + v(1000, "\"old\"") + "}}");
        NodeTree s = parse("{\"ts\":2000,\"work\":{\"ts\":2000,\"title\":" + v(1000, "\"old\"") + ","
                + "\"deep\":{\"ts\":1500,\"note\":" + v(1500, "\"hello\"") + "}},"
                + "\"home\":{\"ts\":2000,\"x\":" + v(2000, "1") + "}}");
        sebook.tree.MergeStats st = c.merge(s);
        check("merge adds missing directory", c.nodeAt("/home") != null);
        check("merge adds missing value", "hello".equals(val(c, "/work/deep/note")));
        check("merge adds nested directory", c.nodeAt("/work/deep") != null);
        check("merge counts added directories", st.added() == 2, st.describe());
        check("merge counts added values", st.valuesAdded() == 2, st.describe());
        check("merge marks dirty", c.isDirty());
    }

    /**
     * The case the value-node model exists for: C edited "user" and S edited
     * "password" under the same parent. Both edits must survive.
     */
    private static void mergeSiblingsIndependently() throws Exception {
        NodeTree c = parse("{\"ts\":5000,\"a\":{\"ts\":5000,\"b\":{\"ts\":5000,"
                + "\"user\":" + v(1000, "\"tom-C\"") + ","
                + "\"password\":" + v(1000, "\"pw-old\"") + "}}}");
        NodeTree s = parse("{\"ts\":9000,\"a\":{\"ts\":9000,\"b\":{\"ts\":9000,"
                + "\"user\":" + v(1000, "\"tom-old\"") + ","
                + "\"password\":" + v(8000, "\"pw-S\"") + "}}}");
        c.merge(s);
        check("edited user is kept", "tom-C".equals(val(c, "/a/b/user")), c.toJson());
        check("edited password is taken", "pw-S".equals(val(c, "/a/b/password")), c.toJson());
        check("user keeps its own newer ts", tsOf(c, "/a/b/user") == 1000);
        check("password carries the newer ts", tsOf(c, "/a/b/password") == 8000);
    }

    private static void mergeValueNewerWins() throws Exception {
        NodeTree c = parse("{\"ts\":1000,\"pwd\":" + v(900, "\"old\"") + "}");
        NodeTree s = parse("{\"ts\":1200,\"pwd\":" + v(1100, "\"new\"") + "}");
        c.merge(s);
        check("newer value wins", "new".equals(val(c, "/pwd")));
        check("winning value gets min(max,now)", tsOf(c, "/pwd") == 1100, "ts=" + tsOf(c, "/pwd"));
        check("parent ts becomes max(S,C)", tsOf(c, "/") >= 1200);
    }

    private static void mergeValueOlderLoses() throws Exception {
        NodeTree c = parse("{\"ts\":2000,\"pwd\":" + v(2000, "\"current\"") + "}");
        NodeTree s = parse("{\"ts\":3000,\"pwd\":" + v(1000, "\"stale\"") + "}");
        c.merge(s);
        check("older value loses even when S's parent is newer", "current".equals(val(c, "/pwd")));
        check("loser keeps its ts", tsOf(c, "/pwd") == 2000);
        check("losing merge stays clean", !c.isDirty(), c.toJson());
    }

    private static void mergeIntermediateNodeUnionsSubtree() throws Exception {
        // S is newer at "work" but older at "work/deep"; deep must not be wiped,
        // and the value only C knows below it must survive.
        NodeTree c = parse("{\"ts\":5000,\"work\":{\"ts\":5000,"
                + "\"deep\":{\"ts\":400,\"mine\":" + v(400, "\"keep-me\"") + "}},"
                + "\"onlyInC\":{\"ts\":5000}}");
        NodeTree s = parse("{\"ts\":4000,\"work\":{\"ts\":9000,"
                + "\"deep\":{\"ts\":100,\"theirs\":" + v(100, "\"added\"") + "}},"
                + "\"onlyInS\":{\"ts\":4000}}");
        c.merge(s);
        check("newer intermediate keeps older descendant value",
                "keep-me".equals(val(c, "/work/deep/mine")));
        check("newer intermediate adds newer descendant value",
                "added".equals(val(c, "/work/deep/theirs")));
        check("sibling missing in imported tree kept", c.nodeAt("/onlyInC") != null);
        check("node only S knows added", c.nodeAt("/onlyInS") != null);
        check("changed intermediate ts = min(max(S,C),now)",
                tsOf(c, "/work") <= System.currentTimeMillis() && tsOf(c, "/work") >= 5000);
    }

    private static void mergeNeverDeletes() throws Exception {
        // Two independently used notebooks: different branches under the same
        // parent must all survive, no matter which side is newer.
        NodeTree c = parse("{\"ts\":1000,\"google\":{\"ts\":1000,"
                + "\"projectA\":{\"ts\":1000,\"note\":" + v(1000, "\"mine\"") + "}}}");
        NodeTree s = parse("{\"ts\":9000,\"google\":{\"ts\":9000,"
                + "\"projectB\":{\"ts\":9000,\"note\":" + v(9000, "\"theirs\"") + "}}}");
        c.merge(s);
        check("branch only C knows survives a much newer S", c.nodeAt("/google/projectA") != null);
        check("branch only C knows keeps its value", "mine".equals(val(c, "/google/projectA/note")));
        check("branch only S knows is added", "theirs".equals(val(c, "/google/projectB/note")));

        // A tree that S knows nothing about at all is still untouched.
        NodeTree c2 = parse("{\"ts\":2500,\"local\":{\"ts\":2500,\"x\":" + v(2500, "1") + "},"
                + "\"v\":" + v(2500, "\"cur\"") + "}");
        NodeTree s2 = parse("{\"ts\":3000}");
        c2.merge(s2);
        check("directory missing in newer S kept", c2.nodeAt("/local") != null);
        check("value missing in newer S kept", "cur".equals(val(c2, "/v")));
        check("nothing to change leaves tree clean", !c2.isDirty(), c2.toJson());
    }

    private static void mergeFutureTsClamped() throws Exception {
        long before = System.currentTimeMillis();
        NodeTree c = parse("{\"ts\":1000,\"a\":" + v(1000, "\"x\"") + "}");
        NodeTree s = parse("{\"ts\":999999999999999999,\"a\":" + v(999999999999999999L, "\"y\"") + "}");
        c.merge(s);
        long after = System.currentTimeMillis();
        check("future value ts clamped to now", tsOf(c, "/a") >= before && tsOf(c, "/a") <= after,
                "ts=" + tsOf(c, "/a"));
        check("future value still wins", "y".equals(val(c, "/a")));
    }

    private static void mergeKeptTreeIsNoOp() throws Exception {
        String body = "{\"ts\":1000,\"work\":{\"ts\":1000,\"title\":" + v(1000, "\"t\"") + "}}";
        NodeTree c = parse(body);
        NodeTree s = parse(body);
        String beforeJson = c.toJson();
        sebook.tree.MergeStats st = c.merge(s);
        check("identical merge keeps json", c.toJson().equals(beforeJson));
        check("identical merge not dirty", !c.isDirty());
        check("identical merge counts as kept", st.updated() == 0 && st.added() == 0, st.describe());

        NodeTree older = parse("{\"ts\":500,\"work\":{\"ts\":500,\"title\":" + v(500, "\"t\"") + "}}");
        c.merge(older);
        check("older twin keeps json", c.toJson().equals(beforeJson));
        check("older twin not dirty", !c.isDirty());
    }

    private static void mergeIsAtomicAndPlain() throws Exception {
        NodeTree c = parse("{\"ts\":5000,\"keep\":" + v(5000, "\"x\"") + "}");
        NodeTree s = parse("{\"ts\":3000,\"fresh\":{\"ts\":3000,\"y\":" + v(3000, "1") + "}}");
        String sBefore = s.toJson();
        c.merge(s);
        check("imported tree not modified", s.toJson().equals(sBefore));
        check("merge resets cwd to root", c.pwd().equals("/"));
        check("merge result readable", "1".equals(val(c, "/fresh/y")));
        check("node whose version is not newer survives", "x".equals(val(c, "/keep")));

        // a copied tree is independent of the source and starts clean
        NodeTree copy = c.copy();
        c.set("keep", JsonNodeFactory.instance.textNode("changed"));
        check("copy is independent", "x".equals(val(copy, "/keep")), copy.toJson());
        check("copy starts clean", !copy.isDirty());
        checkThrows("malformed import rejected", NodeTreeException.class,
                () -> NodeTree.fromJson("{\"ts\":1,\"c\":{\"y\":1}}"));
    }

    private static void passwordGenerator() {
        for (int len : new int[] {4, 8, 16, 20, 64}) {
            String pw = sebook.cli.SebookCli.generatePassword(len);
            boolean size = pw.length() == len;
            boolean categories = pw.chars().anyMatch(Character::isLowerCase)
                    && pw.chars().anyMatch(Character::isUpperCase)
                    && pw.chars().anyMatch(Character::isDigit)
                    && pw.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
            check("password len=" + len + " has all categories", size && categories, "got=" + pw);
        }
        boolean distinct = true;
        for (int i = 0; i < 200; i++) {
            if (sebook.cli.SebookCli.generatePassword(24).equals(
                    sebook.cli.SebookCli.generatePassword(24))) {
                distinct = false;
                break;
            }
        }
        check("passwords are not constant", distinct);
    }

    private static void vaultInspect() throws Exception {
        char[] pwd = "inspect-pw".toCharArray();
        byte[] plain = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] enc = Vault.encrypt(plain, pwd, 8192, 2, 1);
        Vault.HeaderInfo h = Vault.inspect(enc);
        check("inspect version", h.version() == 1);
        check("inspect kdf name", h.kdfName().equals("Argon2id"), h.kdfName());
        check("inspect memory", h.memoryKib() == 8192);
        check("inspect iterations", h.iterations() == 2);
        check("inspect key length", h.keyLen() == 32);
        check("inspect salt length", h.saltLen() == 16);
        check("inspect nonce length", h.nonceLen() == 12);
        check("inspect pad length in range", h.padLen() >= 0 && h.padLen() <= Vault.MAX_PAD_LEN);
        check("inspect salt matches file",
                Arrays.equals(h.salt(), Arrays.copyOfRange(enc, 1 + h.padLen() + 13, 1 + h.padLen() + 29)));
        long expectedCt = enc.length - (1L + h.padLen() + 1 + 1 + 4 + 4 + 1 + 1 + 1 + 16 + 12);
        check("inspect ciphertext length", h.ciphertextLen() == expectedCt,
                h.ciphertextLen() + " vs " + expectedCt);
        check("inspect total length", h.totalLen() == enc.length);
        checkThrows("inspect rejects short data", VaultException.class,
                () -> Vault.inspect(new byte[10]));
    }
}
