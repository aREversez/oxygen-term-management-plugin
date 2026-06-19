package ro.sync.exml.workspace.api.standalone;

public interface StandalonePluginWorkspace {
    void addViewComponentCustomizer(ViewComponentCustomizer c);
    void addMenusAndToolbarsContributorCustomizer(Object c);
    void addMenuBarCustomizer(Object c);
    void addToolbarComponentsCustomizer(Object c);
    void addEditorChangeListener(Object l, int area);
    Object getCurrentEditorAccess(int area);
    void addWindowListener(Object l);
    void removeWindowListener(Object l);
    int MAIN_EDITING_AREA = 0;
}
