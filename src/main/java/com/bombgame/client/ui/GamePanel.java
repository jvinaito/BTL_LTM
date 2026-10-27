package com.bombgame.client.ui;

import com.bombgame.client.NetClient;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Màn hình ván đấu.
 *  - Bảng số 1..100: bấm để chọn (bấm đúp = gửi ngay) hoặc gõ phím số; Enter để gửi.
 *  - Số chọn/gõ "bật" lên có hiệu ứng và được ĐỌC TO.
 *  - Khi có kết quả: số của bạn rồi số đối thủ lần lượt bật ra, kèm âm thanh + đọc to.
 *  - Ô ngoài dải an toàn vỡ dần theo sóng lan; bom nổ có flash, sóng xung kích, rung màn hình, pháo giấy + pháo hoa.
 *
 * Tối ưu hiệu năng: không còn Timer riêng (dùng đồng hồ chung Anim, animation theo dt nên lag không làm hiệu ứng chậm đi),
 * 100 ô số "đứng yên" được vẽ từ sprite dựng sẵn, quầng sáng / chữ phát sáng / hạt đều là sprite cache.
 */
public class GamePanel extends JPanel implements Anim.Tick {
    private static class P { double x, y, vx, vy, g, life, max, size, rot, vr; Color c; boolean rect; }
    private static class Ring { double x, y, r, vr, life, max, w; Color c; }

    private static final double R = 62;
    private static final int MAX_PARTICLES = 520;

    private final NetClient net;
    private final Runnable onQuit;
    private final Random rnd = new Random();
    private final List<P> ps = new ArrayList<>();
    private final List<Ring> rings = new ArrayList<>();
    private final NeonButton send = new NeonButton("GỬI SỐ", Theme.CYAN);
    private final NeonButton sound = new NeonButton("ÂM THANH: BẬT", Theme.GREEN);
    private final NeonButton quit = new NeonButton("THOÁT", Theme.PINK);
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
    private final Timer speakDebounce;
    private final Rectangle2D.Double tmpRect = new Rectangle2D.Double();
    private final Path2D.Double litTicks = new Path2D.Double(), darkTicks = new Path2D.Double();

    // trạng thái ván
    private String me = "", opp = "", status = "", banner = "", sub = "", scoreTxt = "";
    private int round = 1, min = 1, max = 100, myG = -1, oppG = -1, bomb = -1, totalSec = 15;
    private double dMin = 1, dMax = 100, shake, t, flash, entryPop, entryShake, bombA, dangerK;
    private float bannerA;
    private long deadline, revealT0, fireworksUntil, nextFirework;
    private boolean locked, over, soundOn = true, revMe, revOpp;
    private final double[] revA = new double[2];
    private Color bannerC = Theme.GOLD;
    private String entry = "";
    private int lastTickSec = -1;

    // bảng số
    private final double[] alive = new double[101], hov = new double[101], pop = new double[101];
    private final long[] killAt = new long[101];
    private int hover = -1;
    private double gx, gy, cell, cxL, ringCy;
    private int lw = -1, la = -1;

    // cache hình ảnh
    private final BufferedImage[] spAlive = new BufferedImage[101], spDead = new BufferedImage[101];
    private BufferedImage boxImg, sweepImg, cardImg;
    private int cardW, cardH;

    public GamePanel(NetClient net, Runnable onQuit) {
        this.net = net; this.onQuit = onQuit;
        setOpaque(false); setLayout(new BorderLayout()); setFocusable(true);
        controls.setOpaque(false);
        for (NeonButton b : new NeonButton[]{send, sound, quit}) b.setFocusable(false);
        send.setPreferredSize(new Dimension(190, 62)); sound.setPreferredSize(new Dimension(220, 62)); quit.setPreferredSize(new Dimension(150, 62));
        controls.add(send); controls.add(sound); controls.add(quit);
        add(controls, BorderLayout.SOUTH);
        Arrays.fill(alive, 1); Arrays.fill(killAt, Long.MAX_VALUE);
        speakDebounce = new Timer(450, e -> speakEntry());
        speakDebounce.setRepeats(false);
        bindKeys(); bindMouse();
        send.addActionListener(e -> doSend());
        sound.addActionListener(e -> toggleSound());
        quit.addActionListener(e -> {
            if (NeonDialog.confirm(this, "Thoát trận", "Thoát sẽ hủy trận đấu. Bạn chắc chắn chứ?", "THOÁT", "Ở LẠI")) {
                Speaker.stop(); net.send("LEAVE"); onQuit.run();
            }
        });
        Anim.addTick(this);
    }

    @Override public void removeNotify() {
        speakDebounce.stop();
        super.removeNotify();
    }

    // ================= trạng thái từ server =================
    public void start(String me, String opp) {
        this.me = me; this.opp = opp; round = 1; min = 1; max = 100; dMin = 1; dMax = 100;
        over = false; locked = false; myG = oppG = bomb = -1; deadline = 0; bannerA = 0; shake = 0; flash = 0; bombA = 0; dangerK = 0;
        revealT0 = 0; revMe = revOpp = false; revA[0] = revA[1] = 0; entry = ""; hover = -1; fireworksUntil = 0;
        Arrays.fill(alive, 1); Arrays.fill(killAt, Long.MAX_VALUE); Arrays.fill(pop, 0); Arrays.fill(hov, 0);
        ps.clear(); rings.clear(); Speaker.stop();
        status = "Chuẩn bị…"; setCtl(false);
    }

    public void onRound(int r, int mn, int mx, int sec) {
        long now = System.currentTimeMillis();
        round = r; min = mn; max = mx; totalSec = Math.max(1, sec); myG = oppG = -1; locked = false;
        revealT0 = 0; revMe = revOpp = false; revA[0] = revA[1] = 0; lastTickSec = -1;
        entry = ""; speakDebounce.stop();
        deadline = now + sec * 1000L;
        for (int n = 1; n <= 100; n++) if ((n < mn || n > mx) && killAt[n] == Long.MAX_VALUE) killAt[n] = now;
        status = "Chọn một số trong khoảng [" + mn + " - " + mx + "]";
        setCtl(true); requestFocusInWindow();
        SoundFx.play("round");
        Speaker.say("Hiệp " + Speaker.words(r) + ". Dải an toàn từ " + Speaker.words(mn) + " đến " + Speaker.words(mx),
                "Round " + r + ". Safe range " + mn + " to " + mx, true);
    }

