package com.example.termmgmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;

import ro.sync.ecss.extensions.api.AuthorAccess;
import ro.sync.ecss.extensions.api.AuthorSelectionModel;
import ro.sync.exml.workspace.api.PluginWorkspaceProvider;
import ro.sync.exml.workspace.api.editor.page.author.WSAuthorEditorPageBase;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

import com.example.termmgmt.model.TermEntry;
import com.example.termmgmt.model.TermbaseConfig;
import com.example.termmgmt.service.TermbaseRegistry;
import com.example.termmgmt.ui.TermEntryDialog;
import com.example.termmgmt.ui.TermManagementView;
import com.example.termmgmt.util.IconUtils;

public class TermContextMenuInstaller {

    private static final Object MULTI_TERM_SENTINEL = new Object();

    private TermContextMenuInstaller() {
    }

    // ---- Author mode ----

    public static void build(JPopupMenu menu, AuthorAccess authorAccess, TermManagementView view) {
        if (authorAccess.getEditorAccess() instanceof WSAuthorEditorPageBase) {
            AuthorSelectionModel selModel =
                ((WSAuthorEditorPageBase) authorAccess.getEditorAccess()).getAuthorSelectionModel();
            if (selModel != null && selModel.hasMultipleSelection()) {
                return;
            }
        }

        boolean hasSelection = authorAccess.getEditorAccess().hasSelection();
        String selectedText = hasSelection ? authorAccess.getEditorAccess().getSelectedText() : null;

        TermbaseConfig config = view != null ? view.getRecognitionTermbase() : null;
        Object lookupResult = findTermsInConfigInternal(selectedText, config);
        if (lookupResult == MULTI_TERM_SENTINEL) {
            JMenu termMenu = createMultiTermMenu(selectedText, view);
            if (termMenu != null) {
                menu.addSeparator();
                menu.add(termMenu);
            }
            return;
        }
        @SuppressWarnings("unchecked")
        List<TermEntry> scopedMatches = (List<TermEntry>) lookupResult;

        JMenu termMenu = createTermMenu(selectedText, config, view, scopedMatches,
            matches -> insertTranslationAuthor(authorAccess, matches));

        if (termMenu != null) {
            menu.addSeparator();
            menu.add(termMenu);
        }
    }

