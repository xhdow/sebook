package notebook.tree;

/**
 * What a {@link NodeTree#merge(NodeTree)} call did, for reporting and tests.
 *
 * <p>Merging only adds: nothing is ever deleted, so there are no deletion
 * counters. Counts are per node (the root included) and stay disjoint:
 * {@code added} are nodes only the imported tree knew, {@code updated} are
 * nodes whose content changed, {@code kept} are nodes present in both (or only
 * in the current tree) that ended up unchanged. Leaves (values) are counted
 * separately.</p>
 *
 * @param added        nodes only the imported tree knew
 * @param updated      nodes whose content changed during the merge
 * @param kept         nodes that ended up unchanged
 * @param valuesAdded  leaf values only the imported tree knew
 * @param valuesUpdated leaf values whose content changed
 * @param valuesKept   leaf values present on both sides without change
 * @param fillsNormativeTs how many {@code ts} values had to be clamped to
 *                         {@code now} because they claimed to be in the future
 */
public record MergeStats(int added, int updated, int kept,
                         int valuesAdded, int valuesUpdated, int valuesKept,
                         int fillsNormativeTs) {

    public boolean changed() {
        return added + updated + valuesAdded + valuesUpdated > 0;
    }

    /** One-line human summary, used by the {@code .import} command. */
    public String describe() {
        return "nodes: " + added + " added, " + updated + " updated, " + kept + " kept; "
                + "values: " + valuesAdded + " added, " + valuesUpdated + " updated, "
                + valuesKept + " kept"
                + (fillsNormativeTs > 0 ? "; " + fillsNormativeTs + " future ts clamped to now" : "");
    }
}
