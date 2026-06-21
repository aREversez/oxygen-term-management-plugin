package com.example.termmgmt.ui;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TermRecognitionPanel extends JPanel {

    private TermbaseRegistry registry;
    private JComboBox<TermbaseConfig> termbaseCombo;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private JToggleButton highlightToggle;
    private final Map<String, Boolean> highlightEnabledMap = new HashMap<>();
    private List<TermMatch> currentMatches = new ArrayList<>();

    private static class TermMatch {
        final String sourceTerm;
        final String targetTerm;
        final int startOffset;
        final int endOffset;
        TermMatch(String source, String target, int start, int end) {
            this.sourceTerm = source;
            this.targetTerm = target;
            this.startOffset = start;
            this.endOffset = end;
        }
    }

    public TermRecognitionPanel(TermbaseRegistry registry) {
        this.registry = registry;
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout());

        JPanel headerWrap = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel("Scan current document for known terms.");
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

        JButton scanButton = new JButton(IconUtils.loadIcon("scan", 20));
        scanButton.setToolTipText("Scan for terms in current document");
        scanButton.addActionListener(e -> scanDocument());
        actionRow.add(scanButton);

        highlightToggle = new JToggleButton(IconUtils.loadIcon("toggle_highlight", 20));
        highlightToggle.setToolTipText("Toggle term highlighting in Author mode");
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
        northPanel.add(headerWrap);
        northPanel.add(actionRow);
        add(northPanel, BorderLayout.NORTH);

        tableModel = new DefaultTableModel(
            new String[]{"Source", "Target"}, 0
        );
        resultTable = new JTable(tableModel);
        resultTable.setDefaultEditor(Object.class, null);
        resultTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = resultTable.rowAtPoint(e.getPoint());
                    if (row >= 0) {
                        jumpToTerm(row);
                    }
                }
            }
        });
        add(new JScrollPane(resultTable), BorderLayout.CENTER);

        JLabel hintLabel = new JLabel("Double-click to locate term.");
        hintLabel.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.ITALIC));
        hintLabel.setForeground(java.awt.Color.GRAY);
        add(hintLabel, BorderLayout.SOUTH);

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
        }
    }

    public void refreshTermbaseList() {
        loadTermbaseList();
    }

    public void scanDocument() {
        int count = doScan();
        if (count < 0) {
            String text = getDocumentText();
            if (text == null) {
                JOptionPane.showMessageDialog(this,
                    "No document open.",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    public void autoScan() {
        updateHighlightToggleState();
        doScan();
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

    private int doScan() {
        String documentText = getDocumentText();
        if (documentText == null || documentText.isEmpty()) {
            tableModel.setRowCount(0);
            currentMatches.clear();
            clearHighlights();
            return -1;
        }

        TermbaseConfig config = (TermbaseConfig) termbaseCombo.getSelectedItem();
        if (config == null) {
            tableModel.setRowCount(0);
            currentMatches.clear();
            clearHighlights();
            return -1;
        }

        boolean isTextMode = isTextEditorPage();

        // Build segments for mapping string offsets to Author offsets
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
            }
        }

        tableModel.setRowCount(0);
        currentMatches.clear();
        clearHighlights();

        List<TermMatch> allMatches = new ArrayList<>();
        int matchCount = 0;
        List<TermEntry> terms = registry.getTerms(config);
        Map<String, String> seenTerms = new HashMap<>();

        for (TermEntry term : terms) {
            String sourceTerm = term.getSourceTerm();
            if (sourceTerm == null || sourceTerm.isEmpty()) continue;

            String matchTerm = isTextMode ? escapeXmlEntities(sourceTerm) : sourceTerm;
            String escaped = Pattern.quote(matchTerm);
            String regex = isNonDelimitedScript(matchTerm)
                ? escaped
                : "(?<![\\p{L}])" + escaped + "(?![\\p{L}])";
            Pattern pattern = Pattern.compile(regex,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            Matcher matcher = pattern.matcher(documentText);

            boolean firstMatch = true;
            while (matcher.find()) {
                int strStart = matcher.start();
                int strEnd = strStart + matchTerm.length();

                if (isTextMode) {
                    allMatches.add(new TermMatch(sourceTerm, term.getTargetTerm(), strStart, strEnd));
                } else {
                    int authStart = -1, authEnd = -1;
                    for (int[] seg : segments) {
                        int segAuth = seg[0];
                        int segStr = seg[1];
                        int segLen = seg[2];
                        if (strStart >= segStr && strStart < segStr + segLen) {
                            authStart = segAuth + (strStart - segStr);
                        }
                        if (strEnd >= segStr && strEnd <= segStr + segLen) {
                            authEnd = segAuth + (strEnd - segStr);
                        }
                    }
                    if (authStart >= 0 && authEnd >= 0) {
                        allMatches.add(new TermMatch(sourceTerm, term.getTargetTerm(), authStart, authEnd));
                    }
                }

                if (firstMatch) {
                    seenTerms.put(sourceTerm, term.getTargetTerm());
                    firstMatch = false;
                    matchCount++;
                }
            }
        }

        for (Map.Entry<String, String> e : seenTerms.entrySet()) {
            tableModel.addRow(new Object[]{e.getKey(), e.getValue()});
        }

        currentMatches = allMatches;

        if (highlightToggle.isSelected() && !isTextMode) {
            applyHighlights();
        }

        return matchCount;
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

            for (TermMatch match : currentMatches) {
                highlighter.addHighlight(match.startOffset, match.endOffset - 1, painter, null);
            }
        } catch (Exception e) {
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
        }
    }

    private static boolean isNonDelimitedScript(String text) {
        return text.codePoints().anyMatch(cp -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
        });
    }

    private static String escapeXmlEntities(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
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

    private void jumpToTerm(int row) {
        String sourceTerm = (String) tableModel.getValueAt(row, 0);
        if (sourceTerm == null || sourceTerm.isEmpty()) return;

        // Use pre-computed offsets from the scan
        for (TermMatch match : currentMatches) {
            if (match.sourceTerm.equals(sourceTerm)) {
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
                    return;
                } catch (Exception ex) {
                }
            }
        }

        // Fallback: try fresh search if offsets are stale
        try {
            PluginWorkspace workspace = PluginWorkspaceProvider.getPluginWorkspace();
            if (workspace == null) return;
            WSEditor editor = workspace.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return;

            WSEditorPage page = editor.getCurrentPage();

            if (page instanceof WSTextEditorPage) {
                String docText = getDocumentText();
                if (docText == null) return;
                String searchTerm = escapeXmlEntities(sourceTerm);
                int offset = docText.toLowerCase().indexOf(searchTerm.toLowerCase());
                if (offset < 0) return;
                Object textComp = ((WSTextEditorPage) page).getTextComponent();
                if (textComp instanceof javax.swing.text.JTextComponent) {
                    javax.swing.text.JTextComponent jtc = (javax.swing.text.JTextComponent) textComp;
                    jtc.select(offset, offset + searchTerm.length());
                    jtc.requestFocus();
                }
            } else if (page instanceof WSAuthorEditorPage) {
                WSAuthorEditorPage authorPage = (WSAuthorEditorPage) page;
                AuthorDocumentController ctrl = authorPage.getDocumentController();
                jumpToTermInAuthorPage(authorPage, ctrl, sourceTerm);
            }
        } catch (Exception ex) {
        }
    }

    private void jumpToTermInAuthorPage(WSAuthorEditorPage authorPage,
            AuthorDocumentController ctrl, String sourceTerm) {
        try {
            StringBuilder fullText = new StringBuilder();
            java.util.ArrayList<int[]> segments = new java.util.ArrayList<>();
            int contentLen = ctrl.getTextContentLength();
            TextContentIterator it = ctrl.getTextContentIterator(0, contentLen);
            while (it.hasNext()) {
                TextContext ctx = it.next();
                CharSequence text = ctx.getText();
                if (text != null && text.length() > 0) {
                    segments.add(new int[]{ctx.getTextStartOffset(), fullText.length(), text.length()});
                    fullText.append(text);
                }
            }

            if (fullText.length() == 0) return;

            int strOffset = fullText.toString().toLowerCase().indexOf(sourceTerm.toLowerCase());
            if (strOffset < 0) return;

            for (int[] seg : segments) {
                int authStart = seg[0];
                int strStart = seg[1];
                int segLen = seg[2];
                if (strOffset >= strStart && strOffset < strStart + segLen) {
                    int authorOffset = authStart + (strOffset - strStart);
                    authorPage.select(authorOffset, authorOffset + sourceTerm.length());
                    return;
                }
            }
        } catch (Exception e) {
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
        }
        return null;
    }
}
