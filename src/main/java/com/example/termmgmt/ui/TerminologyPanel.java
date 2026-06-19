package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;

import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.editor.page.WSEditorPage;
import ro.sync.exml.workspace.api.editor.page.author.WSAuthorEditorPage;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Tab 3: Terminology Management panel.
 *
 * Manages terms in individual termbases.
 *
 * Features:
 * - JComboBox for selecting enabled termbases
 * - JTable for displaying terms (MULTIPLE_INTERVAL_SELECTION)
 * - Buttons: Reload, Add New Term, Quick Add New Term, Edit Term, Delete Term
 * - File write-back logic for CSV, XLSX, and TBX formats
 */
public class TerminologyPanel extends JPanel {

    private TermbaseRegistry registry;
    private JComboBox<TermbaseConfig> termbaseComboBox;
    private JTable termTable;
    private DefaultTableModel tableModel;

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
        add(northPanel, BorderLayout.NORTH);

        // Create term table
        tableModel = new DefaultTableModel(
            new String[]{"Source Term", "Target Term"}, 0
        );
        termTable = new JTable(tableModel);
        termTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        addTableContextMenu();
        add(new JScrollPane(termTable), BorderLayout.CENTER);

        // Create button panel with icon buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 5));

        JButton reloadButton = new JButton(createIcon("reload"));
        reloadButton.setToolTipText("Reload termbase from disk");
        reloadButton.setPreferredSize(new Dimension(28, 28));
        reloadButton.addActionListener(e -> reloadTermbase());
        buttonPanel.add(reloadButton);

        JButton addButton = new JButton(createIcon("add"));
        addButton.setToolTipText("Add new term");
        addButton.setPreferredSize(new Dimension(28, 28));
        addButton.addActionListener(e -> addNewTerm());
        buttonPanel.add(addButton);

        JButton quickAddButton = new JButton(createIcon("quickadd"));
        quickAddButton.setToolTipText("Quick add term from editor selection");
        quickAddButton.setPreferredSize(new Dimension(28, 28));
        quickAddButton.addActionListener(e -> quickAddNewTerm());
        buttonPanel.add(quickAddButton);

        JButton editButton = new JButton(createIcon("edit"));
        editButton.setToolTipText("Edit selected term");
        editButton.setPreferredSize(new Dimension(28, 28));
        editButton.addActionListener(e -> editTerm());
        buttonPanel.add(editButton);

        JButton deleteButton = new JButton(createIcon("delete"));
        deleteButton.setToolTipText("Delete selected term(s)");
        deleteButton.setPreferredSize(new Dimension(28, 28));
        deleteButton.addActionListener(e -> deleteTerms());
        buttonPanel.add(deleteButton);

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

    private static ImageIcon createIcon(String type) {
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0x505050));
        int cx = 10, cy = 10;
        switch (type) {
            case "reload": {
                // Circular arrow (refresh)
                g.drawArc(3, 3, 14, 14, 60, -300);
                int ax = (int)(10 + 7 * Math.cos(Math.toRadians(60)));
                int ay = (int)(10 + 7 * Math.sin(Math.toRadians(60)));
                g.drawLine(ax, ay, ax + 3, ay - 2);
                g.drawLine(ax, ay, ax - 1, ay - 4);
                break;
            }
            case "add":
                g.drawLine(cx, 3, cx, 17);
                g.drawLine(3, cy, 17, cy);
                break;
            case "quickadd": {
                // Plus with a star/sparkle accent
                g.drawLine(cx, 4, cx, 16);
                g.drawLine(4, cy, 16, cy);
                g.setStroke(new BasicStroke(1.4f));
                // Small asterisk in top-right quadrant
                g.drawLine(14, 2, 14, 6);
                g.drawLine(12, 4, 16, 4);
                g.drawLine(13, 3, 15, 5);
                break;
            }
            case "edit": {
                // Pencil icon
                g.drawLine(4, 16, 13, 7);
                g.drawLine(13, 7, 16, 10);
                g.drawLine(16, 10, 7, 17);
                g.drawLine(5, 15, 3, 17);
                break;
            }
            case "delete":
                g.drawRoundRect(5, 8, 10, 10, 2, 2);
                g.drawLine(6, 6, 14, 6);
                g.drawLine(8, 9, 8, 16);
                g.drawLine(12, 9, 12, 16);
                break;
        }
        g.dispose();
        return new ImageIcon(img);
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
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            return;
        }

        tableModel.setRowCount(0);
        List<TermEntry> terms = registry.getTerms(config);
        for (TermEntry term : terms) {
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

        registry.reloadConfig(config.getFilePath());
        loadTermbaseTerms();
        JOptionPane.showMessageDialog(this,
            "Termbase " + config.getFileName() + " reloaded.",
            "Success", JOptionPane.INFORMATION_MESSAGE);
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
            List<TermEntry> terms = registry.getTerms(config);
            terms.add(newTerm);
            safeSaveTerms(config, terms);
            loadTermbaseTerms();
        }
    }

    /**
     * Quick add a new term using the current editor selection.
     */
    private void quickAddNewTerm() {
        TermbaseConfig config = (TermbaseConfig) termbaseComboBox.getSelectedItem();
        if (config == null) {
            JOptionPane.showMessageDialog(this,
                "Please select a termbase.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!checkFileAccess(config)) return;

        // Get current editor selection (mock for standalone testing)
        String editorSelection = getEditorSelection();

        if (editorSelection == null || editorSelection.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No text selected in editor.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        TermEntryDialog dialog = new TermEntryDialog("Add New Term", editorSelection);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry newTerm = dialog.getTermEntry();
            List<TermEntry> terms = registry.getTerms(config);
            terms.add(newTerm);
            safeSaveTerms(config, terms);
            loadTermbaseTerms();
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

        // Check if only one row is selected
        if (termTable.getSelectedRowCount() > 1) {
            JOptionPane.showMessageDialog(this,
                "Please select only one term to edit.",
                "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Get the term from the table
        String sourceTerm = (String) tableModel.getValueAt(selectedRow, 0);
        String targetTerm = (String) tableModel.getValueAt(selectedRow, 1);

        TermEntry existingTerm = new TermEntry(sourceTerm, targetTerm);

        TermEntryDialog dialog = new TermEntryDialog("Edit Term", existingTerm);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            TermEntry editedTerm = dialog.getTermEntry();
            List<TermEntry> terms = registry.getTerms(config);
            terms.set(selectedRow, editedTerm);
            safeSaveTerms(config, terms);
            loadTermbaseTerms();
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
            // Delete in reverse order to maintain indices
            for (int i = selectedRows.length - 1; i >= 0; i--) {
                terms.remove(selectedRows[i]);
            }
            safeSaveTerms(config, terms);
            loadTermbaseTerms();
        }
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
