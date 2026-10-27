package com.bombgame.client.ui;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kho "sprite" cache. Quầng sáng, bóng mờ, chữ phát sáng... chỉ được tính MỘT LẦN rồi lưu thành ảnh;
 * mỗi khung hình chỉ cần drawImage (rất rẻ) thay vì tô gradient / lặp nhiều lớp như trước.
 * Dùng trên EDT.
 */
public final class Fx {
    private Fx() { }

    public interface Painter { void paint(Graphics2D g); }

    /** Ảnh glow của chữ + vị trí đường cơ sở (baseline) bên trong ảnh. */
    public record TextGlow(BufferedImage img, int oy) { }

    private static <K, V> Map<K, V> lru(int max) {
        return new LinkedHashMap<>(64, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> e) { return size() > max; }
        };
    }

    private static final Map<Long, BufferedImage> GLOWS = lru(160);
    private static final Map<String, BufferedImage> SHAPES = lru(200);
    private static final Map<String, TextGlow> TEXTS = lru(140);
    private static final Map<Integer, BufferedImage> BOMBS = lru(24);
    private static final AlphaComposite[] AC = new AlphaComposite[65];
    private static final double[] PT = new double[2];

    static {
        for (int i = 0; i < AC.length; i++) AC[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, i / 64f);
    }

    // ====================== tiện ích màu / số ======================
    /** AlphaComposite dùng lại (không cấp phát mới mỗi khung hình). */
    public static AlphaComposite ac(double a) { return AC[(int) Math.round(clamp01(a) * 64)]; }

    public static Color rgba(Color c, double a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.max(0, Math.min(255, a * 255)));
    }

    public static Color lerp(Color a, Color b, double k) {
        k = clamp01(k);
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * k),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }

    public static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /** Hệ số làm mượt theo thời gian thực: f = dt*60 (số "khung 60fps" đã trôi qua). */
    public static double ease(double perFrame, double f) { return 1 - Math.pow(1 - perFrame, f); }

    public static double easeOutBack(double x) {
        double c1 = 1.9, c3 = c1 + 1;
        return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
    }

    // ====================== tạo ảnh ======================
    /** Ảnh tương thích màn hình (được GPU tăng tốc khi vẽ lặp lại). */
    public static BufferedImage image(int w, int h) { return image(w, h, Transparency.TRANSLUCENT); }

    public static BufferedImage image(int w, int h, int transparency) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        try {
            if (!GraphicsEnvironment.isHeadless())
                return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                        .getDefaultConfiguration().createCompatibleImage(w, h, transparency);
        } catch (Throwable ignored) { }
        return new BufferedImage(w, h, transparency == Transparency.OPAQUE
                ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB_PRE);
    }

    // ====================== làm mờ (blur) ======================
    /** Box blur tách trục trên mảng ARGB nhân alpha sẵn (premultiplied). */
    private static void pass(int[] src, int[] dst, int w, int h, int r, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w, stride = horiz ? 1 : w, step = horiz ? w : 1;
        int win = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int base = l * step, a = 0, rr = 0, g = 0, b = 0;
            for (int i = 0; i <= r && i < len; i++) {
                int p = src[base + i * stride];
                a += p >>> 24; rr += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * stride] = ((a / win) << 24) | ((rr / win) << 16) | ((g / win) << 8) | (b / win);
                int out = i - r, in = i + r + 1;
                if (out >= 0) {
                    int p = src[base + out * stride];
                    a -= p >>> 24; rr -= (p >> 16) & 255; g -= (p >> 8) & 255; b -= p & 255;
                }
                if (in < len) {
                    int p = src[base + in * stride];
                    a += p >>> 24; rr += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255;
                }
            }
        }
    }

    private static void blur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        for (int i = 0; i < 3; i++) {                     // 3 lượt box blur ≈ gaussian
            pass(px, tmp, w, h, r, true);
            pass(tmp, px, w, h, r, false);
        }
    }

    /**
     * Vẽ hình bằng painter (toạ độ kích thước đầy đủ w×h), làm mờ ở độ phân giải thấp
     * rồi phóng lên -> quầng sáng mềm, rất rẻ để tạo và để vẽ.
     */
    public static BufferedImage blurred(int w, int h, int down, int radius, Painter p) {
        int sw = Math.max(1, (w + down - 1) / down), sh = Math.max(1, (h + down - 1) / down);
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = small.createGraphics();
        Theme.aa(g);
        g.scale(sw / (double) w, sh / (double) h);
        p.paint(g);
        g.dispose();
        blur(((DataBufferInt) small.getRaster().getDataBuffer()).getData(), sw, sh, Math.max(1, radius));
        BufferedImage big = image(w, h);
        Graphics2D bg = big.createGraphics();
        bg.setComposite(AlphaComposite.Src);
        bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        bg.drawImage(small, 0, 0, w, h, null);
        bg.dispose();
        return big;
    }

    // ====================== quầng sáng tròn ======================
    public static BufferedImage glow(Color c, int radius) {
        int r = Math.max(4, (radius + 3) & ~3);
        long key = ((long) (c.getRGB() & 0xffffff) << 20) | r;
        BufferedImage im = GLOWS.get(key);
        if (im == null) {
            im = image(r * 2, r * 2);
            Graphics2D g = im.createGraphics();
            int R = c.getRed(), G = c.getGreen(), B = c.getBlue();
            g.setPaint(new RadialGradientPaint(r, r, r, new float[]{0f, .2f, .45f, .75f, 1f},
                    new Color[]{new Color(R, G, B, 255), new Color(R, G, B, 150), new Color(R, G, B, 58),
                            new Color(R, G, B, 12), new Color(R, G, B, 0)}));
            g.fillRect(0, 0, r * 2, r * 2);
            g.dispose();
            GLOWS.put(key, im);
        }
        return im;
    }

    public static void drawGlow(Graphics2D g, Color c, double cx, double cy, double radius, double alpha) {
        if (alpha < .01 || radius < 1) return;
        BufferedImage im = glow(c, (int) Math.ceil(radius));
        int r = im.getWidth() / 2;
        Composite old = g.getComposite();
        g.setComposite(ac(alpha));
        g.drawImage(im, (int) Math.round(cx - r), (int) Math.round(cy - r), null);
        g.setComposite(old);
    }

    // ====================== quầng sáng bao quanh hình chữ nhật bo góc ======================
    public static void drawSoftRect(Graphics2D g, double x, double y, double w, double h, double arc, int pad,
                                    Color c, double alpha) {
        if (alpha < .01) return;
        int qw = Math.max(4, (int) Math.round(w / 4.0) * 4), qh = Math.max(4, (int) Math.round(h / 4.0) * 4);
        double qx = x + (w - qw) / 2, qy = y + (h - qh) / 2;
        String key = "r" + qw + "x" + qh + "a" + (int) arc + "p" + pad + "c" + c.getRGB();
        BufferedImage im = SHAPES.get(key);
        if (im == null) {
            im = blurred(qw + 2 * pad, qh + 2 * pad, 3, Math.max(1, pad / 6), gg -> {
                gg.setColor(c);
                gg.fill(new RoundRectangle2D.Double(pad, pad, qw, qh, arc, arc));
            });
            SHAPES.put(key, im);
        }
        Composite old = g.getComposite();
        g.setComposite(ac(alpha));
        g.drawImage(im, (int) Math.round(qx - pad), (int) Math.round(qy - pad), null);
        g.setComposite(old);
    }

    // ====================== chữ phát sáng ======================
    /**
     * Quầng sáng mềm phía sau chữ (blur thật, thay cho 27 lần drawString xếp lệch như trước).
     * Chữ rõ nét vẫn do người gọi tự drawString để không bị mờ khi màn hình có DPI cao.
     */
    public static void drawTextGlow(Graphics2D g, String s, Font f, double cx, double baseY, Color c, double alpha) {
        if (s == null || s.isBlank() || alpha < .01) return;
        float qs = Math.max(8f, Math.round(f.getSize2D() / 4f) * 4f);
        String key = s + '\u0001' + f.getName() + f.getStyle() + qs + '\u0001' + c.getRGB();
        TextGlow tg = TEXTS.get(key);
        if (tg == null) {
            tg = buildTextGlow(s, f.deriveFont(qs), c);
            TEXTS.put(key, tg);
        }
        Composite old = g.getComposite();
        g.setComposite(ac(alpha));
        g.drawImage(tg.img(), (int) Math.round(cx - tg.img().getWidth() / 2.0), (int) Math.round(baseY - tg.oy()), null);
        g.setComposite(old);
    }

    private static TextGlow buildTextGlow(String s, Font f, Color c) {
        int spread = Math.max(3, Math.round(f.getSize2D() * .11f));
        Shape outline = f.createGlyphVector(new FontRenderContext(null, true, true), s).getOutline(0, 0);
        Rectangle2D b = outline.getBounds2D();
        int pad = spread * 3;
        int w = (int) Math.ceil(b.getWidth()) + 2 * pad, h = (int) Math.ceil(b.getHeight()) + 2 * pad;
        double ox = pad - b.getX(), oy = pad - b.getY();
        BufferedImage im = blurred(w, h, 2, Math.max(1, Math.round(spread / 4.5f)), g -> {
            g.translate(ox, oy);
            g.setColor(c);
            g.fill(outline);
            g.setStroke(new BasicStroke(spread * .9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(outline);
        });
        return new TextGlow(im, (int) Math.round(oy));
    }

    // ====================== viền: comet chạy quanh hình chữ nhật bo góc ======================
    public static double perimeter(double w, double h, double r) {
        return 2 * (w - 2 * r) + 2 * (h - 2 * r) + 2 * Math.PI * r;
    }

    /** Điểm cách s đơn vị dọc theo viền (đi theo chiều kim đồng hồ, bắt đầu ở (r,0)). */
    public static void pointOnRoundRect(double s, double w, double h, double r, double[] out) {
        double top = w - 2 * r, side = h - 2 * r, arc = Math.PI * r / 2, per = 2 * top + 2 * side + 4 * arc;
        s = ((s % per) + per) % per;
        if (s < top) { out[0] = r + s; out[1] = 0; return; }
        s -= top;
        if (s < arc) { double a = s / r; out[0] = w - r + Math.sin(a) * r; out[1] = r - Math.cos(a) * r; return; }
        s -= arc;
        if (s < side) { out[0] = w; out[1] = r + s; return; }
        s -= side;
        if (s < arc) { double a = s / r; out[0] = w - r + Math.cos(a) * r; out[1] = h - r + Math.sin(a) * r; return; }
        s -= arc;
        if (s < top) { out[0] = w - r - s; out[1] = h; return; }
        s -= top;
        if (s < arc) { double a = s / r; out[0] = r - Math.sin(a) * r; out[1] = h - r + Math.cos(a) * r; return; }
        s -= arc;
        if (s < side) { out[0] = 0; out[1] = h - r - s; return; }
        s -= side;
        double a = s / r;
        out[0] = r - Math.cos(a) * r;
        out[1] = r - Math.sin(a) * r;
    }

    /** Một đốm sáng có đuôi chạy dọc viền, làm bằng vài sprite glow nên gần như không tốn CPU. */
    public static void drawComet(Graphics2D g, double ox, double oy, double w, double h, double r, double s,
                                 Color c, double alpha, int size) {
        int n = 9;
        double step = size * .5;
        for (int i = n; i >= 0; i--) {
            pointOnRoundRect(s - i * step, w, h, r, PT);
            double k = 1 - i / (double) (n + 1);
            drawGlow(g, c, ox + PT[0], oy + PT[1], size * (.4 + .6 * k), alpha * k * k);
        }
        pointOnRoundRect(s, w, h, r, PT);
        drawGlow(g, Color.WHITE, ox + PT[0], oy + PT[1], size * .3, alpha * .85);
    }

    // ====================== vignette (viền tối / đỏ khi nguy hiểm) ======================
    public static void drawVignette(Graphics2D g, int w, int h, Color c, double alpha) {
        if (alpha < .01 || w < 8 || h < 8) return;
        int s = Math.max(64, Math.max(w, h) / 4);
        String key = "v" + s + "c" + c.getRGB();
        BufferedImage im = SHAPES.get(key);
        if (im == null) {
            im = image(s, s);
            Graphics2D gg = im.createGraphics();
            int R = c.getRed(), G = c.getGreen(), B = c.getBlue();
            gg.setPaint(new RadialGradientPaint(s / 2f, s / 2f, s * .72f, new float[]{0f, .5f, 1f},
                    new Color[]{new Color(R, G, B, 0), new Color(R, G, B, 0), new Color(R, G, B, 215)}));
            gg.fillRect(0, 0, s, s);
            gg.dispose();
            SHAPES.put(key, im);
        }
        Composite old = g.getComposite();
        g.setComposite(ac(alpha));
        g.drawImage(im, 0, 0, w, h, null);
        g.setComposite(old);
    }

    // ====================== avatar tròn theo tên ======================
    private static final Color[] AVATAR = {Theme.CYAN, Theme.PINK, Theme.GREEN, Theme.GOLD,
            new Color(0x9b7bff), new Color(0xff8a3d)};

    public static Color avatarColor(String name) { return AVATAR[Math.abs(name.hashCode()) % AVATAR.length]; }

    public static void drawAvatar(Graphics2D g, String name, double cx, double cy, double r) {
        int ri = (int) Math.round(r), s = ri * 2 + 6;
        String key = "av" + name + ":" + ri;
        BufferedImage im = SHAPES.get(key);
        if (im == null) {
            im = image(s, s);
            Graphics2D gg = im.createGraphics();
            Theme.aa(gg);
            Color c = avatarColor(name);
            Ellipse2D circle = new Ellipse2D.Double(3, 3, ri * 2, ri * 2);
            gg.setPaint(new RadialGradientPaint((float) (3 + ri * .7), (float) (3 + ri * .6), (float) (ri * 1.5),
                    new float[]{0, 1}, new Color[]{lerp(c, Color.WHITE, .35), lerp(c, Color.BLACK, .5)}));
            gg.fill(circle);
            gg.setColor(rgba(c, .95));
            gg.setStroke(new BasicStroke(1.4f));
            gg.draw(circle);
            String ini = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            gg.setFont(Theme.font(Font.BOLD, ri * 1.02f));
            gg.setColor(Color.WHITE);
            Theme.centerText(gg, ini, s / 2.0, s / 2.0 + ri * .36);
            gg.dispose();
            SHAPES.put(key, im);
        }
        g.drawImage(im, (int) Math.round(cx - s / 2.0), (int) Math.round(cy - s / 2.0), null);
    }

    // ====================== thân quả bom (vẽ 1 lần) ======================
    /** Ảnh vuông 4r×4r, tâm quả bom ở (2r,2r). */
    public static BufferedImage bombBody(int r) {
        BufferedImage im = BOMBS.get(r);
        if (im == null) {
            im = image(r * 4, r * 4);
            Graphics2D g = im.createGraphics();
            Theme.aa(g);
            double cx = r * 2, cy = r * 2;
            g.setPaint(new RadialGradientPaint((float) (cx - r * .3), (float) (cy - r * .3), (float) (r * 1.4),
                    new float[]{0, 1}, new Color[]{new Color(110, 112, 140), new Color(12, 12, 22)}));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            g.setColor(new Color(255, 255, 255, 80));
            g.fill(new Ellipse2D.Double(cx - r * .6, cy - r * .65, r * .45, r * .3));
            g.setColor(new Color(180, 170, 150));
            g.setStroke(new BasicStroke((float) (r * .16), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new QuadCurve2D.Double(cx + r * .3, cy - r * .9, cx + r * .6, cy - r * 1.5, cx + r * .95, cy - r * 1.3));
            g.dispose();
            BOMBS.put(r, im);
        }
        return im;
    }
}
