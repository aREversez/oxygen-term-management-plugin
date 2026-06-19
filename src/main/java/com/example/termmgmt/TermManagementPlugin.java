package com.example.termmgmt;

import ro.sync.exml.plugin.Plugin;
import ro.sync.exml.plugin.PluginDescriptor;

public class TermManagementPlugin extends Plugin {

    private static TermManagementPlugin instance;

    public TermManagementPlugin(PluginDescriptor descriptor) {
        super(descriptor);
        instance = this;
    }

    public static TermManagementPlugin getInstance() {
        return instance;
    }
}
