package com.example.termmgmt.util;

import com.kitfox.svg.SVGDiagram;
import com.kitfox.svg.SVGException;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

public class SvgIcon implements Icon {

    private final SVGDiagram diagram;
    private final int width;
    private final int height;

    public SvgIcon(SVGDiagram diagram, int width, int height) {
        this.diagram = diagram;
        this.width = width;
        this.height = height;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2d = (Graphics2D) g.create();
        g2d.translate(x, y);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double sx = (double) width / diagram.getWidth();
        double sy = (double) height / diagram.getHeight();
        g2d.scale(sx, sy);
        try {
            diagram.render(g2d);
        } catch (SVGException e) {
            e.printStackTrace();
        }
        g2d.dispose();
    }

    @Override
    public int getIconWidth() {
        return width;
    }

    @Override
    public int getIconHeight() {
        return height;
    }
}