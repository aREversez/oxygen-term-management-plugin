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
import java.util.List;

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
        return "Term Management";
    }

    private void buildUI() {
        registry = TermbaseRegistry.getInstance();
        registry.loadConfigs();
        
        ui = new JPanel(new BorderLayout(8, 8));

        // Header description
        JLabel headerLabel = new JLabel("Add termbases and activate them for translation.");
        headerLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Termbase table with proper model that can be updated
        String[] columns = {"File Name", "Path", "Format", "Status", "Term Count"};
        tableModel = new DefaultTableModel(columns, 0);
        termbaseTable = new JTable(tableModel);
        termbaseTable.setFillsViewportHeight(true);
        JScrollPane scrollPane = new JScrollPane(termbaseTable);
        scrollPane.setPreferredSize(new Dimension(500, 200));

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton("Add");
        JButton reloadBtn = new JButton("Reload");
        JButton editBtn = new JButton("Edit");
        JButton removeBtn = new JButton("Remove");
        JButton enableBtn = new JButton("Enable");
        JButton disableBtn = new JButton("Disable");
        
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
            tableModel.addRow(new Object[]{
                config.getFileName(),
                config.getFilePath(),
                config.getFormat().name(),
                config.isEnabled() ? "Enabled" : "Disabled",
                termCount
            });
        }
    }

    private void addTermbase() {
        // Use AWT FileDialog for native Windows dialog with rubber-band multi-select
        Window owner = SwingUtilities.getWindowAncestor(ui);
        FileDialog dialog;
        if (owner instanceof Frame) {
            dialog = new FileDialog((Frame) owner, "Select Termbase File(s)", FileDialog.LOAD);
        } else if (owner instanceof Dialog) {
            dialog = new FileDialog((Dialog) owner, "Select Termbase File(s)", FileDialog.LOAD);
        } else {
            dialog = new FileDialog((Frame) null, "Select Termbase File(s)", FileDialog.LOAD);
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
            msg.append(added).append(" termbase(s) added.\n");
        }
        if (duplicates > 0) {
            msg.append(duplicates).append(" file(s) already exist in the list.\n");
        }
        if (skipped > 0) {
            msg.append(skipped).append(" file(s) skipped (unsupported format).\n");
        }

        if (msg.length() > 0) {
            msg.append("\nSupported formats: TBX (.tbx), XLSX (.xlsx), CSV (.csv)");
            JOptionPane.showMessageDialog(ui, msg.toString(), "Add Termbase",
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
            JOptionPane.showMessageDialog(ui, "Please select at least one termbase to reload.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        for (int row : selectedRows) {
            String filePath = (String) tableModel.getValueAt(row, 1);
            registry.reloadConfig(filePath);
        }
        JOptionPane.showMessageDialog(ui, selectedRows.length + " termbase(s) reloaded successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void editTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, "Please select a termbase to edit.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (selectedRows.length > 1) {
            JOptionPane.showMessageDialog(ui, "Please select only one termbase to edit.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String filePath = (String) tableModel.getValueAt(selectedRows[0], 1);
        File file = new File(filePath);
        if (!file.exists()) {
            JOptionPane.showMessageDialog(ui, "File not found: " + filePath, "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(ui, "Failed to open file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void removeTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, "Please select termbase(s) to remove.", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        int confirm = JOptionPane.showConfirmDialog(ui,
            "Are you sure you want to remove " + selectedRows.length + " termbase(s)?",
            "Confirm Remove", JOptionPane.OK_CANCEL_OPTION);
        
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
            JOptionPane.showMessageDialog(ui, "Please select termbase(s) to enable.", "Warning", JOptionPane.WARNING_MESSAGE);
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
            JOptionPane.showMessageDialog(ui, "Please select termbase(s) to disable.", "Warning", JOptionPane.WARNING_MESSAGE);
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
