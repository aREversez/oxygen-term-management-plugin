package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.util.FileAccessUtils;
import com.example.termmgmt.util.I18N;
import com.example.termmgmt.util.IconUtils;
import com.example.termmgmt.util.TableRowUtils;
import com.example.termmgmt.util.TermEntryUtils;
import com.example.termmgmt.util.TermbaseChecker;
import com.example.termmgmt.util.TermbaseChecker.Issue;
import com.example.termmgmt.util.TermbaseChecker.Severity;

import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.author.WSAuthorEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tab 3: Terminology Management panel.
 *
 * Manages terms in individual termbases.
 *
 * Features:
 * - JComboBox for selecting enabled termbases
 * - JTable for displaying terms (MULTIPLE_INTERVAL_SELECTION)
 * - Buttons: Reload, Add New Term, Quick Add New Term, Edit Term, Delete Term, Undo Delete
 * - Inline editing, filter, sort, undo support
 * - File write-back logic for CSV, XLSX, and TBX formats
 */
public class TerminologyPanel extends JPanel {

    private TermbaseRegistry registry;
    private JComboBox<TermbaseConfig> termbaseComboBox;
    private JTable termTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> tableSorter;
    private List<TermEntry> currentTerms = new ArrayList<>();
    private TermbaseConfig currentConfig;
    private JButton undoButton;

    // Undo support: the entries removed by the last delete, with their old positions. Undo puts
    // just these back into the current list; it never rewrites the file from an old copy.
    private List<TermEntryUtils.RemovedEntry> undoRemoved;
    private TermbaseConfig undoConfig;
    /** Language pair in force when the entries were deleted; undo is void once it changes. */
    private String undoPairKey;

    public TerminologyPanel(TermbaseRegistry registry) {
        this.registry = registry;
        initComponents();
    }

