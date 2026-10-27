package com.bombgame.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Tấm kính mờ viền neon. Phần thân (gradient, ánh sáng trên cùng, quầng góc) được dựng 1 lần vào ảnh cache;
 * mỗi khung hình chỉ vẽ 2 đốm sáng "comet" chạy quanh viền. Không còn Timer riêng.
 */
public class GlassPanel extends JPanel {
    private static final double ARC = 26;
    private final Color c1, c2;
    private BufferedImage cache;
    private int cw, ch;

    public GlassPanel(LayoutManager layout) {
        this(layout, Theme.CYAN, Theme.PINK);
    }

    public GlassPanel(LayoutManager layout, Color c1, Color c2) {
        super(layout);
        this.c1 = c1;
        this.c2 = c2;
        setOpaque(false);
    }

    private BufferedImage build(int w, int h) {
        BufferedImage im = Fx.image(w, h);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        RoundRectangle2D shape = new RoundRectangle2D.Double(1, 1, w - 2, h - 2, ARC, ARC);
        g.setPaint(new GradientPaint(0, 0, new Color(15, 18, 52, 244), w, h, new Color(31, 13, 62, 240)));
        g.fill(shape);
        Shape old = g.getClip();
        g.clip(shape);
        int big = (int) (Math.max(w, h) * .55);
        Fx.drawGlow(g, c1, w * .1, 0, big, .11);
        Fx.drawGlow(g, c2, w * .95, h, big, .11);
        g.setClip(old);
        double sheen = Math.min(110, h * .35);
        g.setPaint(new GradientPaint(0, 2, new Color(255, 255, 255, 30), 0, (float) sheen, new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(2, 2, w - 4, sheen, ARC - 2, ARC - 2));
        g.setStroke(new BasicStroke(1f));
        g.setColor(new Color(255, 255, 255, 18));
        g.draw(new RoundRectangle2D.Double(3.5, 3.5, w - 7, h - 7, ARC - 5, ARC - 5));
        g.setPaint(new GradientPaint(0, 0, Fx.rgba(c1, .75), w, h, Fx.rgba(c2, .75)));   // viền gradient: vẽ 1 lần
        g.setStroke(new BasicStroke(1.5f));
        g.draw(shape);
        g.dispose();
        return im;
    }

    @Override protected void paintComponent(Graphics g0) {
        int w = getWidth(), h = getHeight();
        if (w < 8 || h < 8) return;
        if (cache == null || cw != w || ch != h) { cache = build(w, h); cw = w; ch = h; }
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.drawImage(cache, 0, 0, null);

        double t = Anim.time();
        if (Anim.quality() >= 1) {                       // đốm sáng chạy quanh viền
            double per = Fx.perimeter(w - 2, h - 2, ARC / 2), s = (t * 120) % per;
            Fx.drawComet(g, 1, 1, w - 2, h - 2, ARC / 2, s, c1, .9, 15);
            Fx.drawComet(g, 1, 1, w - 2, h - 2, ARC / 2, s + per / 2, c2, .9, 15);
        }
        g.dispose();
    }
}
