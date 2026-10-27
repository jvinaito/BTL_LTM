package com.bombgame.client.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Âm thanh hiệu ứng tự tổng hợp (không cần file): bấm số, gửi, đếm ngược, lộ số, nổ bom, thắng/thua. */
public final class SoundFx {
    private static final float SR = 22050f;
    private static final Map<String, byte[]> BANK = new HashMap<>();
    private static final ExecutorService EX = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sfx"); t.setDaemon(true); return t;
    });
    private static final Random R = new Random(7);
    private static volatile boolean enabled = true, broken;

    static {
        BANK.put("key", pcm(tone(1100, .04, .30, 60)));
        BANK.put("select", pcm(cat(tone(700, .05, .35, 30), tone(1050, .09, .35, 25))));
        BANK.put("error", pcm(sweep(210, 110, .25, .45, 5)));
        BANK.put("send", pcm(sweep(500, 1500, .18, .35, 8)));
        BANK.put("tick", pcm(tone(1900, .05, .35, 50)));
        BANK.put("round", pcm(mix(0, tone(523, .35, .22, 6), 0, tone(659, .35, .22, 6), 0, tone(784, .35, .22, 6))));
        BANK.put("reveal", pcm(mix(0, noise(.30, .30, 9, .25), 0, sweep(900, 200, .30, .25, 6), 0, tone(70, .30, .70, 9))));
        BANK.put("boom", pcm(mix(0, noise(1.3, .9, 3.2, .06), 0, tone(55, 1.0, .9, 3.5), 0, noise(.25, .6, 14, .5))));
        BANK.put("win", pcm(cat(tone(523, .13, .4, 5), tone(659, .13, .4, 5), tone(784, .13, .4, 5),
                mix(0, tone(1047, .6, .35, 3), 0, tone(784, .6, .25, 3), 0, tone(523, .6, .25, 3)))));
        BANK.put("lose", pcm(cat(tone(392, .2, .4, 5), tone(349, .2, .4, 5), tone(311, .2, .4, 5), sweep(311, 120, .6, .45, 3))));
    }

    private SoundFx() { }

    public static void setEnabled(boolean b) { enabled = b; }

    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile int fails;

    /** Ép nạp lớp (tổng hợp sẵn mọi âm thanh) và khởi tạo mixer ở luồng nền. */
    public static void warmUp() {
        try { AudioSystem.getClip().close(); } catch (Throwable ignored) { }
    }

    public static void play(String name) {
        if (!enabled || broken) return;
        byte[] d = BANK.get(name);
        if (d == null || ACTIVE.get() >= 8) return;          // giới hạn số clip chồng nhau, tránh nghẽn
        ACTIVE.incrementAndGet();
        EX.execute(() -> {
            Clip c = null;
            boolean registered = false;
            try {
                c = AudioSystem.getClip();
                c.open(new AudioFormat(SR, 16, 1, true, false), d, 0, d.length);
                final Clip cc = c;
                c.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) cc.close();
                    else if (ev.getType() == LineEvent.Type.CLOSE) ACTIVE.decrementAndGet();
                });
                registered = true;
                c.start();
                fails = 0;
            } catch (Exception ex) {
                if (!registered) ACTIVE.decrementAndGet(); else c.close();
                if (++fails >= 4) broken = true;                // chỉ tắt hẳn khi lỗi liên tục
            }
        });
    }

    // ---------- tổng hợp ----------
    private static double[] tone(double f, double dur, double vol, double decay) {
        int n = (int) (SR * dur); double[] b = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / SR, env = Math.exp(-decay * t) * Math.min(1, i / (SR * .002));
            b[i] = (Math.sin(2 * Math.PI * f * t) + .3 * Math.sin(4 * Math.PI * f * t)) * vol * env;
        }
        return b;
    }

    private static double[] sweep(double f0, double f1, double dur, double vol, double decay) {
        int n = (int) (SR * dur); double[] b = new double[n]; double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / SR, f = f0 + (f1 - f0) * (t / dur);
            ph += 2 * Math.PI * f / SR;
            b[i] = Math.sin(ph) * vol * Math.exp(-decay * t) * Math.min(1, i / (SR * .002));
        }
        return b;
    }

    private static double[] noise(double dur, double vol, double decay, double lp) {
        int n = (int) (SR * dur); double[] b = new double[n]; double y = 0;
        for (int i = 0; i < n; i++) {
            y += lp * ((R.nextDouble() * 2 - 1) - y);
            b[i] = y * vol * 3 * Math.exp(-decay * i / SR);
        }
        return b;
    }

    private static double[] cat(double[]... parts) {
        int n = 0; for (double[] p : parts) n += p.length;
        double[] o = new double[n]; int k = 0;
        for (double[] p : parts) { System.arraycopy(p, 0, o, k, p.length); k += p.length; }
        return o;
    }

    /** mix(offsetSamples1, buf1, offsetSamples2, buf2, ...) */
    private static double[] mix(Object... a) {
        int n = 0;
        for (int i = 0; i < a.length; i += 2) n = Math.max(n, ((Integer) a[i]) + ((double[]) a[i + 1]).length);
        double[] o = new double[n];
        for (int i = 0; i < a.length; i += 2) {
            int off = (Integer) a[i]; double[] p = (double[]) a[i + 1];
            for (int j = 0; j < p.length; j++) o[off + j] += p[j];
        }
        return o;
    }

    private static byte[] pcm(double[] d) {
        byte[] b = new byte[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            int v = (int) (Math.max(-1, Math.min(1, d[i])) * 32000);
            b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }
}
