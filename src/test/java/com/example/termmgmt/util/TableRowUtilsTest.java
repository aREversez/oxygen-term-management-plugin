package com.example.termmgmt.util;

import com.example.termmgmt.model.TermEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TableRowUtilsTest {

    private List<TermEntry> terms;
    private DefaultTableModel model;
    private JTable table;
    private TableRowSorter<DefaultTableModel> sorter;

    @BeforeEach
    void setUp() {
        // Model order (= list order): b, a, c
        terms = new ArrayList<>(List.of(
            new TermEntry("b", "B"),
            new TermEntry("a", "A"),
            new TermEntry("c", "C")));
        model = new DefaultTableModel(new String[]{"src", "tgt"}, 0);
        for (TermEntry t : terms) {
            model.addRow(new Object[]{t.getSourceTerm(), t.getTargetTerm()});
        }
        table = new JTable(model);
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);
    }

    @Test
    void toModelRow_withoutSorting_isIdentity() {
        assertEquals(0, TableRowUtils.toModelRow(table, 0));
        assertEquals(2, TableRowUtils.toModelRow(table, 2));
    }

    @Test
    void toModelRow_whenSorted_mapsViewRowToModelRow() {
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        // View order is now a, b, c -> view row 0 is model row 1 ("a")
        assertEquals(1, TableRowUtils.toModelRow(table, 0));
        assertEquals(0, TableRowUtils.toModelRow(table, 1));
        assertEquals(2, TableRowUtils.toModelRow(table, 2));
    }

    @Test
    void toModelRow_whenFiltered_mapsViewRowToModelRow() {
        sorter.setRowFilter(RowFilter.regexFilter("^(a|c)$", 0));
        // View shows only a (model 1) and c (model 2)
        assertEquals(1, TableRowUtils.toModelRow(table, 0));
        assertEquals(2, TableRowUtils.toModelRow(table, 1));
    }

    @Test
    void toModelRow_outOfRange_returnsMinusOne() {
        assertEquals(-1, TableRowUtils.toModelRow(table, -1));
        assertEquals(-1, TableRowUtils.toModelRow(table, 3));
        sorter.setRowFilter(RowFilter.regexFilter("^a$", 0));
        assertEquals(-1, TableRowUtils.toModelRow(table, 1));
    }

    /** Mirrors what TerminologyPanel.editTerm() does with the selected row. */
    @Test
    void editingSelectedRow_whenSorted_replacesTheSelectedEntry() {
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        table.setRowSelectionInterval(0, 0); // user selects "a" (first visible row)

        int modelRow = TableRowUtils.toModelRow(table, table.getSelectedRow());
        assertEquals("a", model.getValueAt(modelRow, 0));

        terms.set(modelRow, new TermEntry("a", "A-edited"));

        assertEquals("B", terms.get(0).getTargetTerm());
        assertEquals("A-edited", terms.get(1).getTargetTerm());
        assertEquals("C", terms.get(2).getTargetTerm());
    }

    @Test
    void toModelRowsDescending_returnsDistinctModelRowsHighestFirst() {
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        // View: a(m1), b(m0), c(m2)
        assertArrayEquals(new int[]{2, 1, 0}, TableRowUtils.toModelRowsDescending(table, new int[]{0, 1, 2}));
        assertArrayEquals(new int[]{1, 0}, TableRowUtils.toModelRowsDescending(table, new int[]{0, 1}));
        assertArrayEquals(new int[]{1}, TableRowUtils.toModelRowsDescending(table, new int[]{0, 0}));
    }

    @Test
    void toModelRowsDescending_dropsInvalidRowsAndHandlesEmpty() {
        assertArrayEquals(new int[]{}, TableRowUtils.toModelRowsDescending(table, new int[]{}));
        assertArrayEquals(new int[]{2, 0}, TableRowUtils.toModelRowsDescending(table, new int[]{-1, 0, 2, 7}));
    }

    /** Mirrors what TerminologyPanel.deleteTerms() does with the selected rows. */
    @Test
    void deletingSelectedRows_whenSorted_removesExactlyTheSelectedEntries() {
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
        table.setRowSelectionInterval(0, 1); // user selects "a" and "b"

        for (int modelRow : TableRowUtils.toModelRowsDescending(table, table.getSelectedRows())) {
            terms.remove(modelRow);
        }

        assertEquals(1, terms.size());
        assertEquals("c", terms.get(0).getSourceTerm());
    }

    @Test
    void deletingSelectedRows_whenFilteredAndNonContiguous_removesExactlyTheSelectedEntries() {
        sorter.setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.DESCENDING)));
        // View: c(m2), b(m0), a(m1); select c and a (view rows 0 and 2)
        table.setRowSelectionInterval(0, 0);
        table.addRowSelectionInterval(2, 2);

        for (int modelRow : TableRowUtils.toModelRowsDescending(table, table.getSelectedRows())) {
            terms.remove(modelRow);
        }

        assertEquals(1, terms.size());
        assertEquals("b", terms.get(0).getSourceTerm());
    }
}
