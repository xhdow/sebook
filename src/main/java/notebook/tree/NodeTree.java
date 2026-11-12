package notebook.tree;

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
 * <p>Every node is a JSON object. A key whose value is an object is a
 * <b>child node</b>; any other value (string, number, boolean, array, null) is
 * a <b>leaf value</b>. There is no separate "children" field: nesting is the
 * structure.</p>
 *
 * <pre>
 * {
 *   "ts": 1731123456789,
 *   "name": "my notes",
 *   "work": {
 *     "ts": 1731123456790,
 *     "title": "project"
 *   }
 * }
 * </pre>
 *
 * <h2>Timestamps</h2>
 * <p>Every object node carries a numeric {@value #TS} field holding the epoch
 * millisecond of its last modification. Modifying anything inside a node
 * updates that node's {@code ts} and propagates upward through every ancestor,
 * including the root. Setting a value that is equal to the current one is a no
 * operation and does not touch {@code ts}. Read-only navigation never touches
 * {@code ts}.</p>
 *
 * <p>The key {@value #TS} is reserved and cannot be created, renamed or
 * deleted through this API.</p>
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
     *                           object, or any nested object lacks a valid
     *                           {@code ts} field
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
     * Walks the tree and makes sure every object node has a numeric {@code ts}.
     * The root is always stamped with {@code now} when missing (it stands for the
     * tree itself), nested nodes are rejected when missing for strictness.
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
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getValue().isObject()) {
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
     * {@link #match(String)} so fuzzy names work. A segment that resolves to a
     * leaf value is rejected.</p>
     *
     * @throws NodeTreeException if the path cannot be resolved
     * @throws AmbiguousPathException if a segment matches several children
     */
    public void cd(String path) throws NodeTreeException {
        Objects.requireNonNull(path, "path");
        List<String> target = resolve(path);
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

    /** Lists the children (object-valued entries) of the current node, sorted by name. */
    public List<String> ls() {
        return childrenOf(current());
    }

    /** Lists the leaf keys (non-object entries except {@code ts}) of the current node. */
    public List<String> keys() {
        return keysOf(current());
    }

    /** Lists every key of the current node (children and leaves), sorted. */
    public List<String> allKeys() {
        List<String> all = new ArrayList<>();
        all.addAll(childrenOf(current()));
        all.addAll(keysOf(current()));
        all.sort(String::compareTo);
        return all;
    }

    /** True if the named child of the current node exists. */
    public boolean hasChild(String name) {
        JsonNode n = current().get(name);
        return n != null && n.isObject();
    }

    /** True if the current node holds a leaf value under {@code key}. */
    public boolean hasValue(String key) {
        JsonNode n = current().get(key);
        return n != null && !n.isObject() && !key.equals(TS);
    }

    // ---------------------------------------------------------------------
    // Reading / writing values
    // ---------------------------------------------------------------------

    /**
     * Reads a leaf value from the current node.
     *
     * @return the JSON value, or {@code null} for a JSON null / a missing key
     */
    public JsonNode get(String key) {
        JsonNode n = current().get(key);
        return n;
    }

    /**
     * Sets a leaf value on the current node. If the key currently holds a child
     * node this fails; use {@link #cd(String)} first or delete it.
     *
     * @throws NodeTreeException if {@code key} is reserved, blank or already a child
     */
    public void set(String key, JsonNode value) throws NodeTreeException {
        checkUserKey(key);
        if (value == null) {
            throw new NodeTreeException("value must not be null (use clear)");
        }
        if (value.isObject()) {
            throw new NodeTreeException("value is an object; use mkdir to create nodes");
        }
        ObjectNode node = current();
        JsonNode existing = node.get(key);
        if (existing != null && existing.isObject()) {
            throw new NodeTreeException("'" + key + "' is a child node, not a value");
        }
        if (existing != null && existing.equals(value)) {
            return; // no change, no ts update
        }
        node.set(key, value);
        touch();
    }

    /** Removes a leaf key from the current node. Returns whether it existed. */
    public boolean clear(String key) throws NodeTreeException {
        checkUserKey(key);
        ObjectNode node = current();
        JsonNode existing = node.get(key);
        if (existing == null || existing.isObject()) {
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
     * Creates an empty child node under the current node, stamped with the
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
        if (existing != null) {
            if (existing.isObject()) {
                throw new NodeTreeException("child node already exists: " + name);
            }
            throw new NodeTreeException("a value named '" + name + "' already exists");
        }
        ObjectNode child = JsonNodeFactory.instance.objectNode();
        child.put(TS, System.currentTimeMillis());
        node.set(name, child);
        touch();
    }

    /**
     * Deletes a child node (and everything below it). Returns whether it existed.
     * Refuses to delete a leaf value; use {@link #clear(String)} for those.
     */
    public boolean rmdir(String name) throws NodeTreeException {
        checkUserKey(name);
        ObjectNode node = current();
        JsonNode existing = node.get(name);
        if (existing == null) {
            return false;
        }
        if (!existing.isObject()) {
            throw new NodeTreeException("'" + name + "' is a value, not a node");
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

    private void touch() {
        dirty = true;
        long now = System.currentTimeMillis();
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
        if (key.equals(TS)) {
            throw new NodeTreeException("'" + TS + "' is a reserved key");
        }
    }

    // ---------------------------------------------------------------------
    // Merging
    // ---------------------------------------------------------------------

    /**
     * Three-way merges another tree into this one, starting at the root.
     *
     * <p>Let {@code C} be this tree and {@code S} the imported one. Every node
     * is aligned by its position (its path from the root). When a value only
     * one side has is merged, the ancestor links are filled with its owning
     * object's timestamp, which is the {@code ts} the other side would have
     * stored had it known the value too.</p>
     *
     * <h2>Nodes</h2>
     * <ul>
     *   <li>A path only {@code S} has is added verbatim.</li>
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
     * <h2>Leaves</h2>
     * <p>A key holding different values on both sides keeps the newer one
     * ({@code ts(S.owner)} versus {@code ts(C.owner)}; a tie keeps the current
     * value). Missing on either side counts as old.</p>
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
        MergeOutcome merged = mergeObject(root, other.root, now, counter);
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
     */
    private MergeOutcome mergeObject(ObjectNode here, ObjectNode otherNode, long now, Counter counter) {
        long tsC = here.path(TS).asLong();
        long tsS = otherNode.path(TS).asLong();
        ObjectNode out = here.deepCopy();
        boolean changed = false;

        // keys the imported side has (children and values)
        for (Map.Entry<String, JsonNode> e : otherNode.properties()) {
            String name = e.getKey();
            if (name.equals(TS)) {
                continue;
            }
            JsonNode theirs = e.getValue();
            JsonNode mine = here.get(name);
            if (theirs.isObject()) {
                if (mine != null && mine.isObject()) {
                    // same path on both sides: never replace the subtree, recurse
                    MergeOutcome sub = mergeObject((ObjectNode) mine, (ObjectNode) theirs, now, counter);
                    if (!sub.node().equals(mine)) {
                        out.set(name, sub.node());
                    }
                    changed |= sub.changed();
                } else if (mine != null) {
                    // a node here, a value there: the newer owner wins
                    if (tsS > tsC) {
                        stampAdded((ObjectNode) theirs, tsS, now, counter);
                        out.set(name, theirs.deepCopy());
                        changed = true;
                    } else {
                        counter.kept++;
                    }
                } else {
                    stampAdded((ObjectNode) theirs, tsS, now, counter);
                    out.set(name, theirs.deepCopy());
                    changed = true;
                }
            } else if (mine == null) {
                out.set(name, theirs.deepCopy());
                counter.valuesAdded++;
                changed = true;
            } else if (mine.isObject()) {
                // a value here, a node there: the newer owner wins
                if (tsS > tsC) {
                    out.set(name, theirs.deepCopy());
                    counter.valuesAdded++;
                    changed = true;
                } else {
                    counter.kept++;
                }
            } else if (mine.equals(theirs)) {
                counter.valuesKept++;
            } else if (tsS > tsC) {
                out.set(name, theirs.deepCopy());
                counter.valuesUpdated++;
                changed = true;
            } else {
                counter.valuesKept++;
            }
        }

        // keys only the current side has are never removed: merging only adds.
        // Two independent notebooks may legitimately hold different branches
        // (google/projectA on one side, google/projectB on the other); absence
        // in the imported tree is not evidence that anything was deleted.
        for (Iterator<String> it = here.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            if (!name.equals(TS) && !otherNode.has(name)) {
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
     * Records a whole node the current side lacks: it counts as added together
     * with its descendants, and its timestamps are clamped to {@code now}.
     */
    private static void stampAdded(ObjectNode node, long tsS, long now, Counter counter) {
        node.put(TS, clamp(tsS, now, counter));
        counter.added++;
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String key = e.getKey();
            if (key.equals(TS)) {
                continue;
            }
            if (e.getValue().isObject()) {
                stampAdded((ObjectNode) e.getValue(), e.getValue().path(TS).asLong(), now, counter);
            } else {
                counter.valuesAdded++;
            }
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

    private static List<String> childrenOf(ObjectNode node) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (e.getValue().isObject()) {
                out.add(e.getKey());
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    private static List<String> keysOf(ObjectNode node) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            if (!e.getKey().equals(TS) && !e.getValue().isObject()) {
                out.add(e.getKey());
            }
        }
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
        List<String> children = childrenOf(parent);
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
        List<String> hits = scored.stream()
                .filter(s -> s.distance <= threshold)
                .sorted(Comparator.comparingInt(Scored::distance).thenComparing(Scored::name))
                .map(Scored::name)
                .toList();
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
        for (String child : childrenOf(node)) {
            collect((ObjectNode) node.get(child), prefix + "/" + child, out);
        }
    }
}