    public void onResult(int mine, int o, int mn, int mx) {
        long now = System.currentTimeMillis();
        myG = mine; oppG = o; min = mn; max = mx; deadline = 0; setCtl(false);
        revealT0 = now; revMe = revOpp = false; revA[0] = revA[1] = 0;
        for (int n = 1; n <= 100; n++)
            if ((n < mn || n > mx) && killAt[n] == Long.MAX_VALUE)
                killAt[n] = now + 2000 + Math.min(Math.abs(n - mine), Math.abs(n - o)) * 18L;
        status = "Bạn chọn " + mine + "  ·  Đối thủ chọn " + o;
    }

    public void onGameOver(String outcome, int bomb, String reason, double my, double op) {
        over = true; this.bomb = bomb; deadline = 0; bombA = 0; lay();
        boolean boom = reason.equals("BOOM");
        String vi, en;
        switch (outcome) {
            case "WIN" -> { banner = "CHIẾN THẮNG!"; bannerC = Theme.GOLD; confetti(); vi = "Chiến thắng!"; en = "You win!";
                fireworksUntil = System.currentTimeMillis() + 3200; nextFirework = System.currentTimeMillis() + 500; }
            case "LOSE" -> { banner = "BẠN ĐÃ THUA"; bannerC = Theme.RED; vi = "Bạn đã thua."; en = "You lose."; }
            default -> { banner = "HÒA!"; bannerC = Theme.CYAN; vi = "Hòa."; en = "A draw."; }
        }
        if (boom) {
            sub = myG == bomb && oppG == bomb ? "Cả hai cùng dẫm bom!"
                : myG == bomb ? "Bạn dẫm trúng bom — BÙM!" : "Đối thủ dẫm trúng bom — BÙM!";
            double bx = cxOf(bomb), by = cyOf(bomb);
            burst(bx, by, 150, 12, Theme.GOLD, Theme.RED, new Color(255, 140, 0), Color.WHITE);
            for (int i = 0; i < 4; i++) ring(bx, by, cell * (4 + 2.5 * i), 30 + i * 6, 6 - i, i % 2 == 0 ? Theme.GOLD : Theme.RED);
            flash = 1; shake = myG == bomb ? 26 : 12;
            SoundFx.play("boom");
        } else sub = "Hết 5 hiệp! Gần bom hơn sẽ thắng";
        scoreTxt = "+" + (my == (int) my ? String.valueOf((int) my) : String.valueOf(my)) + " điểm";
        Timer after = new Timer(boom ? 800 : 150, e -> SoundFx.play(outcome.equals("LOSE") ? "lose" : outcome.equals("WIN") ? "win" : "round"));
        after.setRepeats(false); after.start();
        Speaker.say(vi + " Quả bom là " + Speaker.words(bomb), en + " The bomb was " + bomb, false);
        status = "";
    }

    public void setStatus(String s) { status = s; }

    // ================= hành động =================
    private void setCtl(boolean b) { send.setEnabled(b); }

    private boolean canInput() { return !locked && !over && deadline != 0; }

    private int entryVal() { return entry.isEmpty() ? -1 : Integer.parseInt(entry); }

    private void toggleSound() {
        soundOn = !soundOn; SoundFx.setEnabled(soundOn); Speaker.setEnabled(soundOn);
        sound.setText(soundOn ? "ÂM THANH: BẬT" : "ÂM THANH: TẮT");
        if (soundOn) SoundFx.play("select");
    }

    private void typeDigit(int d) {
        if (!canInput() || (entry.isEmpty() && d == 0)) return;
        String s = entry + d;
        if (s.length() > 3 || Integer.parseInt(s) > 100) s = String.valueOf(d);
        if (s.equals("0")) return;
        entry = s; entryChanged(false);
    }

    private void backspace() {
        if (!canInput() || entry.isEmpty()) return;
        entry = entry.substring(0, entry.length() - 1); entryChanged(false);
    }

    private void clearEntry() {
        if (!canInput() || entry.isEmpty()) return;
        entry = ""; entryPop = .6; speakDebounce.stop(); Speaker.stop(); SoundFx.play("key");
    }

    private void entryChanged(boolean click) {
        entryPop = 1; speakDebounce.stop();
        SoundFx.play(click ? "select" : "key");
        int v = entryVal();
        if (v < 1) return;
        pop[v] = 1;
        if (click) speakEntry(); else speakDebounce.restart();
    }

    private void speakEntry() {
        int v = entryVal();
        if (v > 0 && canInput()) Speaker.say(Speaker.words(v), String.valueOf(v), true);
    }

    private void doSend() {
        if (!canInput()) return;
        int n = entryVal();
        if (n < min || n > max) {
            status = n < 0 ? "Hãy chọn một số trước!" : "Số không hợp lệ! Hãy chọn từ " + min + " đến " + max;
            entryShake = 16; shake = 7; SoundFx.play("error");
            return;
        }
        if (speakDebounce.isRunning()) { speakDebounce.stop(); Speaker.say(Speaker.words(n), String.valueOf(n), true); }
        net.send("GUESS|" + n); locked = true; setCtl(false);
        status = "Đã gửi " + n + " — đang chờ đối thủ…";
        SoundFx.play("send");
        lay();
        ring(cxOf(n), cyOf(n), cell * 2.2, 26, 4, Theme.GOLD); pop[n] = 1;
    }

