package com.example.termmgmt.prefs;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseLoader;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.util.I18N;
import com.example.termmgmt.util.TermConflictUtils;

import ro.sync.exml.plugin.option.OptionPagePluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.options.WSOptionsStorage;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

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
    private JCheckBox caseSensitiveCheck;
    private JCheckBox matchInflectionsCheck;
    private int reloadGeneration; // EDT only
    private boolean addInProgress; // EDT only
    private DefaultTableModel tableModel;
    private TermbaseRegistry registry;
    // Kept as fields so addTermbase() can grey them out while the background load runs;
    // their handlers mutate the shared TermbaseConfig objects the worker reads from.
    private JButton removeBtn;
    private JButton enableBtn;
    private JButton disableBtn;

    @Override
    public JComponent init(PluginWorkspace pluginWorkspace) {
        if (ui == null) {
            buildUI();
        } else {
            // init() is called multiple times (e.g. after Cancel).
            // Reload from OptionsStorage to discard unsaved changes.
            registry.loadConfigs();
            caseSensitiveCheck.setSelected(loadCaseSensitiveOption());
            matchInflectionsCheck.setSelected(loadMatchInflectionsOption());
            reloadSettings();
        }
        return ui;
    }

    @Override
    public void apply(PluginWorkspace pluginWorkspace) {
        // Save termbase configurations to persistent storage
        registry.saveConfigs();
        registry.setCaseSensitive(caseSensitiveCheck.isSelected());
        saveCaseSensitiveOption(caseSensitiveCheck.isSelected());
        registry.setMatchInflections(matchInflectionsCheck.isSelected());
        saveMatchInflectionsOption(matchInflectionsCheck.isSelected());
        // Propagate the applied changes (added/removed/enabled/disabled rows) to the open panels.
        // Panels no longer re-read OptionsStorage on refresh (defect C), so this is what makes an
        // Apply show up in the term-recognition and terminology combos right away.
        registry.fireTermsChanged();
    }

    @Override
    public void restoreDefaults() {
        // Reset in-memory configs only — do NOT persist.
        // If Cancel is clicked, init() will reload from OptionsStorage.
        registry.setConfigs(new java.util.ArrayList<>());
        registry.setCaseSensitive(false);
        caseSensitiveCheck.setSelected(false);
        registry.setMatchInflections(false);
        matchInflectionsCheck.setSelected(false);
        reloadSettings();
    }

    @Override
    public String getTitle() {
        return I18N.getString("window.title");
    }

    private void buildUI() {
        registry = TermbaseRegistry.getInstance();
        registry.loadConfigs();
        
        ui = new JPanel(new BorderLayout(8, 8));

        // Header description
        JLabel headerLabel = new JLabel(I18N.getString("prefs.add.termbases"));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Matching option, applied on OK/Apply together with the termbase list
        caseSensitiveCheck = new JCheckBox(I18N.getString("prefs.case-sensitive"));
        caseSensitiveCheck.setSelected(loadCaseSensitiveOption());
        matchInflectionsCheck = new JCheckBox(I18N.getString("prefs.match-inflections"));
        matchInflectionsCheck.setToolTipText(I18N.getString("prefs.match-inflections.tooltip"));
        matchInflectionsCheck.setSelected(loadMatchInflectionsOption());
        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        caseSensitiveCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        matchInflectionsCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        optionsPanel.add(caseSensitiveCheck);
        optionsPanel.add(matchInflectionsCheck);
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(headerLabel, BorderLayout.NORTH);
        northPanel.add(optionsPanel, BorderLayout.SOUTH);

        // Termbase table with proper model that can be updated
        String[] columns = {
            I18N.getString("prefs.col.filename"),
            I18N.getString("prefs.col.path"),
            I18N.getString("prefs.col.format"),
            I18N.getString("prefs.col.status"),
            I18N.getString("prefs.col.termcount"),
            I18N.getString("prefs.col.langs")
        };
        tableModel = new DefaultTableModel(columns, 0);
        termbaseTable = new JTable(tableModel);
        termbaseTable.setFillsViewportHeight(true);
        // Keep the columns where they were laid out: dragging a header divider must not reorder
        // them, and the file-name/path columns need enough room to be readable (defect H). With
        // auto-resize off the columns honour their preferred widths and the scroll pane scrolls.
        termbaseTable.getTableHeader().setReorderingAllowed(false);
        termbaseTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        applyColumnWidths();
        // The file name / path may still exceed the fixed column widths and get clipped to an
        // ellipsis. Show the full value on hover, but only when it is actually clipped so a
        // readable cell does not raise a redundant tooltip (defect H).
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
        termbaseTable.getColumnModel().getColumn(0).setCellRenderer(tipRenderer);
        termbaseTable.getColumnModel().getColumn(1).setCellRenderer(tipRenderer);
        JScrollPane scrollPane = new JScrollPane(termbaseTable);
        scrollPane.setPreferredSize(new Dimension(500, 200));

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton(I18N.getString("prefs.add"));
        JButton reloadBtn = new JButton(I18N.getString("prefs.reload"));
        JButton editBtn = new JButton(I18N.getString("prefs.edit"));
        JButton langsBtn = new JButton(I18N.getString("prefs.langs"));
        removeBtn = new JButton(I18N.getString("prefs.remove"));
        enableBtn = new JButton(I18N.getString("prefs.enable"));
        disableBtn = new JButton(I18N.getString("prefs.disable"));
        
        // Wire up button click handlers
        addBtn.addActionListener(e -> addTermbase());
        reloadBtn.addActionListener(e -> reloadTermbase());
        editBtn.addActionListener(e -> editTermbase());
        langsBtn.addActionListener(e -> chooseLanguages());
        removeBtn.addActionListener(e -> removeTermbase());
        enableBtn.addActionListener(e -> enableTermbase());
        disableBtn.addActionListener(e -> disableTermbase());
        
        buttonPanel.add(addBtn);
        buttonPanel.add(reloadBtn);
        buttonPanel.add(editBtn);
        buttonPanel.add(langsBtn);
        buttonPanel.add(removeBtn);
        buttonPanel.add(enableBtn);
        buttonPanel.add(disableBtn);

        ui.add(northPanel, BorderLayout.NORTH);
        ui.add(scrollPane, BorderLayout.CENTER);
        ui.add(buttonPanel, BorderLayout.SOUTH);

        // Load current settings
        reloadSettings();
    }

    /** Readable default widths for the six termbase-table columns; the file name gets room. */
    private void applyColumnWidths() {
        int[] widths = { 170, 320, 70, 90, 80, 150 };
        javax.swing.table.TableColumnModel cm = termbaseTable.getColumnModel();
        for (int i = 0; i < widths.length && i < cm.getColumnCount(); i++) {
            cm.getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    private void reloadSettings() {
        // Clear table
        tableModel.setRowCount(0);

        // Rows come from the configuration at once, so row i is always configs.get(i): the
        // remove/enable/disable actions rely on that. Whether the file exists and how many
        // terms it has need disk access (possibly a slow network drive), so a worker fills
        // those two columns in afterwards.
        List<TermbaseConfig> configs = registry.getConfigs();
        for (TermbaseConfig config : configs) {
            tableModel.addRow(new Object[]{
                config.getFileName(),
                config.getFilePath(),
                config.getFormat().name(),
                config.isEnabled() ? I18N.getString("prefs.status.enabled") : I18N.getString("prefs.status.disabled"),
                "\u2026",
                "\u2026"
            });
        }

        final int generation = ++reloadGeneration;
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() {
                List<Object[]> info = new ArrayList<>();
                for (TermbaseConfig config : configs) {
                    boolean exists = new File(config.getFilePath()).exists();
                    int termCount = 0;
                    try {
                        termCount = registry.getTerms(config).size();
                    } catch (Exception e) {
                        // Ignore if terms can't be loaded
                    }
                    // getTerms has loaded the file, so the config now knows its languages.
                    String langs = describeLanguages(config);
                    info.add(new Object[]{exists, termCount, langs});
                }
                return info;
            }

            @Override
            protected void done() {
                if (generation != reloadGeneration) {
                    return; // the table has been rebuilt since; a newer worker fills it
                }
                try {
                    List<Object[]> info = get();
                    for (int i = 0; i < info.size() && i < tableModel.getRowCount(); i++) {
                        if (!configs.get(i).getFilePath().equals(tableModel.getValueAt(i, 1))) {
                            continue;
                        }
                        if (!(Boolean) info.get(i)[0]) {
                            tableModel.setValueAt(I18N.getString("prefs.status.missing"), i, 3);
                        }
                        tableModel.setValueAt(info.get(i)[1], i, 4);
                        tableModel.setValueAt(info.get(i)[2], i, 5);
                    }
                } catch (Exception e) {
                    // Leave the placeholders; the table itself is already complete.
                }
            }
        }.execute();
    }

    /** "source -> target", with a note when the chosen pair is not in the file. */
    private static String describeLanguages(TermbaseConfig config) {
        String src = config.getSourceLang();
        String tgt = config.getTargetLang();
        if (src == null || tgt == null) {
            return "";
        }
        String text = src + " \u2192 " + tgt;
        if (config.isSelectionFallback()) {
            text += " (" + I18N.getString("prefs.langs.fallback") + ")";
        }
        return text;
    }

    /** Lets the user pick which two languages of the selected termbase the plugin works with. */
    private void chooseLanguages() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length != 1) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.langs"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        TermbaseConfig config = registry.getConfigs().get(selectedRows[0]);
        // Reading the file for its language list can be slow; keep it off the EDT.
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                registry.getTerms(config);
                if (config.getAvailableLangs().isEmpty()) {
                    // Served from the cache of an earlier session of this config object: the
                    // language list is only filled by an actual read.
                    registry.reloadConfig(config.getFilePath());
                }
                return new ArrayList<>(config.getAvailableLangs());
            }

            @Override
            protected void done() {
                List<String> langs;
                try {
                    langs = get();
                } catch (Exception e) {
                    langs = new ArrayList<>();
                }
                if (langs.size() < 2) {
                    JOptionPane.showMessageDialog(ui, I18N.getString("prefs.langs.unavailable"),
                        I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                showLanguageDialog(config, langs);
            }
        }.execute();
    }

    private void showLanguageDialog(TermbaseConfig config, List<String> langs) {
        JComboBox<String> sourceBox = new JComboBox<>(langs.toArray(new String[0]));
        JComboBox<String> targetBox = new JComboBox<>(langs.toArray(new String[0]));
        sourceBox.setSelectedItem(matchIgnoreCase(langs, config.getSourceLang()));
        targetBox.setSelectedItem(matchIgnoreCase(langs, config.getTargetLang()));

        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
        panel.add(new JLabel(I18N.getString("prefs.langs.source")));
        panel.add(sourceBox);
        panel.add(new JLabel(I18N.getString("prefs.langs.target")));
        panel.add(targetBox);

        String useDefault = I18N.getString("prefs.langs.default");
        Object[] options = { UIManager.getString("OptionPane.okButtonText"), useDefault,
                             UIManager.getString("OptionPane.cancelButtonText") };
        while (true) {
            int choice = JOptionPane.showOptionDialog(ui, panel, I18N.getString("prefs.langs.title"),
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice == 0) {
                String src = (String) sourceBox.getSelectedItem();
                String tgt = (String) targetBox.getSelectedItem();
                if (src == null || tgt == null || src.equalsIgnoreCase(tgt)) {
                    JOptionPane.showMessageDialog(ui, I18N.getString("prefs.langs.same"),
                        I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
                    continue;
                }
                applyLanguages(config, src, tgt);
            } else if (choice == 1) {
                // "Use default" only resets the two pickers to the file's first two languages and
                // keeps the dialog open; the choice is committed by OK, not by this button (defect D).
                sourceBox.setSelectedIndex(0);
                targetBox.setSelectedIndex(1);
                continue;
            }
            return; // OK committed the choice, or Cancel / window close discards it
        }
    }

    private static String matchIgnoreCase(List<String> langs, String wanted) {
        for (String l : langs) {
            if (l.equalsIgnoreCase(wanted)) {
                return l;
            }
        }
        return langs.get(0);
    }

    /** Stores the choice, re-reads the termbase with it and refreshes everything that shows terms. */
    private void applyLanguages(TermbaseConfig config, String source, String target) {
        String before = config.langPairKey();
        config.setSelectedLangs(source, target);
        if (before.equals(config.langPairKey())) {
            return;
        }
        registry.saveConfigs();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                registry.reloadConfig(config.getFilePath());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(ui, I18N.getString("msg.failed.reload", e.getMessage()),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                }
                reloadSettings();
            }
        }.execute();
    }

    private void addTermbase() {
        if (addInProgress) {
            return; // files from the previous "Add" are still being loaded
        }
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
            String lower = name.toLowerCase(Locale.ROOT);
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

        int skipped = 0;
        int duplicates = 0;

        // Cheap checks first: already registered, unsupported extension.
        List<AddCandidate> candidates = new ArrayList<>();
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
            candidates.add(new AddCandidate(file, filePath, format));
        }

        final int duplicateCount = duplicates;
        final int skippedCount = skipped;
        if (candidates.isEmpty()) {
            completeAddTermbases(candidates, duplicateCount, skippedCount);
            return;
        }

        // Reading the files and comparing them with the enabled termbases is the slow part;
        // do all of it on a worker. The questions to the user stay on the EDT, in the same
        // order as before, and are answered from the results computed here.
        //
        // Snapshot which termbases are enabled now, so the worker never reads the mutable
        // `enabled` field of a TermbaseConfig the EDT could flip from an Enable/Disable click.
        // The row-index buttons are also disabled below as a belt-and-suspenders guard: they
        // would rebuild the table and shift indices while the worker is still referring to
        // the configs it captured above.
        final java.util.Set<String> enabledPaths = new java.util.HashSet<>();
        for (TermbaseConfig c : configs) {
            if (c.isEnabled()) {
                enabledPaths.add(c.getFilePath());
            }
        }
        addInProgress = true;
        removeBtn.setEnabled(false);
        enableBtn.setEnabled(false);
        disableBtn.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                for (AddCandidate candidate : candidates) {
                    try {
                        candidate.terms = TermbaseLoader.loadTerms(
                            new TermbaseConfig(candidate.filePath, candidate.format, true));
                    } catch (Exception e) {
                        candidate.loadError = e;
                        continue;
                    }
                    for (TermbaseConfig existingConfig : configs) {
                        if (!enabledPaths.contains(existingConfig.getFilePath())) continue;
                        if (existingConfig.getFilePath().equals(candidate.filePath)) continue;
                        try {
                            List<TermConflictUtils.Conflict> found = TermConflictUtils.findConflicts(
                                candidate.terms, registry.getTerms(existingConfig));
                            if (!found.isEmpty()) {
                                candidate.conflicts.add(new ExistingConflicts(existingConfig.getFileName(), found));
                            }
                        } catch (Exception e) {
                            // A termbase that cannot be read cannot be compared with.
                        }
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    completeAddTermbases(candidates, duplicateCount, skippedCount);
                } finally {
                    addInProgress = false;
                    removeBtn.setEnabled(true);
                    enableBtn.setEnabled(true);
                    disableBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    /** Ask the user about each loaded file, register the accepted ones and show the summary. */
    private void completeAddTermbases(List<AddCandidate> candidates, int duplicates, int skipped) {
        int added = 0;
        List<AddCandidate> accepted = new ArrayList<>();

        for (AddCandidate candidate : candidates) {
            File file = candidate.file;

            if (candidate.loadError != null) {
                int retry = JOptionPane.showConfirmDialog(ui,
                    I18N.getString("prefs.cannot.load.terms", file.getName(), candidate.loadError.getMessage()),
                    I18N.getString("prefs.load.error"), JOptionPane.YES_NO_OPTION, JOptionPane.ERROR_MESSAGE);
                if (retry == JOptionPane.YES_OPTION) {
                    skipped++;
                    continue;
                }
                // User chose No — abort the whole operation
                break;
            }

            if (candidate.terms.isEmpty()) {
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
            for (ExistingConflicts existing : candidate.conflicts) {
                hasConflict = true;
                appendConflictLines(conflictMsg, existing.termbaseName, existing.conflicts);
            }
            // Termbases accepted earlier in this same batch count as existing ones too.
            for (AddCandidate earlier : accepted) {
                List<TermConflictUtils.Conflict> found =
                    TermConflictUtils.findConflicts(candidate.terms, earlier.terms);
                if (!found.isEmpty()) {
                    hasConflict = true;
                    appendConflictLines(conflictMsg, earlier.config.getFileName(), found);
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

            candidate.config = new TermbaseConfig(candidate.filePath, candidate.format, true);
            accepted.add(candidate);
            added++;
        }

        // Re-read the configuration: it may have changed while the files were loading.
        List<TermbaseConfig> latest = registry.getConfigs();
        java.util.Set<String> latestPaths = new java.util.HashSet<>();
        for (TermbaseConfig c : latest) {
            latestPaths.add(new File(c.getFilePath()).getAbsolutePath());
        }
        for (AddCandidate candidate : accepted) {
            if (latestPaths.add(candidate.filePath)) {
                latest.add(candidate.config);
            }
        }
        registry.setConfigs(latest);
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

    private static void appendConflictLines(StringBuilder out, String termbaseName,
                                            List<TermConflictUtils.Conflict> conflicts) {
        for (TermConflictUtils.Conflict conflict : conflicts) {
            TermEntry newTerm = conflict.newTerm;
            if (conflict.isIdenticalTarget()) {
                out.append(I18N.getString("prefs.conflict.duplicate.line",
                    newTerm.getSourceTerm(), newTerm.getTargetTerm(), termbaseName));
            } else {
                out.append(I18N.getString("prefs.conflict.conflict.line",
                    newTerm.getSourceTerm(), newTerm.getTargetTerm(),
                    conflict.existingTerm.getTargetTerm(), termbaseName));
            }
        }
    }

    /** A file chosen in "Add", with what the worker found out about it. */
    private static final class AddCandidate {
        final File file;
        final String filePath;
        final TermbaseConfig.Format format;
        List<TermEntry> terms;
        Exception loadError;
        final List<ExistingConflicts> conflicts = new ArrayList<>();
        TermbaseConfig config; // set once the user accepted the file

        AddCandidate(File file, String filePath, TermbaseConfig.Format format) {
            this.file = file;
            this.filePath = filePath;
            this.format = format;
        }
    }

    /** Conflicts between a candidate and one already registered termbase. */
    private static final class ExistingConflicts {
        final String termbaseName;
        final List<TermConflictUtils.Conflict> conflicts;

        ExistingConflicts(String termbaseName, List<TermConflictUtils.Conflict> conflicts) {
            this.termbaseName = termbaseName;
            this.conflicts = conflicts;
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

    /** Current "case sensitive matching" setting; defaults to off when storage is unavailable. */
    private boolean loadCaseSensitiveOption() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return false;
            return Boolean.parseBoolean(
                w.getOptionsStorage().getOption(TermbaseRegistry.CASE_SENSITIVE_OPTION_KEY, "false"));
        } catch (Exception e) {
            return false;
        }
    }

    private void saveCaseSensitiveOption(boolean value) {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return;
            w.getOptionsStorage().setOption(TermbaseRegistry.CASE_SENSITIVE_OPTION_KEY, String.valueOf(value));
        } catch (Exception e) {
        }
    }

    /** Current "match inflected forms" setting; defaults to off when storage is unavailable. */
    private boolean loadMatchInflectionsOption() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return false;
            return Boolean.parseBoolean(
                w.getOptionsStorage().getOption(TermbaseRegistry.MATCH_INFLECTIONS_OPTION_KEY, "false"));
        } catch (Exception e) {
            return false;
        }
    }

    private void saveMatchInflectionsOption(boolean value) {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return;
            w.getOptionsStorage().setOption(TermbaseRegistry.MATCH_INFLECTIONS_OPTION_KEY, String.valueOf(value));
        } catch (Exception e) {
        }
    }

    private void reloadTermbase() {
        int[] selectedRows = termbaseTable.getSelectedRows();
        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(ui, I18N.getString("prefs.select.reload"), I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        List<String> filePaths = new ArrayList<>();
        for (int row : selectedRows) {
            filePaths.add((String) tableModel.getValueAt(row, 1));
        }

        // Reloading reads each file in full: do it off the EDT, and report a file that cannot
        // be read (corrupt, locked, gone) instead of letting the exception escape.
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() {
                List<String> failures = new ArrayList<>();
                for (String filePath : filePaths) {
                    try {
                        registry.reloadConfig(filePath);
                    } catch (Exception e) {
                        failures.add(new File(filePath).getName() + ": " + e.getMessage());
                    }
                }
                return failures;
            }

            @Override
            protected void done() {
                reloadSettings();
                try {
                    List<String> failures = get();
                    if (failures.isEmpty()) {
                        JOptionPane.showMessageDialog(ui, I18N.getString("prefs.reload.success", filePaths.size()), I18N.getString("msg.success"), JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(ui, I18N.getString("msg.failed.reload", String.join("\n", failures)), I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(ui, I18N.getString("msg.failed.reload", e.getMessage()), I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
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
