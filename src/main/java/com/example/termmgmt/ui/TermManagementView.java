package com.example.termmgmt.ui;

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

    public TermManagementView() {
        this.registry = TermbaseRegistry.getInstance();
        registry.loadConfigs();
        initComponents();
    }

    public TermManagementView(StandalonePluginWorkspace workspace) {
        this();
    }

    private void initComponents() {
        if (tabbedPane != null) return;
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(380, 480));
        setMinimumSize(new Dimension(300, 400));

        recognitionPanel = new TermRecognitionPanel(registry);
        terminologyPanel = new TerminologyPanel(registry);
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Term Recognition", recognitionPanel);
        tabbedPane.addTab("Termbase Search",
            new TermbaseSearchPanel(registry));
        tabbedPane.addTab("Terminology", terminologyPanel);
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
}
