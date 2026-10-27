package com.bombgame.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Nút phát sáng neon. Không còn Timer riêng (trước đây mỗi nút một Timer chạy mãi, repaint cả nền):
 * thân nút được cache, halo là sprite, hiệu ứng hover/nhấn/vệt sáng đọc từ đồng hồ chung {@link Anim}.
 */
public class NeonButton extends JButton {
    private static final double ARC = 20;
    private static final int PAD = 9;
    private final Color c;
    private final double seed;
    private float hover, press;
    private boolean over;
    private double lastT = -1;
    private BufferedImage body;
    private int bw, bh;
    private boolean bOn;

    public NeonButton(String text, Color c) {
        super(text);
        this.c = c;
        this.seed = (System.identityHashCode(this) & 255) / 255.0;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setForeground(Color.WHITE);
        setFont(Theme.font(Font.BOLD, 15));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { over = true; }

            @Override public void mouseExited(MouseEvent e) { over = false; }
        });
    }

    private BufferedImage buildBody(int w, int h, boolean on) {
        BufferedImage im = Fx.image(w, h);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        Color base = on ? c : new Color(88, 90, 112);
        double x = PAD, y = PAD, bwid = w - 2 * PAD, bhei = h - 2 * PAD;
        RoundRectangle2D rr = new RoundRectangle2D.Double(x, y, bwid, bhei, ARC, ARC);
        g.setPaint(new GradientPaint(0, (float) y, Fx.lerp(base, Color.WHITE, .22), 0, (float) (y + bhei), Fx.lerp(base, Color.BLACK, .30)));
        g.fill(rr);
        g.setPaint(new GradientPaint(0, (float) y, new Color(255, 255, 255, on ? 92 : 40), 0, (float) (y + bhei * .55), new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(x + 1, y + 1, bwid - 2, bhei * .55, ARC - 2, ARC - 2));
        g.setStroke(new BasicStroke(1.2f));
        g.setColor(new Color(255, 255, 255, on ? 90 : 38));
        g.draw(new RoundRectangle2D.Double(x + .6, y + .6, bwid - 1.2, bhei - 1.2, ARC - 1, ARC - 1));
        g.dispose();
        return im;
    }

    @Override protected void paintComponent(Graphics g0) {
        int w = getWidth(), h = getHeight();
        if (w < 4 || h < 4) return;
        boolean on = isEnabled();
        double t = Anim.time();
        if (lastT < 0) lastT = t;
        double dt = Math.max(0, Math.min(.1, t - lastT));
        if (dt > 0) {                                            // làm mượt hover / nhấn theo thời gian thực
            lastT = t;
            hover += ((over && on ? 1 : 0) - hover) * (float) Fx.ease(.2, dt * 60);
            press += ((getModel().isPressed() && on ? 1 : 0) - press) * (float) Fx.ease(.35, dt * 60);
        }
        if (body == null || bw != w || bh != h || bOn != on) { body = buildBody(w, h, on); bw = w; bh = h; bOn = on; }

        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        double pulse = .5 + .5 * Math.sin(t * 2.2 + seed * 6.28);
        double bwid = w - 2 * PAD, bhei = h - 2 * PAD;
        if (on) Fx.drawSoftRect(g, PAD, PAD, bwid, bhei, ARC, PAD, c, .50 + .45 * hover + .12 * pulse);

        if (press > .01) {                                       // lún nhẹ khi nhấn
            g.translate(w / 2.0, h / 2.0 + press);
            g.scale(1 - .035 * press, 1 - .035 * press);
            g.translate(-w / 2.0, -h / 2.0);
        }
        g.drawImage(body, 0, 0, null);
        RoundRectangle2D rr = new RoundRectangle2D.Double(PAD, PAD, bwid, bhei, ARC, ARC);
        if (on && hover > .02) {
            g.setComposite(Fx.ac(.15 * hover));
            g.setColor(Color.WHITE);
            g.fill(rr);
            g.setComposite(AlphaComposite.SrcOver);
        }
        if (on && Anim.quality() >= 1) {                         // vệt sáng quét ngang
            double p = hover > .02 ? (t * 1.1 + seed) % 1.7 : (t * .5 + seed * 3.4) % 3.4;   // hover: quét liên tục; rảnh: thỉnh thoảng
            if (p >= 0 && p <= 1) {
                double sx = PAD - 40 + (bwid + 80) * p;
                Shape old = g.getClip();
                g.clip(rr);
                g.setPaint(new LinearGradientPaint((float) (sx - 40), 0, (float) (sx + 40), 0, new float[]{0f, .5f, 1f},
                        new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 64), new Color(255, 255, 255, 0)}));
                g.fill(new java.awt.geom.Rectangle2D.Double(sx - 40, PAD, 80, bhei));
                g.setClip(old);
            }
        }
        g.setFont(getFont());
        FontMetrics fm = g.getFontMetrics();
        String s = getText();
        float tx = (w - fm.stringWidth(s)) / 2f, ty = (h + fm.getAscent() - fm.getDescent()) / 2f;
        g.setColor(new Color(0, 0, 0, on ? 70 : 30));
        g.drawString(s, tx, ty + 1.2f);
        g.setColor(on ? Color.WHITE : new Color(175, 175, 195));
        g.drawString(s, tx, ty);
        g.dispose();
    }
}
