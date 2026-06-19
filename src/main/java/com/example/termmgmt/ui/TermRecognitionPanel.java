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
import ro.sync.ecss.extensions.api.AuthorDocumentController;
import ro.sync.ecss.extensions.api.content.TextContentIterator;
import ro.sync.ecss.extensions.api.content.TextContext;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tab 1: Term Recognition panel.
 *
 * Analyzes the current editor document and identifies terms from enabled termbases.
 *
 * Integration with Oxygen:
 * - In the real Oxygen plugin, uses AuthorAccess or TextContentIterator to get document text
 * - For standalone testing, uses a mock document text
 */
public class TermRecognitionPanel extends JPanel {

    private TermbaseRegistry registry;
    private JComboBox<TermbaseConfig> termbaseCombo;
    private JTable resultTable;
    private DefaultTableModel tableModel;

    public TermRecognitionPanel(TermbaseRegistry registry) {
        this.registry = registry;
        initComponents();
    }

    /**
     * Initialize the UI components.
     */
    private void initComponents() {
        setLayout(new BorderLayout());

        // Header text
        JPanel headerWrap = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel("Scan current document for known terms.");
        headerLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        headerWrap.add(headerLabel, BorderLayout.CENTER);

        // Dropdown + Scan button row
        JPanel actionRow = new JPanel(new BorderLayout(6, 0));
        actionRow.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));

        termbaseCombo = new JComboBox<>();
        termbaseCombo.setPreferredSize(new Dimension(200, termbaseCombo.getPreferredSize().height));
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
        actionRow.add(termbaseCombo, BorderLayout.WEST);

        JButton scanButton = new JButton("Scan");
        scanButton.addActionListener(e -> scanDocument());
        actionRow.add(scanButton, BorderLayout.EAST);

        // Stack header and action row
        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.add(headerWrap);
        northPanel.add(actionRow);
        add(northPanel, BorderLayout.NORTH);

        // Create result table
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

        // Hint label
        JLabel hintLabel = new JLabel("Double-click to locate term.");
        hintLabel.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
        hintLabel.setFont(hintLabel.getFont().deriveFont(Font.ITALIC));
        hintLabel.setForeground(Color.GRAY);
        add(hintLabel, BorderLayout.SOUTH);

        // Load termbases
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
            // Silently ignore in standalone testing
        }
    }

    public void refreshTermbaseList() {
        loadTermbaseList();
    }

    /**
     * Scan the current document for terms (shows warning only on failure).
     */
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

    /**
     * Auto-scan without showing any dialogs (for tab-switch / document-change events).
     */
    public void autoScan() {
        doScan();
    }

    /**
     * Internal scan logic. Returns match count, or -1 if cannot scan.
     */
    private int doScan() {
        String documentText = getDocumentText();
        if (documentText == null || documentText.isEmpty()) {
            tableModel.setRowCount(0);
            return -1;
        }

        TermbaseConfig config = (TermbaseConfig) termbaseCombo.getSelectedItem();
        if (config == null) {
            tableModel.setRowCount(0);
            return -1;
        }

        boolean isTextMode = isTextEditorPage();

        tableModel.setRowCount(0);

        int matchCount = 0;
        List<TermEntry> terms = registry.getTerms(config);
        for (TermEntry term : terms) {
            String sourceTerm = term.getSourceTerm();
            if (sourceTerm != null && sourceTerm.length() > 0) {
                String matchTerm = isTextMode ? escapeXmlEntities(sourceTerm) : sourceTerm;
                String escaped = Pattern.quote(matchTerm);
                Pattern pattern = Pattern.compile("(?<![\\p{L}])" + escaped + "(?![\\p{L}])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                if (pattern.matcher(documentText).find()) {
                    tableModel.addRow(new Object[]{
                        sourceTerm,
                        term.getTargetTerm()
                    });
                    matchCount++;
                }
            }
        }
        return matchCount;
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

    /**
     * Double-click handler: jump to the matched term in the editor.
     */
    private void jumpToTerm(int row) {
        String sourceTerm = (String) tableModel.getValueAt(row, 0);
        if (sourceTerm == null || sourceTerm.isEmpty()) return;

        try {
            PluginWorkspace workspace = PluginWorkspaceProvider.getPluginWorkspace();
            if (workspace == null) return;
            WSEditor editor = workspace.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
            if (editor == null) return;

            WSEditorPage page = editor.getCurrentPage();

            if (page instanceof WSTextEditorPage) {
                jumpToTermInTextPage((WSTextEditorPage) page, sourceTerm);
            } else if (page instanceof WSAuthorEditorPage) {
                WSAuthorEditorPage authorPage = (WSAuthorEditorPage) page;
                AuthorDocumentController ctrl = authorPage.getDocumentController();
                jumpToTermInAuthorPage(authorPage, ctrl, sourceTerm);
            }
        } catch (Exception ex) {
            // Best-effort feature; silently ignore failures
        }
    }

    /**
     * Jump to term in Text mode using indexOf on the raw XML text.
     */
    private void jumpToTermInTextPage(WSTextEditorPage textPage, String sourceTerm) {
        try {
            String docText = getDocumentText();
            if (docText == null) return;

            String searchTerm = escapeXmlEntities(sourceTerm);
            int offset = docText.toLowerCase().indexOf(searchTerm.toLowerCase());
            if (offset < 0) return;

            Object textComp = textPage.getTextComponent();
            if (textComp instanceof javax.swing.text.JTextComponent) {
                javax.swing.text.JTextComponent jtc = (javax.swing.text.JTextComponent) textComp;
                jtc.select(offset, offset + searchTerm.length());
                jtc.requestFocus();
            }
        } catch (Exception e) {
            // Silently ignore
        }
    }

    /**
     * Jump to term in Author mode using TextContentIterator for precise offsets.
     */
    private void jumpToTermInAuthorPage(WSAuthorEditorPage authorPage,
            AuthorDocumentController ctrl, String sourceTerm) {
        try {
            // Build the full text from text contexts to match exactly what Author
            // rendering uses (proper whitespace normalization, no hidden markers).
            StringBuilder fullText = new StringBuilder();
            java.util.List<int[]> segments = new java.util.ArrayList<>();
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

            // Map the string offset back to Author offset
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
            // Silently ignore
        }
    }

    /**
     * Get the current editor document text via Oxygen API.
     * Falls back to mock text if not running inside Oxygen.
     *
     * @return the document text, or null if no document is open
     */
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
                // Use TextContentIterator to get the text exactly as Author
                // rendering uses (proper whitespace normalization).
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
            // Fall through to mock text for testing
        }
        return null;
    }
}
