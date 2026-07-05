package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.util.IconUtils;

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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
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

    // Undo support: one-step snapshot for delete
    private List<TermEntry> undoSnapshot;
    private TermbaseConfig undoConfig;

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
        JLabel headerLabel = new JLabel("Manage terms in selected termbase.");
        headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        headerWrap.add(headerLabel, BorderLayout.CENTER);
        northPanel.add(headerWrap);
        northPanel.add(Box.createVerticalStrut(8));

        JPanel selectionPanel = new JPanel();
        selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.X_AXIS));
        selectionPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 0));
        JLabel selectLabel = new JLabel("Select Termbase:");
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
        filterField.putClientProperty("JTextField.placeholderText", "Filter terms...");
        filterPanel.add(filterField, BorderLayout.CENTER);
        northPanel.add(filterPanel);

        add(northPanel, BorderLayout.NORTH);

        // Create term table with in-place editing backed by TermEntry list
        tableModel = new DefaultTableModel(
            new String[]{"Source Term", "Target Term"}, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }

            @Override
            public void setValueAt(Object value, int row, int column) {
                super.setValueAt(value, row, column);
                if (currentConfig == null || row >= currentTerms.size()) return;
                TermEntry entry = currentTerms.get(row);
                if (column == 0) {
                    entry.setSourceTerm((String) value);
                } else if (column == 1) {
                    entry.setTargetTerm((String) value);
                }
                registry.saveTerms(currentConfig, currentTerms);
            }
        };
        termTable = new JTable(tableModel);
        termTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Set up sorting with Chinese-aware collation
        tableSorter = new TableRowSorter<>(tableModel);
        Collator chineseCollator = Collator.getInstance(Locale.CHINESE);
        tableSorter.setComparator(0, (a, b) -> chineseCollator.compare((String) a, (String) b));
        tableSorter.setComparator(1, (a, b) -> chineseCollator.compare((String) a, (String) b));
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

        // Create button panel with icon buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 5));

        JButton reloadButton = new JButton(IconUtils.loadIcon("reload", 16));
        reloadButton.setToolTipText("Reload termbase from disk");
        reloadButton.setPreferredSize(new Dimension(28, 28));
        reloadButton.addActionListener(e -> reloadTermbase());
        buttonPanel.add(reloadButton);

        JButton addButton = new JButton(IconUtils.loadIcon("add", 16));
        addButton.setToolTipText("Add new term");
        addButton.setPreferredSize(new Dimension(24, 24));
        addButton.addActionListener(e -> addNewTerm());
        buttonPanel.add(addButton);

        JButton quickAddButton = new JButton(IconUtils.loadIcon("quick_add", 16));
        quickAddButton.setToolTipText("Quick add term from editor selection");
        quickAddButton.setPreferredSize(new Dimension(24, 24));
        quickAddButton.addActionListener(e -> quickAddNewTerm());
        buttonPanel.add(quickAddButton);

        JButton editButton = new JButton(IconUtils.loadIcon("edit", 16));
        editButton.setToolTipText("Edit selected term");
        editButton.setPreferredSize(new Dimension(24, 24));
        editButton.addActionListener(e -> editTerm());
        buttonPanel.add(editButton);

        JButton deleteButton = new JButton(IconUtils.loadIcon("delete", 16));
        deleteButton.setToolTipText("Delete selected term(s)");
        deleteButton.setPreferredSize(new Dimension(24, 24));
        deleteButton.addActionListener(e -> deleteTerms());
        buttonPanel.add(deleteButton);

        undoButton = new JButton("Undo");
        undoButton.setToolTipText("Undo last delete");
        undoButton.setEnabled(false);
        undoButton.addActionListener(e -> undoDelete());
        buttonPanel.add(undoButton);

        JButton resetSortButton = new JButton("Reset Sort");
        resetSortButton.setToolTipText("Restore original row order");
        resetSortButton.addActionListener(e -> {
            tableSorter.setSortKeys(null);
            tableSorter.setRowFilter(null);
            filterField.setText("");
        });
        buttonPanel.add(resetSortButton);

        add(buttonPanel, BorderLayout.SOUTH);

        // Load enabled termbases
        loadTermbaseList();
    }

    private void addTableContextMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem editItem = new JMenuItem("Edit Term");
        editItem.addActionListener(e -> editTerm());
        popup.add(editItem);

        JMenuItem deleteItem = new JMenuItem("Delete Term");
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
                if (row >= 0) {
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
        registry.loadConfigs();
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
    private void loadTermbaseTerms() {
        currentConfig = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (currentConfig == null) {
            currentTerms = new ArrayList<>();
            tableModel.setRowCount(0);
            return;
        }

        currentTerms = new ArrayList<>(registry.getTerms(currentConfig));
        tableModel.setRowCount(0);
        for (TermEntry term : currentTerms) {
            tableModel.addRow(new Object[]{
                term.getSourceTerm() != null ? term.getSourceTerm() : "",
                term.getTargetTerm() != null ? term.getTargetTerm() : ""
            });
        }
    }

    /**
     * Reload the selected termbase from disk.
     */
    private void reloadTermbase() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                "Please select a termbase.",
                "Warning", JOptionPane.WARNING_MESSAGE);
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
                        "Termbase " + captured.getFileName() + " reloaded.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    String message = getFileLockedMessage(e);
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        "Failed to reload: " + message,
                        "Error", JOptionPane.ERROR_MESSAGE);
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
        List<TermEntry> terms = registry.getTerms(currentConfig);
        for (TermEntry existing : terms) {
            if (existing.getSourceTerm() != null && existing.getSourceTerm().trim().equals(newSource)) {
                String newTarget = newTerm.getTargetTerm() != null ? newTerm.getTargetTerm().trim() : "";
                String existingTarget = existing.getTargetTerm() != null ? existing.getTargetTerm().trim() : "";
                String msg;
                if (newTarget.equals(existingTarget)) {
                    msg = "Source term \"" + newSource + "\" already exists\n"
                        + "with the same translation \"" + existingTarget + "\".\n\n"
                        + "Add it anyway?";
                } else {
                    msg = "Source term \"" + newSource + "\" already exists\n"
                        + "with a different translation \"" + existingTarget + "\".\n\n"
                        + "Add it anyway?";
                }
                int choice = JOptionPane.showConfirmDialog(this, msg,
                    "Duplicate Term", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
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
                "Please select a termbase.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        TermEntryDialog dialog = new TermEntryDialog("Add New Term", (TermEntry) null);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry newTerm = dialog.getTermEntry();
            if (!checkDuplicateInCurrentTerms(newTerm)) return;
            List<TermEntry> terms = registry.getTerms(config);
            terms.add(newTerm);
            saveAndReloadAsync(config, terms);
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
                "Please select a termbase.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        if (selectedText == null || selectedText.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No text selected in editor.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        TermEntryDialog dialog = new TermEntryDialog("Add New Term", selectedText);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry newTerm = dialog.getTermEntry();
            if (!checkDuplicateInCurrentTerms(newTerm)) return;
            List<TermEntry> terms = registry.getTerms(config);
            terms.add(newTerm);
            saveAndReloadAsync(config, terms);
        }
    }

    /**
     * Edit a selected term.
     */
    private void editTerm() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                "Please select a termbase.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        int selectedRow = termTable.getSelectedRow();
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this,
                "Please select a term to edit.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (termTable.getSelectedRowCount() > 1) {
            JOptionPane.showMessageDialog(this,
                "Please select only one term to edit.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String sourceTerm = (String) tableModel.getValueAt(selectedRow, 0);
        String targetTerm = (String) tableModel.getValueAt(selectedRow, 1);

        TermEntry existingTerm = new TermEntry(sourceTerm, targetTerm);

        TermEntryDialog dialog = new TermEntryDialog("Edit Term", existingTerm);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry newTerm = dialog.getTermEntry();
            List<TermEntry> terms = registry.getTerms(config);
            terms.set(selectedRow, newTerm);
            saveAndReloadAsync(config, terms);
        }
    }

    /**
     * Delete selected terms.
     */
    private void deleteTerms() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                "Please select a termbase.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        int[] selectedRows = termTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                "Please select term(s) to delete.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Confirm deletion
        int confirm = JOptionPane.showConfirmDialog(this,
            String.format("Delete %d term(s) from %s?", selectedRows.length, config.getFileName()),
            "Confirm Delete", JOptionPane.OK_CANCEL_OPTION);

        if (confirm == JOptionPane.OK_OPTION) {
            List<TermEntry> terms = registry.getTerms(config);
            // Save undo snapshot before modifying
            undoSnapshot = new ArrayList<>(terms);
            undoConfig = config;
            undoButton.setEnabled(true);

            // Delete in reverse order to maintain indices
            for (int i = selectedRows.length - 1; i >= 0; i--) {
                int modelRow = termTable.convertRowIndexToModel(selectedRows[i]);
                if (modelRow >= 0 && modelRow < terms.size()) {
                    terms.remove(modelRow);
                }
            }
            saveAndReloadAsync(config, terms);
        }
    }

    private void undoDelete() {
        if (undoSnapshot == null || undoConfig == null) {
            undoButton.setEnabled(false);
            return;
        }
        TermbaseConfig config = undoConfig;
        List<TermEntry> snapshot = undoSnapshot;
        undoSnapshot = null;
        undoConfig = null;
        undoButton.setEnabled(false);

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                registry.saveTerms(config, snapshot);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    // If the current combo selection matches undoConfig, reload display
                    if (termbaseComboBox.getSelectedItem() != null
                            && ((TermbaseConfig) termbaseComboBox.getSelectedItem()).getFilePath()
                                .equals(config.getFilePath())) {
                        loadTermbaseTerms();
                    }
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        "Delete undone.", "Undo", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    String message = getFileLockedMessage(e);
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        message, "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
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
        File file = new File(config.getFilePath());
        if (!file.exists()) return true;
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            return true;
        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("being used by another process")) {
                JOptionPane.showMessageDialog(this,
                    "The termbase file is currently open in another application.\n" +
                    "Please close the file and try again.",
                    "Error", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                    "Cannot access file: " + msg,
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
            return false;
        }
    }

    private void saveAndReloadAsync(TermbaseConfig config, List<TermEntry> terms) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                registry.saveTerms(config, terms);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    loadTermbaseTerms();
                } catch (Exception e) {
                    String message = getFileLockedMessage(e);
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        message, "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
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
                    String message = getFileLockedMessage(e);
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        "Failed to reload: " + message,
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void safeSaveTerms(TermbaseConfig config, List<TermEntry> terms) {
        try {
            registry.saveTerms(config, terms);
        } catch (Exception ex) {
            String message = getFileLockedMessage(ex);
            JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String getFileLockedMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg != null && msg.toLowerCase().contains("being used by another process")) {
            return "The XLSX termbase file is currently open in another application (e.g., Excel).\n" +
                   "Please close the file and try again.";
        }
        return "Failed to save termbase: " + (msg != null ? msg : "Unknown error");
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
}
