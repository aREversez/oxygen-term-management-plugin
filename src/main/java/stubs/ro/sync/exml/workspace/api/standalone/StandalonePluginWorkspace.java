package ro.sync.exml.workspace.api.standalone;

import ro.sync.exml.workspace.api.PluginWorkspace;

public interface StandalonePluginWorkspace extends PluginWorkspace {
    void addViewComponentCustomizer(ViewComponentCustomizer c);
    void addMenusAndToolbarsContributorCustomizer(Object c);
    void addMenuBarCustomizer(Object c);
    void addToolbarComponentsCustomizer(Object c);
    void addEditorChangeListener(Object l, int area);
    Object getCurrentEditorAccess(int area);
    void addWindowListener(Object l);
    void removeWindowListener(Object l);
    int MAIN_EDITING_AREA = 0;
    /** @since 27 */
    void showView(String viewId, boolean show);
    /** @since 27 */
    void openView(String viewId);
    /** @since 27 */
    ViewInfo[] getViews();
    /** @since 27 */
    Object getComponentProvider(String type);
    /**
     * Show a transient message in Oxygen's bottom status bar; it dismisses itself and never
     * blocks the user. In the real SDK this is inherited from WorkspaceUtilities (PluginWorkspace
     * extends Workspace extends WorkspaceUtilities); declared here so the stub mirrors the API the
     * plugin compiles against.
     */
    void showStatusMessage(String message);
}
