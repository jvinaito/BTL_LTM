package com.bombgame.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.Map;

public final class Theme {
    public static final Color BG1 = new Color(0x0f0c29), BG2 = new Color(0x302b63),
            CYAN = new Color(0x00e5ff), PINK = new Color(0xff2d95), GREEN = new Color(0x39ff9c),
            RED = new Color(0xff4757), GOLD = new Color(0xffd166), TEXT = new Color(0xeaf0ff),
            MUTED = new Color(0x8e9ac0), SPARK = new Color(255, 170, 40);

    /** Họ font hiển thị được tiếng Việt: ưu tiên Segoe UI (Windows), rồi các font phổ biến khác. */
    public static final String FAMILY = pickFamily();
    private static final Map<Integer, Font> FONTS = new HashMap<>();

    private Theme() {
    }

    private static String pickFamily() {
        String probe = "Đoán số · Hiệp ẢỆỞỠỢ ươ";
        for (String name : new String[]{"Segoe UI", "Inter", "SF Pro Display", "Helvetica Neue", "Noto Sans",
                "Roboto", "Ubuntu", "Cantarell", "DejaVu Sans", "Arial"}) {
            Font f = new Font(name, Font.PLAIN, 12);
            if (f.getFamily().equalsIgnoreCase(name) && f.canDisplayUpTo(probe) == -1) return name;
        }
        return Font.DIALOG;
    }

    /** Font có cache — trước đây mỗi lần gọi lại tạo Font mới (hàng trăm lần mỗi khung hình). */
    public static Font font(int style, float size) {
        int key = (style << 16) | Math.round(size * 2);
        Font f = FONTS.get(key);
        if (f == null) {
            if (FONTS.size() > 600) FONTS.clear();
            f = new Font(FAMILY, style, 12).deriveFont(size);
            FONTS.put(key, f);
        }
        return f;
    }

