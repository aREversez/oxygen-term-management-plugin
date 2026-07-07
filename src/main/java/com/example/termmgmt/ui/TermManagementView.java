package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.util.I18N;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import javax.swing.*;
import java.awt.*;

public class TermManagementView extends JPanel {

    private JTabbedPane tabbedPane;
    private TermbaseRegistry registry;
    private TermRecognitionPanel recognitionPanel;
    private TerminologyPanel terminologyPanel;
    private TermbaseSearchPanel searchPanel;

    public TermManagementView() {
        this.registry = TermbaseRegistry.getInstance();
        registry.loadConfigs();
        initComponents();
        registry.addChangeListener(() -> SwingUtilities.invokeLater(this::refreshAllPanels));
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
            JComponent sel = (JComponent) tabbedPane.getSelectedComponent();
            if (sel == recognitionPanel) {
                recognitionPanel.refreshTermbaseList();
                recognitionPanel.autoScan();
            } else if (sel == terminologyPanel) {
                terminologyPanel.refreshTermbaseList();
            }
        });
        add(tabbedPane, BorderLayout.CENTER);
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
