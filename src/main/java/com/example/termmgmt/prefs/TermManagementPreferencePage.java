package com.example.termmgmt.prefs;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseLoader;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.util.I18N;

import ro.sync.exml.plugin.option.OptionPagePluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Preferences page for Term Management plugin.
 * Appears under Preferences > Plugins > Term Management.
 *
 * CRITICAL: Must extend OptionPagePluginExtension directly (no wrapper).
 * Method signatures must match the real Oxygen SDK:
 *   - init(PluginWorkspace) returns JComponent
 *   - apply(PluginWorkspace) returns void
 *   - restoreDefaults() has NO parameter
 *   - getTitle() returns String
 */
public class TermManagementPreferencePage extends OptionPagePluginExtension {

    private static final String LAST_TERMBASE_DIR_KEY = "com.example.termmgmt.last-termbase-dir";

    private JPanel ui;
    private JTable termbaseTable;
    private DefaultTableModel tableModel;
    private TermbaseRegistry registry;

    @Override
    public JComponent init(PluginWorkspace pluginWorkspace) {
        if (ui == null) {
            buildUI();
        } else {
            // init() is called multiple times (e.g. after Cancel).
            // Reload from OptionsStorage to discard unsaved changes.
            registry.loadConfigs();
            reloadSettings();
        }
        return ui;
    }

    @Override
    public void apply(PluginWorkspace pluginWorkspace) {
        // Save termbase configurations to persistent storage
        registry.saveConfigs();
    }

    @Override
    public void restoreDefaults() {
        // Reset in-memory configs only — do NOT persist.
        // If Cancel is clicked, init() will reload from OptionsStorage.
        registry.setConfigs(new java.util.ArrayList<>());
        reloadSettings();
    }

    @Override
    public String getTitle() {
        return I18N.getString("plugin.name");
    }

    private void buildUI() {
        registry = TermbaseRegistry.getInstance();
        registry.loadConfigs();
        
        ui = new JPanel(new BorderLayout(8, 8));

        // Header description
        JLabel headerLabel = new JLabel(I18N.getString("prefs.add.termbases"));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Termbase table with proper model that can be updated
        String[] columns = {
            I18N.getString("prefs.col.filename"),
            I18N.getString("prefs.col.path"),
            I18N.getString("prefs.col.format"),
            I18N.getString("prefs.col.status"),
            I18N.getString("prefs.col.termcount")
        };
        tableModel = new DefaultTableModel(columns, 0);
        termbaseTable = new JTable(tableModel);
        termbaseTable.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(termbaseTable);
        scrollPane.setPreferredSize(new Dimension(500, 200));

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton(I18N.getString("prefs.add"));
        JButton reloadBtn = new JButton(I18N.getString("prefs.reload"));
        JButton editBtn = new JButton(I18N.getString("prefs.edit"));
        JButton removeBtn = new JButton(I18N.getString("prefs.remove"));
        JButton enableBtn = new JButton(I18N.getString("prefs.enable"));
        JButton disableBtn = new JButton(I18N.getString("prefs.disable"));
        
        // Wire up button click handlers
        addBtn.addActionListener(e -> addTermbase());
        reloadBtn.addActionListener(e -> reloadTermbase());
        editBtn.addActionListener(e -> editTermbase());
        removeBtn.addActionListener(e -> removeTermbase());
        enableBtn.addActionListener(e -> enableTermbase());
        disableBtn.addActionListener(e -> disableTermbase());
        
        buttonPanel.add(addBtn);
        buttonPanel.add(reloadBtn);
        buttonPanel.add(editBtn);
        buttonPanel.add(removeBtn);
        buttonPanel.add(enableBtn);
        buttonPanel.add(disableBtn);

        ui.add(headerLabel, BorderLayout.NORTH);
        ui.add(scrollPane, BorderLayout.CENTER);
        ui.add(buttonPanel, BorderLayout.SOUTH);

        // Load current settings
        reloadSettings();
    }