    private void bindKeys() {
        for (int d = 0; d <= 9; d++) {
            final int dd = d;
            key(KeyStroke.getKeyStroke(KeyEvent.VK_0 + d, 0), "d" + d, () -> typeDigit(dd));
            key(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0 + d, 0), "n" + d, () -> typeDigit(dd));
        }
        key(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "bs", this::backspace);
        key(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "del", this::clearEntry);
        key(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "esc", this::clearEntry);
        key(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "enter", this::doSend);
    }

    private void key(KeyStroke ks, String name, Runnable r) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ks, name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (isShowing()) r.run(); }
        });
    }

    private void bindMouse() {
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                hover = cellAt(e.getX(), e.getY());
                boolean ok = hover > 0 && canInput() && hover >= min && hover <= max;
                setCursor(Cursor.getPredefinedCursor(ok ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
            @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
            @Override public void mouseExited(MouseEvent e) { hover = -1; }
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int n = cellAt(e.getX(), e.getY());
                if (n < 0 || !canInput()) return;
                if (n < min || n > max) { shake = 3; SoundFx.play("error"); return; }
                entry = String.valueOf(n); entryChanged(true);
                ring(cxOf(n), cyOf(n), cell * 1.3, 20, 3, Theme.CYAN); burst(cxOf(n), cyOf(n), 14, 4, Theme.CYAN, Color.WHITE);
                if (e.getClickCount() >= 2) doSend();
            }
        };
        addMouseListener(ma); addMouseMotionListener(ma);
    }

    // ================= hiệu ứng =================
    private void burst(double x, double y, int n, double vmax, Color... cs) {
        n = Math.min(n, Math.max(0, MAX_PARTICLES - ps.size()));
        if (Anim.quality() == 0) n /= 2;
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, v = 1 + rnd.nextDouble() * vmax;
            P p = new P(); p.x = x; p.y = y; p.vx = Math.cos(a) * v; p.vy = Math.sin(a) * v; p.g = .18;
            p.life = p.max = 30 + rnd.nextInt(45); p.size = 3 + rnd.nextDouble() * 6; p.c = cs[rnd.nextInt(cs.length)];
            ps.add(p);
        }
    }

    private void ring(double x, double y, double maxR, double life, double w, Color c) {
        Ring r = new Ring(); r.x = x; r.y = y; r.r = 0; r.vr = maxR / life; r.life = r.max = life; r.w = w; r.c = c; rings.add(r);
    }

    private void confetti() {
        Color[] cs = {Theme.GOLD, Theme.CYAN, Theme.PINK, Theme.GREEN, Color.WHITE};
        int n = Math.min(Anim.quality() >= 2 ? 170 : 90, Math.max(0, MAX_PARTICLES - ps.size()));
        for (int i = 0; i < n; i++) {
            P p = new P(); p.x = rnd.nextDouble() * getWidth(); p.y = -rnd.nextDouble() * 280;
            p.vx = (rnd.nextDouble() - .5) * 3; p.vy = 2 + rnd.nextDouble() * 4; p.g = .03; p.rect = true;
            p.rot = rnd.nextDouble() * 6; p.vr = .08 + rnd.nextDouble() * .16;
            p.life = p.max = 170 + rnd.nextInt(90); p.size = 6 + rnd.nextDouble() * 7; p.c = cs[rnd.nextInt(cs.length)];
            ps.add(p);
        }
    }

    private void reveal(int n, Color c, boolean mine) {
        lay();
        double x = cxOf(n), y = cyOf(n);
        pop[n] = 1;
        ring(x, y, cell * 3, 28, 5, c); ring(x, y, cell * 1.6, 18, 3, Color.WHITE);
        burst(x, y, 38, 7, c, Color.WHITE);
        shake = Math.max(shake, 5);
        SoundFx.play("reveal");
        Speaker.say((mine ? "Bạn chọn " : "Đối thủ chọn ") + Speaker.words(n), (mine ? "You picked " : "Opponent picked ") + n, mine);
    }

    /** Cập nhật logic mỗi khung hình (gọi từ đồng hồ chung Anim). Mọi easing đều theo dt nên không phụ thuộc FPS. */
    @Override public void tick(double dt) {
        if (!isVisible()) return;
        long now = System.currentTimeMillis();
        double f = dt * 60;
        t += dt;
        double k12 = Fx.ease(.12, f), k11 = Fx.ease(.11, f), k25 = Fx.ease(.25, f), k08 = Fx.ease(.08, f);
        double d90 = Math.pow(.9, f), d86 = Math.pow(.86, f), d85 = Math.pow(.85, f);
        lay();
        dMin += (min - dMin) * k12; dMax += (max - dMax) * k12;
        shake *= d90; flash *= d90; entryPop *= d86; entryShake *= d85;
        bannerA += ((over ? 1f : 0f) - bannerA) * (float) k08;
        if (over) bombA = Math.min(1, bombA + .03 * f);
        boolean danger = deadline != 0 && !locked && deadline - now <= 5000;
        dangerK += ((danger ? 1 : 0) - dangerK) * k12;

        for (int n = 1; n <= 100; n++) {
            double prev = alive[n];
            boolean dead = now >= killAt[n];
            alive[n] += ((dead ? 0 : 1) - alive[n]) * k11;
            if (prev > .55 && alive[n] <= .55 && dead) burst(cxOf(n), cyOf(n), 7, 3, Theme.RED, new Color(255, 140, 80));
            boolean h = n == hover && canInput() && n >= min && n <= max;
            hov[n] += ((h ? 1 : 0) - hov[n]) * k25;
            if (!h && hov[n] < .01) hov[n] = 0;
            pop[n] *= d90;
            if (pop[n] < .01) pop[n] = 0;
        }

        if (revealT0 != 0) {
            long dm = now - revealT0;
            revA[0] = Fx.clamp01((dm - 300) / 550.0); revA[1] = Fx.clamp01((dm - 1300) / 550.0);
            if (!revMe && dm >= 300) { revMe = true; reveal(myG, Theme.CYAN, true); }
            if (!revOpp && dm >= 1300) { revOpp = true; reveal(oppG, Theme.PINK, false); }
        }

        if (deadline != 0 && !locked) {
            int sec = (int) Math.ceil(Math.max(0, (deadline - now) / 1000.0));
            if (sec != lastTickSec) { lastTickSec = sec; if (sec >= 1 && sec <= 5) SoundFx.play("tick"); }
        }

        if (fireworksUntil > now && now >= nextFirework) {          // pháo hoa khi thắng
            nextFirework = now + 330 + rnd.nextInt(280);
            Color[] cs = {Theme.GOLD, Theme.CYAN, Theme.PINK, Theme.GREEN};
            Color c = cs[rnd.nextInt(cs.length)];
            double x = getWidth() * (.15 + rnd.nextDouble() * .7), y = getHeight() * (.12 + rnd.nextDouble() * .4);
            burst(x, y, 46, 6.5, c, Color.WHITE, c);
            ring(x, y, 70, 22, 3, c);
        }

        double drag = Math.pow(.99, f);
        for (int i = ps.size() - 1; i >= 0; i--) {
            P p = ps.get(i);
            p.x += p.vx * f; p.y += p.vy * f; p.vy += p.g * f; p.vx *= drag; p.rot += p.vr * f; p.life -= f;
            if (p.life <= 0) { ps.set(i, ps.get(ps.size() - 1)); ps.remove(ps.size() - 1); }
        }
        double rd = Math.pow(.96, f);
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring r = rings.get(i);
            r.r += r.vr * f; r.vr *= rd; r.life -= f;
            if (r.life <= 0) rings.remove(i);
        }
    }

    // ================= bố cục =================
    private int ah() { return getHeight() - controls.getHeight(); }

    private void lay() {
        int w = getWidth(), a = ah();
        if (w == lw && a == la) return;
        lw = w; la = a;
        double size = Math.max(220, Math.min(a - 120, w * 0.52));
        double nc = Math.floor(size / 10.0);              // ô vuông kích thước nguyên -> sprite khít, không lệch 1px
        if (nc != cell) {
            cell = nc;
            Arrays.fill(spAlive, null); Arrays.fill(spDead, null);
            boxImg = null; sweepImg = null;
        }
        size = cell * 10;
        gx = Math.round(w - size - 38);
        gy = Math.round(100 + Math.max(0, (a - 100 - 20 - size) / 2.0));
        cxL = gx / 2.0 - 8;
        ringCy = 170;
    }

    private int cellAt(double x, double y) {
        lay();
        if (x < gx || y < gy || x >= gx + cell * 10 || y >= gy + cell * 10) return -1;
        return (int) ((y - gy) / cell) * 10 + (int) ((x - gx) / cell) + 1;
    }

    private double cxOf(int n) { return gx + ((Math.max(1, n) - 1) % 10) * cell + cell / 2; }

    private double cyOf(int n) { return gy + ((Math.max(1, n) - 1) / 10) * cell + cell / 2; }

    // ================= vẽ =================
    @Override public void paint(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        if (shake > .5) g.translate((rnd.nextDouble() - .5) * shake, (rnd.nextDouble() - .5) * shake);
        super.paint(g);
        Theme.aa(g);
        for (Ring r : rings) {
            double k = Math.max(0, r.life / r.max);
            g.setStroke(new BasicStroke((float) (1 + r.w * k)));
            g.setColor(Fx.rgba(r.c, k * .9));
            g.draw(new Ellipse2D.Double(r.x - r.r, r.y - r.r, r.r * 2, r.r * 2));
        }
        drawParticles(g);
        if (flash > .02) { g.setColor(Fx.rgba(Color.WHITE, Math.min(1, flash) * .75)); g.fillRect(0, 0, getWidth(), getHeight()); }
        g.dispose();
    }

    private void drawParticles(Graphics2D g) {
        boolean core = Anim.quality() >= 1;
        for (P p : ps) {
            double k = Math.max(0, Math.min(1, p.life / p.max));
            if (p.rect) {                                      // pháo giấy lật 3D
                AffineTransform old = g.getTransform();
                double flip = Math.max(.15, Math.abs(Math.cos(p.rot * 1.3)));
                g.translate(p.x, p.y);
                g.rotate(p.rot * .5);
                g.setColor(Fx.rgba(p.c, k));
                tmpRect.setRect(-p.size / 2, -p.size * .25 * flip, p.size, p.size * .5 * flip);
                g.fill(tmpRect);
                g.setTransform(old);
            } else {
                Fx.drawGlow(g, p.c, p.x, p.y, p.size * 2.4, k * .9);
                if (core) Fx.drawGlow(g, Color.WHITE, p.x, p.y, p.size * .9, k);
            }
        }
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create(); Theme.aa(g);
        lay();
        int w = getWidth(), a = ah();
        if (dangerK > .01) Fx.drawVignette(g, w, getHeight(), Theme.RED, dangerK * (.30 + .22 * Math.sin(t * 10)));
        drawHeader(g, w);
        drawTimer(g);
        drawRange(g);
        drawCard(g, a);
        drawGrid(g);
        g.setFont(Theme.font(Font.BOLD, 15)); g.setColor(Theme.TEXT); Theme.centerText(g, status, cxL, a - 12);
        if (bannerA > .02) drawBanner(g, a);
        g.dispose();
    }

    private void drawHeader(Graphics2D g, int w) {
        pill(g, me, 30, 24, Theme.CYAN, false); pill(g, opp, w - 30, 24, Theme.PINK, true);
        g.setFont(Theme.font(Font.BOLD, 30)); Theme.glowText(g, "HIỆP " + round + "/5", w / 2.0, 50, Color.WHITE);
        for (int i = 1; i <= 5; i++) {
            double x = w / 2.0 + (i - 3) * 22 - 6, s = 12 + (i == round && !over ? 3 * Math.sin(t * 6) : 0);
            if (i == round) Fx.drawGlow(g, Theme.CYAN, x + 6, 68, s * 1.9, .55);
            g.setColor(i <= round ? Theme.CYAN : new Color(255, 255, 255, 50));
            g.fill(new Ellipse2D.Double(x + 6 - s / 2, 68 - s / 2, s, s));
        }
    }

    private void drawTimer(Graphics2D g) {
        double cx = cxL, cy = ringCy;
        double left = deadline == 0 ? 0 : Math.max(0, (deadline - System.currentTimeMillis()) / 1000.0);
        double frac = deadline == 0 ? 0 : Math.min(1, left / totalSec);
        Color rc = locked ? Theme.GOLD : frac > .33 ? Theme.CYAN : Theme.RED;
        boolean urgent = deadline != 0 && !locked && left <= 5;
        double hr = R * (1.9 + (urgent ? .15 * Math.sin(t * 14) : .05 * Math.sin(t * 2)));
        Fx.drawGlow(g, rc, cx, cy, hr, deadline == 0 ? .10 : .36);
        // 60 vạch chia: gom thành 2 path thay vì 60 lệnh vẽ riêng
        litTicks.reset(); darkTicks.reset();
        for (int i = 0; i < 60; i++) {
            double ang = Math.PI / 2 - i * Math.PI * 2 / 60, r1 = R + 17, r2 = R + (i % 5 == 0 ? 27 : 23);
            Path2D.Double p = i / 60.0 < frac ? litTicks : darkTicks;
            p.moveTo(cx + Math.cos(ang) * r1, cy - Math.sin(ang) * r1);
            p.lineTo(cx + Math.cos(ang) * r2, cy - Math.sin(ang) * r2);
        }
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 28)); g.draw(darkTicks);
        g.setColor(Fx.rgba(rc, .9)); g.draw(litTicks);
        g.setStroke(new BasicStroke(10)); g.setColor(new Color(255, 255, 255, 25));
        g.draw(new Ellipse2D.Double(cx - R, cy - R, 2 * R, 2 * R));
        if (frac > 0) {
            Arc2D arc = new Arc2D.Double(cx - R, cy - R, 2 * R, 2 * R, 90, -360 * frac, Arc2D.OPEN);
            g.setStroke(new BasicStroke(20, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(Fx.rgba(rc, .18)); g.draw(arc);
            g.setStroke(new BasicStroke(10, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.setColor(rc); g.draw(arc);
            double ha = Math.PI / 2 - Math.PI * 2 * frac;       // đốm sáng ở đầu cung
            double hx = cx + Math.cos(ha) * R, hy = cy - Math.sin(ha) * R;
            Fx.drawGlow(g, rc, hx, hy, 20, .9);
            g.setColor(Color.WHITE); g.fill(new Ellipse2D.Double(hx - 3, hy - 3, 6, 6));
        }
        if (urgent && Math.sin(t * 14) > 0.25) {
            g.setColor(Fx.rgba(Theme.RED, .10));
            g.fill(new Ellipse2D.Double(cx - R - 10, cy - R - 10, (R + 10) * 2, (R + 10) * 2));
        }
        float fs = (float) (urgent ? 48 + 7 * Math.sin(t * 14) : 48);
        g.setFont(Theme.font(Font.BOLD, fs));
        g.setColor(deadline == 0 ? Theme.MUTED : urgent ? Theme.RED : locked ? Theme.GOLD : Color.WHITE);
        Theme.centerText(g, deadline == 0 ? "--" : String.valueOf((int) Math.ceil(left)), cx, cy + fs * .36);
        if (deadline != 0) {
            g.setFont(Theme.font(Font.BOLD, 11));
            g.setColor(Fx.rgba(rc, .9));
            Theme.centerText(g, locked ? "ĐÃ GỬI" : urgent ? "SẮP HẾT GIỜ" : "THỜI GIAN CÒN LẠI", cx, cy + 83);
        }
    }

    private void drawRange(Graphics2D g) {
        double y0 = ringCy + R;
        g.setFont(Theme.font(Font.BOLD, 13)); g.setColor(Theme.MUTED); Theme.centerText(g, "DẢI AN TOÀN", cxL, y0 + 38);
        g.setFont(Theme.font(Font.BOLD, 46)); Theme.glowText(g, Math.round(dMin) + "  –  " + Math.round(dMax), cxL, y0 + 88, Theme.GREEN);
    }

    private BufferedImage buildCard(int cw, int ch) {
        BufferedImage im = Fx.image(cw, ch);
        Graphics2D g = im.createGraphics(); Theme.aa(g);
        RoundRectangle2D card = new RoundRectangle2D.Double(0, 0, cw, ch, 28, 28);
        g.setPaint(new GradientPaint(0, 0, new Color(16, 19, 54, 238), 0, ch, new Color(30, 14, 58, 230)));
        g.fill(card);
        g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 26), 0, ch * .4f, new Color(255, 255, 255, 0)));
        g.fill(new RoundRectangle2D.Double(1, 1, cw - 2, ch * .4, 26, 26));
        g.dispose();
        return im;
    }

    private void drawCard(Graphics2D g, int a) {
        double cy0 = ringCy + R + 112, ch = Math.max(120, Math.min(176, a - cy0 - 40));
        double cw = Math.max(260, Math.min(gx - 70, 390)), x0 = cxL - cw / 2;
        boolean resultMode = myG > 0;
        int v = entryVal();
        boolean bad = !resultMode && !locked && v > 0 && (v < min || v > max);
        Color accent = resultMode ? Color.WHITE : locked ? Theme.GOLD : bad ? Theme.RED : Theme.CYAN;
        int iw = (int) cw, ih = (int) ch;
        if (cardImg == null || cardW != iw || cardH != ih) { cardImg = buildCard(iw, ih); cardW = iw; cardH = ih; }
        double glowK = .10 + .06 * Math.sin(t * 3) + .22 * entryPop;
        Fx.drawSoftRect(g, x0, cy0, iw, ih, 28, 16, resultMode ? Theme.PINK : accent, Math.min(1, .45 + glowK * 2.4));
        g.drawImage(cardImg, (int) Math.round(x0), (int) Math.round(cy0), null);
        RoundRectangle2D card = new RoundRectangle2D.Double(x0, cy0, cw, ch, 28, 28);
        g.setPaint(new GradientPaint((float) x0, (float) cy0, Fx.rgba(accent, .95), (float) (x0 + cw), (float) (cy0 + ch), Fx.rgba(Theme.PINK, .7)));
        g.setStroke(new BasicStroke(1.8f)); g.draw(card);

        if (!resultMode) {
            g.setFont(Theme.font(Font.BOLD, 13)); g.setColor(Theme.MUTED);
            Theme.centerText(g, locked ? "ĐÃ KHÓA SỐ" : "SỐ BẠN CHỌN", cxL, cy0 + 30);
            double base = cy0 + ch * .70;
            if (entry.isEmpty()) {
                g.setFont(Theme.font(Font.BOLD, 80)); g.setColor(Fx.rgba(Theme.MUTED, .35 + .25 * Math.sin(t * 4)));
                Theme.centerText(g, "?", cxL, base);
            } else {
                double sc = 1 + .45 * entryPop * entryPop + (locked ? .04 * Math.sin(t * 5) : 0);
                double sx = entryShake > .5 ? (rnd.nextDouble() - .5) * entryShake : 0;
                g.setFont(Theme.font(Font.BOLD, (float) (86 * sc)));
                Color nc = bad ? Theme.RED : locked ? Theme.GOLD : Theme.CYAN;
                Theme.glowText(g, entry, cxL + sx, base, nc);
            }
            g.setFont(Theme.font(Font.PLAIN, 12.5f));
            if (locked) {
                int dots = 1 + (int) (t * 2.5) % 3;
                g.setColor(Theme.GOLD); Theme.centerText(g, "Đang chờ đối thủ" + ".".repeat(dots), cxL, cy0 + ch - 14);
            } else if (bad) {
                g.setColor(Theme.RED); Theme.centerText(g, "Ngoài dải an toàn [" + min + " – " + max + "]", cxL, cy0 + ch - 14);
            } else {
                g.setColor(Theme.MUTED); Theme.centerText(g, "Bấm vào bảng hoặc gõ số  ·  Enter để gửi", cxL, cy0 + ch - 14);
            }
        } else {
            g.setColor(new Color(255, 255, 255, 40)); g.draw(new Line2D.Double(cxL, cy0 + 18, cxL, cy0 + ch - 18));
            drawRevealed(g, cxL - cw / 4, cy0, ch, me, myG, revA[0], Theme.CYAN);
            drawRevealed(g, cxL + cw / 4, cy0, ch, opp, oppG, revA[1], Theme.PINK);
        }
    }

    private void drawRevealed(Graphics2D g, double cx, double cy0, double ch, String name, int val, double p, Color c) {
        String nm = name.length() > 11 ? name.substring(0, 10) + "…" : name;
        g.setFont(Theme.font(Font.BOLD, 13)); g.setColor(c); Theme.centerText(g, nm.toUpperCase(), cx, cy0 + 30);
        double base = cy0 + ch * .70;
        if (p <= 0) {
            g.setFont(Theme.font(Font.BOLD, 70)); g.setColor(Fx.rgba(Theme.MUTED, .35 + .25 * Math.sin(t * 6)));
            Theme.centerText(g, "?", cx, base); return;
        }
        double e = Fx.easeOutBack(p), sc = .35 + .65 * e;
        g.setFont(Theme.font(Font.BOLD, (float) (70 * sc)));
        Theme.glowText(g, String.valueOf(val), cx, base, c, Math.min(1, p * 2));
    }

    // ---------- bảng số ----------
    private void buildGridImages(double size, double pad) {
        int bs = (int) (size + 2 * pad);
        boxImg = Fx.image(bs, bs);
        Graphics2D g = boxImg.createGraphics(); Theme.aa(g);
        RoundRectangle2D box = new RoundRectangle2D.Double(1, 1, bs - 2, bs - 2, 30, 30);
        g.setPaint(new GradientPaint(0, 0, new Color(14, 17, 48, 222), bs, bs, new Color(29, 13, 57, 212)));
        g.fill(box);
        g.setPaint(new GradientPaint(0, 0, Fx.rgba(Theme.CYAN, .8), bs, bs, Fx.rgba(Theme.PINK, .8)));
        g.setStroke(new BasicStroke(1.8f)); g.draw(box);
        g.dispose();
        sweepImg = Fx.image(150, bs);
        g = sweepImg.createGraphics();
        g.setPaint(new LinearGradientPaint(0, 0, 150, 0, new float[]{0f, .5f, 1f},
                new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 11), new Color(255, 255, 255, 0)}));
        g.fillRect(0, 0, 150, bs);
        g.dispose();
    }

    private void drawGrid(Graphics2D g) {
        double size = cell * 10, pad = 14, mx = gx + size / 2, my = gy + size / 2, hr = size * .85;
        if (boxImg == null) buildGridImages(size, pad);
        Fx.drawGlow(g, Theme.GREEN, mx, my, hr, (1 - dangerK) * (.20 + .06 * Math.sin(t * 2)));
        Fx.drawGlow(g, Theme.RED, mx, my, hr, dangerK * (.24 + .08 * Math.sin(t * 9)));
        int bx = (int) (gx - pad), by = (int) (gy - pad), bs = boxImg.getWidth();
        g.drawImage(boxImg, bx, by, null);
        if (dangerK > .02) {
            g.setColor(Fx.rgba(Theme.RED, dangerK * .10));
            g.fill(new RoundRectangle2D.Double(bx + 1, by + 1, bs - 2, bs - 2, 30, 30));
        }
        Shape oldClip = g.getClip();                           // vệt sáng quét ngang qua bảng
        g.clipRect(bx + 6, by + 6, bs - 12, bs - 12);
        g.drawImage(sweepImg, (int) (bx - 150 + ((t * 110) % (bs + 300))), by, null);
        g.setClip(oldClip);
        if (Anim.quality() >= 1) {                             // 2 đốm sáng chạy quanh viền bảng
            double per = Fx.perimeter(bs - 2, bs - 2, 15), s = (t * 150) % per;
            Fx.drawComet(g, bx + 1, by + 1, bs - 2, bs - 2, 15, s, dangerK > .5 ? Theme.RED : Theme.CYAN, .9, 16);
            Fx.drawComet(g, bx + 1, by + 1, bs - 2, bs - 2, 15, s + per / 2, Theme.PINK, .9, 16);
        }
        double dim = over ? 1 - .55 * bombA : 1;
        for (int n = 1; n <= 100; n++) {
            if (over && n == bomb) continue;
            if (isIdle(n)) {                                   // ô đứng yên: chỉ blit sprite
                boolean dead = alive[n] < .5;
                BufferedImage sp = dead ? spDead[n] : spAlive[n];
                if (sp == null) { sp = buildCell(n, dead); if (dead) spDead[n] = sp; else spAlive[n] = sp; }
                if (dim < .999) g.setComposite(Fx.ac(dim));
                g.drawImage(sp, (int) (gx + ((n - 1) % 10) * cell), (int) (gy + ((n - 1) / 10) * cell), null);
                if (dim < .999) g.setComposite(AlphaComposite.SrcOver);
            } else drawCell(g, n);
        }
        if (over && bomb > 0) drawBombCell(g);
    }

    private boolean isIdle(int n) {
        double a = alive[n];
        if (hov[n] != 0 || pop[n] != 0) return false;
        if (a < .07) return true;
        if (a < .999) return false;
        if (entryVal() == n && myG < 0) return false;
        return !((n == myG && revA[0] > 0) || (n == oppG && revA[1] > 0));
    }

    /** Dựng sprite một ô ở trạng thái "đứng yên" (còn sống hoặc đã bị loại). */
    private BufferedImage buildCell(int n, boolean dead) {
        int S = (int) cell;
        BufferedImage im = Fx.image(S, S);
        Graphics2D g = im.createGraphics(); Theme.aa(g);
        double c = S / 2.0;
        if (dead) {
            g.setFont(Theme.font(Font.PLAIN, (float) (cell * .3))); g.setColor(new Color(255, 255, 255, 24));
            Theme.centerText(g, String.valueOf(n), c, c + cell * .1);
        } else {
            double sz = cell - 7, arc = 12;
            RoundRectangle2D rr = new RoundRectangle2D.Double(c - sz / 2, c - sz / 2, sz, sz, arc, arc);
            g.setPaint(new GradientPaint(0, (float) (c - sz / 2), Fx.rgba(Theme.GREEN, .13), 0, (float) (c + sz / 2), new Color(18, 20, 56, 175)));
            g.fill(rr);
            g.setPaint(new GradientPaint(0, (float) (c - sz / 2), new Color(255, 255, 255, 22), 0, (float) c, new Color(255, 255, 255, 0)));
            g.fill(new RoundRectangle2D.Double(c - sz / 2 + 1, c - sz / 2 + 1, sz - 2, sz / 2, arc - 2, arc - 2));
            g.setStroke(new BasicStroke(1.2f)); g.setColor(Fx.rgba(Theme.GREEN, .34)); g.draw(rr);
            g.setFont(Theme.font(Font.BOLD, (float) (cell * (n == 100 ? .30 : .36)))); g.setColor(Fx.rgba(Theme.TEXT, .95));
            Theme.centerText(g, String.valueOf(n), c, c + cell * .13);
        }
        g.dispose();
        return im;
    }

    /** Ô đang chuyển động (hover / chọn / bật lên / đang vỡ) vẽ trực tiếp — chỉ vài ô mỗi khung hình. */
    private void drawCell(Graphics2D g, int n) {
        double a = alive[n], h = hov[n], pp = pop[n];
        double cx = cxOf(n), cy = cyOf(n);
        double dim = over ? 1 - .55 * bombA : 1;
        boolean sel = entryVal() == n && myG < 0;
        boolean mine = n == myG && revA[0] > 0, other = n == oppG && revA[1] > 0;
        double sz = (cell - 7) * (.55 + .45 * a) * (1 + .13 * h + .32 * pp + (sel ? .06 : 0));
        double arc = 12 * (sz / (cell - 7));
        RoundRectangle2D rr = new RoundRectangle2D.Double(cx - sz / 2, cy - sz / 2, sz, sz, arc, arc);
        if (a < .07) {
            g.setFont(Theme.font(Font.PLAIN, (float) (cell * .3))); g.setColor(new Color(255, 255, 255, (int) (24 * dim)));
            Theme.centerText(g, String.valueOf(n), cx, cy + cell * .1);
            return;
        }
        if (sel || h > .05 || pp > .05) {                      // quầng sáng (sprite)
            Color gc = sel ? Theme.CYAN : Theme.GREEN;
            Fx.drawSoftRect(g, cx - sz / 2, cy - sz / 2, sz, sz, arc, 10, gc, .55 + .8 * Math.max(h, sel ? 1 : pp) * .6);
        }
        Color top = Fx.lerp(Theme.RED, sel ? Theme.CYAN : Theme.GREEN, a);
        double ka = a * dim;
        if (sel) {
            g.setPaint(new GradientPaint((float) cx, (float) (cy - sz / 2), Fx.rgba(Theme.CYAN, .95), (float) cx, (float) (cy + sz / 2), Fx.rgba(new Color(60, 90, 255), .9)));
        } else {
            g.setPaint(new GradientPaint((float) cx, (float) (cy - sz / 2), Fx.rgba(top, (.10 + .30 * h) * ka + (1 - a) * .35),
                    (float) cx, (float) (cy + sz / 2), new Color(18, 20, 56, (int) (170 * ka + 60 * (1 - a)))));
        }
        g.fill(rr);
        g.setStroke(new BasicStroke(sel ? 2f : 1.2f));
        g.setColor(sel ? Color.WHITE : Fx.rgba(top, (.30 + .6 * h) * ka + (1 - a) * .4));
        g.draw(rr);
        g.setFont(Theme.font(Font.BOLD, (float) (cell * (n == 100 ? .30 : .36) * Math.min(1.25, sz / (cell - 7)))));
        g.setColor(sel ? Color.WHITE : Fx.rgba(Fx.lerp(Theme.RED, Theme.TEXT, a), .95 * ka + (1 - a) * .6));
        Theme.centerText(g, String.valueOf(n), cx, cy + cell * .13);
        if (mine || other) {                                   // vòng đánh dấu lượt chọn
            double k = mine ? revA[0] : revA[1], pulse = 3 + 2.5 * Math.sin(t * 5);
            Color rc = mine ? Theme.CYAN : Theme.PINK;
            g.setStroke(new BasicStroke(3f)); g.setColor(Fx.rgba(rc, k));
            g.draw(new RoundRectangle2D.Double(cx - sz / 2 - 2, cy - sz / 2 - 2, sz + 4, sz + 4, arc + 4, arc + 4));
            g.setStroke(new BasicStroke(1.5f)); g.setColor(Fx.rgba(rc, k * .4));
            g.draw(new RoundRectangle2D.Double(cx - sz / 2 - pulse - 3, cy - sz / 2 - pulse - 3, sz + 2 * pulse + 6, sz + 2 * pulse + 6, arc + 8, arc + 8));
            if (mine && other) {
                g.setStroke(new BasicStroke(3f)); g.setColor(Fx.rgba(Theme.PINK, k));
                g.draw(new RoundRectangle2D.Double(cx - sz / 2 - 6, cy - sz / 2 - 6, sz + 12, sz + 12, arc + 8, arc + 8));
            }
        }
    }

    private void drawBombCell(Graphics2D g) {
        double cx = cxOf(bomb), cy = cyOf(bomb), s = cell * (1.0 + .35 * Math.min(1, bombA * 3) + .04 * Math.sin(t * 8));
        Fx.drawGlow(g, Theme.RED, cx, cy, s * 2.6, .55);
        Fx.drawGlow(g, Theme.GOLD, cx, cy, s * 1.6, .55);
        RoundRectangle2D rr = new RoundRectangle2D.Double(cx - s / 2, cy - s / 2, s, s, 14, 14);
        g.setPaint(new GradientPaint((float) cx, (float) (cy - s / 2), new Color(255, 190, 60), (float) cx, (float) (cy + s / 2), new Color(200, 30, 40)));
        g.fill(rr);
        g.setStroke(new BasicStroke(2.5f)); g.setColor(Color.WHITE); g.draw(rr);
        Theme.bomb(g, cx, cy + s * .06, s * .27, t);
        g.setFont(Theme.font(Font.BOLD, (float) (s * .24))); g.setColor(Color.WHITE);
        Theme.centerText(g, String.valueOf(bomb), cx, cy + s * .43);
    }

    private void drawBanner(Graphics2D g, int a) {
        float al = Math.min(1, bannerA);
        double x0 = 26, y0 = 98, w = gx - 62, h = a - y0 - 28;
        RoundRectangle2D box = new RoundRectangle2D.Double(x0, y0, w, h, 30, 30);
        float pa = Math.min(1f, al * 2.5f);
        Fx.drawSoftRect(g, x0, y0, w, h, 30, 18, bannerC, .55 * al);
        g.setPaint(new GradientPaint((float) x0, (float) y0, new Color(10, 8, 34, (int) (255 * pa)), (float) x0, (float) (y0 + h), new Color(24, 10, 44, (int) (255 * pa))));
        g.fill(box);
        g.setStroke(new BasicStroke(2f)); g.setColor(Fx.rgba(bannerC, .85 * al)); g.draw(box);
        double cx = x0 + w / 2, sc = .6 + .4 * Math.min(1, bannerA * 1.4);
        g.setFont(Theme.font(Font.BOLD, (float) (44 * sc)));
        Theme.glowText(g, banner, cx, y0 + 64, bannerC, al);
        g.setFont(Theme.font(Font.BOLD, 13)); g.setColor(Fx.rgba(Theme.MUTED, al)); Theme.centerText(g, "QUẢ BOM LÀ", cx, y0 + 112);
        g.setFont(Theme.font(Font.BOLD, (float) (104 * Math.min(1, .5 + bombA))));
        Theme.glowText(g, String.valueOf(bomb), cx, y0 + 208, Theme.GOLD, al);
        g.setFont(Theme.font(Font.BOLD, 17)); g.setColor(Fx.rgba(Color.WHITE, al));
        Theme.centerText(g, sub, cx, y0 + 252);
        g.setFont(Theme.font(Font.BOLD, 22)); g.setColor(Fx.rgba(bannerC, al)); Theme.centerText(g, scoreTxt, cx, y0 + 292);
        g.setFont(Theme.font(Font.PLAIN, 12)); g.setColor(Fx.rgba(Theme.MUTED, al)); Theme.centerText(g, "Sắp hỏi ván mới…", cx, y0 + h - 16);
    }

    private void pill(Graphics2D g, String s, double x, double y, Color c, boolean right) {
        g.setFont(Theme.font(Font.BOLD, 16));
        int tw = g.getFontMetrics().stringWidth(s), w = tw + 56;
        double px = right ? x - w : x;
        RoundRectangle2D rr = new RoundRectangle2D.Double(px, y, w, 36, 36, 36);
        Fx.drawSoftRect(g, px, y, w, 36, 36, 10, c, .38);
        g.setColor(Fx.rgba(c, .16)); g.fill(rr);
        g.setColor(c); g.setStroke(new BasicStroke(1.5f)); g.draw(rr);
        double d = 10 + 2 * Math.sin(t * 4);
        Fx.drawGlow(g, c, px + 19, y + 18, 11, .6);
        g.fill(new Ellipse2D.Double(px + 19 - d / 2, y + 18 - d / 2, d, d));
        g.setColor(Color.WHITE); g.drawString(s, (float) (px + 34), (float) (y + 24));
    }
}
