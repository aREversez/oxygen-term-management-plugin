package com.example.termmgmt.util;

import javax.swing.JTable;

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
}
