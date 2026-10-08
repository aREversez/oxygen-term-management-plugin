package com.example.termmgmt.util;

import javax.swing.JTable;
import java.util.Arrays;

/**
 * Helpers for translating JTable view rows (what the user sees and selects)
 * into model rows (what the backing list is indexed by).
 *
 * A JTable with a RowSorter reorders and filters rows in the view only, so a
 * view row index must never be used to index the underlying data directly.
 */
public final class TableRowUtils {

    private TableRowUtils() {
    }

    /**
     * Convert a view row index to the corresponding model row index.
     *
     * @return the model row, or -1 if {@code viewRow} is not a valid view row
     */
    public static int toModelRow(JTable table, int viewRow) {
        if (viewRow < 0 || viewRow >= table.getRowCount()) {
            return -1;
        }
        return table.convertRowIndexToModel(viewRow);
    }

    /**
     * Convert a set of selected view rows to distinct model rows, sorted in
     * descending order.
     *
     * Removing entries from a list by index must go from the highest index to
     * the lowest, otherwise every removal shifts the entries still to be
     * removed. Sorted views make the model order unrelated to the view order,
     * so the order has to be established on the model rows, after conversion.
     * Invalid view rows are dropped.
     */
    public static int[] toModelRowsDescending(JTable table, int[] viewRows) {
        int[] rows = Arrays.stream(viewRows)
            .map(viewRow -> toModelRow(table, viewRow))
            .filter(modelRow -> modelRow >= 0)
            .distinct()
            .sorted()
            .toArray();
        for (int i = 0, j = rows.length - 1; i < j; i++, j--) {
            int tmp = rows[i];
            rows[i] = rows[j];
            rows[j] = tmp;
        }
        return rows;
    }

    /**
     * Whether a right-click on {@code clickedRow} should replace the selection with that row.
     * Standard table behaviour: a click on a row that is already part of the selection keeps the
     * whole selection (so a context-menu action applies to every selected row); a click on any
     * other row selects just that row. A click outside the rows (-1) changes nothing.
     */
    public static boolean shouldSelectOnPopup(int clickedRow, int[] selectedViewRows) {
        if (clickedRow < 0) {
            return false;
        }
        for (int r : selectedViewRows) {
            if (r == clickedRow) {
                return false;
            }
        }
        return true;
    }
}
