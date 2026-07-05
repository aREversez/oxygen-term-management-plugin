package com.example.termmgmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
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

    private TermContextMenuInstaller() {
    }

    // ---- Author mode ----

    public static void build(JPopupMenu menu, AuthorAccess authorAccess, TermManagementView view) {
        // Check for multi-selection (Ctrl+click multiple disjoint terms)
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
        List<TermEntry> scopedMatches = findTermsInConfig(selectedText, config);

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
        }
    }

    // ---- Text mode ----

    public static void build(JPopupMenu menu, WSTextEditorPage textPage, TermManagementView view) {
        String selectedText = textPage.getSelectedText();
        boolean hasSelection = selectedText != null && !selectedText.trim().isEmpty();
        TermbaseConfig config = view != null ? view.getRecognitionTermbase() : null;
        List<TermEntry> scopedMatches = findTermsInConfig(hasSelection ? selectedText : null, config);

        JMenu termMenu = createTermMenu(hasSelection ? selectedText : null, config, view, scopedMatches,
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
        search.setEnabled(hasSelection);
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

    // ---- Helpers ----

    private static void ensureViewVisible() {
        try {
            StandalonePluginWorkspace ws =
                (StandalonePluginWorkspace) PluginWorkspaceProvider.getPluginWorkspace();
            if (ws != null) {
                ws.showView("com.example.termmgmt.TermManagementView", true);
            }
        } catch (Exception e) {
            System.err.println("Failed to show Term Management view: " + e.getMessage());
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
        // Multiple matches in same termbase → let user pick
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

    private static List<TermEntry> findTermsInConfig(String text, TermbaseConfig config) {
        if (text == null || text.trim().isEmpty() || config == null) {
            return Collections.emptyList();
        }
        String searchKey = text.trim();
        List<TermEntry> terms = TermbaseRegistry.getInstance().getTerms(config);
        List<TermEntry> matches = new ArrayList<>();
        for (TermEntry entry : terms) {
            if (entry.getSourceTerm() != null
                    && entry.getSourceTerm().trim().equalsIgnoreCase(searchKey)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    private static void editTermDirect(TermEntry target) {
        if (target == null) return;
        try {
            TermEntry editCopy = new TermEntry(target.getSourceTerm(), target.getTargetTerm());
            TermEntryDialog dialog = new TermEntryDialog("Edit Term", editCopy);
            dialog.setVisible(true);

            if (dialog.isConfirmed()) {
                TermEntry updated = dialog.getTermEntry();
                TermbaseConfig config = TermbaseRegistry.getInstance().getConfigByFilePath(target.getSourceFilePath());
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
                TermbaseRegistry.getInstance().saveTerms(config, terms);
            }
        } catch (Exception e) {
            System.err.println("Failed to edit term: " + e.getMessage());
        }
    }
}
