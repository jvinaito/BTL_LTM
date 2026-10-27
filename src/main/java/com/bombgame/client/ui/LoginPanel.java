package com.bombgame.client.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Ellipse2D;
import java.util.Random;

public class LoginPanel extends JPanel {
    public interface Submit { void go(String host, String cmd, String user, String pass); }

    private final JTextField host = new Theme.NeonField("localhost:9999"), user = new Theme.NeonField("");
    private final JPasswordField pass = new Theme.NeonPassword();
    private final JLabel msg = new JLabel(" ", SwingConstants.CENTER);

    /** Quả bom nhún nhẹ, tia lửa bay từ ngòi, vệ tinh quay quanh. Toàn bộ tính từ Anim.time() nên không cần timer/trạng thái. */
    private static final class BombIcon extends JComponent {
        private static final int SPARKS = 14;
        private final float[][] sp = new float[SPARKS][4];      // pha, vx, vy, tốc độ

        BombIcon() {
            setOpaque(false);
            setPreferredSize(new Dimension(170, 118));
            Random r = new Random(7);
            for (float[] s : sp) { s[0] = r.nextFloat(); s[1] = -1f + r.nextFloat() * 2.4f; s[2] = -1.6f - r.nextFloat() * 1.4f; s[3] = .8f + r.nextFloat() * .8f; }
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Theme.aa(g);
            double t = Anim.time(), cx = getWidth() / 2.0, cy = getHeight() / 2.0 + 12 + Math.sin(t * 2.4) * 3;
            double r = 32;
            Fx.drawGlow(g, Theme.PINK, cx, cy, 78, .30 + .08 * Math.sin(t * 3));
            Fx.drawGlow(g, Theme.CYAN, cx - 26, cy - 14, 54, .12);
            Theme.bomb(g, cx, cy, r, t);
            double sx = cx + r * .95, sy = cy - r * 1.3;          // đầu ngòi
            int n = Anim.quality() >= 2 ? SPARKS : Anim.quality() == 1 ? SPARKS / 2 : 0;
            for (int i = 0; i < n; i++) {
                float[] s = sp[i];
                double u = ((t * s[3] + s[0]) % 1);
                double x = sx + s[1] * u * 30, y = sy + s[2] * u * 26 + 46 * u * u;
                Fx.drawGlow(g, i % 2 == 0 ? Theme.GOLD : Theme.SPARK, x, y, 3 + 5 * (1 - u), (1 - u) * .95);
            }
            if (Anim.quality() >= 1) for (int i = 0; i < 3; i++) {   // vệ tinh quỹ đạo
                double a = t * 1.5 + i * 2.094, d = .5 + .5 * Math.sin(a);
                Color c = i == 1 ? Theme.PINK : Theme.CYAN;
                double ox = cx + Math.cos(a) * 62, oy = cy - 4 + Math.sin(a) * 20;
                Fx.drawGlow(g, c, ox, oy, 8 + 5 * d, .6 + .4 * d);
                g.setColor(Color.WHITE);
                g.fill(new Ellipse2D.Double(ox - 1.5, oy - 1.5, 3, 3));
            }
            g.dispose();
        }
    }

    public LoginPanel(Submit submit) {
        setOpaque(false);
        setLayout(new GridBagLayout());
        GlassPanel card = new GlassPanel(new GridBagLayout());
        card.setBorder(new EmptyBorder(26, 44, 30, 44));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; c.insets = new Insets(2, 0, 2, 0);

        GradientLabel title = new GradientLabel("BOMB GUESS", 42f, Theme.CYAN, Theme.PINK, true);
        JLabel sub = new JLabel("Đoán số · Né bom · Hạ đối thủ", SwingConstants.CENTER);
        sub.setFont(Theme.font(Font.PLAIN, 14));
        sub.setForeground(Theme.MUTED);
        card.add(new BombIcon(), c); card.add(title, c); card.add(sub, c);

        c.insets = new Insets(16, 0, 4, 0); card.add(label("MÁY CHỦ"), c);
        c.insets = new Insets(0, 0, 2, 0); card.add(host, c);
        c.insets = new Insets(10, 0, 4, 0); card.add(label("TÊN ĐĂNG NHẬP"), c);
        c.insets = new Insets(0, 0, 2, 0); card.add(user, c);
        c.insets = new Insets(10, 0, 4, 0); card.add(label("MẬT KHẨU"), c);
        c.insets = new Insets(0, 0, 2, 0); card.add(pass, c);

        msg.setFont(Theme.font(Font.BOLD, 13));
        msg.setForeground(Theme.PINK);
        c.insets = new Insets(10, 0, 2, 0); card.add(msg, c);
        NeonButton login = new NeonButton("ĐĂNG NHẬP", Theme.CYAN), reg = new NeonButton("ĐĂNG KÝ", Theme.PINK);
        login.setPreferredSize(new Dimension(100, 52)); reg.setPreferredSize(new Dimension(100, 52));
        JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
        row.setOpaque(false); row.add(login); row.add(reg);
        c.insets = new Insets(2, -8, -6, -8); card.add(row, c);

        java.awt.event.ActionListener doLogin = e -> submit.go(host.getText().trim(), "LOGIN", user.getText().trim(), new String(pass.getPassword()));
        login.addActionListener(doLogin); pass.addActionListener(doLogin); user.addActionListener(doLogin);
        reg.addActionListener(e -> submit.go(host.getText().trim(), "REGISTER", user.getText().trim(), new String(pass.getPassword())));
        card.setPreferredSize(new Dimension(450, 640));
        add(card);

        addHierarchyListener(e -> {                      // tự đặt con trỏ vào ô phù hợp khi màn hình hiện ra
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing())
                SwingUtilities.invokeLater(() -> (user.getText().isEmpty() ? user : pass).requestFocusInWindow());
        });
    }

    private JLabel label(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.font(Font.BOLD, 11));
        l.setForeground(Theme.MUTED);
        return l;
    }

    public void error(String s) { msg.setForeground(Theme.PINK); msg.setText(s); }

    public void info(String s) { msg.setForeground(Theme.GREEN); msg.setText(s); }
}
