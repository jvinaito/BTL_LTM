package com.bombgame.client.ui;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Bộ vẽ ô bảng cho sảnh chờ: avatar + tên, huy chương top 3, pill trạng thái, hàng được chọn / di chuột.
 * Một component duy nhất được dùng lại cho mọi ô nên rất nhẹ.
 */
final class CellRenderer extends JComponent implements TableCellRenderer {
    private static final String YOU = " (bạn)";
    private static final Color[][] MEDAL = {
            {new Color(255, 232, 130), new Color(214, 150, 20)},     // vàng
            {new Color(238, 243, 252), new Color(136, 148, 172)},    // bạc
            {new Color(244, 176, 118), new Color(150, 82, 40)}};     // đồng

    private final boolean board;
    private String text = "";
    private int col, cols;
    private boolean sel, hover, mine;

    CellRenderer(boolean board) {
        this.board = board;
        setOpaque(false);
    }

    @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean isSel, boolean foc, int r, int c) {
        text = String.valueOf(v);
        col = c;
        cols = t.getColumnCount();
        sel = isSel;
        hover = Integer.valueOf(r).equals(t.getClientProperty("hoverRow"));
        Object me = t.getClientProperty("me");
        String name = String.valueOf(t.getValueAt(r, board ? 1 : 0));
        if (name.endsWith(YOU)) name = name.substring(0, name.length() - YOU.length());
        mine = me != null && name.equals(me);
        return this;
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Theme.aa(g);
        int w = getWidth(), h = getHeight();
        boolean first = col == 0, last = col == cols - 1;

        if (sel || hover || mine) {                       // nền hàng bo tròn, liền mạch qua các cột
            double l = first ? 4 : -20, r = last ? w - 4 : w + 20;
            RoundRectangle2D rr = new RoundRectangle2D.Double(l, 2, r - l, h - 4, 14, 14);
            g.setColor(sel ? Fx.rgba(Theme.CYAN, .20) : mine ? Fx.rgba(Theme.CYAN, .09) : new Color(255, 255, 255, 15));
            g.fill(rr);
            if (sel) {
                g.setColor(Fx.rgba(Theme.CYAN, .35));
                g.setStroke(new BasicStroke(1f));
                g.draw(rr);
                if (first) {
                    g.setColor(Theme.CYAN);
                    g.fill(new RoundRectangle2D.Double(6, h * .28, 3, h * .44, 3, 3));
                }
            }
        }

        if (board) {
            switch (col) {
                case 0 -> rank(g, w, h);
                case 1 -> name(g, w, h);
                case 2 -> number(g, w, h, Theme.GOLD, true);
                default -> number(g, w, h, Theme.TEXT, false);
            }
        } else {
            switch (col) {
                case 0 -> name(g, w, h);
                case 3 -> status(g, w, h);
                case 1 -> number(g, w, h, Theme.GOLD, true);
                default -> number(g, w, h, Theme.TEXT, false);
            }
        }
        g.dispose();
    }

    private static double baseline(Graphics2D g, int h) {
        FontMetrics fm = g.getFontMetrics();
        return (h + fm.getAscent() - fm.getDescent()) / 2.0;
    }

    private void number(Graphics2D g, int w, int h, Color c, boolean bold) {
        g.setFont(Theme.font(bold ? Font.BOLD : Font.PLAIN, 14));
        g.setColor(c);
        Theme.centerText(g, text, w / 2.0, baseline(g, h));
    }

    private void name(Graphics2D g, int w, int h) {
        boolean you = text.endsWith(YOU);
        String name = you ? text.substring(0, text.length() - YOU.length()) : text;
        double r = 15;
        Fx.drawAvatar(g, name, 12 + r, h / 2.0, r);
        g.setFont(Theme.font(Font.BOLD, 14));
        int tx = (int) (12 + 2 * r + 10);
        String shown = fit(g, name, w - tx - (you ? 54 : 8));
        g.setColor(sel ? Color.WHITE : Theme.TEXT);
        g.drawString(shown, tx, (float) baseline(g, h));
        if (you) {                                          // nhãn "BẠN"
            int bx = tx + g.getFontMetrics().stringWidth(shown) + 8;
            g.setFont(Theme.font(Font.BOLD, 10));
            int bw = g.getFontMetrics().stringWidth("BẠN") + 12;
            RoundRectangle2D pill = new RoundRectangle2D.Double(bx, h / 2.0 - 9, bw, 18, 18, 18);
            g.setColor(Fx.rgba(Theme.CYAN, .18));
            g.fill(pill);
            g.setColor(Fx.rgba(Theme.CYAN, .8));
            g.setStroke(new BasicStroke(1f));
            g.draw(pill);
            g.setColor(Theme.CYAN);
            Theme.centerText(g, "BẠN", bx + bw / 2.0, baseline(g, h));
        }
    }

    private static String fit(Graphics2D g, String s, int maxW) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private void status(Graphics2D g, int w, int h) {
        boolean free = text.contains("Rảnh");
        String label = free ? "Rảnh" : "Bận";
        Color c = free ? Theme.GREEN : Theme.RED;
        g.setFont(Theme.font(Font.BOLD, 12));
        int pw = g.getFontMetrics().stringWidth(label) + 36;
        double x = (w - pw) / 2.0;
        RoundRectangle2D pill = new RoundRectangle2D.Double(x, h / 2.0 - 12, pw, 24, 24, 24);
        g.setColor(Fx.rgba(c, .15));
        g.fill(pill);
        g.setColor(Fx.rgba(c, .75));
        g.setStroke(new BasicStroke(1.2f));
        g.draw(pill);
        double pulse = free ? .6 + .4 * Math.sin(Anim.time() * 3) : .9;
        Fx.drawGlow(g, c, x + 14, h / 2.0, 9, .8 * pulse);
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x + 14 - 3.5, h / 2.0 - 3.5, 7, 7));
        g.drawString(label, (float) (x + 25), (float) baseline(g, h));
    }

    private void rank(Graphics2D g, int w, int h) {
        int n;
        try { n = Integer.parseInt(text.replace("#", "").trim()); } catch (NumberFormatException e) { n = 99; }
        double cx = w / 2.0, cy = h / 2.0;
        if (n >= 1 && n <= 3) {
            Color[] m = MEDAL[n - 1];
            Fx.drawGlow(g, m[0], cx, cy, 24, .38);
            g.setPaint(new RadialGradientPaint((float) (cx - 3), (float) (cy - 4), 17f, new float[]{0, 1}, m));
            g.fill(new Ellipse2D.Double(cx - 12, cy - 12, 24, 24));
            g.setColor(new Color(255, 255, 255, 140));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Ellipse2D.Double(cx - 12, cy - 12, 24, 24));
            g.setFont(Theme.font(Font.BOLD, 13));
            g.setColor(new Color(40, 24, 6));
            Theme.centerText(g, String.valueOf(n), cx, baseline(g, h));
        } else {
            g.setFont(Theme.font(Font.BOLD, 13));
            g.setColor(Theme.MUTED);
            Theme.centerText(g, text, cx, baseline(g, h));
        }
    }
}
