package sebook.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A navigable, versioned JSON node tree.
 *
 * <h2>Model</h2>
 * <p>Every entry is a JSON object carrying its own {@value #TS}. Two kinds of
 * nodes exist, distinguished by the {@value #NT} type marker:</p>
 * <ul>
 *   <li><b>Directory</b> ({@code nt} is absent or {@code "d"}): its other keys
 *       name its children.</li>
 *   <li><b>Value</b> ({@code nt} is {@code "v"}): its payload lives in
 *       {@code val}, which may be any JSON value (string, number, boolean,
 *       array, null).</li>
 * </ul>
 *
 * <pre>
 * {
 *   "ts": 1731123456789,
 *   "nt": "d",
 *   "title": { "ts": 1731123456790, "nt": "v", "val": "my notes" },
 *   "work": {
 *     "ts": 1731123456791,
 *     "nt": "d",
 *     "owner": { "ts": 1731123456792, "nt": "v", "val": "tom" }
 *   }
 * }
 * </pre>
 *
 * <p>Because each value carries its own timestamp, two values under the same
 * parent can be edited independently and still be merged without ambiguity.</p>
 *
 * <h2>Timestamps</h2>
 * <p>Every node carries a numeric {@value #TS} field holding the epoch
 * millisecond of its last modification. Modifying anything inside a node
 * updates that node's {@code ts} and propagates upward through every ancestor,
 * including the root. Writing a value equal to the current one is a no
 * operation and does not touch {@code ts}. Read-only navigation never touches
 * {@code ts}.</p>
 *
 * <p>The keys {@value #TS}, {@value #NT} and {@value #VAL} are reserved and
 * cannot be created, renamed or deleted as child names through this API.</p>
 *
 * <h2>Independence</h2>
 * <p>This class is pure in-memory logic: it has no knowledge of files,
 * encryption or the command line, so it can be embedded in other programs.
 * Serialize with {@link #toJson()} and restore with {@link #fromJson(String)}.</p>
 *
 * <p>Not thread safe. The caller must serialize access.</p>
 */
public final class NodeTree {

    /** Reserved key holding the last-modification timestamp (epoch millis). */
    public static final String TS = "ts";
    /** Node type discriminator: {@value #NT_DIR} for directories, {@value #NT_VALUE} for values. */
    public static final String NT = "nt";
    /** Value of {@link #NT} marking a value node. */
    public static final String NT_VALUE = "v";
    /** Value of {@link #NT} marking a directory node. */
    public static final String NT_DIR = "d";
    /** Payload key of a value node. */
    public static final String VAL = "val";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .setSerializationInclusion(JsonInclude.Include.ALWAYS);

    private final ObjectNode root;
    private final List<String> cwd = new ArrayList<>(); // path from root, empty == root
    private boolean dirty;

    /** Creates a new, empty tree containing only the root node. */
    public NodeTree() {
        this.root = JsonNodeFactory.instance.objectNode();
        root.put(TS, System.currentTimeMillis());
        this.dirty = false;
    }

    private NodeTree(ObjectNode root) {
        this.root = root;
        this.dirty = false;
    }

    // ---------------------------------------------------------------------
    // (De)serialization
    // ---------------------------------------------------------------------

    /**
     * Parses a tree from plain JSON text.
     *
     * @throws NodeTreeException if the JSON is invalid, the top level is not an
     *                           object, any nested node lacks a numeric
     *                           {@code ts}, or a value node ({@code nt = "v"})
     *                           lacks its {@code val} payload
     */
    public static NodeTree fromJson(String json) throws NodeTreeException {
        Objects.requireNonNull(json, "json");
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new NodeTreeException("invalid JSON: " + e.getOriginalMessage());
        }
        if (parsed == null || !parsed.isObject()) {
            throw new NodeTreeException("the document root must be a JSON object");
        }
        ObjectNode root = (ObjectNode) parsed;
        long now = System.currentTimeMillis();
        normalize(root, "/", now, true);
        return new NodeTree(root);
    }

    /**
     * Checks the shape of a parsed tree: every node needs a numeric {@code ts},
     * value nodes need a {@code val} payload, and directories cannot hold any.
     * The root is always stamped with {@code now} when missing (it stands for
     * the tree itself), nested nodes are rejected when missing for strictness.
     */
    private static void normalize(ObjectNode node, String path, long now, boolean isRoot) throws NodeTreeException {
        JsonNode ts = node.get(TS);
        if (ts == null || !ts.isIntegralNumber()) {
            if (isRoot) {
                node.put(TS, now);
            } else {
                throw new NodeTreeException("node " + path + " is missing a numeric '" + TS + "' field");
            }
        }
        if (isValue(node)) {
            if (!node.has(VAL)) {
                throw new NodeTreeException("value node " + path + " is missing its '" + VAL + "' payload");
            }
            return;
        }
        if (node.has(VAL)) {
            throw new NodeTreeException("directory " + path + " must not hold a '" + VAL + "' payload"
                    + " (set '" + NT + "':'" + NT_VALUE + "' to make it a value node)");
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!isReserved(e.getKey())) {
                if (!e.getValue().isObject()) {
                    throw new NodeTreeException("directory " + path + " holds a raw value under '"
                            + e.getKey() + "'; wrap it as {\"" + TS + "\":..,\"" + NT + "\":\""
                            + NT_VALUE + "\",\"" + VAL + "\":..}");
                }
                normalize((ObjectNode) e.getValue(), path + e.getKey() + "/", now, false);
            }
        }
    }

    /**
     * A private, independent copy of this tree.
     *
     * <p>The copy starts at the root and starts out clean, even when this tree
     * has unsaved changes. Describes exactly the state that a fresh
     * {@link #fromJson(String)} of {@link #toJson()} would produce.</p>
     */
    public NodeTree copy() throws NodeTreeException {
        return fromJson(toJson());
    }

    /** Serializes the whole tree to pretty-printed JSON. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("serialization failed", e);
        }
    }

    // ---------------------------------------------------------------------
    // Node kinds
    // ---------------------------------------------------------------------

    /** True if {@code node} is a value node ({@code nt = "v"}); otherwise it is a directory. */
    public static boolean isValue(JsonNode node) {
        return node != null && node.isObject() && NT_VALUE.equals(node.path(NT).asText(null));
    }

    /** True if {@code node} is a directory ({@code nt} absent or {@code "d"}). */
    public static boolean isDirectory(JsonNode node) {
        return node != null && node.isObject() && !isValue(node);
    }

    /** True for {@value #TS}, {@value #NT} and {@value #VAL}, which are never child names. */
    static boolean isReserved(String key) {
        return key.equals(TS) || key.equals(NT) || key.equals(VAL);
    }

    /** The payload of a value node, or {@code null} when {@code node} is not a value. */
    public static JsonNode payload(JsonNode node) {
        return isValue(node) ? node.get(VAL) : null;
    }

    // ---------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------

    /** Absolute path of the current node: {@code "/"} for the root. */
    public String pwd() {
        if (cwd.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", cwd);
    }

    /** The current node object (live view; do not mutate directly). */
    public ObjectNode current() {
        ObjectNode n = root;
        for (String segment : cwd) {
            n = (ObjectNode) n.get(segment);
        }
        return n;
    }

    /**
     * Changes the working node.
     *
     * <p>Accepts absolute paths ({@code /a/b}), relative paths ({@code a/b}),
     * {@code .} and {@code ..}. Each segment is resolved with
     * {@link #match(String)} so fuzzy names work. Only directories can be
     * entered; a segment that resolves to a value node is rejected.</p>
     *
     * @throws NodeTreeException if the path cannot be resolved or names a value
     * @throws AmbiguousPathException if a segment matches several children
     */
    public void cd(String path) throws NodeTreeException {
        Objects.requireNonNull(path, "path");
        List<String> target = resolve(path);
        for (int i = 1; i <= target.size(); i++) {
            ObjectNode n = nodeAt(target.subList(0, i));
            if (isValue(n)) {
                throw new NodeTreeException("'" + pathOf(target.subList(0, i))
                        + "' is a value, not a directory");
            }
        }
        cwd.clear();
        cwd.addAll(target);
    }

    /**
     * Resolves a path to the list of child names it designates, without
     * changing the working node. Useful for {@code .open} previews.
     */
    public List<String> resolve(String path) throws NodeTreeException {
        List<String> stack = new ArrayList<>(cwd);
        String cleaned = path == null ? "" : path;
        if (cleaned.isBlank() || cleaned.equals(".")) {
            return stack;
        }
        if (cleaned.startsWith("/")) {
            stack.clear();
            cleaned = cleaned.substring(1);
        }
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        if (cleaned.isEmpty()) {
            return List.of();
        }
        for (String segment : cleaned.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            ObjectNode here = nodeAt(stack);
            SegmentLookup lookup = lookupSegment(here, segment);
            switch (lookup.kind) {
                case EXACT, FUZZY_UNIQUE -> stack.add(lookup.name);
                case FUZZY_AMBIGUOUS -> throw new AmbiguousPathException(
                        "ambiguous name '" + segment + "'", lookup.candidates);
                default -> throw new NodeTreeException("no such node: '" + segment + "' in " + pathOf(stack));
            }
        }
        return stack;
    }

    /** Lists the child directories of the current node, sorted by name. */
    public List<String> ls() {
        return directoriesOf(current());
    }

    /** Lists the child values of the current node, sorted by name. */
    public List<String> keys() {
        return valuesOf(current());
    }

    /** Lists every child name of the current node (directories and values), sorted. */
    public List<String> allKeys() {
        List<String> all = new ArrayList<>();
        all.addAll(directoriesOf(current()));
        all.addAll(valuesOf(current()));
        all.sort(String::compareTo);
        return all;
    }

    /** True if the named child directory of the current node exists. */
    public boolean hasChild(String name) {
        return isDirectory(current().get(name));
    }

    /** True if the current node holds a value under {@code key}. */
    public boolean hasValue(String key) {
        return isValue(current().get(key));
    }

    // ---------------------------------------------------------------------
    // Reading / writing values
    // ---------------------------------------------------------------------

    /**
     * Reads the payload of a value under {@code key} in the current node.
     *
     * @return the payload, or {@code null} when the key is missing or is a
     *         directory
     */
    public JsonNode get(String key) {
        JsonNode n = current().get(key);
        return payload(n);
    }

    /**
     * The whole {@code key} entry of the current node (directory or value node),
     * with its own {@code ts}. {@code null} when missing. Used for {@code .ll}.
     */
    public JsonNode entry(String key) {
        return current().get(key);
    }

    /**
     * Sets the payload of a value under {@code key} on the current node,
     * creating the value node when needed. If the key currently holds a
     * directory this fails; use {@link #cd(String)} first or remove it.
     *
     * @param value any JSON value, including {@code null} (stored as JSON null)
     * @throws NodeTreeException if {@code key} is reserved, blank or a directory
     */
    public void set(String key, JsonNode value) throws NodeTreeException {
        checkUserKey(key);
        if (value == null) {
            value = JsonNodeFactory.instance.nullNode();
        }
        ObjectNode node = current();
        JsonNode existing = node.get(key);
        if (isDirectory(existing)) {
            throw new NodeTreeException("'" + key + "' is a directory, not a value");
        }
        if (isValue(existing)) {
            if (Objects.equals(existing.get(VAL), value)) {
                return; // no change, no ts update
            }
            long now = System.currentTimeMillis();
            ((ObjectNode) existing).set(VAL, value);
            ((ObjectNode) existing).put(TS, now);
            touch(now);
            return;
        }
        if (existing != null) {
            throw new NodeTreeException("'" + key + "' exists but is neither a directory nor a value");
        }
        long now = System.currentTimeMillis();
        ObjectNode child = JsonNodeFactory.instance.objectNode();
        child.put(TS, now);
        child.put(NT, NT_VALUE);
        child.set(VAL, value);
        node.set(key, child);
        touch(now);
    }

    /** Removes the value under {@code key} from the current node. Returns whether it existed. */
    public boolean clear(String key) throws NodeTreeException {
        checkUserKey(key);
        ObjectNode node = current();
        if (!isValue(node.get(key))) {
            return false;
        }
        node.remove(key);
        touch();
        return true;
    }

    // ---------------------------------------------------------------------
    // Children
    // ---------------------------------------------------------------------

    /**
     * Creates an empty child directory under the current node, stamped with the
     * current time.
     *
     * @throws NodeTreeException if the name is reserved, blank, already used, or
     *                           is not a valid single path segment
     */
    public void mkdir(String name) throws NodeTreeException {
        checkUserKey(name);
        if (name.contains("/")) {
            throw new NodeTreeException("child name must not contain '/'");
        }
        ObjectNode node = current();
        JsonNode existing = node.get(name);
        if (isDirectory(existing)) {
            throw new NodeTreeException("child node already exists: " + name);
        }
        if (isValue(existing)) {
            throw new NodeTreeException("a value named '" + name + "' already exists");
        }
        ObjectNode child = JsonNodeFactory.instance.objectNode();
        child.put(TS, System.currentTimeMillis());
        child.put(NT, NT_DIR);
        node.set(name, child);
        touch();
    }

    /**
     * Deletes a child directory (and everything below it). Returns whether it
     * existed. Refuses to delete a value; use {@link #clear(String)} for those.
     */
    public boolean rmdir(String name) throws NodeTreeException {
        checkUserKey(name);
        ObjectNode node = current();
        JsonNode existing = node.get(name);
        if (existing == null) {
            return false;
        }
        if (isValue(existing)) {
            throw new NodeTreeException("'" + name + "' is a value, not a directory");
        }
        node.remove(name);
        if (cwd.size() > 0 && name.equals(cwd.get(cwd.size() - 1))) {
            // current node was deleted by the caller's own operation; move up
            cwd.remove(cwd.size() - 1);
        }
        touch();
        return true;
    }

    // ---------------------------------------------------------------------
    // Data access helpers
    // ---------------------------------------------------------------------

    /** The node at an absolute or relative path (resolved without fuzzy matching). */
    public ObjectNode nodeAt(String path) throws NodeTreeException {
        return nodeAt(resolvePathExact(path));
    }

    /** Resolves a path using exact name matching only (no fuzzy fallback). */
    public List<String> resolvePathExact(String path) throws NodeTreeException {
        List<String> stack = new ArrayList<>(cwd);
        String cleaned = path == null ? "" : path.strip();
        if (cleaned.isEmpty() || cleaned.equals(".")) {
            return stack;
        }
        if (cleaned.startsWith("/")) {
            stack.clear();
            cleaned = cleaned.substring(1);
        }
        for (String segment : cleaned.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            ObjectNode here = nodeAt(stack);
            JsonNode child = here.get(segment);
            if (child == null || !child.isObject()) {
                throw new NodeTreeException("no such node: '" + segment + "' in " + pathOf(stack));
            }
            stack.add(segment);
        }
        return stack;
    }

    private ObjectNode nodeAt(List<String> path) {
        ObjectNode n = root;
        for (String segment : path) {
            n = (ObjectNode) n.get(segment);
        }
        return n;
    }

    private String pathOf(List<String> stack) {
        return stack.isEmpty() ? "/" : "/" + String.join("/", stack);
    }

    /** Timestamp of the current node. */
    public long ts() {
        return current().path(TS).asLong();
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Marks the tree as saved; call after persisting. */
    public void markClean() {
        dirty = false;
    }

    /**
     * Marks the tree dirty and stamps the current node and every ancestor with
     * the current time.
     *
     * <p>For callers that changed the node objects directly (for example a
     * rename that moves a child inside its parent): the change must be
     * reflected in every ancestor's timestamp, exactly as {@link #set} would
     * do. The tree itself is not modified otherwise.</p>
     */
    public void touchParents() {
        touch();
    }

    private void touch() {
        touch(System.currentTimeMillis());
    }

    /** Stamps the current node and every ancestor with {@code now}. */
    private void touch(long now) {
        dirty = true;
        ObjectNode n = root;
        n.put(TS, now);
        for (String segment : cwd) {
            n = (ObjectNode) n.get(segment);
            n.put(TS, now);
        }
    }

    private static void checkUserKey(String key) throws NodeTreeException {
        if (key == null || key.isBlank()) {
            throw new NodeTreeException("key must not be blank");
        }
        if (isReserved(key)) {
            throw new NodeTreeException("'" + key + "' is a reserved key");
        }
    }

    // ---------------------------------------------------------------------
    // Merging
    // ---------------------------------------------------------------------

    /**
     * Three-way merges another tree into this one, starting at the root.
     *
     * <p>Let {@code C} be this tree and {@code S} the imported one. Every node
     * is aligned by its position (its path from the root).</p>
     *
     * <h2>Directories</h2>
     * <ul>
     *   <li>A path only {@code S} has is added with its whole subtree.</li>
     *   <li>A path only {@code C} has is kept. Merging only ever adds: the two
     *       notebooks may describe different work (say {@code projectA} on one
     *       side and {@code projectB} on the other), so a path missing from
     *       {@code S} is not evidence that it was deleted. Use {@code .rm} to
     *       delete explicitly.</li>
     *   <li>A path both have is recursed into. It keeps the structure of both
     *       sides, so a newer {@code ts} on an intermediate node is never
     *       treated as a replacement for the whole subtree. Its content is the
     *       union of both sides.</li>
     * </ul>
     *
     * <h2>Values</h2>
     * <p>A value present on both sides with the same payload is kept. With
     * different payloads the newer value wins, compared by the value's
     * <b>own</b> {@code ts} (a tie keeps the current one). Because each value
     * carries its own timestamp, the {@code user} and {@code password} values
     * under one parent are decided independently: editing {@code user} on one
     * side and {@code password} on the other merges both edits.</p>
     *
     * <p>When the same path is a directory on one side and a value on the
     * other, the newer node wins as a whole (a tie keeps the current shape).</p>
     *
     * <h2>Timestamps</h2>
     * <p>The merged {@code ts} obeys {@code min(max(S.ts, C.ts), now)}:</p>
     * <ul>
     *   <li>A node added whole keeps the imported timestamp of itself and of
     *       every node below it, clamped to {@code now}.</li>
     *   <li>A node whose content changed gets {@code min(max(S.ts, C.ts), now)}.</li>
     *   <li>A node that ends up unchanged keeps its own {@code ts}, like any
     *       set of an unchanged value. In particular, keeping {@code C} is
     *       not a modification.</li>
     * </ul>
     *
     * <p>When the result is identical to this tree the merge is a no-op and
     * nothing is marked dirty. The working node is reset to the root.</p>
     *
     * @param other the tree to import; it is not modified
     * @return what the merge did
     */
    public MergeStats merge(NodeTree other) throws NodeTreeException {
        Objects.requireNonNull(other, "other");
        long now = System.currentTimeMillis();
        Counter counter = new Counter();
        MergeOutcome merged = mergeNode(root, other.root, now, counter);
        cwd.clear();
        if (merged.changed()) {
            // mutate in place so references to root handed out earlier stay valid
            root.removeAll();
            root.setAll(merged.node());
            dirty = true;
        } else {
            // still never leave the root in the future
            root.put(TS, clamp(root.path(TS).asLong(), now, counter));
        }
        return counter.toStats();
    }

    /**
     * Result of merging one node: its merged content and the {@code ts} it must
     * end up carrying.
     *
     * @param node    the merged object
     * @param changed whether anything in this subtree differs from the current
     *                content
     */
    private record MergeOutcome(ObjectNode node, boolean changed) {
    }

    /**
     * Merges the imported node {@code otherNode} into the current node
     * {@code here}, both representing the same path.
     *
     * <p>Directories are merged key by key, never replaced as a whole. Value
     * nodes are compared with their own {@code ts}, so two values below the
     * same parent are decided independently of each other.</p>
     */
    private MergeOutcome mergeNode(ObjectNode here, ObjectNode otherNode, long now, Counter counter) {
        long tsC = here.path(TS).asLong();
        long tsS = otherNode.path(TS).asLong();

        if (isValue(here) && isValue(otherNode)) {
            return mergeValue(here, otherNode, now, counter);
        }
        if (!isValue(here) && !isValue(otherNode)) {
            return mergeDirectory(here, otherNode, now, counter);
        }
        // kind conflict (directory on one side, value on the other): the newer
        // node wins as a whole, because a directory and a value cannot coexist
        ValueDecision decision = decide(tsC, tsS, now);
        if (decision == ValueDecision.TAKE_IMPORTED) {
            ObjectNode copy = otherNode.deepCopy();
            stampAdded(copy, now, counter);
            counter.updated++;
            return new MergeOutcome(copy, true);
        }
        if (decision == ValueDecision.TAKE_CURRENT) {
            counter.kept++;
            ObjectNode out = here.deepCopy();
            out.put(TS, clamp(tsC, now, counter));
            return new MergeOutcome(out, false);
        }
        // equal timestamps but different kinds: keep the current shape
        counter.kept++;
        ObjectNode out = here.deepCopy();
        out.put(TS, clamp(tsC, now, counter));
        return new MergeOutcome(out, false);
    }

    /** Two value nodes under the same path: the newer payload wins. */
    private MergeOutcome mergeValue(ObjectNode here, ObjectNode otherNode, long now, Counter counter) {
        long tsC = here.path(TS).asLong();
        long tsS = otherNode.path(TS).asLong();
        if (Objects.equals(here.get(VAL), otherNode.get(VAL))) {
            counter.valuesKept++;
            counter.kept++;
            ObjectNode out = here.deepCopy();
            out.put(TS, clamp(tsC, now, counter));
            return new MergeOutcome(out, false);
        }
        if (tsS > tsC) {
            counter.valuesUpdated++;
            counter.updated++;
            ObjectNode out = otherNode.deepCopy();
            out.put(TS, normativeTs(tsS, tsC, now, counter));
            return new MergeOutcome(out, true);
        }
        counter.valuesKept++;
        counter.kept++;
        ObjectNode out = here.deepCopy();
        out.put(TS, clamp(tsC, now, counter));
        return new MergeOutcome(out, false);
    }

    /** What to do with a key that both trees hold. */
    private enum ValueDecision { TAKE_CURRENT, TAKE_IMPORTED, EQUAL }

    /** Compares timestamps: the newer side wins, a tie keeps the current side. */
    private static ValueDecision decide(long tsC, long tsS, long now) {
        if (tsS > tsC) {
            return ValueDecision.TAKE_IMPORTED;
        }
        if (tsS == tsC) {
            return ValueDecision.EQUAL;
        }
        return ValueDecision.TAKE_CURRENT;
    }

    /** Two directories under the same path: merge their children. */
    private MergeOutcome mergeDirectory(ObjectNode here, ObjectNode otherNode, long now, Counter counter) {
        long tsC = here.path(TS).asLong();
        long tsS = otherNode.path(TS).asLong();
        ObjectNode out = here.deepCopy();
        boolean changed = false;

        // keys the imported side has (subdirectories and values)
        for (Map.Entry<String, JsonNode> e : otherNode.properties()) {
            String name = e.getKey();
            if (isReserved(name)) {
                continue; // "ts", and "nt"/"val" on a directory carry no content
            }
            JsonNode theirs = e.getValue();
            JsonNode mine = here.get(name);
            if (mine == null) {
                // a path the current side lacks: take the whole subtree as is
                ObjectNode copy = theirs.deepCopy();
                stampAdded(copy, now, counter);
                out.set(name, copy);
                changed = true;
                continue;
            }
            // same path on both sides: recurse; a recursive call can never
            // replace a directory as a whole, so newer branch ts cannot wipe
            // content that only the current side knows
            MergeOutcome sub = mergeNode((ObjectNode) mine, (ObjectNode) theirs, now, counter);
            if (!sub.node().equals(mine)) {
                out.set(name, sub.node());
            }
            changed |= sub.changed();
        }

        // keys only the current side has are never removed: merging only adds.
        // Two independent notebooks may legitimately hold different branches
        // (google/projectA on one side, google/projectB on the other); absence
        // in the imported tree is not evidence that anything was deleted.
        for (Iterator<String> it = here.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!isReserved(name) && !otherNode.has(name)) {
                counter.kept++;
            }
        }

        if (!changed) {
            counter.kept++;
            out.put(TS, clamp(tsC, now, counter));
            return new MergeOutcome(out, false);
        }
        counter.updated++;
        out.put(TS, normativeTs(tsS, tsC, now, counter));
        return new MergeOutcome(out, true);
    }

    /**
     * Records a whole subtree the current side lacks: it counts as added
     * together with its descendants, and its timestamps are clamped to
     * {@code now}.
     */
    private static void stampAdded(ObjectNode node, long now, Counter counter) {
        node.put(TS, clamp(node.path(TS).asLong(), now, counter));
        if (isValue(node)) {
            counter.valuesAdded++;
            return;
        }
        counter.added++;
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getKey().equals(TS) || !e.getValue().isObject()) {
                continue;
            }
            stampAdded((ObjectNode) e.getValue(), now, counter);
        }
    }

    /** Mutable counts while a merge runs; {@code normativeTs} counts the clamped timestamps. */
    private static final class Counter {
        int added;
        int updated;
        int kept;
        int valuesAdded;
        int valuesUpdated;
        int valuesKept;
        int normativeTs;

        MergeStats toStats() {
            return new MergeStats(added, updated, kept,
                    valuesAdded, valuesUpdated, valuesKept, normativeTs);
        }
    }

    /** The stored {@code ts} of {@code node}, clamped to {@code now}. */
    private static long clamp(long ts, long now, Counter counter) {
        if (ts > now) {
            counter.normativeTs++;
            return now;
        }
        return ts;
    }

    /** {@code min(max(s, c), now)}: the winning timestamp, never in the future. */
    private static long normativeTs(long s, long c, long now, Counter counter) {
        return clamp(Math.max(s, c), now, counter);
    }

    private static List<String> directoriesOf(ObjectNode node) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!isReserved(e.getKey()) && isDirectory(e.getValue())) {
                out.add(e.getKey());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static List<String> valuesOf(ObjectNode node) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!isReserved(e.getKey()) && isValue(e.getValue())) {
                out.add(e.getKey());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    // ---------------------------------------------------------------------
    // Fuzzy matching
    // ---------------------------------------------------------------------

    /**
     * Fuzzy-resolves a single name against the children of the current node.
     * Exact matches always win. Otherwise candidates are ranked by ignoring
     * case, then by substring containment, then by edit distance.
     */
    public Match match(String name) {
        SegmentLookup lookup = lookupSegment(current(), name);
        return new Match(lookup.kind, name, lookup.name, lookup.candidates);
    }

    private record SegmentLookup(MatchKind kind, String name, List<String> candidates) {
    }

    private static SegmentLookup lookupSegment(ObjectNode parent, String name) {
        // values are addressable too: a fuzzy alias may point at either kind
        List<String> children = new ArrayList<>(directoriesOf(parent));
        children.addAll(valuesOf(parent));
        if (children.contains(name)) {
            return new SegmentLookup(MatchKind.EXACT, name, List.of());
        }
        String needle = name.toLowerCase(Locale.ROOT);
        record Scored(String name, int distance, boolean contains) {
        }
        List<Scored> scored = new ArrayList<>();
        int best = Integer.MAX_VALUE;
        for (String candidate : children) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            int distance = editDistance(needle, lower);
            boolean contains = lower.contains(needle) || needle.contains(lower);
            if (contains) {
                distance = Math.min(distance, 1); // containment is at least as good as one typo
            }
            scored.add(new Scored(candidate, distance, contains));
            best = Math.min(best, distance);
        }
        if (scored.isEmpty()) {
            return new SegmentLookup(MatchKind.NONE, null, List.of());
        }
        int threshold = Math.max(1, needle.length() / 3);
        // Stream.toList() is API 34+; collect explicitly so Android 8..13 work too
        List<String> hits = new ArrayList<>();
        scored.stream()
                .filter(s -> s.distance <= threshold)
                .sorted(Comparator.comparingInt(Scored::distance).thenComparing(Scored::name))
                .map(Scored::name)
                .forEach(hits::add);
        if (hits.isEmpty()) {
            return new SegmentLookup(MatchKind.NONE, null, List.of());
        }
        if (hits.size() == 1) {
            return new SegmentLookup(MatchKind.FUZZY_UNIQUE, hits.get(0), List.of());
        }
        return new SegmentLookup(MatchKind.FUZZY_AMBIGUOUS, null, hits);
    }

    /** Plain Levenshtein distance. */
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    // ---------------------------------------------------------------------
    // Internal helpers used by tests and CLI
    // ---------------------------------------------------------------------

    /** Depth-first map of absolute path to node, for debugging. */
    public Map<String, ObjectNode> flatten() {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        collect(root, "", out);
        return Collections.unmodifiableMap(out);
    }

    private static void collect(ObjectNode node, String prefix, Map<String, ObjectNode> out) {
        out.put(prefix.isEmpty() ? "/" : prefix, node);
        for (String child : directoriesOf(node)) {
            collect((ObjectNode) node.get(child), prefix + "/" + child, out);
        }
    }
}
