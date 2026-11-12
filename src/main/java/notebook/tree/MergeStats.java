package notebook.tree;

/**
 * What a {@link NodeTree#merge(NodeTree)} call did, for reporting and tests.
 *
 * <p>Merging only adds: nothing is ever deleted, so there are no deletion
 * counters. Directory nodes and value nodes are counted separately. Because
 * every value now carries its own {@code ts}, two values under the same parent
 * are decided independently.</p>
 *
 * @param added         directories only the imported tree knew
 * @param updated       directories whose content changed during the merge
 * @param kept          directories present on both sides (or only here) that ended up unchanged
 * @param valuesAdded   value nodes only the imported tree knew
 * @param valuesUpdated value nodes whose timestamp made the imported payload win
 * @param valuesKept    value nodes present on both sides without change
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
        return "directories: " + added + " added, " + updated + " updated, " + kept + " kept; "
                + "values: " + valuesAdded + " added, " + valuesUpdated + " updated, "
                + valuesKept + " kept"
                + (fillsNormativeTs > 0 ? "; " + fillsNormativeTs + " future ts clamped to now" : "");
    }
}