    public static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /** Tooltip tối cho hợp giao diện. */
    public static void installDefaults() {
        UIManager.put("ToolTip.background", new Color(18, 20, 52));
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", font(Font.PLAIN, 13));
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0, 229, 255, 150)), new EmptyBorder(4, 8, 4, 8)));
    }

    // ====================== ô nhập liệu bo tròn ======================
    public static class NeonField extends JTextField {
        public NeonField(String text) { super(text); styleField(this); }

        @Override protected void paintComponent(Graphics g) { fieldBackground(g, this); super.paintComponent(g); }
    }

    public static class NeonPassword extends JPasswordField {
        public NeonPassword() { styleField(this); }

        @Override protected void paintComponent(Graphics g) { fieldBackground(g, this); super.paintComponent(g); }
    }

    private static void styleField(JTextComponent f) {
        f.setOpaque(false);
        f.setForeground(TEXT);
        f.setCaretColor(CYAN);
        f.setFont(font(Font.PLAIN, 15));
        f.setSelectionColor(new Color(0, 229, 255, 90));
        f.setSelectedTextColor(Color.WHITE);
        f.setBorder(new EmptyBorder(11, 16, 11, 16));
    }

    /** Giữ lại cho code cũ: gắn style neon vào một JTextField có sẵn. */
    public static void field(JTextField f) { styleField(f); }

    private static void fieldBackground(Graphics g0, JComponent c) {
        Graphics2D g = (Graphics2D) g0.create();
        aa(g);
        int w = c.getWidth(), h = c.getHeight();
        boolean focus = c.hasFocus();
        RoundRectangle2D rr = new RoundRectangle2D.Double(1, 1, w - 2, h - 2, 16, 16);
        g.setColor(new Color(9, 11, 36, 215));
        g.fill(rr);
        if (focus) {
            g.setStroke(new BasicStroke(5f));
            g.setColor(Fx.rgba(CYAN, .12));
            g.draw(new RoundRectangle2D.Double(3, 3, w - 6, h - 6, 13, 13));
        }
        g.setPaint(new GradientPaint(0, 0, Fx.rgba(CYAN, focus ? .95 : .38), w, 0, Fx.rgba(PINK, focus ? .85 : .26)));
        g.setStroke(new BasicStroke(focus ? 1.8f : 1.3f));
        g.draw(rr);
        g.dispose();
    }

    // ====================== vẽ chữ ======================
    public static void centerText(Graphics2D g, String s, double cx, double y) {
        g.drawString(s, (float) (cx - g.getFontMetrics().stringWidth(s) / 2.0), (float) y);
    }

    /** Chữ phát sáng: quầng blur (cache) + chữ rõ nét. */
    public static void glowText(Graphics2D g, String s, double cx, double y, Color c) {
        glowText(g, s, cx, y, c, 1);
    }

    public static void glowText(Graphics2D g, String s, double cx, double y, Color c, double alpha) {
        Fx.drawTextGlow(g, s, g.getFont(), cx, y, c, .8 * alpha);
        g.setColor(alpha >= .999 ? c : Fx.rgba(c, alpha));
        centerText(g, s, cx, y);
    }

    public static void bomb(Graphics2D g, double cx, double cy, double r, double t) {
        int ri = Math.max(6, (int) Math.round(r));
        g.drawImage(Fx.bombBody(ri), (int) Math.round(cx - 2 * ri), (int) Math.round(cy - 2 * ri), null);
        double sx = cx + ri * .95, sy = cy - ri * 1.3, sr = ri * (.22 + .1 * Math.sin(t * 8));
        Fx.drawGlow(g, SPARK, sx, sy, sr * 3.6, .95);
        g.setColor(new Color(255, 240, 150));
        g.fill(new java.awt.geom.Ellipse2D.Double(sx - sr, sy - sr, sr * 2, sr * 2));
    }

    // ====================== bảng dữ liệu ======================
    /** JTable có dòng gợi ý khi chưa có dữ liệu. */
    public static class NeonTable extends JTable {
        private final String empty;

        public NeonTable(TableModel m, String empty) {
            super(m);
            this.empty = empty;
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getRowCount() == 0) {
                Graphics2D g = (Graphics2D) g0.create();
                aa(g);
                g.setFont(font(Font.PLAIN, 14));
                g.setColor(Fx.rgba(MUTED, .85));
                centerText(g, empty, getWidth() / 2.0, Math.min(getHeight() / 2.0, 90));
                g.dispose();
            }
        }
    }

    public static JScrollPane table(JTable t, boolean board) {
        t.setOpaque(false);
        t.setShowGrid(false);
        t.setRowHeight(46);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setForeground(TEXT);
        t.setFillsViewportHeight(true);
        t.setFocusable(false);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setDefaultRenderer(Object.class, new CellRenderer(board));
        int[] widths = board ? new int[]{64, 200, 80, 70, 70} : new int[]{210, 80, 80, 120};
        TableColumnModel cm = t.getColumnModel();
        for (int i = 0; i < cm.getColumnCount() && i < widths.length; i++) cm.getColumn(i).setPreferredWidth(widths[i]);

        t.putClientProperty("hoverRow", -1);
        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { setHover(e.getPoint()); }

            @Override public void mouseExited(MouseEvent e) { setHover(null); }

            private void setHover(Point p) {
                int r = p == null ? -1 : t.rowAtPoint(p);
                if (!Integer.valueOf(r).equals(t.getClientProperty("hoverRow"))) {
                    t.putClientProperty("hoverRow", r);
                    t.repaint();
                }
            }
        };
        t.addMouseMotionListener(hover);
        t.addMouseListener(hover);

        JTableHeader h = t.getTableHeader();
        h.setOpaque(false);
        h.setBackground(new Color(0, 0, 0, 0));
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(0, 34));
        h.setDefaultRenderer((tb, v, s, f, r, c) -> {
            JLabel l = new JLabel(String.valueOf(v).toUpperCase());
            l.setForeground(MUTED);
            l.setFont(font(Font.BOLD, 11));
            l.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(255, 255, 255, 38)),
                    new EmptyBorder(8, 10, 8, 10)));
            boolean left = board ? c == 1 : c == 0;
            l.setHorizontalAlignment(left ? SwingConstants.LEFT : SwingConstants.CENTER);
            return l;
        });

        JScrollPane sp = new JScrollPane(t);
        sp.setColumnHeaderView(h);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getColumnHeader().setOpaque(false);
        sp.setBorder(null);
        sp.setViewportBorder(null);
        sp.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(12, 0));
        sp.getVerticalScrollBar().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        return sp;
    }

    /** Thanh cuộn mảnh, trong suốt. */
    private static final class SlimScrollBarUI extends BasicScrollBarUI {
        @Override protected JButton createDecreaseButton(int o) { return zero(); }

        @Override protected JButton createIncreaseButton(int o) { return zero(); }

        private static JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setFocusable(false);
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }

        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g = (Graphics2D) g0.create();
            aa(g);
            g.setColor(new Color(255, 255, 255, isDragging ? 120 : isThumbRollover() ? 86 : 52));
            g.fill(new RoundRectangle2D.Double(r.x + 3, r.y + 2, r.width - 6, r.height - 4, 8, 8));
            g.dispose();
        }
    }
}
