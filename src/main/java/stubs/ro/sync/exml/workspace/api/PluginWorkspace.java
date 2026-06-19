package ro.sync.exml.workspace.api;

/**
 * Stub interface for Oxygen PluginWorkspace.
 * The real Oxygen SDK declares this as an interface (NOT a class).
 * Used for compilation only - excluded from JAR (ro/sync/**).
 */
public interface PluginWorkspace {

    int MAIN_EDITING_AREA = 0;

    /**
     * Get the options storage for persisting preferences.
     */
    Object getOptionsStorage();

    /**
     * Get the current editor access.
     */
    Object getCurrentEditorAccess(int editingArea);
}