    private static void insertTranslationAuthor(AuthorAccess authorAccess, List<TermEntry> matches) {
        String translation = resolveSingleTranslation(matches);
        if (translation == null) return;

        try {
            int selStart = authorAccess.getEditorAccess().getSelectionStart();
            authorAccess.getEditorAccess().deleteSelection();
            if (selStart >= 0) {
                authorAccess.getDocumentController().insertText(selStart, translation);
            }
        } catch (Exception e) {
            System.err.println("Failed to insert translation: " + e.getMessage());
            JOptionPane.showMessageDialog(null,
                "Failed to insert translation.\n" + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- Text mode ----

    public static void build(JPopupMenu menu, WSTextEditorPage textPage, TermManagementView view) {
        String selectedText = textPage.getSelectedText();
        boolean hasSelection = selectedText != null && !selectedText.trim().isEmpty();
        TermbaseConfig config = view != null ? view.getRecognitionTermbase() : null;
        String lookupText = hasSelection ? selectedText : null;
        Object lookupResult = findTermsInConfigInternal(lookupText, config);
        if (lookupResult == MULTI_TERM_SENTINEL) {
            JMenu termMenu = createMultiTermMenu(lookupText, view);
            if (termMenu != null) {
                menu.addSeparator();
                menu.add(termMenu);
            }
            return;
        }
        @SuppressWarnings("unchecked")
        List<TermEntry> scopedMatches = (List<TermEntry>) lookupResult;

        JMenu termMenu = createTermMenu(lookupText, config, view, scopedMatches,
            matches -> insertTranslationText(textPage, matches));

        if (termMenu != null) {
            menu.addSeparator();
            menu.add(termMenu);
        }
    }

    private static void insertTranslationText(WSTextEditorPage textPage, List<TermEntry> matches) {
        String translation = resolveSingleTranslation(matches);
        if (translation == null) return;

        try {
            Object comp = textPage.getTextComponent();
            if (comp instanceof JTextComponent) {
                ((JTextComponent) comp).replaceSelection(translation);
            }
        } catch (Exception e) {
            System.err.println("Failed to insert translation: " + e.getMessage());
            JOptionPane.showMessageDialog(null,
                "Failed to insert translation.\n" + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---- Shared menu builder ----

    private static JMenu createTermMenu(String selectedText, TermbaseConfig config,
            TermManagementView view, List<TermEntry> scopedMatches,
            Consumer<List<TermEntry>> insertAction) {
        boolean hasSelection = selectedText != null && !selectedText.trim().isEmpty();
        boolean hasConfig = config != null;
        int matchCount = scopedMatches.size();

        JMenu termMenu = new JMenu("Term Management");
        termMenu.setIcon(IconUtils.loadLogo(16));

        // Quick Add — when text selected, config exists, term NOT known in this termbase
        JMenuItem quickAdd = new JMenuItem("Quick Add", IconUtils.loadIcon("quick_add", 16));
        quickAdd.setEnabled(hasSelection && hasConfig && matchCount == 0);
        if (hasSelection && hasConfig && matchCount == 0 && view != null) {
            quickAdd.addActionListener(e -> {
                ensureViewVisible();
                view.quickAddFromSelection(selectedText);
            });
        }
        termMenu.add(quickAdd);

        // Insert Translation / Edit Term
        if (matchCount >= 1) {
            String label = matchCount == 1
                ? "Insert Translation: " + scopedMatches.get(0).getTargetTerm()
                : "Insert Translation (" + matchCount + " options)";
            JMenuItem insertTranslation = new JMenuItem(label, IconUtils.loadIcon("edit", 16));
            insertTranslation.addActionListener(e -> insertAction.accept(scopedMatches));
            termMenu.add(insertTranslation);

            JMenuItem editTerm = new JMenuItem("Edit Term", IconUtils.loadIcon("edit", 16));
            editTerm.addActionListener(e -> {
                TermEntry target = matchCount == 1
                    ? scopedMatches.get(0)
                    : chooseEntry(scopedMatches, "Select term to edit:");
                if (target != null) {
                    editTermDirect(target);
                }
            });
            termMenu.add(editTerm);
        }

        termMenu.addSeparator();

        // Search in Termbase — always available when text selected
        JMenuItem search = new JMenuItem("Search in Termbase", IconUtils.loadIcon("scan", 16));
        search.setEnabled(hasSelection && view != null);
        if (hasSelection && view != null) {
            search.addActionListener(e -> {
                ensureViewVisible();
                view.searchInTermbase(selectedText);
            });
        }
        termMenu.add(search);

        boolean hasEnabled = false;
        for (java.awt.Component item : termMenu.getMenuComponents()) {
            if (item instanceof JMenuItem && ((JMenuItem) item).isEnabled()) {
                hasEnabled = true;
                break;
            }
        }

        return hasEnabled ? termMenu : null;
    }

    // ---- Multi-term guard menu ----

    private static JMenu createMultiTermMenu(String selectedText, TermManagementView view) {
        JMenu termMenu = new JMenu("Term Management");
        termMenu.setIcon(IconUtils.loadLogo(16));

        // Use a JPanel+JLabel as a label row — not a JMenuItem, so Oxygen won't
        // filter it out and clicking it won't dismiss the menu.
        JLabel hintLabel = new JLabel("Select only one term");
        hintLabel.setFont(hintLabel.getFont().deriveFont(java.awt.Font.ITALIC));
        hintLabel.setForeground(UIManager.getColor("MenuItem.disabledForeground"));
        hintLabel.setBorder(BorderFactory.createEmptyBorder(2, 24, 2, 8));
        JPanel hintPanel = new JPanel(new java.awt.BorderLayout());
        hintPanel.setOpaque(false);
        hintPanel.add(hintLabel, java.awt.BorderLayout.CENTER);
        termMenu.add(hintPanel);

        boolean hasSelection = selectedText != null && !selectedText.trim().isEmpty();
        if (hasSelection && view != null) {
            termMenu.addSeparator();
            JMenuItem search = new JMenuItem("Search in Termbase", IconUtils.loadIcon("scan", 16));
            search.addActionListener(e -> {
                ensureViewVisible();
                view.searchInTermbase(selectedText);
            });
            termMenu.add(search);
        }

        return termMenu;
    }

    // ---- Shared helpers ----

    private static void ensureViewVisible() {
        try {
            StandalonePluginWorkspace ws =
                (StandalonePluginWorkspace) PluginWorkspaceProvider.getPluginWorkspace();
            if (ws != null) {
                ws.showView("com.example.termmgmt.TermManagementView", true);
            }
        } catch (Exception e) {
            System.err.println("Failed to show Term Management view: " + e.getMessage());
            JOptionPane.showMessageDialog(null,
                "Failed to open Term Management view.\n" + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * If exactly 1 match, return its target term. If multiple, show a chooser
     * and return the user's selection. Returns null if cancelled or no match.
     */
    private static String resolveSingleTranslation(List<TermEntry> matches) {
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) {
            String t = matches.get(0).getTargetTerm();
            return (t == null || t.trim().isEmpty()) ? null : t;
        }
        // Multiple matches in same termbase -> let user pick
        String[] options = matches.stream().map(TermEntry::getTargetTerm).toArray(String[]::new);
        Object pick = JOptionPane.showInputDialog(null,
            "Select translation:", "Multiple Translations",
            JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        return (pick != null) ? pick.toString().trim() : null;
    }

    /**
     * Show a chooser dialog to pick one entry from the list.
     * Returns the chosen entry, or null if cancelled.
     */
    private static TermEntry chooseEntry(List<TermEntry> entries, String message) {
        String[] labels = entries.stream()
            .map(e -> e.getTargetTerm() != null ? e.getTargetTerm() : "(empty)")
            .toArray(String[]::new);
        Object pick = JOptionPane.showInputDialog(null,
            message, "Select Entry",
            JOptionPane.QUESTION_MESSAGE, null, labels, labels[0]);
        if (pick == null) return null;
        int idx = java.util.Arrays.asList(labels).indexOf(pick);
        return idx >= 0 ? entries.get(idx) : null;
    }

    private static Object findTermsInConfigInternal(String text, TermbaseConfig config) {
        if (text == null || text.trim().isEmpty() || config == null) {
            return Collections.emptyList();
        }
        String searchKey = text.trim();

        // Step 1: exact match via source index (O(1)), then filter by config
        List<TermEntry> exact = TermbaseRegistry.getInstance().findTermsBySource(searchKey);
        List<TermEntry> scoped = new ArrayList<>();
        String filePath = config.getFilePath();
        for (TermEntry e : exact) {
            if (filePath.equals(e.getSourceFilePath())) {
                scoped.add(e);
            }
        }
        if (!scoped.isEmpty()) {
            return scoped;
        }

        // Step 2: no exact match — check if the selection contains 2+ different terms
        List<TermEntry> allTerms;
        try {
            allTerms = TermbaseRegistry.getInstance().getTerms(config);
        } catch (Exception e) {
            return Collections.emptyList();
        }
        if (allTerms == null || allTerms.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> distinctFound = new HashSet<>();
        for (TermEntry entry : allTerms) {
            try {
                String sourceTerm = entry.getSourceTerm();
                if (sourceTerm == null || sourceTerm.isEmpty()) continue;
                if (TermbaseRegistry.getInstance().getMatchPattern(sourceTerm).matcher(searchKey).find()) {
                    distinctFound.add(sourceTerm);
                    if (distinctFound.size() >= 2) {
                        return MULTI_TERM_SENTINEL;
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to match term '" + entry.getSourceTerm()
                    + "' against selection: " + e.getMessage());
            }
        }

        return Collections.emptyList();
    }

    private static void editTermDirect(TermEntry target) {
        if (target == null) return;
        try {
            TermEntry editCopy = new TermEntry(target.getSourceTerm(), target.getTargetTerm());
            TermEntryDialog dialog = new TermEntryDialog("Edit Term", editCopy);
            dialog.setVisible(true);

            if (dialog.isConfirmed()) {
                TermEntry updated = dialog.getTermEntry();
                TermbaseConfig config = TermbaseRegistry.getInstance()
                    .getConfigByFilePath(target.getSourceFilePath());
                if (config == null) return;

                List<TermEntry> terms = TermbaseRegistry.getInstance().getTerms(config);
                for (int i = 0; i < terms.size(); i++) {
                    TermEntry e = terms.get(i);
                    if (e.getSourceTerm().equals(target.getSourceTerm())
                            && (e.getTargetTerm() == null ? target.getTargetTerm() == null
                                    : e.getTargetTerm().equals(target.getTargetTerm()))) {
                        terms.set(i, updated);
                        break;
                    }
                }
                TermbaseConfig captured = config;
                List<TermEntry> capturedTerms = terms;
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        TermbaseRegistry.getInstance().saveTerms(captured, capturedTerms);
                        return null;
                    }

                    @Override
                    protected void done() {
                        try {
                            get();
                        } catch (Exception e) {
                            System.err.println("Failed to edit term: " + e.getMessage());
                            JOptionPane.showMessageDialog(null,
                                "Failed to save edited term.\n" + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            }
        } catch (Exception e) {
            System.err.println("Failed to prepare term edit: " + e.getMessage());
            JOptionPane.showMessageDialog(null,
                "Failed to prepare term edit.\n" + e.getMessage(),
                "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}
