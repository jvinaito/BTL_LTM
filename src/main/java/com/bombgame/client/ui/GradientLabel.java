package com.bombgame.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Tiêu đề chữ chuyển màu gradient chảy liên tục, có quầng sáng mềm (cache). */
public class GradientLabel extends JComponent {
    private String text;
    private final Color a, b;
    private final boolean center;

    public GradientLabel(String text, float size, Color a, Color b, boolean center) {
        this.text = text;
        this.a = a;
        this.b = b;
        this.center = center;
        setOpaque(false);
        setFont(Theme.font(Font.BOLD, size));
        setBorder(new EmptyBorder(6, 18, 10, 18));
    }

    public void setText(String s) {
        text = s;
        revalidate();
        repaint();
    }

    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        Insets in = getInsets();
        return new Dimension(fm.stringWidth(text) + in.left + in.right, fm.getHeight() + in.top + in.bottom);
    }

    @Override protected void paintComponent(Graphics g0) {
        if (text == null || text.isEmpty()) return;
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        Insets in = getInsets();
        int tw = fm.stringWidth(text);
        double x = center ? (getWidth() - tw) / 2.0 : in.left, y = in.top + fm.getAscent();
        double t = Anim.time();
        float[] hsb = Color.RGBtoHSB(Fx.lerp(a, b, .5).getRed(), Fx.lerp(a, b, .5).getGreen(), Fx.lerp(a, b, .5).getBlue(), null);
        Fx.drawTextGlow(g, text, getFont(), x + tw / 2.0, y, Color.getHSBColor(hsb[0], 1f, 1f), .32 + .10 * Math.sin(t * 1.7));
        float period = Math.max(40f, tw * 1.1f), off = (float) ((t * .22) % 1) * period * 2;
        g.setPaint(new GradientPaint((float) x - period * 2 + off, 0, a, (float) x - period + off, 0, b, true));
        g.drawString(text, (float) x, (float) y);
        g.dispose();
    }
}
