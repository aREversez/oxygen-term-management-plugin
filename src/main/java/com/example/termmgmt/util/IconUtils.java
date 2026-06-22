package com.example.termmgmt.util;

import com.kitfox.svg.SVGDiagram;
import com.kitfox.svg.SVGException;
import com.kitfox.svg.SVGUniverse;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class IconUtils {

    private static final Color LIGHT_ICON = new Color(0x505050);
    private static final Color DARK_ICON = new Color(0xC0C0C0);

    private static Boolean isDark = null;

    private IconUtils() {}

    public static boolean isDarkTheme() {
        if (isDark != null) return isDark;
        Color bg = UIManager.getColor("Panel.background");
        if (bg == null) bg = UIManager.getColor("control");
        if (bg == null) return false;
        double brightness = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        isDark = brightness < 128;
        return isDark;
    }

    public static void resetThemeCache() {
        isDark = null;
    }

    public static Icon loadIcon(String name, int size) {
        String svg = readResource("/icons/" + name + ".svg");
        if (svg == null) return null;
        String hex = colorHex(isDarkTheme() ? DARK_ICON : LIGHT_ICON);
        svg = svg.replace("currentColor", hex);
        SVGDiagram diagram = loadDiagram(svg, "icon_" + name);
        if (diagram == null) return null;
        return new SvgIcon(diagram, size, size);
    }

    public static ImageIcon loadLogo(int size) {
        String svg = readResource("/icons/logo.svg");
        if (svg == null) return null;

        try {
            SVGDiagram diagram = loadDiagram(svg, "logo");
            if (diagram == null) return null;

            double dw = diagram.getWidth();
            double dh = diagram.getHeight();
            if (dw <= 0 || dh <= 0) return null;

            double scale = Math.min((double) size / dw, (double) size / dh);
            int renderW = (int) Math.round(dw * scale);
            int renderH = (int) Math.round(dh * scale);

            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            int x = (size - renderW) / 2;
            int y = (size - renderH) / 2;
            g.translate(x, y);
            g.scale(scale, scale);
            diagram.render(g);
            g.dispose();

            return new ImageIcon(img);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static SVGDiagram loadDiagram(String svgContent, String name) {
        try {
            SVGUniverse universe = new SVGUniverse();
            URI uri = universe.loadSVG(new StringReader(svgContent), name);
            SVGDiagram diagram = universe.getDiagram(uri);
            if (diagram == null || diagram.getWidth() <= 0 || diagram.getHeight() <= 0) {
                return null;
            }
            return diagram;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String readResource(String path) {
        try (InputStream is = IconUtils.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String colorHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
