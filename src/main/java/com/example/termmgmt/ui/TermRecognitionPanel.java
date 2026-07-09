package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.DocumentScanner;
import com.example.termmgmt.service.DocumentScanner.ScanResult;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.util.I18N;
import com.example.termmgmt.util.IconUtils;

import ro.sync.ecss.extensions.api.AuthorDocumentController;
import ro.sync.ecss.extensions.api.content.TextContentIterator;
import ro.sync.ecss.extensions.api.content.TextContext;
import ro.sync.ecss.extensions.api.highlights.AuthorHighlighter;
import ro.sync.ecss.extensions.api.highlights.ColorHighlightPainter;
import ro.sync.exml.view.graphics.Color;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TermRecognitionPanel extends JPanel {

    private TermbaseRegistry registry;
    private JComboBox<TermbaseConfig> termbaseCombo;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private JToggleButton highlightToggle;
    private final Map<String, Boolean> highlightEnabledMap = new HashMap<>();
    private List<ScanResult> currentMatches = new ArrayList<>();

    private JLabel statsLabel;
    private TermManagementView parentView;

    private JButton scanButton;
    private JButton prevButton;
    private JButton nextButton;
    private JLabel posLabel;
    private final Map<String, Integer> navIndices = new HashMap<>();
    private SwingWorker<List<ScanResult>, Void> currentScanWorker;

    public TermRecognitionPanel(TermbaseRegistry registry, TermManagementView parentView) {
        this.registry = registry;
        this.parentView = parentView;
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        JPanel headerWrap = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel(I18N.getString("tab.term.recognition.header"));
        headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        headerWrap.add(headerLabel, BorderLayout.CENTER);

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        actionRow.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));

        termbaseCombo = new JComboBox<>();
        termbaseCombo.setPreferredSize(new Dimension(150, termbaseCombo.getPreferredSize().height));
        termbaseCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                if (value instanceof TermbaseConfig) {
                    value = ((TermbaseConfig) value).getFileName();
                }
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            }
        });
        termbaseCombo.addActionListener(e -> {
            TermbaseConfig sel = (TermbaseConfig) termbaseCombo.getSelectedItem();
            if (sel != null) saveLastTermbasePath(sel.getFilePath());
            autoScan();
        });
        termbaseCombo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                refreshTermbaseList();
            }
            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {}
        });
        actionRow.add(termbaseCombo);

        scanButton = new JButton(IconUtils.loadIcon("scan", 16));
        scanButton.setToolTipText(I18N.getString("btn.scan.tooltip"));
        scanButton.addActionListener(e -> scanDocument());
        actionRow.add(scanButton);

        highlightToggle = new JToggleButton(IconUtils.loadIcon("toggle_highlight", 16));
        highlightToggle.setToolTipText(I18N.getString("btn.highlight.tooltip"));
        highlightToggle.addActionListener(e -> {
            String url = getCurrentEditorUrl();
            boolean sel = highlightToggle.isSelected();
            if (url != null) highlightEnabledMap.put(url, sel);
            if (sel) {
                applyHighlights();
            } else {
                clearHighlights();
            }
        });
        actionRow.add(highlightToggle);

        actionRow.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                Container parent = actionRow.getParent();
                if (parent != null) parent.revalidate();
            }
        });

        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        headerWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        northPanel.add(headerWrap);
        actionRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        northPanel.add(actionRow);

        statsLabel = new JLabel(" ");
        statsLabel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        statsLabel.setFont(statsLabel.getFont().deriveFont(Font.BOLD));
        statsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        statsLabel.setHorizontalAlignment(SwingConstants.LEFT);
        northPanel.add(statsLabel);

        add(northPanel, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(
            new String[]{I18N.getString("msg.col.source"), I18N.getString("msg.col.target")}, 0
        );
        resultTable = new JTable(tableModel);
        resultTable.setDefaultEditor(Object.class, null);
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = resultTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        String source = (String) tableModel.getValueAt(row, 0);
                        String target = (String) tableModel.getValueAt(row, 1);
                        if (source != null && source.length() > 0) {
                            TermbaseConfig config = (TermbaseConfig) termbaseCombo.getSelectedItem();
                            if (config != null && parentView != null) {
                                parentView.switchToTerminology(config.getFilePath(), source, target);
                            }
                        }
                    }
                }
            }
        });
        add(new JScrollPane(resultTable), BorderLayout.CENTER);

        // Bottom panel: hint + nav buttons
        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));

        JLabel hintLabel = new JLabel(I18N.getString("msg.hint.double.click"));
        hintLabel.setBorder(BorderFactory.createEmptyBorder(4, 5, 0, 5));
        hintLabel.setForeground(java.awt.Color.GRAY);
        hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        southPanel.add(hintLabel);

        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        prevButton = new JButton(I18N.getString("btn.prev"));
        prevButton.setToolTipText(I18N.getString("btn.prev.tooltip"));
        prevButton.setEnabled(false);
        prevButton.addActionListener(e -> navigatePrev());
        navPanel.add(prevButton);

        posLabel = new JLabel(I18N.getString("msg.nav.default"));
        posLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        navPanel.add(posLabel);

        nextButton = new JButton(I18N.getString("btn.next"));
        nextButton.setToolTipText(I18N.getString("btn.next.tooltip"));
        nextButton.setEnabled(false);
        nextButton.addActionListener(e -> navigateNext());
        navPanel.add(nextButton);

        // Update nav state on row selection
        resultTable.getSelectionModel().addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            updateNavState();
        });

        navPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        southPanel.add(navPanel);

        add(southPanel, BorderLayout.SOUTH);

        loadTermbaseList();
    }

    private static final String LAST_TB_KEY = "com.example.termmgmt.last-termbase-recognition";

    private void loadTermbaseList() {
        registry.loadConfigs();
        String prevPath = null;
        TermbaseConfig prev = (TermbaseConfig) termbaseCombo.getSelectedItem();
        if (prev != null) {
            prevPath = prev.getFilePath();
        } else {
            prevPath = loadLastTermbasePath();
        }
        termbaseCombo.removeAllItems();
        List<TermbaseConfig> enabled = registry.getEnabledConfigs();
        for (TermbaseConfig cfg : enabled) {
            termbaseCombo.addItem(cfg);
        }
        if (prevPath != null) {
            for (int i = 0; i < termbaseCombo.getItemCount(); i++) {
                if (termbaseCombo.getItemAt(i).getFilePath().equals(prevPath)) {
                    termbaseCombo.setSelectedIndex(i);
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
            System.err.println("Failed to save last termbase path: " + e.getMessage());
        }
    }
    
    public TermbaseConfig getSelectedTermbase() {
        return (TermbaseConfig) termbaseCombo.getSelectedItem();
    }

    public void refreshTermbaseList() {
        loadTermbaseList();
    }

    public void scanDocument() {
        String text = getDocumentText();
        if (text == null || text.isEmpty()) {
            tableModel.setRowCount(0);
            currentMatches.clear();
            clearHighlights();
            statsLabel.setText(" ");
            JOptionPane.showMessageDialog(this,
                I18N.getString("msg.no.document"),
                I18N.getString("msg.warning"), JOptionPane.WARNING_MESSAGE);
            return;
        }
        startScan(text);
    }

    public void autoScan() {
        updateHighlightToggleState();
        String text = getDocumentText();
        if (text == null || text.isEmpty()) {
            tableModel.setRowCount(0);
            currentMatches.clear();
            clearHighlights();
            statsLabel.setText(" ");
            return;
        }
        startScan(text);
    }

    private String getCurrentEditorUrl() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return null;
            WSEditor editor = w.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return null;
            java.net.URL url = editor.getEditorLocation();
            return url != null ? url.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateHighlightToggleState() {
        String url = getCurrentEditorUrl();
        if (url != null && highlightEnabledMap.containsKey(url)) {
            boolean enabled = highlightEnabledMap.get(url);
            if (highlightToggle.isSelected() != enabled) {
                highlightToggle.setSelected(enabled);
            }
        } else {
            if (highlightToggle.isSelected()) {
                highlightToggle.setSelected(false);
            }
        }
    }

    private void startScan(String documentText) {
        TermbaseConfig config = (TermbaseConfig) termbaseCombo.getSelectedItem();
        if (config == null) {
            tableModel.setRowCount(0);
            currentMatches.clear();
            clearHighlights();
            statsLabel.setText(" ");
            return;
        }

        boolean isTextMode = isTextEditorPage();

        // Build segments on EDT
        List<int[]> segments = new ArrayList<>();
        if (!isTextMode) {
            try {
                PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
                if (w != null) {
                    WSEditor editor = w.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
                    if (editor != null && editor.getCurrentPage() instanceof WSAuthorEditorPage) {
                        AuthorDocumentController ctrl =
                            ((WSAuthorEditorPage) editor.getCurrentPage()).getDocumentController();
                        int contentLen = ctrl.getTextContentLength();
                        TextContentIterator it = ctrl.getTextContentIterator(0, contentLen);
                        int strPos = 0;
                        while (it.hasNext()) {
                            TextContext ctx = it.next();
                            CharSequence text = ctx.getText();
                            if (text != null && text.length() > 0) {
                                segments.add(new int[]{ctx.getTextStartOffset(), strPos, text.length()});
                                strPos += text.length();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to build Author segments: " + e.getMessage());
            }
        }

        // Cancel previous scan if still running
        if (currentScanWorker != null && !currentScanWorker.isDone()) {
            currentScanWorker.cancel(true);
        }

        tableModel.setRowCount(0);
        currentMatches.clear();
        clearHighlights();
        statsLabel.setText(I18N.getString("msg.scanning"));
        scanButton.setEnabled(false);

        boolean capturedIsTextMode = isTextMode;
        List<int[]> capturedSegments = segments;
        String capturedText = documentText;
        TermbaseConfig capturedConfig = config;
        boolean highlightSelected = highlightToggle.isSelected();

        currentScanWorker = new SwingWorker<List<ScanResult>, Void>() {
            @Override
            protected List<ScanResult> doInBackground() throws Exception {
                List<TermEntry> terms = registry.getTerms(capturedConfig);
                DocumentScanner scanner = new DocumentScanner();
                return scanner.scan(capturedText, terms, capturedIsTextMode, capturedSegments);
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) return;
                    List<ScanResult> result = get();
                    currentMatches = result;

                    // Update table with unique pairs
                    tableModel.setRowCount(0);
                    java.util.Set<String> tablePairs = new java.util.HashSet<>();
                    for (ScanResult m : result) {
                        String pairKey = m.sourceTerm + "|" + m.targetTerm;
                        if (tablePairs.add(pairKey)) {
                            tableModel.addRow(new Object[]{m.sourceTerm, m.targetTerm});
                        }
                    }

                    int totalHits = result.size();
                    int uniqueTerms = tablePairs.size();
                    if (uniqueTerms == 0) {
                        statsLabel.setText(I18N.getString("msg.no.matches"));
                    } else {
                        statsLabel.setText(I18N.getString("msg.matched.terms", totalHits, uniqueTerms));
                    }

                    if (highlightSelected && !capturedIsTextMode) {
                        applyHighlights();
                    }
                } catch (Exception e) {
                    System.err.println("Scan failed: " + e.getMessage());
                        statsLabel.setText(I18N.getString("msg.scan.failed"));
                    JOptionPane.showMessageDialog(TermRecognitionPanel.this,
                        I18N.getString("msg.failed.scan.document", e.getMessage()),
                        I18N.getString("msg.error"), JOptionPane.ERROR_MESSAGE);
                } finally {
                    scanButton.setEnabled(true);
                }
            }
        };
        currentScanWorker.execute();
    }

    private void applyHighlights() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return;
            WSEditor editor = w.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return;
            WSEditorPage page = editor.getCurrentPage();
            if (!(page instanceof WSAuthorEditorPage)) return;

            AuthorHighlighter highlighter = ((WSAuthorEditorPage) page).getHighlighter();
            if (highlighter == null) return;

            ColorHighlightPainter painter = new ColorHighlightPainter();
            painter.setBgColor(new Color(255, 230, 0, 80));

            for (ScanResult match : currentMatches) {
                highlighter.addHighlight(match.startOffset, match.endOffset - 1, painter, null);
            }
        } catch (Exception e) {
            System.err.println("Failed to apply highlights: " + e.getMessage());
        }
    }

    private void clearHighlights() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return;
            WSEditor editor = w.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return;
            WSEditorPage page = editor.getCurrentPage();
            if (!(page instanceof WSAuthorEditorPage)) return;

            AuthorHighlighter highlighter = ((WSAuthorEditorPage) page).getHighlighter();
            if (highlighter != null) {
                highlighter.removeAllHighlights();
            }
        } catch (Exception e) {
            System.err.println("Failed to clear highlights: " + e.getMessage());
        }
    }

    private boolean isTextEditorPage() {
        try {
            PluginWorkspace w = PluginWorkspaceProvider.getPluginWorkspace();
            if (w == null) return false;
            WSEditor e = w.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (e == null) return false;
            return e.getCurrentPage() instanceof WSTextEditorPage;
        } catch (Exception ex) {
            return false;
        }
    }

    private void updateNavState() {
        int row = resultTable.getSelectedRow();
        if (row < 0) {
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            posLabel.setText(I18N.getString("msg.nav.default"));
            return;
        }
        String sourceTerm = (String) tableModel.getValueAt(row, 0);
        if (sourceTerm == null) {
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            posLabel.setText(I18N.getString("msg.nav.default"));
            return;
        }
        int count = 0;
        for (ScanResult m : currentMatches) {
            if (m.sourceTerm.equals(sourceTerm)) count++;
        }
        if (count == 0) {
            prevButton.setEnabled(false);
            nextButton.setEnabled(false);
            posLabel.setText(I18N.getString("msg.nav.default"));
            return;
        }
        int idx = navIndices.getOrDefault(sourceTerm, 0);
        if (idx < 0 || idx >= count) {
            idx = 0;
            navIndices.put(sourceTerm, idx);
        }
        prevButton.setEnabled(true);
        nextButton.setEnabled(true);
        posLabel.setText(I18N.getString("msg.nav.position", idx + 1, count));
    }

    private void navigatePrev() {
        int row = resultTable.getSelectedRow();
        if (row < 0) return;
        String sourceTerm = (String) tableModel.getValueAt(row, 0);
        if (sourceTerm == null) return;
        int count = 0;
        for (ScanResult m : currentMatches) {
            if (m.sourceTerm.equals(sourceTerm)) count++;
        }
        if (count == 0) return;
        int idx = navIndices.getOrDefault(sourceTerm, 0);
        idx = (idx - 1 + count) % count;
        navIndices.put(sourceTerm, idx);
        jumpToOccurrence(sourceTerm, idx);
        updateNavState();
    }

    private void navigateNext() {
        int row = resultTable.getSelectedRow();
        if (row < 0) return;
        String sourceTerm = (String) tableModel.getValueAt(row, 0);
        if (sourceTerm == null) return;
        int count = 0;
        for (ScanResult m : currentMatches) {
            if (m.sourceTerm.equals(sourceTerm)) count++;
        }
        if (count == 0) return;
        int idx = navIndices.getOrDefault(sourceTerm, 0);
        idx = (idx + 1) % count;
        navIndices.put(sourceTerm, idx);
        jumpToOccurrence(sourceTerm, idx);
        updateNavState();
    }

    private void jumpToOccurrence(String sourceTerm, int occurrenceIndex) {
        if (sourceTerm == null || sourceTerm.isEmpty()) return;

        // Collect matches for this term
        List<ScanResult> matches = new ArrayList<>();
        for (ScanResult m : currentMatches) {
            if (m.sourceTerm.equals(sourceTerm)) matches.add(m);
        }
        if (occurrenceIndex < 0 || occurrenceIndex >= matches.size()) return;
        ScanResult match = matches.get(occurrenceIndex);

        try {
            PluginWorkspace workspace = PluginWorkspaceProvider.getPluginWorkspace();
            if (workspace == null) return;
            WSEditor editor = workspace.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return;

            WSEditorPage page = editor.getCurrentPage();

            if (page instanceof WSTextEditorPage) {
                Object textComp = ((WSTextEditorPage) page).getTextComponent();
                if (textComp instanceof javax.swing.text.JTextComponent) {
                    javax.swing.text.JTextComponent jtc = (javax.swing.text.JTextComponent) textComp;
                    jtc.select(match.startOffset, match.endOffset);
                    jtc.requestFocus();
                }
            } else if (page instanceof WSAuthorEditorPage) {
                ((WSAuthorEditorPage) page).select(match.startOffset, match.endOffset);
            }
        } catch (Exception ex) {
            System.err.println("Failed to jump to occurrence: " + ex.getMessage());
        }
    }

    private String getDocumentText() {
        try {
            PluginWorkspace workspace = PluginWorkspaceProvider.getPluginWorkspace();
            if (workspace == null) return null;

            WSEditor editor = workspace.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return null;

            WSEditorPage page = editor.getCurrentPage();
            if (page == null) return null;

            if (page instanceof WSAuthorEditorPage) {
                AuthorDocumentController ctrl = ((WSAuthorEditorPage) page).getDocumentController();
                StringBuilder sb = new StringBuilder();
                TextContentIterator it = ctrl.getTextContentIterator(0, ctrl.getTextContentLength());
                while (it.hasNext()) {
                    TextContext ctx = it.next();
                    CharSequence text = ctx.getText();
                    if (text != null) {
                        sb.append(text);
                    }
                }
                return sb.toString();
            } else if (page instanceof WSTextEditorPage) {
                javax.swing.text.Document doc = ((WSTextEditorPage) page).getDocument();
                return doc.getText(0, doc.getLength());
            }
        } catch (Exception e) {
            System.err.println("Failed to get document text: " + e.getMessage());
        }
        return null;
    }
}
