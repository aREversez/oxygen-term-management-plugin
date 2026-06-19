package ro.sync.exml.plugin.option;

import ro.sync.exml.plugin.PluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;
import javax.swing.JComponent;

/**
 * Stub for Oxygen OptionPagePluginExtension.
 * Signatures match the real Oxygen 27/28 SDK javadoc.
 *
 * KEY: init/apply take PluginWorkspace (not PluginOptionsAdapter),
 * and restoreDefaults() has NO parameter.
 */
public abstract class OptionPagePluginExtension implements PluginExtension {

    /**
     * Initialize the option page GUI and load stored option values.
     * May be called multiple times (e.g. after Cancel to reload state).
     * @param pluginWorkspace the workspace, use getOptionsStorage() for persistence
     * @return the Swing component to display
     */
    public abstract JComponent init(PluginWorkspace pluginWorkspace);

    /**
     * Called when Apply or OK is pressed. Save options here.
     * @param pluginWorkspace the workspace
     */
    public abstract void apply(PluginWorkspace pluginWorkspace);

    /**
     * Called when Restore Defaults is pressed.
     * NO parameter (unlike init/apply).
     */
    public abstract void restoreDefaults();

    /**
     * Returns the title shown in the Preferences tree node.
     */
    public abstract String getTitle();

    /**
     * Optional: returns the option page key.
     */
    public String getKey() { return null; }
}
