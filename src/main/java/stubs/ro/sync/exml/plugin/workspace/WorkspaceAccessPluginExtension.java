package ro.sync.exml.plugin.workspace;

import ro.sync.exml.plugin.PluginExtension;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;

public interface WorkspaceAccessPluginExtension extends PluginExtension {
    default void init(WorkspaceAccessPluginExtensionContext context) {}
    default void applicationStarted(StandalonePluginWorkspace workspace) {}
    default boolean applicationClosing() { return true; }
}
