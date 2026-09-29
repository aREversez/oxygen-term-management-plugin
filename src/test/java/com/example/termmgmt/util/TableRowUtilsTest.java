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
}
