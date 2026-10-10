package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermStatus;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.service.TermbaseConverter;
import com.example.termmgmt.service.HttpTransport;
import com.example.termmgmt.service.OpenAiHttpTransport;
import com.example.termmgmt.service.SuggestionRun;
import com.example.termmgmt.service.TranslationSuggester;
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
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.io.File;

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

        // Button bar in two rows. A plain FlowLayout always reports a single-row
        // preferred height whatever the available width, so when the panel is
        // narrower than the row (a docked sidebar) the trailing buttons are clipped
        // and unreachable. WrapPanel keeps FlowLayout's centering but recomputes its
        // preferred height for however many rows the current width actually needs,
        // so the bar grows vertically and every button stays reachable at any width.
        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
        JPanel iconRow = new WrapPanel(8, 4);
        JPanel textRow = new WrapPanel(8, 4);

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

        JButton exportButton = new JButton(I18N.getString("btn.export"));
        exportButton.setToolTipText(I18N.getString("btn.export.tooltip"));
        exportButton.addActionListener(e -> showExportDialog());
        textRow.add(exportButton);

        JButton suggestButton = new JButton(I18N.getString("btn.suggest.translation"));
        suggestButton.setToolTipText(I18N.getString("btn.suggest.translation.tooltip"));
        suggestButton.addActionListener(e -> suggestTranslations());
        textRow.add(suggestButton);

        // The text buttons ate too much room in the narrow sidebar. Shrink their font a
        // step below the platform default and trim the padding between the label and the
        // border on all four sides, so each button is visibly more compact while the text
        // still breathes; the full description stays in each button's tooltip.
        Font baseFont = UIManager.getFont("Button.font");
        if (baseFont == null) baseFont = textRow.getFont();
        Font compactFont = baseFont.deriveFont(Math.max(10f, baseFont.getSize2D() - 1.5f));
        for (Component c : textRow.getComponents()) {
            if (c instanceof JButton) {
                ((JButton) c).setFont(compactFont);
                ((JButton) c).setMargin(new Insets(1, 4, 1, 4));
            }
        }

        buttonPanel.add(iconRow);
        buttonPanel.add(textRow);

        add(buttonPanel, BorderLayout.SOUTH);

        // Load enabled termbases
        loadTermbaseList();
    }

    /**
     * A centered flow row whose preferred height reflects how many rows the current
     * width actually needs. {@link FlowLayout} reports a single-row preferred height
     * regardless of width, so in a narrow docked panel its trailing components are
     * clipped by {@code BorderLayout.SOUTH}. This subclass mirrors FlowLayout's own
     * wrapping algorithm to return a multi-row preferred height once the width is
     * known, letting the button bar grow vertically instead of losing buttons.
     */
    private static final class WrapPanel extends JPanel {
        private final int hgap;
        private final int vgap;
        private int lastWidth = -1;

        WrapPanel(int hgap, int vgap) {
            super(new FlowLayout(FlowLayout.CENTER, hgap, vgap));
            this.hgap = hgap;
            this.vgap = vgap;
            // getPreferredSize can only size the wrapped rows once the real width is
            // known, but on the very first pass the width is still 0 (single-row
            // fallback). Revalidate when the width settles so the parent hands back
            // the extra height the wrap needs instead of clipping the last row.
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (getWidth() != lastWidth) {
                        lastWidth = getWidth();
                        revalidate();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension single = getLayout().preferredLayoutSize(this);
            int width = getWidth();
            if (width <= 0) {
                return single;   // not laid out yet: fall back to one row
            }
            Insets ins = getInsets();
            int maxWidth = width - ins.left - ins.right;
            int x = 0;
            int rowHeight = 0;
            int totalHeight = 0;
            for (Component c : getComponents()) {
                if (!c.isVisible()) continue;
                Dimension d = c.getPreferredSize();
                if (x > 0 && x + d.width > maxWidth) {
                    totalHeight += rowHeight + vgap;
                    x = 0;
                    rowHeight = 0;
                }
                x += d.width + hgap;
                rowHeight = Math.max(rowHeight, d.height);
            }
            totalHeight += rowHeight;
            // FlowLayout pads the first row's top and the last row's bottom with a vgap
            // too; without this the bottom row is clipped (the reported "obscured" bar).
            return new Dimension(width, totalHeight + 2 * vgap + ins.top + ins.bottom);
        }
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
        // CompletableFuture wraps the failure in a CompletionException whose message is just
        // the outer RuntimeException's toString ("java.lang.RuntimeException: Failed to save
        // termbase: <path>"), hiding the real reason. Walk to the deepest cause that carries a
        // message so the dialog names what actually went wrong.
        String msg = rootCauseMessage(ex);
        return I18N.getString("msg.failed.save.termbase.generic", msg != null ? msg : "Unknown error");
    }

    /** The message of the last throwable in the cause chain that has one, or null. */
    private static String rootCauseMessage(Throwable ex) {
        String best = null;
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.isBlank()) best = m;
            if (t.getCause() == t) break;
        }
        return best;
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

    // ------------------------------------------------------------------ D3: AI translation suggestions

    /** A source term paired with the AI-suggested translation for the review dialog. */
    private record PendingSuggestion(TermEntry entry, String suggestion) {}

    /**
     * Cap on how many blank entries one suggestion run may request: requests are serial,
     * so an unbounded run against a large termbase would hammer the endpoint (and the user
     * would wait far too long for the review dialog to appear).
     */
    static final int MAX_SUGGESTION_REQUESTS = 200;

    /** Reads the AI-translation configuration persisted by the preference page. */
    private TranslationSuggester.Config loadAiConfig() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return new TranslationSuggester.Config(null, null, null, false);
            WSOptionsStorage os = w.getOptionsStorage();
            final String prefix = "com.example.termmgmt.ai-translate.";
            boolean enabled = Boolean.parseBoolean(os.getOption(prefix + "enabled", "false"));
            String apiUrl = os.getOption(prefix + "api-url", "");
            String model = os.getOption(prefix + "model", "");
            String apiKey = os.getOption(prefix + "api-key", "");
            return new TranslationSuggester.Config(apiUrl, model, apiKey, enabled);
        } catch (Exception e) {
            return new TranslationSuggester.Config(null, null, null, false);
        }
    }

    private void suggestTranslations() {
        if (currentConfig == null) {
            JOptionPane.showMessageDialog(this, I18N.getString("prefs.select.langs"),
                I18N.getString("msg.error"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        TranslationSuggester.Config aiConfig = loadAiConfig();
        if (!aiConfig.enabled()) {
            JOptionPane.showMessageDialog(this, I18N.getString("btn.suggest.translation.disabled"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!aiConfig.isValid()) {
            JOptionPane.showMessageDialog(this, I18N.getString("btn.suggest.translation.notconfigured"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Only entries whose target translation is blank are candidates.
        List<TermEntry> blanks = new ArrayList<>();
        String sourceLang = currentConfig.getSelectedSourceLang() != null
            ? currentConfig.getSelectedSourceLang() : currentConfig.getSourceLang();
        String targetLang = currentConfig.getSelectedTargetLang() != null
            ? currentConfig.getSelectedTargetLang() : currentConfig.getTargetLang();
        for (TermEntry t : currentTerms) {
            String target = t.getTargetTerm();
            String source = t.getSourceTerm();
            if ((target == null || target.trim().isEmpty()) && source != null && !source.isBlank()) {
                blanks.add(t);
            }
        }
        if (blanks.isEmpty()) {
            JOptionPane.showMessageDialog(this, I18N.getString("btn.suggest.translation.noneblank"),
                I18N.getString("msg.info"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        // Serial requests need a bound; ask before trimming the tail.
        if (blanks.size() > MAX_SUGGESTION_REQUESTS) {
            int proceed = JOptionPane.showConfirmDialog(this,
                I18N.getString("btn.suggest.translation.limit",
                    blanks.size(), MAX_SUGGESTION_REQUESTS, currentConfig.getFileName()),
                I18N.getString("btn.suggest.translation"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (proceed != JOptionPane.OK_OPTION) return;
            blanks = new ArrayList<>(blanks.subList(0, MAX_SUGGESTION_REQUESTS));
        }

        final TermbaseConfig configSnapshot = currentConfig;
        final List<TermEntry> candidates = new ArrayList<>(blanks);
        final String src = sourceLang;
        final String tgt = targetLang;
        final HttpTransport transport = new OpenAiHttpTransport();
        final List<String> sourceTerms = new ArrayList<>();
        for (TermEntry c : candidates) {
            sourceTerms.add(c.getSourceTerm());
        }
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

        JProgressBar progressBar = new JProgressBar(0, candidates.size());
        progressBar.setStringPainted(true);
        JButton cancelBtn = new JButton(I18N.getString("btn.cancel"));
        JPanel progressPanel = new JPanel(new BorderLayout(8, 8));
        progressPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        progressPanel.add(new JLabel(I18N.getString("btn.suggest.translation.dialog.progress")),
            BorderLayout.NORTH);
        progressPanel.add(progressBar, BorderLayout.CENTER);
        JPanel southRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        southRow.add(cancelBtn);
        progressPanel.add(southRow, BorderLayout.SOUTH);
        final JDialog progressDialog = new JDialog(SwingUtilities.getWindowAncestor(this),
            I18N.getString("btn.suggest.translation"),
            Dialog.ModalityType.MODELESS);
        progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        progressDialog.add(progressPanel);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);

        final SwingWorker<SuggestionRun.Outcome, Void> worker = new SwingWorker<>() {
            @Override
            protected SuggestionRun.Outcome doInBackground() {
                return SuggestionRun.run(sourceTerms, src, tgt, aiConfig, transport,
                    () -> cancelled.get() || isCancelled(),
                    (done, total, current) -> SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(done);
                        progressBar.setString(I18N.getString("btn.suggest.translation.progress",
                            done, total, current));
                    }));
            }

            @Override
            protected void done() {
                progressDialog.setVisible(false);
                progressDialog.dispose();
                if (cancelled.get()) {
                    return;   // the run was abandoned: nothing to review or save
                }
                SuggestionRun.Outcome outcome;
                try {
                    outcome = get();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        I18N.getString("btn.suggest.translation.error", cause.getMessage()),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }
                List<PendingSuggestion> suggestions = new ArrayList<>();
                outcome.translations().forEach((index, text) ->
                    suggestions.add(new PendingSuggestion(candidates.get(index), text)));
                if (suggestions.isEmpty()) {
                    if (outcome.firstError() != null) {
                        // Every request failed: say why instead of "no suggestions were returned".
                        JOptionPane.showMessageDialog(TerminologyPanel.this,
                            I18N.getString("btn.suggest.translation.error", outcome.firstError()),
                            I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(TerminologyPanel.this,
                            I18N.getString("btn.suggest.translation.empty"),
                            I18N.getString("msg.info"), JOptionPane.INFORMATION_MESSAGE);
                    }
                    return;
                }
                reviewSuggestions(configSnapshot, suggestions, omissionNotice(outcome, candidates));
            }
        };
        // Cancel closes the dialog at once and interrupts the worker: the request in flight is
        // abandoned (no more tokens spent) instead of being waited out for up to its timeout.
        final Runnable cancelRun = () -> {
            if (!cancelled.compareAndSet(false, true)) return;
            cancelBtn.setEnabled(false);
            progressDialog.setVisible(false);
            progressDialog.dispose();
            worker.cancel(true);
        };
        cancelBtn.addActionListener(e -> cancelRun.run());
        progressDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancelRun.run();
            }
        });
        worker.execute();
        // Show only after execute() so done() can never run against an unassigned field.
        progressDialog.setVisible(true);
    }

    /** How many source terms are named in the "got no suggestion" notice before it says "...". */
    private static final int MAX_OMITTED_TERMS_SHOWN = 5;

    /**
     * Names the terms that stay blank and why, so a suggestion that never came back is visible
     * instead of silently missing from the list. Null when every term got a suggestion.
     */
    private static String omissionNotice(SuggestionRun.Outcome outcome, List<TermEntry> candidates) {
        if (outcome.omitted() == 0) {
            return null;
        }
        StringBuilder notice = new StringBuilder();
        if (!outcome.failures().isEmpty()) {
            StringBuilder details = new StringBuilder();
            int shown = 0;
            for (Map.Entry<Integer, String> f : outcome.failures().entrySet()) {
                if (shown == MAX_OMITTED_TERMS_SHOWN) {
                    details.append("; ...");
                    break;
                }
                if (shown++ > 0) details.append("; ");
                details.append(candidates.get(f.getKey()).getSourceTerm()).append(" (")
                    .append(f.getValue()).append(')');
            }
            notice.append(I18N.getString("btn.suggest.translation.omitted",
                outcome.failures().size(), outcome.total(), details.toString()));
        }
        if (outcome.unprocessed() > 0) {
            if (notice.length() > 0) notice.append("\n");
            notice.append(I18N.getString("btn.suggest.translation.aborted",
                outcome.firstError() != null ? outcome.firstError() : "-", outcome.unprocessed()));
        }
        return notice.toString();
    }

    /** Show a checkbox list of suggestions and persist the accepted ones. */
    private void reviewSuggestions(TermbaseConfig config, List<PendingSuggestion> suggestions,
                                   String notice) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        List<JCheckBox> boxes = new ArrayList<>();
        for (PendingSuggestion s : suggestions) {
            JCheckBox cb = new JCheckBox(
                (s.entry().getSourceTerm() != null ? s.entry().getSourceTerm() : "")
                    + "  \u2192  " + s.suggestion(), true);
            boxes.add(cb);
            panel.add(cb);
        }
        JScrollPane sp = new JScrollPane(panel);
        sp.setPreferredSize(new Dimension(420, Math.min(360, 28 * suggestions.size() + 16)));

        JPanel content = new JPanel(new BorderLayout(0, 8));
        if (notice != null) {
            JTextArea note = new JTextArea(notice, 0, 40);
            note.setEditable(false);
            note.setLineWrap(true);
            note.setWrapStyleWord(true);
            note.setOpaque(false);
            content.add(note, BorderLayout.NORTH);
        }
        content.add(sp, BorderLayout.CENTER);
        int choice = JOptionPane.showConfirmDialog(this, content,
            I18N.getString("btn.suggest.translation.review"),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        // Collect accepted (original entry -> new target). Apply once, off the shared UI list.
        List<PendingSuggestion> accepted = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).isSelected()) accepted.add(suggestions.get(i));
        }
        if (accepted.isEmpty()) return;

        registry.updateTermsAsync(config, terms -> {
            for (PendingSuggestion s : accepted) {
                TermEntry edited = s.entry().copy();
                edited.setTargetTerm(s.suggestion());
                TermEntryUtils.replaceEntryMerging(terms, s.entry(), edited);
            }
            return terms;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                SwingUtilities.invokeLater(() -> {
                    // Same handling as an inline edit: name the real reason (and tell the user
                    // to close Excel when the file is locked), then reload so the table shows
                    // what is actually stored, since nothing was written.
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        getFileLockedMessage(error, config),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                    loadTermbaseTerms();
                });
            } else {
                SwingUtilities.invokeLater(this::loadTermbaseTerms);
            }
        });
    }

    // ------------------------------------------------------------------ D1: Export dialog

    private void showExportDialog() {
        if (currentConfig == null) {
            JOptionPane.showMessageDialog(this, I18N.getString("prefs.select.langs"),
                I18N.getString("msg.error"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Format selector
        String[] formats = {"CSV", "XLSX", "TBX", "DITA Glossary"};
        String chosen = (String) JOptionPane.showInputDialog(this,
            I18N.getString("btn.export.format"),
            I18N.getString("btn.export.title"),
            JOptionPane.PLAIN_MESSAGE, null, formats, formats[0]);
        if (chosen == null) return;

        // File chooser
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18N.getString("btn.export.title"));
        String ext = switch (chosen) {
            case "CSV" -> ".csv";
            case "XLSX" -> ".xlsx";
            case "DITA Glossary" -> ".dita";
            default -> ".tbx";
        };
        String base = currentConfig.getFileName().replaceAll("\\.[^.]+$", "");
        // A same-format export would otherwise default straight onto the source file, one
        // "Yes" click away from destroying the termbase; aim beside it instead.
        String suggested = ext.equals(sourceExtension()) ? base + "_export" + ext : base + ext;
        fc.setSelectedFile(new File(suggested));
        int result = fc.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) return;

        File targetFile = fc.getSelectedFile();
        // Ensure correct extension
        if (!targetFile.getName().toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
            targetFile = new File(targetFile.getAbsolutePath() + ext);
        }
        // Never export onto a termbase the workspace knows: the export regenerates whole
        // files, so landing on a registered termbase (source included) would destroy it.
        if (isRegisteredTermbaseFile(targetFile)) {
            JOptionPane.showMessageDialog(this,
                I18N.getString("btn.export.error.target_is_registered",
                    targetFile.getAbsolutePath()),
                I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        // Overwrite confirmation
        if (targetFile.exists()) {
            int overwrite = JOptionPane.showConfirmDialog(this,
                I18N.getString("btn.export.confirm.overwrite", targetFile.getName()),
                I18N.getString("btn.export.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (overwrite != JOptionPane.YES_OPTION) return;
        }

        final File finalTarget = targetFile;
        final String finalFormat = chosen;
        // Run in background
        final TermbaseConfig configSnapshot = currentConfig;
        new SwingWorker<TermbaseConverter.ConversionReport, Void>() {
            @Override
            protected TermbaseConverter.ConversionReport doInBackground() throws Exception {
                if ("DITA Glossary".equals(finalFormat)) {
                    // Same source-file guard as the format converter, inside convertToDita.
                    return TermbaseConverter.convertToDita(configSnapshot, finalTarget.toPath());
                }
                TermbaseConfig.Format fmt = switch (finalFormat) {
                    case "CSV" -> TermbaseConfig.Format.CSV;
                    case "XLSX" -> TermbaseConfig.Format.XLSX;
                    default -> TermbaseConfig.Format.TBX;
                };
                return TermbaseConverter.convert(configSnapshot, fmt, finalTarget.toPath());
            }
            @Override
            protected void done() {
                try {
                    TermbaseConverter.ConversionReport report = get();
                    StringBuilder msg = new StringBuilder();
                    msg.append(I18N.getString("btn.export.success", report.entryCount(), finalTarget.getName()));
                    if (report.hasLoss()) {
                        msg.append("\n\n").append(I18N.getString("btn.export.report.dropped")).append("\n");
                        for (TermbaseConverter.DroppedField df : report.droppedFields()) {
                            msg.append("  - ").append(df.fieldName())
                               .append(": ").append(df.affectedEntries()).append(" entries\n");
                        }
                    } else {
                        msg.append("\n").append(I18N.getString("btn.export.report.none"));
                    }
                    JOptionPane.showMessageDialog(TerminologyPanel.this, msg.toString(),
                        I18N.getString("btn.export.report.title"), JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(TerminologyPanel.this,
                        I18N.getString("btn.export.error", cause.getMessage()),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Extension of the current termbase file, lower case, including the dot; "" if none. */
    private String sourceExtension() {
        String name = currentConfig != null ? currentConfig.getFileName() : "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /**
     * Whether {@code file} is one of the registered termbases' files - enabled or not,
     * since a user can disable a library precisely to export over it - compared by real
     * path so "other.csv" or a different case does not slip past the check.
     */
    private boolean isRegisteredTermbaseFile(File file) {
        java.nio.file.Path p = file.toPath().toAbsolutePath().normalize();
        try {
            if (java.nio.file.Files.exists(p)) {
                p = p.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
            }
        } catch (java.io.IOException ignored) {
            // Unresolvable: keep the normalized comparison.
        }
        for (TermbaseConfig cfg : registry.getConfigs()) {
            java.nio.file.Path q = Path.of(cfg.getFilePath()).toAbsolutePath().normalize();
            try {
                if (java.nio.file.Files.exists(q)) {
                    q = q.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
                }
            } catch (java.io.IOException ignored) {
                // As above.
            }
            if (p.equals(q)) return true;
        }
        return false;
    }
}
