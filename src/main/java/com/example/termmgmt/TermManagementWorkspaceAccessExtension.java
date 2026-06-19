package com.example.termmgmt;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.util.Base64;

import javax.swing.ImageIcon;

import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.listeners.WSEditorChangeListener;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ViewComponentCustomizer;
import ro.sync.exml.workspace.api.standalone.ViewInfo;

import com.example.termmgmt.ui.TermManagementView;

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
                    viewInfo.setIcon(createViewIcon());
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

    private static ImageIcon createViewIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        // Dark blue rounded square background with white "T" letter
        g.setColor(new java.awt.Color(0x2B579A));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 12));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String letter = "T";
        int x = (16 - fm.stringWidth(letter)) / 2;
        int y = (16 - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(letter, x, y);
        g.dispose();

        // Convert to base64 PNG for future use (optional)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(img, "png", baos);
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            // b64 can be embedded as a constant if desired
        } catch (Exception e) {
            // Ignore
        }

        return new ImageIcon(img);
    }

    @Override
    public boolean applicationClosing() {
        return true;
    }
}