    private void reloadSettings() {
        // Clear table
        tableModel.setRowCount(0);

        // Load data from TermbaseRegistry
        List<TermbaseConfig> configs = registry.getConfigs();
        for (TermbaseConfig config : configs) {
            int termCount = 0;
            try {
                termCount = registry.getTerms(config).size();
            } catch (Exception e) {
                // Ignore if terms can't be loaded
            }

            // Health check: does the file still exist?
            String statusText;
            if (new File(config.getFilePath()).exists()) {
                statusText = config.isEnabled() ? I18N.getString("prefs.status.enabled") : I18N.getString("prefs.status.disabled");
            } else {
                statusText = I18N.getString("prefs.status.missing");
            }

            tableModel.addRow(new Object[]{
                config.getFileName(),
                config.getFilePath(),
                config.getFormat().name(),
                statusText,
                termCount
            });
        }
    }

    private void addTermbase() {
        // Use AWT FileDialog for native Windows dialog with rubber-band multi-select
        Window owner = SwingUtilities.getWindowAncestor(ui);
        FileDialog dialog;
        String fileDialogTitle = I18N.getString("prefs.file.dialog.title");
        if (owner instanceof Frame) {
            dialog = new FileDialog((Frame) owner, fileDialogTitle, FileDialog.LOAD);
        } else if (owner instanceof Dialog) {
            dialog = new FileDialog((Dialog) owner, fileDialogTitle, FileDialog.LOAD);
        } else {
            dialog = new FileDialog((Frame) null, fileDialogTitle, FileDialog.LOAD);
        }
        dialog.setMultipleMode(true);
        dialog.setFilenameFilter((dir, name) -> {
            String lower = name.toLowerCase();
            return lower.endsWith(".tbx") || lower.endsWith(".xlsx") || lower.endsWith(".csv");
        });

        String lastDir = loadLastTermbaseDir();
        if (lastDir != null) {
            dialog.setDirectory(lastDir);
        }

        dialog.setVisible(true);

        File[] files = dialog.getFiles();
        if (files.length == 0) return;

        // Save last used directory
        saveLastTermbaseDir(files[0].getParent());

        // Build a set of existing file paths for duplicate detection
        List<TermbaseConfig> configs = registry.getConfigs();
        java.util.Set<String> existingPaths = new java.util.HashSet<>();
        for (TermbaseConfig c : configs) {
            existingPaths.add(new File(c.getFilePath()).getAbsolutePath());
        }

        int added = 0;
        int skipped = 0;
        int duplicates = 0;

        for (File file : files) {
            String filePath = file.getAbsolutePath();

            // Check duplicate
            if (existingPaths.contains(filePath)) {
                duplicates++;
                continue;
            }

            // Validate format
            TermbaseConfig.Format format;
            try {
                format = TermbaseLoader.detectFormat(filePath);
            } catch (IllegalArgumentException e) {
                skipped++;
                continue;
            }

            // Duplicate content check: load terms and compare with enabled termbases
            List<TermEntry> newTerms;
            try {
                newTerms = TermbaseLoader.loadTerms(new TermbaseConfig(filePath, format, true));
            } catch (Exception e) {
                int retry = JOptionPane.showConfirmDialog(ui,
                    I18N.getString("prefs.cannot.load.terms", file.getName(), e.getMessage()),
                    I18N.getString("prefs.load.error"), JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
                if (retry == JOptionPane.YES_OPTION) {
                    skipped++;
                    continue;
                }
                // User chose No — abort the whole operation
                break;
            }

            if (newTerms.isEmpty()) {
                int retry = JOptionPane.showConfirmDialog(ui,
                    I18N.getString("prefs.empty.file", file.getName()),
                    I18N.getString("prefs.empty.file.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (retry != JOptionPane.YES_OPTION) {
                    skipped++;
                    continue;
                }
            }

            StringBuilder conflictMsg = new StringBuilder();
            boolean hasConflict = false;
            for (TermbaseConfig existingConfig : configs) {
                if (!existingConfig.isEnabled()) continue;
                if (existingConfig.getFilePath().equals(filePath)) continue;
                List<TermEntry> existingTerms = registry.getTerms(existingConfig);
                for (TermEntry newTerm : newTerms) {
                    if (newTerm.getSourceTerm() == null || newTerm.getSourceTerm().isEmpty()) continue;
                    String newSource = newTerm.getSourceTerm().trim();
                    for (TermEntry existingTerm : existingTerms) {
                        if (existingTerm.getSourceTerm() == null) continue;
                        if (newSource.equals(existingTerm.getSourceTerm().trim())) {
                            hasConflict = true;
                            if (java.util.Objects.equals(newTerm.getTargetTerm(), existingTerm.getTargetTerm())) {
                                conflictMsg.append(I18N.getString("prefs.conflict.duplicate.line",
                                    newTerm.getSourceTerm(), newTerm.getTargetTerm(), existingConfig.getFileName()));
                            } else {
                                conflictMsg.append(I18N.getString("prefs.conflict.conflict.line",
                                    newTerm.getSourceTerm(), newTerm.getTargetTerm(), existingTerm.getTargetTerm(), existingConfig.getFileName()));
                            }
                        }
                }
            }
            }

            if (hasConflict) {
                String title = I18N.getString("prefs.conflict.title");
                String message = I18N.getString("prefs.conflict.message", file.getName(), conflictMsg.toString());
                int choice = JOptionPane.showConfirmDialog(ui, message, title,
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    skipped++;
                    continue;
                }
            }

            TermbaseConfig config = new TermbaseConfig(filePath, format, true);
            configs.add(config);
            existingPaths.add(filePath);
            added++;
        }

        registry.setConfigs(configs);
        reloadSettings();

        // Build summary message
        StringBuilder msg = new StringBuilder();
        if (added > 0) {
            msg.append(I18N.getString("prefs.added.count", added)).append("\n");
        }
        if (duplicates > 0) {
            msg.append(I18N.getString("prefs.duplicates.count", duplicates)).append("\n");
        }
        if (skipped > 0) {
            msg.append(I18N.getString("prefs.skipped.count", skipped)).append("\n");
        }

        if (msg.length() > 0) {
            msg.append("\n").append(I18N.getString("prefs.supported.formats"));
            JOptionPane.showMessageDialog(ui, msg.toString(), I18N.getString("prefs.add.termbases.short"),
                added > 0 ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        }
    }

    private String loadLastTermbaseDir() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return null;
            WSOptionsStorage os = w.getOptionsStorage();
            return os.getOption(LAST_TERMBASE_DIR_KEY, null);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveLastTermbaseDir(String dir) {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return;
            WSOptionsStorage os = w.getOptionsStorage();
            os.setOption(LAST_TERMBASE_DIR_KEY, dir != null ? dir : "");
        } catch (Exception e) {
        }
    }

    private void reloadTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.reload"), I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        for (int row : selectedRows) {
            String filePath = (String) tableModel.getValueAt(row, 1);
            registry.reloadConfig(filePath);
        }
        reloadSettings();
        JOptionPane.showMessageDialog(ui, I18N.getString("prefs.reload.success", selectedRows.length), I18N.getString("msg.success"), JOptionPane.INFORMATION_MESSAGE);
    }

