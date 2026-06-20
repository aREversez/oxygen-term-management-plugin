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

    public static ImageIcon loadIcon(String name, int size) {
        String svg = readResource("/icons/" + name + ".svg");
        if (svg == null) return null;
        String hex = colorHex(isDarkTheme() ? DARK_ICON : LIGHT_ICON);
        svg = svg.replace("currentColor", hex);
        return render(svg, name, size);
    }

    public static ImageIcon loadLogo(int size) {
        String svg = readResource("/icons/logo.svg");
        if (svg == null) return null;
        return render(svg, "logo", size);
    }

    private static ImageIcon render(String svgContent, String name, int targetSize) {
        try {
            SVGUniverse universe = new SVGUniverse();
            URI uri = universe.loadSVG(new StringReader(svgContent), "icon_" + name);
            SVGDiagram diagram = universe.getDiagram(uri);
            if (diagram == null) return null;

            double dw = diagram.getWidth();
            double dh = diagram.getHeight();
            if (dw <= 0 || dh <= 0) return null;

            int nativeW = (int) Math.ceil(dw);
            int nativeH = (int) Math.ceil(dh);

            // Step 1: render at native SVG size (no transform interference)
            BufferedImage nativeImg = new BufferedImage(nativeW, nativeH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D ng = nativeImg.createGraphics();
            ng.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            diagram.render(ng);
            ng.dispose();

            // Step 2: scale to target size
            BufferedImage result = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
            Graphics2D rg = result.createGraphics();
            rg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            rg.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            rg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            double scale = Math.min((double) targetSize / nativeW, (double) targetSize / nativeH);
            int drawW = (int) Math.round(nativeW * scale);
            int drawH = (int) Math.round(nativeH * scale);
            int x = (targetSize - drawW) / 2;
            int y = (targetSize - drawH) / 2;

            rg.drawImage(nativeImg, x, y, drawW, drawH, null);
            rg.dispose();

            return new ImageIcon(result);
        } catch (SVGException e) {
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