    /**
     * Initialize the UI components.
     */
    private void initComponents() {
        setLayout(new BorderLayout());

        // Header + selection panel (north area)
        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));

        JPanel headerWrap = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel(I18N.getString("tab.terminology.header"));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        headerWrap.add(headerLabel, BorderLayout.CENTER);
        northPanel.add(headerWrap);
        northPanel.add(Box.createVerticalStrut(8));

        JPanel selectionPanel = new JPanel();
        selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.X_AXIS));
        selectionPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        JLabel selectLabel = new JLabel(I18N.getString("lbl.select.termbase"));
        termbaseComboBox = new JComboBox<>();
        selectionPanel.add(selectLabel);
        selectionPanel.add(Box.createHorizontalStrut(8));
        selectionPanel.add(termbaseComboBox);
        selectionPanel.add(Box.createHorizontalGlue());
        termbaseComboBox.setPreferredSize(new Dimension(200, termbaseComboBox.getPreferredSize().height));
        termbaseComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                if (value instanceof TermbaseConfig) {
                    value = ((TermbaseConfig) value).getFileName();
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });
        termbaseComboBox.addActionListener(e -> {
            TermbaseConfig sel = (TermbaseConfig) termbaseComboBox.getSelectedItem();
            if (sel != null) saveLastTermbasePath(sel.getFilePath());
            loadTermbaseTerms();
        });
        termbaseComboBox.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                refreshTermbaseList();
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        northPanel.add(selectionPanel);

        // Filter field
        JPanel filterPanel = new JPanel(new BorderLayout(4, 0));
        filterPanel.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
        JTextField filterField = new JTextField();
        filterField.putClientProperty("JTextField.placeholderText", I18N.getString("msg.filter.placeholder"));
        filterPanel.add(filterField, BorderLayout.CENTER);
        northPanel.add(filterPanel);

        add(northPanel, BorderLayout.NORTH);

        // Create term table with in-place editing backed by TermEntry list
        tableModel = new DefaultTableModel(
            new String[]{I18N.getString("lbl.source.term"), I18N.getString("lbl.target.term"), I18N.getString("msg.col.status")}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0 || column == 1;
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                super.setValueAt(value, row, column);
                if (currentConfig == null || row >= currentTerms.size()) return;
                TermbaseConfig config = currentConfig;
                TermEntry original = currentTerms.get(row);
                // Replace the entry instead of mutating it: the original object is shared with
                // the registry's cache and is how the queued write finds it. The deep copy
                // carries extra columns and the TBX id through the edit.
                TermEntry edited = original.copy();
                if (column == 0) {
                    edited.setSourceTerm((String) value);
                } else if (column == 1) {
                    edited.setTargetTerm((String) value);
                }
                currentTerms.set(row, edited);
                registry.updateTermsAsync(config, terms -> {
                    TermEntryUtils.replaceEntryMerging(terms, original, edited);
                    return terms;
                }).whenComplete((ignored, error) -> {
                    if (error != null) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(TerminologyPanel.this,
                                getFileLockedMessage(error, config), I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                            loadTermbaseTerms(); // nothing was written: show what is really stored
                        });
                    }
                });
            }
        };
        termTable = new JTable(tableModel);
        termTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Set up sorting with Chinese-aware collation
        tableSorter = new TableRowSorter<>(tableModel);
        Collator chineseCollator = Collator.getInstance(Locale.CHINESE);
        tableSorter.setComparator(0, (a, b) -> chineseCollator.compare((String) a, (String) b));
        tableSorter.setComparator(1, (a, b) -> chineseCollator.compare((String) a, (String) b));
        tableSorter.setComparator(2, (a, b) -> chineseCollator.compare((String) a, (String) b));
        termTable.setRowSorter(tableSorter);

        // Wire up filter text field
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            void update() {
                String text = filterField.getText();
                if (text.trim().isEmpty()) {
                    tableSorter.setRowFilter(null);
                } else {
                    tableSorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text.trim())));
                }
            }
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });

        addTableContextMenu();
        add(new JScrollPane(termTable), BorderLayout.CENTER);

        // Button bar in two rows. FlowLayout always reports a single-row preferred
        // height, whatever the available width, so letting it wrap would push the
        // last buttons below the height BorderLayout.SOUTH gives them and make them
        // unreachable in a narrow view. Two explicit rows keep the preferred height
        // covering every button at any width.
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        JPanel iconRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 5));
        JPanel textRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 5));

        JButton reloadButton = new JButton(IconUtils.loadIcon("reload", 16));
        reloadButton.setToolTipText(I18N.getString("btn.reload.tooltip"));
        reloadButton.setPreferredSize(new Dimension(28, 28));
        reloadButton.addActionListener(e -> reloadTermbase());
        iconRow.add(reloadButton);

        JButton addButton = new JButton(IconUtils.loadIcon("add", 16));
        addButton.setToolTipText(I18N.getString("btn.add.new.tooltip"));
        addButton.setPreferredSize(new Dimension(24, 24));
        addButton.addActionListener(e -> addNewTerm());
        iconRow.add(addButton);

        JButton quickAddButton = new JButton(IconUtils.loadIcon("quick_add", 16));
        quickAddButton.setToolTipText(I18N.getString("btn.quick.add.tooltip"));
        quickAddButton.setPreferredSize(new Dimension(24, 24));
        quickAddButton.addActionListener(e -> quickAddNewTerm());
        iconRow.add(quickAddButton);

        JButton editButton = new JButton(IconUtils.loadIcon("edit", 16));
        editButton.setToolTipText(I18N.getString("btn.edit.tooltip"));
        editButton.setPreferredSize(new Dimension(24, 24));
        editButton.addActionListener(e -> editTerm());
        iconRow.add(editButton);

        JButton deleteButton = new JButton(IconUtils.loadIcon("delete", 16));
        deleteButton.setToolTipText(I18N.getString("btn.delete.tooltip"));
        deleteButton.setPreferredSize(new Dimension(24, 24));
        deleteButton.addActionListener(e -> deleteTerms());
        iconRow.add(deleteButton);

        undoButton = new JButton(I18N.getString("btn.undo"));
        undoButton.setToolTipText(I18N.getString("btn.undo.tooltip"));
        undoButton.setEnabled(false);
        undoButton.addActionListener(e -> undoDelete());
        textRow.add(undoButton);

        JButton resetSortButton = new JButton(I18N.getString("btn.reset.sort"));
        resetSortButton.setToolTipText(I18N.getString("btn.reset.sort.tooltip"));
        resetSortButton.addActionListener(e -> {
            tableSorter.setSortKeys(null);
            tableSorter.setRowFilter(null);
            filterField.setText("");
        });
        textRow.add(resetSortButton);

        JButton checkButton = new JButton(I18N.getString("btn.check"));
        checkButton.setToolTipText(I18N.getString("btn.check.tooltip"));
        checkButton.addActionListener(e -> runTermbaseCheck());
        textRow.add(checkButton);

        buttonPanel.add(iconRow);
        buttonPanel.add(textRow);

        add(buttonPanel, BorderLayout.SOUTH);

        // Load enabled termbases
        loadTermbaseList();
    }

    private void addTableContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem editItem = new JMenuItem(I18N.getString("menu.edit.term"));
        editItem.addActionListener(e -> editTerm());
        popup.add(editItem);

        JMenuItem deleteItem = new JMenuItem(I18N.getString("menu.delete.term"));
        deleteItem.addActionListener(e -> deleteTerms());
        popup.add(deleteItem);

        termTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showPopup(e);
            }
            private void showPopup(MouseEvent e) {
                int row = termTable.rowAtPoint(e.getPoint());
                // Keep a multi-selection when the click lands inside it, so "Delete Term" from
                // the context menu acts on every selected row, as the toolbar button does.
                if (TableRowUtils.shouldSelectOnPopup(row, termTable.getSelectedRows())) {
                    termTable.setRowSelectionInterval(row, row);
                }
                popup.show(termTable, e.getX(), e.getY());
            }
        });
    }

    private static final String LAST_TB_KEY = "com.example.termmgmt.last-termbase-terminology";

    /**
     * Load enabled termbases into the combo box, preserving selection.
     */
    private void loadTermbaseList() {
        // Read the shared in-memory configs; do NOT reload from OptionsStorage here. The periodic
        // external-change probe refreshes this list, and re-reading storage would swap in fresh
        // TermbaseConfig objects that drop the resolved column layout and any not-yet-applied
        // preferences edit - added rows would vanish (defect C).
        String prevPath = null;
        TermbaseConfig prev = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (prev != null) {
            prevPath = prev.getFilePath();
        } else {
            prevPath = loadLastTermbasePath();
        }
        termbaseComboBox.removeAllItems();
        List<TermbaseConfig> enabledConfigs = registry.getEnabledConfigs();
        for (TermbaseConfig config : enabledConfigs) {
            termbaseComboBox.addItem(config);
        }
        if (prevPath != null) {
            for (int i = 0; i < termbaseComboBox.getItemCount(); i++) {
                if (termbaseComboBox.getItemAt(i).getFilePath().equals(prevPath)) {
                    termbaseComboBox.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    private String loadLastTermbasePath() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return null;
            WSOptionsStorage os = w.getOptionsStorage();
            return os.getOption(LAST_TB_KEY, null);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveLastTermbasePath(String path) {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return;
            WSOptionsStorage os = w.getOptionsStorage();
            os.setOption(LAST_TB_KEY, path != null ? path : "");
        } catch (Exception e) {
            // Silently ignore in standalone testing
        }
    }

    /**
     * Refresh the termbase list from storage.
     */
    public void refreshTermbaseList() {
        loadTermbaseList();
    }

    /**
     * Load terms for the selected termbase.
     */
    public void loadTermbaseTerms() {
        TermbaseConfig newConfig = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        // The 2-second external-change probe can reload a termbase and fire this while the user
        // has rows selected for a delete or edit; rebuilding the table below would silently drop
        // that selection. Remember the selected entries first so the rebuild can put them back.
        // Selection only carries over within the same termbase - switching termbases starts clean.
        boolean sameTermbase = newConfig != null && currentConfig != null
            && newConfig.getFilePath().equals(currentConfig.getFilePath());
        List<TermEntry> selectedBefore = sameTermbase ? captureSelectedEntries() : java.util.Collections.emptyList();

        currentConfig = newConfig;
        if (currentConfig == null) {
            currentTerms = new ArrayList<>();
            tableModel.setRowCount(0);
            return;
        }

        try {
            currentTerms = new ArrayList<>(registry.getTerms(currentConfig));
        } catch (Exception e) {
            currentTerms = new ArrayList<>();
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.failed.load.terms", e.getMessage()),
                I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
        }
        tableModel.setRowCount(0);
        for (TermEntry term : currentTerms) {
            String st = statusDisplay(term.getStatus());
            tableModel.addRow(new Object[]{
                term.getSourceTerm() != null ? term.getSourceTerm() : "",
                term.getTargetTerm() != null ? term.getTargetTerm() : "",
                st
            });
        }
        restoreTableSelection(selectedBefore);
    }

    /** The entries currently selected in the table, resolved through the row sorter (sort/filter safe). */
    private List<TermEntry> captureSelectedEntries() {
        List<TermEntry> out = new ArrayList<>();
        for (int view : termTable.getSelectedRows()) {
            int model = termTable.convertRowIndexToModel(view);
            if (model >= 0 && model < currentTerms.size()) {
                out.add(currentTerms.get(model));
            }
        }
        return out;
    }

    /**
     * Re-select the rows whose (source, target) match the previously selected entries so a
     * background reload does not drop a multi-row selection. Entries the external edit removed
     * are simply not re-selected; a row hidden by the filter maps to view -1 and is skipped.
     */
    private void restoreTableSelection(List<TermEntry> previouslySelected) {
        if (previouslySelected == null || previouslySelected.isEmpty()) {
            return;
        }
        List<TermEntry> pending = new ArrayList<>(previouslySelected);
        termTable.clearSelection();
        for (int model = 0; model < currentTerms.size() && !pending.isEmpty(); model++) {
            TermEntry row = currentTerms.get(model);
            for (int p = 0; p < pending.size(); p++) {
                TermEntry wanted = pending.get(p);
                if (java.util.Objects.equals(row.getSourceTerm(), wanted.getSourceTerm())
                    && java.util.Objects.equals(row.getTargetTerm(), wanted.getTargetTerm())) {
                    int view = termTable.convertRowIndexToView(model);
                    if (view >= 0) {
                        termTable.addRowSelectionInterval(view, view);
                    }
                    pending.remove(p);
                    break;
                }
            }
        }
    }

    /**
     * Reload the selected termbase from disk.
     */
    private void reloadTermbase() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.termbase.please"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        TermbaseConfig captured = config;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                registry.reloadConfig(captured.getFilePath());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadTermbaseTerms();
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        I18N.getString("msg.reload.success", captured.getFileName()),
                        I18N.getString("msg.success"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    String message = getFileLockedMessage(e, captured);
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        I18N.getString("msg.failed.reload", message),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /**
     * Check if a new term's source already exists in the current termbase.
     * Shows a warning dialog if it does and returns false if user cancels.
     */
    private boolean checkDuplicateInCurrentTerms(TermEntry newTerm) {
        if (newTerm == null || newTerm.getSourceTerm() == null || newTerm.getSourceTerm().trim().isEmpty()) {
            return true;
        }
        String newSource = newTerm.getSourceTerm().trim();
        String filePath = currentConfig != null ? currentConfig.getFilePath() : null;
        if (filePath == null) return true;

        List<TermEntry> matches;
        try {
            matches = registry.findTermsBySource(newSource);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.failed.read.termbase", e.getMessage()),
                I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
        for (TermEntry existing : matches) {
            if (filePath.equals(existing.getSourceFilePath())) {
                String newTarget = newTerm.getTargetTerm() != null ? newTerm.getTargetTerm().trim() : "";
                String existingTarget = existing.getTargetTerm() != null ? existing.getTargetTerm().trim() : "";
                String msg;
                if (newTarget.equals(existingTarget)) {
                    msg = I18N.getString("msg.duplicate.same.translation", newSource, existingTarget);
                } else {
                    msg = I18N.getString("msg.duplicate.different.translation", newSource, existingTarget);
                }
                int choice = JOptionPane.showConfirmDialog(this, msg,
                    I18N.getString("msg.duplicate.term"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                return choice == JOptionPane.YES_OPTION;
            }
        }
        return true;
    }

    /**
     * Add a new term to the selected termbase.
     */
    private void addNewTerm() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.termbase.please"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (!checkFileAccess(config)) return;

        TermEntryDialog dialog = new TermEntryDialog(I18N.getString("dlg.add.new.term"), (TermEntry) null);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry newTerm = dialog.getTermEntry();
            if (!checkDuplicateInCurrentTerms(newTerm)) return;
            updateAndReloadAsync(config, terms -> {
                terms.add(newTerm);
                return terms;
            });
        }
    }

    /**
     * Quick add a new term using the current editor selection.
     * Called from the panel's Quick Add button.
     */
    private void quickAddNewTerm() {
        quickAddFromExternalSelection(getEditorSelection());
    }

    /**
     * Quick add a term using externally provided selected text.
     * Callable from outside the panel (e.g. context menu) without
     * reading editor selection or panel state redundantly.
     *
     * @param selectedText text selected in the editor, or null
     */
    public void quickAddFromExternalSelection(String selectedText) {
        quickAddFromExternalSelection(selectedText, (TermbaseConfig) termbaseComboBox.getSelectedItem());
    }

    public void quickAddFromExternalSelection(String selectedText, TermbaseConfig config) {
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.termbase.please"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        if (selectedText == null || selectedText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.no.editor.selection"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        TermEntryDialog dialog = new TermEntryDialog(I18N.getString("dlg.add.new.term"), selectedText);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry newTerm = dialog.getTermEntry();
            if (!checkDuplicateInCurrentTerms(newTerm)) return;
            updateAndReloadAsync(config, terms -> {
                terms.add(newTerm);
                return terms;
            });
        }
    }

    /**
     * Edit a selected term.
     */
    private void editTerm() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.termbase.please"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        int selectedRow = termTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.term.please"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (termTable.getSelectedRowCount() > 1) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.only.one"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        // The table is sorted/filtered, so translate the view row to the model row
        // before indexing the table model or the term list.
        int modelRow = TableRowUtils.toModelRow(termTable, selectedRow);
        if (modelRow < 0) return;

        String sourceTerm = (String) tableModel.getValueAt(modelRow, 0);
        String targetTerm = (String) tableModel.getValueAt(modelRow, 1);

        // The entry being edited, captured before the dialog: the write is applied to the list
        // as it is when it runs, so the entry is located by identity, not by its row.
        TermEntry original = modelRow < currentTerms.size()
            ? currentTerms.get(modelRow) : new TermEntry(sourceTerm, targetTerm);
        // Edit a deep copy so the dialog round-trip never drops extra columns or the TBX id;
        // the dialog mutates this instance and getTermEntry() hands it back.
        TermEntry existingTerm = original.copy();

        TermEntryDialog dialog = new TermEntryDialog(I18N.getString("dlg.edit.term"), existingTerm);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry newTerm = dialog.getTermEntry();
            updateAndReloadAsync(config, terms -> {
                TermEntryUtils.replaceEntryMerging(terms, original, newTerm);
                return terms;
            });
        }
    }

    /**
     * Delete selected terms.
     */
    private void deleteTerms() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.termbase.please"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        int[] selectedRows = termTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.select.term.delete.please"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Confirm deletion
        String confirmMessage = I18N.getString("msg.confirm.delete", selectedRows.length, config.getFileName());
        if (config.getFormat() == TermbaseConfig.Format.TBX) {
            // A TBX entry holds more than the two terms; undo can only bring the terms back.
            confirmMessage += "\n\n" + I18N.getString("msg.confirm.delete.tbx.note");
        }
        int confirm = JOptionPane.showConfirmDialog(this, confirmMessage,
            I18N.getString("msg.confirm.delete.title"), JOptionPane.OK_CANCEL_OPTION);

        if (confirm == JOptionPane.OK_OPTION) {
            try {
                // Identify the selected entries, not their rows: the delete is applied to the
                // list as it is when it runs, which may already contain other changes.
                List<TermEntry> selected = new ArrayList<>();
                for (int modelRow : TableRowUtils.toModelRowsDescending(termTable, selectedRows)) {
                    if (modelRow < currentTerms.size()) {
                        selected.add(currentTerms.get(modelRow));
                    }
                }
                // Filled by the mutator on the registry's thread; read on the EDT only after the
                // update has completed, which is when undo becomes available (a failed delete
                // leaves nothing to undo).
                List<TermEntryUtils.RemovedEntry> removed = new ArrayList<>();
                updateAndReloadAsync(config, terms -> {
                    removed.clear();
                    removed.addAll(TermEntryUtils.removeEntriesRecording(terms, selected));
                    return terms;
                }, () -> {
                    if (removed.isEmpty()) return;   // nothing was deleted; keep any earlier undo
                    undoRemoved = removed;
                    undoConfig = config;
                    undoPairKey = config.langPairKey();
                    undoButton.setEnabled(true);
                });
            } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.failed.delete.terms", e.getMessage()),
                I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void undoDelete() {
        if (undoRemoved == null || undoConfig == null) {
            undoButton.setEnabled(false);
            return;
        }
        if (!undoConfig.langPairKey().equals(undoPairKey)) {
            // The termbase's language pair changed since the delete: the removed entries were
            // read under the old pair and cannot be put back under the new one.
            undoRemoved = null;
            undoConfig = null;
            undoButton.setEnabled(false);
            JOptionPane.showMessageDialog(this, I18N.getString("msg.undo.langs.changed"),
                I18N.getString("btn.undo"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        TermbaseConfig config = undoConfig;
        List<TermEntryUtils.RemovedEntry> removed = undoRemoved;
        undoButton.setEnabled(false);   // no second click while this one is running

        // Applied to the list as it is now (re-read from disk if the file changed), so terms
        // added or edited since the delete, here or in another program, are left alone.
        registry.updateTermsAsync(config, terms -> {
            TermEntryUtils.reinsertRemoved(terms, removed);
            return terms;
        }).whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (error == null) {
                if (undoRemoved == removed) {   // not replaced by a newer delete meanwhile
                    undoRemoved = null;
                    undoConfig = null;
                }
                // If the current combo selection matches the termbase, reload the display
                if (termbaseComboBox.getSelectedItem() != null
                        && ((TermbaseConfig) termbaseComboBox.getSelectedItem()).getFilePath()
                            .equals(config.getFilePath())) {
                    loadTermbaseTerms();
                }
                JOptionPane.showMessageDialog(TerminologyPanel.this,
                    I18N.getString("msg.delete.undone"), I18N.getString("btn.undo"), JOptionPane.INFORMATION_MESSAGE);
            } else {
                // Keep the undo data so the user can try again (file locked, say).
                undoButton.setEnabled(undoRemoved == removed);
                JOptionPane.showMessageDialog(TerminologyPanel.this,
                    getFileLockedMessage(error, config), I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    /**
     * Select a term in the terminology panel for editing.
     * Called from TermbaseSearchPanel on double-click.
     */
    public void selectTerm(String filePath, String sourceTerm, String targetTerm) {
        // Find matching termbase in combo
        for (int i = 0; i < termbaseComboBox.getItemCount(); i++) {
            if (termbaseComboBox.getItemAt(i).getFilePath().equals(filePath)) {
                termbaseComboBox.setSelectedIndex(i);
                break;
            }
        }
        // Wait for combo to load terms, then find and select the row
        SwingUtilities.invokeLater(() -> {
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                String s = (String) tableModel.getValueAt(row, 0);
                String t = (String) tableModel.getValueAt(row, 1);
                if (sourceTerm.equals(s) && (targetTerm == null || targetTerm.equals(t))) {
                    int viewRow = termTable.convertRowIndexToView(row);
                    if (viewRow >= 0) {
                        termTable.setRowSelectionInterval(viewRow, viewRow);
                        termTable.scrollRectToVisible(termTable.getCellRect(viewRow, 0, true));
                    }
                    break;
                }
            }
        });
    }

    private boolean checkFileAccess(TermbaseConfig config) {
        FileAccessUtils.Result result = FileAccessUtils.probeWritable(config.getFilePath());
        switch (result.status) {
            case OK:
                return true;
            case LOCKED:
                JOptionPane.showMessageDialog(this,
                    I18N.getString("msg.file.locked"),
                    I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                return false;
            default:
                JOptionPane.showMessageDialog(this,
                    I18N.getString("msg.cannot.access.file", result.detail),
                    I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                return false;
        }
    }

    /**
     * Apply a change to the termbase's latest contents on the registry's writer thread, then
     * refresh the table. The change is described by {@code mutator} instead of passing a full
     * list taken earlier, so it is applied on top of any change queued before it rather than
     * overwriting it.
     */
    private void updateAndReloadAsync(TermbaseConfig config, UnaryOperator<List<TermEntry>> mutator) {
        updateAndReloadAsync(config, mutator, null);
    }

    /** As above; {@code onSuccess} (may be null) runs on the EDT after a successful update. */
    private void updateAndReloadAsync(TermbaseConfig config, UnaryOperator<List<TermEntry>> mutator,
                                      Runnable onSuccess) {
        registry.updateTermsAsync(config, mutator).whenComplete((ignored, error) ->
            SwingUtilities.invokeLater(() -> {
                if (error == null) {
                    if (onSuccess != null) onSuccess.run();
                    loadTermbaseTerms();
                } else {
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        getFileLockedMessage(error, config), I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                }
            }));
    }

    private void reloadAsync(TermbaseConfig config) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                registry.reloadConfig(config.getFilePath());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadTermbaseTerms();
                } catch (Exception e) {
                    String message = getFileLockedMessage(e, config);
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        I18N.getString("msg.failed.reload", message),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private String getFileLockedMessage(Throwable ex, TermbaseConfig config) {
        if (FileAccessUtils.isLockFailure(ex, config.getFilePath())) {
            return I18N.getString(config.getFormat() == TermbaseConfig.Format.XLSX
                ? "msg.file.locked.xlsx" : "msg.file.locked");
        }
        String msg = ex.getMessage();
        return I18N.getString("msg.failed.save.termbase.generic", msg != null ? msg : "Unknown error");
    }

    /**
     * Get the current editor selection from Oxygen.
     *
     * @return the selected text, or null if no selection
     */
    private String getEditorSelection() {
        try {
            PluginWorkspace ws = PluginWorkspaceProvider.getPluginWorkspace();
            if (ws == null) return null;
            WSEditor editor = ws.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return null;
            WSEditorPage page = editor.getCurrentPage();
            if (page == null) return null;
            if (page instanceof WSAuthorEditorPage) {
                return ((WSAuthorEditorPage) page).getSelectedText();
            } else if (page instanceof WSTextEditorPage) {
                return ((WSTextEditorPage) page).getSelectedText();
            }
        } catch (Exception e) {
            // Silently fall through
        }
        return null;
    }

    /** Display text for a term status in the terminology table. */
    private static String statusDisplay(TermStatus status) {
        if (status == null) return "";
        switch (status) {
            case PREFERRED: return I18N.getString("status.preferred");
            case ADMITTED:  return I18N.getString("status.admitted");
            case DEPRECATED: return I18N.getString("status.deprecated");
            default: return "";
        }
    }

    /**
     * Step 4.1: run the termbase quality check on a background thread and display
     * results in a dialog table with CSV export.
     */
    private void runTermbaseCheck() {
        List<TermbaseConfig> enabledConfigs = registry.getEnabledConfigs();
        if (enabledConfigs.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.no.enabled.termbases"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Gather terms on a worker to avoid blocking the EDT with file I/O.
        new SwingWorker<List<Issue>, Void>() {
            @Override
            protected List<Issue> doInBackground() {
                List<Issue> allIssues = new ArrayList<>();
                Map<String, List<TermEntry>> allTerms = new LinkedHashMap<>();
                for (TermbaseConfig config : enabledConfigs) {
                    try {
                        List<TermEntry> terms = registry.getTerms(config);
                        allTerms.put(config.getFilePath(), terms);
                        allIssues.addAll(TermbaseChecker.check(terms, config.getFilePath()));
                    } catch (Exception e) {
                        // Skip termbases that fail to load
                    }
                }
                // Cross-termbase check
                if (allTerms.size() > 1) {
                    allIssues.addAll(TermbaseChecker.checkCrossTermbase(allTerms));
                }
                return allIssues;
            }

            @Override
            protected void done() {
                try {
                    List<Issue> issues = get();
                    showCheckResults(issues);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        I18N.getString("msg.check.failed", e.getMessage()),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void showCheckResults(List<Issue> issues) {
        if (issues.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.check.no.issues"),
                I18N.getString("msg.info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        String[] cols = { I18N.getString("check.col.severity"), I18N.getString("check.col.rule"),
            I18N.getString("check.col.message"), I18N.getString("check.col.termbase") };
        DefaultTableModel model = new DefaultTableModel(cols, issues.size()) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        for (int i = 0; i < issues.size(); i++) {
            Issue iss = issues.get(i);
            model.setValueAt(severityText(iss.severity), i, 0);
            model.setValueAt(iss.rule, i, 1);
            model.setValueAt(messageText(iss), i, 2);
            model.setValueAt(iss.termbasePath != null ? iss.termbasePath : "", i, 3);
        }

        JTable table = new JTable(model);
        // Show the full text on hover when a cell is clipped, and let the long Message and
        // Termbase columns scroll horizontally instead of being squeezed to unreadable widths
        // by the default all-columns auto-resize (defect H sibling, quality-check results table).
        javax.swing.table.TableCellRenderer tipRenderer = new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String text = value == null ? "" : value.toString();
                int colWidth = table.getColumnModel().getColumn(column).getWidth();
                boolean clipped = getFontMetrics(getFont()).stringWidth(text) > colWidth;
                setToolTipText(clipped ? text : null);
                return this;
            }
        };
        table.setDefaultRenderer(Object.class, tipRenderer);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(430);
        table.getColumnModel().getColumn(3).setPreferredWidth(360);
        // Dragging the header used to reorder these columns; keep the fixed column order (sibling of H).
        table.getTableHeader().setReorderingAllowed(false);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(700, 400));

        JButton exportBtn = new JButton(I18N.getString("btn.check.export"));
        exportBtn.addActionListener(e -> exportCheckCsv(issues));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scroll, BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JLabel summary = new JLabel(I18N.getString("msg.check.summary", issues.size()));
        south.add(summary);
        south.add(exportBtn);
        panel.add(south, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(this, panel,
            I18N.getString("msg.check.title"), JOptionPane.PLAIN_MESSAGE);
    }

    /** Translated severity label (check.severity.warning / .error); the raw name if untranslated. */
    private static String severityText(Severity severity) {
        String key = "check.severity." + severity.name().toLowerCase(java.util.Locale.ROOT);
        String text = I18N.getString(key);
        return text.equals("[" + key + "]") ? severity.name() : text;
    }

    /** Localized issue message, falling back to the checker's locale-independent English text. */
    private static String messageText(Issue iss) {
        String text = I18N.getString(iss.messageKey, iss.messageArgs);
        return text.equals("[" + iss.messageKey + "]") ? iss.message : text;
    }

    private void exportCheckCsv(List<Issue> issues) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18N.getString("btn.check.export"));
        fc.setSelectedFile(new java.io.File("termbase_check.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (java.io.Writer w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(fc.getSelectedFile()), java.nio.charset.StandardCharsets.UTF_8)) {
            w.write('\uFEFF'); // BOM for Excel
            w.write(String.join(",",
                csvField(I18N.getString("check.col.severity")), csvField(I18N.getString("check.col.rule")),
                csvField(I18N.getString("check.col.message")), csvField(I18N.getString("check.col.termbase")))
                + "\r\n");
            for (Issue iss : issues) {
                w.write(csvField(severityText(iss.severity)) + "," + csvField(iss.rule) + ","
                    + csvField(messageText(iss)) + "," + csvField(iss.termbasePath != null ? iss.termbasePath : "") + "\r\n");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(),
                I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String csvField(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
