package com.example.termmgmt;

import java.net.URL;

import ro.sync.ecss.extensions.api.AuthorAccess;
import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.editor.page.text.WSTextEditorPage;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer;
import ro.sync.exml.workspace.api.standalone.ViewInfo;
import ro.sync.exml.workspace.api.standalone.actions.MenusAndToolbarsContributorCustomizer;

import com.example.termmgmt.ui.TermManagementView;
import com.example.termmgmt.util.I18N;
import com.example.termmgmt.util.IconUtils;

public class TermManagementWorkspaceAccessExtension
        implements WorkspaceAccessPluginExtension {

    private TermManagementView view;

    @Override
    public void applicationStarted(StandalonePluginWorkspace workspace) {
        workspace.addViewComponentCustomizer(new ViewComponentCustomizer() {
            @Override
            public void customizeView(ViewInfo viewInfo) {
                if ("com.example.termmgmt.TermManagementView".equals(viewInfo.getViewID())) {
                    view = new TermManagementView(workspace);
                    viewInfo.setTitle(I18N.getString("window.title"));
                    viewInfo.setIcon(IconUtils.loadLogo(16));
                    viewInfo.setComponent(view);
                }
            }
        });

        // Register editor context menu (Author + Text modes)
        workspace.addMenusAndToolbarsContributorCustomizer(new MenusAndToolbarsContributorCustomizer() {
            @Override
            public void customizeAuthorPopUpMenu(javax.swing.JPopupMenu menu, AuthorAccess authorAccess) {
                TermContextMenuInstaller.build(menu, authorAccess, view);
            }

            @Override
            public void customizeTextPopUpMenu(javax.swing.JPopupMenu menu, WSTextEditorPage textPage) {
                TermContextMenuInstaller.build(menu, textPage, view);
            }
        });

        // Listen for editor changes to auto-scan
        workspace.addEditorChangeListener(new WSEditorChangeListener() {
            @Override
            public void editorSelected(URL editorUrl) {
                if (view != null) {
                    view.autoScanRecognition();
                }
            }
        }, PluginWorkspace.MAIN_EDITING_AREA);
    }

    @Override
    public boolean applicationClosing() {
        return true;
    }
}
