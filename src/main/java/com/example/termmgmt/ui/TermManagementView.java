package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.ExternalChangeCheck;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.util.I18N;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class TermManagementView extends JPanel {

    private JTabbedPane tabbedPane;
    private TermbaseRegistry registry;
    private TermRecognitionPanel recognitionPanel;
    private TerminologyPanel terminologyPanel;
    private TermbaseSearchPanel searchPanel;
    /** 7.1: Guard against multiple concurrent external-change checks. */
    private final AtomicBoolean checkingExternal = new AtomicBoolean(false);
    /**
     * Guards the listener against reacting to our own tab changes: a look-and-feel may fire a
     * ChangeEvent for setToolTipAt, and answering that with "clear all hints and check again"
     * would wipe out the very hint we just put up.
     */
    private boolean suppressTabEvents = false;
    private final Runnable registryListener = () -> SwingUtilities.invokeLater(this::refreshAllPanels);

    public TermManagementView() {
        this.registry = TermbaseRegistry.getInstance();
        registry.loadConfigs();
        initComponents();
        registry.addChangeListener(registryListener);
    }

    /**
     * Detach from the singleton registry. Call before discarding this view, otherwise the
     * registry keeps the view (and its panels) reachable and keeps refreshing it.
     */
    public void dispose() {
        registry.removeChangeListener(registryListener);
    }

    private void refreshAllPanels() {
        if (recognitionPanel != null) {
            recognitionPanel.refreshTermbaseList();
            recognitionPanel.autoScan();
        }
        if (terminologyPanel != null) {
            terminologyPanel.refreshTermbaseList();
            terminologyPanel.loadTermbaseTerms();
        }
    }

    public TermManagementView(StandalonePluginWorkspace workspace) {
        this();
    }

    private void initComponents() {
        if (tabbedPane != null) return;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(380, 480));
        setMinimumSize(new Dimension(300, 400));

        recognitionPanel = new TermRecognitionPanel(registry, this);
        terminologyPanel = new TerminologyPanel(registry);
        searchPanel = new TermbaseSearchPanel(registry, this);
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab(I18N.getString("tab.term.recognition"), recognitionPanel);
        tabbedPane.addTab(I18N.getString("tab.termbase.search"), searchPanel);
        tabbedPane.addTab(I18N.getString("tab.terminology"), terminologyPanel);
        tabbedPane.addChangeListener(e -> {
            if (suppressTabEvents) return;
            // 7.2: Clear tooltip on every tab switch.
            withoutTabEvents(() -> ExternalChangeCheck.clearAllTooltips(tabbedPane.getTabCount(),
                i -> tabbedPane.setToolTipTextAt(i, null)));
            JComponent sel = (JComponent) tabbedPane.getSelectedComponent();
            if (sel == recognitionPanel) {
                checkExternalChanges();
                recognitionPanel.refreshTermbaseList();
                recognitionPanel.autoScan();
            } else if (sel == terminologyPanel) {
                checkExternalChanges();
                terminologyPanel.refreshTermbaseList();
            }
        });
        add(tabbedPane, BorderLayout.CENTER);
    }

    /**
     * 7.1+7.2: Lazily check whether any enabled termbase file was modified externally.
     * Runs on a background thread to avoid blocking the EDT. Updates UI on completion.
     */
    private void checkExternalChanges() {
        // 15: Clear stale hints at the very start of every check, before the already-running
        // guard, so a hint from a previous pass cannot linger even when this pass is skipped.
        withoutTabEvents(() -> ExternalChangeCheck.clearAllTooltips(tabbedPane.getTabCount(),
            i -> tabbedPane.setToolTipTextAt(i, null)));
        if (!checkingExternal.compareAndSet(false, true)) return; // already running
        final int tabAtStart = tabbedPane.getSelectedIndex();
        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() {
                return ExternalChangeCheck.reloadModified(new ExternalChangeCheck.Source() {
                    @Override public List<TermbaseConfig> enabledConfigs() { return registry.getEnabledConfigs(); }
                    @Override public boolean isExternallyModified(String filePath) { return registry.isExternallyModified(filePath); }
                    @Override public void reload(String filePath) { registry.reloadConfig(filePath); }
                });
            }
            @Override
            protected void done() {
                checkingExternal.set(false);
                try {
                    if (ExternalChangeCheck.shouldNotify(Boolean.TRUE.equals(get()), tabAtStart,
                            tabbedPane.getSelectedIndex())) {
                        // Tell the user with a dialog: a per-tab tooltip only paints while the
                        // mouse hovers the tab header, so it was never actually seen after a
                        // tab switch. The tooltip stays as the passive hint on the tab header.
                        String msg = I18N.getString("msg.external.reload.notify");
                        withoutTabEvents(() -> tabbedPane.setToolTipTextAt(tabAtStart, msg));
                        JOptionPane.showMessageDialog(TermManagementView.this, msg,
                            I18N.getString("msg.info"), JOptionPane.INFORMATION_MESSAGE);
                    }
                } catch (Exception ignored) { }
            }
        }.execute();
    }

    /** Runs tab-property changes without letting our own ChangeListener answer them. */
    private void withoutTabEvents(Runnable changes) {
        suppressTabEvents = true;
        try {
            changes.run();
        } finally {
            suppressTabEvents = false;
        }
    }

    /**
     * Trigger auto-scan on the Term Recognition panel.
     * Can be called externally (e.g. on editor change).
     */
    public void autoScanRecognition() {
        if (recognitionPanel != null) {
            recognitionPanel.autoScan();
        }
    }

    /**
     * Get the termbase currently selected in the Term Recognition tab.
     */
    public TermbaseConfig getRecognitionTermbase() {
        return recognitionPanel != null ? recognitionPanel.getSelectedTermbase() : null;
    }

    /**
     * Switch to the Terminology tab and attempt to select a specific term
     * by its file path (first matching termbase in combo) and source term text.
     */
    public void switchToTerminology(String filePath, String sourceTerm, String targetTerm) {
        if (tabbedPane == null || terminologyPanel == null) return;
        tabbedPane.setSelectedIndex(2); // Terminology is tab index 2
        terminologyPanel.selectTerm(filePath, sourceTerm, targetTerm);
    }

    /**
     * Quick add a term using selected text from the editor.
     * Uses the Recognition tab's currently selected termbase.
     * Callable from external context menus.
     */
    public void quickAddFromSelection(String selectedText) {
        if (terminologyPanel != null) {
            terminologyPanel.quickAddFromExternalSelection(selectedText, getRecognitionTermbase());
        }
    }

    /**
     * Switch to the Termbase Search tab and execute a search for the given text.
     * Callable from external context menus.
     */
    public void searchInTermbase(String searchText) {
        if (tabbedPane == null || searchPanel == null) return;
        tabbedPane.setSelectedIndex(1); // Termbase Search is tab index 1
        searchPanel.executeSearch(searchText);
    }
}
