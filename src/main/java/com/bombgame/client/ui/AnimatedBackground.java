package com.bombgame.client.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Nền cyberpunk "lung linh": cực quang chuyển động, bokeh trôi, sao lấp lánh có tia sáng, sao băng,
 * sàn lưới synthwave có phối cảnh và parallax theo chuột.
 *
 * Tối ưu: lớp nền tĩnh (gradient, tinh vân, vignette, quầng chân trời) được vẽ MỘT LẦN vào ảnh cache;
 * mọi thứ chuyển động đều là sprite đã dựng sẵn nên mỗi khung hình chỉ tốn vài chục lệnh drawImage.
 * Tự giảm số lượng hiệu ứng khi {@link Anim#quality()} bị hạ.
 */
public class AnimatedBackground extends JPanel {
    private static final int NS = 120, ND = 80, NB = 16;
    private static final Color VIOLET = new Color(125, 55, 255), ICE = new Color(190, 215, 255);
    private static final Color[] STAR_COL = {Theme.CYAN, Theme.PINK, ICE};
    private static final int[] STAR_CORE = {2, 3, 4};
    private static final int[] BOKEH_R = {22, 36, 54, 74};

    private final float[][] stars = new float[NS][6];    // x, y, tốc độ, cỡ(0-2), pha, màu
    private final float[][] dust = new float[ND][5];     // x, y, pha, tốc độ nhấp nháy, màu
    private final float[][] bokeh = new float[NB][7];    // x, y, tốc độ, cỡ(0-3), màu, biên độ ngang, pha
    private final BufferedImage[][] starImg = new BufferedImage[3][3];
    private final BufferedImage[] flare = new BufferedImage[3];
    private final BufferedImage[][] bokehImg = new BufferedImage[3][4];
    private final Color[][] dustCol = new Color[3][16];

    private BufferedImage base, floorImg, ringImg;
    private int bw, bh;
    private double mx, my, lastT;                         // parallax theo chuột (đã làm mượt, -1..1)
    private long shootCycle = -1;
    private double shootOff, shootX, shootY, shootDx, shootDy;

    public AnimatedBackground() {
        setOpaque(true);
        setBackground(new Color(7, 5, 28));
        Random r = new Random(2710);
        for (float[] s : stars) {
            s[0] = r.nextFloat();
            s[1] = r.nextFloat();
            float p = r.nextFloat();
            s[3] = p < .70f ? 0 : p < .92f ? 1 : 2;
            s[2] = (.008f + r.nextFloat() * .03f) * (1 + s[3] * .6f);
            s[4] = r.nextFloat();
            s[5] = r.nextInt(3);
        }
        for (float[] d : dust) { d[0] = r.nextFloat(); d[1] = r.nextFloat(); d[2] = r.nextFloat(); d[3] = r.nextFloat(); d[4] = r.nextInt(3); }
        Color[] bc = {Theme.CYAN, Theme.PINK, VIOLET};
        for (float[] b : bokeh) {
            b[0] = r.nextFloat(); b[1] = r.nextFloat(); b[2] = .012f + r.nextFloat() * .02f;
            b[3] = r.nextInt(4); b[4] = r.nextInt(3); b[5] = 10 + r.nextFloat() * 30; b[6] = r.nextFloat() * 6.28f;
        }
        for (int c = 0; c < 3; c++) {
            for (int s = 0; s < 3; s++) starImg[c][s] = starSprite(STAR_COL[c], STAR_CORE[s]);
            flare[c] = flareSprite(STAR_COL[c]);
            for (int s = 0; s < 4; s++) bokehImg[c][s] = bokehSprite(bc[c], BOKEH_R[s]);
            for (int i = 0; i < 16; i++) dustCol[c][i] = Fx.rgba(STAR_COL[c], .15 + i / 15.0 * .85);
        }
    }

    // ====================== sprite ======================
    private static BufferedImage starSprite(Color c, int core) {
        int r = core * 3 + 2;
        BufferedImage im = Fx.image(r * 2, r * 2);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        g.setPaint(new RadialGradientPaint(r, r, r, new float[]{0, 1}, new Color[]{Fx.rgba(c, .6), Fx.rgba(c, 0)}));
        g.fill(new Ellipse2D.Float(0, 0, r * 2, r * 2));
        g.setColor(Fx.lerp(c, Color.WHITE, .65));
        g.fill(new Ellipse2D.Float(r - core / 2f, r - core / 2f, core, core));
        g.dispose();
        return im;
    }

    /** Tia sáng 4 cánh cho những ngôi sao lấp lánh. */
    private static BufferedImage flareSprite(Color c) {
        int r = 13, s = r * 2;
        BufferedImage im = Fx.image(s, s);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Color core = Fx.lerp(c, Color.WHITE, .7);
        for (int i = 0; i < 4; i++) {
            double a = i * Math.PI / 2;
            float ex = (float) (r + Math.cos(a) * (r - 1)), ey = (float) (r + Math.sin(a) * (r - 1));
            g.setPaint(new GradientPaint(r, r, Fx.rgba(core, .95), ex, ey, Fx.rgba(c, 0)));
            g.draw(new Line2D.Float(r, r, ex, ey));
        }
        g.dispose();
        return im;
    }

    private static BufferedImage bokehSprite(Color c, int r) {
        BufferedImage im = Fx.image(r * 2, r * 2);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        g.setPaint(new RadialGradientPaint(r, r, r, new float[]{0f, .78f, .93f, 1f},
                new Color[]{Fx.rgba(c, .10), Fx.rgba(c, .16), Fx.rgba(c, .40), Fx.rgba(c, 0)}));
        g.fill(new Ellipse2D.Float(0, 0, r * 2, r * 2));
        g.dispose();
        return im;
    }

    // ====================== lớp nền tĩnh ======================
    private BufferedImage buildBase(int w, int h) {
        BufferedImage im = Fx.image(w, h, Transparency.OPAQUE);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        g.setPaint(new GradientPaint(0, 0, new Color(6, 4, 24), 0, h, new Color(31, 12, 64)));
        g.fillRect(0, 0, w, h);
        g.setPaint(new GradientPaint(0, 0, new Color(0, 34, 78, 80), w, h, new Color(80, 0, 90, 80)));
        g.fillRect(0, 0, w, h);
        // tinh vân tĩnh
        Fx.drawGlow(g, VIOLET, w * .50, h * .42, Math.max(w, h) * .42, .16);
        Fx.drawGlow(g, new Color(0, 160, 200), w * .08, h * .05, Math.max(w, h) * .30, .12);
        Fx.drawGlow(g, new Color(190, 20, 120), w * .95, h * .98, Math.max(w, h) * .30, .12);
        // quầng chân trời
        int hz = (int) (h * .6);
        g.setPaint(new GradientPaint(0, hz - 130, new Color(255, 40, 150, 0), 0, hz, new Color(255, 40, 150, 52)));
        g.fillRect(0, hz - 130, w, 130);
        g.setPaint(new GradientPaint(0, hz, new Color(255, 40, 150, 52), 0, hz + 100, new Color(255, 40, 150, 0)));
        g.fillRect(0, hz, w, 100);
        // sàn tối dần về phía người xem
        g.setPaint(new GradientPaint(0, hz, new Color(6, 2, 24, 0), 0, h, new Color(6, 2, 24, 130)));
        g.fillRect(0, hz, w, h - hz);
        Fx.drawVignette(g, w, h, new Color(2, 1, 12), .8);
        g.dispose();
        return im;
    }

    /** Các đường dọc của sàn lưới (gần như tĩnh) — cache, chỉ blit lệch theo parallax. */
    private BufferedImage buildFloor(int w, int h) {
        int hz = (int) (h * .6), fh = Math.max(1, h - hz), fw = w + 80;
        BufferedImage im = Fx.image(fw, fh);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        g.setStroke(new BasicStroke(1f));
        double span = w / 11.0, cx = fw / 2.0;
        for (int i = -16; i <= 16; i++) {
            double xt = cx + i * span * .16, xb = cx + i * span, ym = fh * .45, xm = xt + (xb - xt) * .45;
            g.setColor(new Color(0, 225, 255, 12));
            g.draw(new Line2D.Double(xt, 0, xm, ym));
            g.setColor(new Color(0, 225, 255, 32));
            g.draw(new Line2D.Double(xm, ym, xb, fh));
        }
        g.dispose();
        return im;
    }

    private static final double[] RING_R = {150, 225};

    /** Hai vòng quỹ đạo (ellipse) vẽ sẵn: tâm ảnh = tâm vòng. */
    private BufferedImage buildRings() {
        int rw = (int) (RING_R[1] * 2) + 8, rh = (int) (RING_R[1] * .64) + 8;
        BufferedImage im = Fx.image(rw, rh);
        Graphics2D g = im.createGraphics();
        Theme.aa(g);
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 2; i++) {
            g.setColor(Fx.rgba(i == 0 ? Theme.CYAN : Theme.PINK, .10));
            g.draw(new Ellipse2D.Double(rw / 2.0 - RING_R[i], rh / 2.0 - RING_R[i] * .32, RING_R[i] * 2, RING_R[i] * .64));
        }
        g.dispose();
        return im;
    }

    private void updateParallax(double time) {
        double d = Math.min(.1, Math.max(0, time - lastT));
        lastT = time;
        if (d <= 0) return;
        double tx = 0, ty = 0;
        if (!GraphicsEnvironment.isHeadless() && isShowing() && getWidth() > 0 && getHeight() > 0) {
            try {
                PointerInfo pi = MouseInfo.getPointerInfo();
                if (pi != null) {
                    Point p = pi.getLocation();
                    SwingUtilities.convertPointFromScreen(p, this);
                    tx = Math.max(-1, Math.min(1, p.x / (double) getWidth() * 2 - 1));
                    ty = Math.max(-1, Math.min(1, p.y / (double) getHeight() * 2 - 1));
                }
            } catch (Throwable ignored) { }
        }
        double k = 1 - Math.exp(-d * 3);
        mx += (tx - mx) * k;
        my += (ty - my) * k;
    }

    private static double frac(double v) { return v - Math.floor(v); }

    // ====================== vẽ ======================
    @Override protected void paintComponent(Graphics g0) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (base == null || bw != w || bh != h) {
            base = buildBase(w, h); floorImg = buildFloor(w, h); ringImg = buildRings(); bw = w; bh = h;
        }
        double time = Anim.time();
        int q = Anim.quality();
        updateParallax(time);
        double px = mx * 14, py = my * 9;

        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        g.drawImage(base, 0, 0, null);
        double pulse = .5 + .5 * Math.sin(time * .7);

        // cực quang di chuyển
        if (q >= 1) {
            Fx.drawGlow(g, Theme.CYAN, w * (.18 + .08 * Math.sin(time * .5)) + px * 1.2, h * (.22 + .04 * Math.cos(time * .4)) + py * 1.2, 250, .36);
            Fx.drawGlow(g, Theme.PINK, w * (.82 + .07 * Math.cos(time * .38)) + px * 1.2, h * (.72 + .04 * Math.sin(time * .33)) + py * 1.2, 270, .32);
        }
        if (q >= 2)
            Fx.drawGlow(g, VIOLET, w * .5 + px * .5, h * (.5 + .05 * Math.sin(time * .25)) + py * .5, 210, .16 + .11 * pulse);

        // bokeh trôi lên
        int nb = q >= 2 ? NB : q == 1 ? NB / 2 : 0;
        for (int i = 0; i < nb; i++) {
            float[] b = bokeh[i];
            BufferedImage im = bokehImg[(int) b[4]][(int) b[3]];
            int r = im.getWidth() / 2;
            double y = frac(b[1] - b[2] * time) * (h + 2 * r) - r + py * 1.4;
            double x = b[0] * w + Math.sin(time * .3 + b[6]) * b[5] + px * 1.6;
            double edge = Math.min(1, Math.min(y + r, h + r - y) / 90.0);
            g.setComposite(Fx.ac((.42 + .34 * Math.sin(time * .6 + b[6] * 5)) * Math.max(0, edge)));
            g.drawImage(im, (int) Math.round(x - r), (int) Math.round(y - r), null);
        }
        g.setComposite(AlphaComposite.SrcOver);

        // sàn lưới synthwave
        if (q >= 1) {
            double hz = h * .6, cx = w * .5 + px * .6;
            g.setStroke(new BasicStroke(1f));
            double off = (time * .22) % 1;
            for (int k = 0; k < 14; k++) {
                double u = (k + off) / 14.0, y = hz + (h - hz) * u * u;
                g.setColor(new Color(255, 45, 149, (int) (8 + 54 * u * (.85 + .15 * pulse))));
                g.draw(new Line2D.Double(0, y, w, y));
            }
            g.drawImage(floorImg, (int) Math.round(-40 + px * .6), (int) hz, null);
        }

        // vòng quỹ đạo năng lượng với vệ tinh nhỏ
        if (q >= 1) {
            double rcx = w * .5 + px * .4, rcy = h * .46 + py * .4;
            g.drawImage(ringImg, (int) Math.round(rcx - ringImg.getWidth() / 2.0), (int) Math.round(rcy - ringImg.getHeight() / 2.0), null);
            for (int i = 0; i < 2; i++) {
                double rad = RING_R[i];
                Color c = i == 0 ? Theme.CYAN : Theme.PINK;
                double ang = time * (.55 + .22 * i) * (i == 0 ? 1 : -1) + i * 2.4;
                double depth = .5 + .5 * Math.sin(ang);
                Fx.drawGlow(g, c, rcx + Math.cos(ang) * rad, rcy + Math.sin(ang) * rad * .32, 7 + 5 * depth, .55 + .4 * depth);
            }
        }

        // sao
        int ns = q >= 2 ? NS : q == 1 ? (int) (NS * .6) : (int) (NS * .35);
        for (int i = 0; i < ns; i++) {
            float[] s = stars[i];
            int sz = (int) s[3];
            double depth = 1 + sz * .8;
            double x = ((s[0] * w + px * depth * .6) % w + w) % w;
            double y = frac(s[1] - s[2] * time) * h + py * depth * .6;
            double tw = .5 + .5 * Math.sin(time * (1.2 + s[2] * 55) + s[4] * 40);
            BufferedImage im = starImg[(int) s[5]][sz];
            int r = im.getWidth() / 2;
            g.setComposite(Fx.ac(.30 + .70 * tw));
            g.drawImage(im, (int) x - r, (int) y - r, null);
            if (sz >= 1 && q >= 1 && i % 3 == 0) {                 // tia lấp lánh
                BufferedImage fl = flare[(int) s[5]];
                g.setComposite(Fx.ac(tw * tw * tw));
                g.drawImage(fl, (int) x - fl.getWidth() / 2, (int) y - fl.getHeight() / 2, null);
            }
        }
        g.setComposite(AlphaComposite.SrcOver);

        // bụi lấp lánh
        int nd = q >= 2 ? ND : q == 1 ? ND / 2 : 0;
        for (int i = 0; i < nd; i++) {
            float[] d = dust[i];
            double a = .5 + .5 * Math.sin(time * (2 + d[3] * 3) + d[2] * 30);
            a *= a;
            if (a < .12) continue;
            int x = (int) (((d[0] * w + px * .3) % w + w) % w), y = (int) (frac(d[1] - time * .004) * h);
            g.setColor(dustCol[(int) d[4]][(int) (a * 15)]);
            g.fillRect(x, y, 2, 2);
        }

        // sao băng
        long cyc = (long) Math.floor(time / 7.0);
        if (cyc != shootCycle) {
            shootCycle = cyc;
            Random r = new Random(cyc * 7919L + 13);
            shootOff = 1 + r.nextDouble() * 4.5;
            shootX = .1 + r.nextDouble() * .6;
            shootY = r.nextDouble() * .35;
            double ang = Math.toRadians(20 + r.nextDouble() * 25);
            shootDx = Math.cos(ang);
            shootDy = Math.sin(ang);
        }
        double ph = (time - cyc * 7.0 - shootOff) / .9;
        if (q >= 1 && ph > 0 && ph < 1) {
            double len = w * .13, travel = w * .45, hx = shootX * w + ph * travel * shootDx, hy = shootY * h + ph * travel * shootDy;
            double tx = hx - shootDx * len, ty = hy - shootDy * len, fade = Math.sin(ph * Math.PI);
            g.setPaint(new GradientPaint((float) tx, (float) ty, Fx.rgba(Color.WHITE, 0), (float) hx, (float) hy, Fx.rgba(Color.WHITE, .9 * fade)));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(tx, ty, hx, hy));
            Fx.drawGlow(g, Theme.CYAN, hx, hy, 15, .85 * fade);
        }
        g.dispose();
    }
}
