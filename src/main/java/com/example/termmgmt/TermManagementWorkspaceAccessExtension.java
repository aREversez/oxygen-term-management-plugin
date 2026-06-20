package com.example.termmgmt;

import java.net.URL;

import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer;
import ro.sync.exml.workspace.api.standalone.ViewInfo;

import com.example.termmgmt.ui.TermManagementView;
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
                    viewInfo.setTitle("Term Management");
                    viewInfo.setIcon(IconUtils.loadLogo(16));
                    viewInfo.setComponent(view);
                }
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