    private void editTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.edit"), I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (selectedRows.length > 1) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.one.edit"), I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String filePath = (String) tableModel.getValueAt(selectedRows[0], 1);
        File file = new File(filePath);
        if (!file.exists()) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.file.not.found", filePath), I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.failed.open.file", ex.getMessage()), I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.remove"), I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        int confirm = JOptionPane.showConfirmDialog(ui,
            I18N.getString("prefs.confirm.remove", selectedRows.length),
            I18N.getString("prefs.confirm.remove.title"), JOptionPane.OK_CANCEL_OPTION);
        
        if (confirm == JOptionPane.OK_OPTION) {
            List<TermbaseConfig> configs = registry.getConfigs();
            // Remove in reverse order to maintain indices
            for (int i = selectedRows.length - 1; i >= 0; i--) {
                configs.remove(selectedRows[i]);
            }
            registry.setConfigs(configs);
            reloadSettings();
        }
    }

    private void enableTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.enable"), I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        List<TermbaseConfig> configs = registry.getConfigs();
        for (int row : selectedRows) {
            configs.get(row).setEnabled(true);
        }
        registry.setConfigs(configs);
        reloadSettings();
    }

    private void disableTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.disable"), I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        List<TermbaseConfig> configs = registry.getConfigs();
        for (int row : selectedRows) {
            configs.get(row).setEnabled(false);
        }
        registry.setConfigs(configs);
        reloadSettings();
    }
}
