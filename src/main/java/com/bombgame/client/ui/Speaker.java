package com.bombgame.client.ui;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Đọc to số/kết quả bằng giọng nói của hệ điều hành (không cần thư viện ngoài).
 * Windows: System.Speech (nếu có giọng tiếng Việt thì đọc tiếng Việt, không thì đọc số bằng giọng mặc định).
 * macOS: say (giọng Linh). Linux: espeak-ng / espeak / spd-say.
 */
public final class Speaker {
    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WIN = OS.contains("win"), MAC = OS.contains("mac");
    private static volatile boolean enabled = true, failed;

    // ---- Windows: 1 tiến trình PowerShell sống lâu để không bị trễ ----
    private static final java.util.concurrent.ExecutorService WIN_EX = java.util.concurrent.Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "tts-win"); t.setDaemon(true); return t; });
    private static Process ps;
    private static BufferedWriter psIn;

    // ---- macOS / Linux: mỗi câu 1 tiến trình, xếp hàng tuần tự ----
    private static final ThreadPoolExecutor EX = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> { Thread t = new Thread(r, "tts"); t.setDaemon(true); return t; });
    private static volatile Process cur;
    private static volatile int linuxIdx = 0;

    private Speaker() { }

    public static void setEnabled(boolean b) { enabled = b; if (!b) stop(); }

    /** @param vi câu tiếng Việt; @param en câu dự phòng (đọc bằng giọng không phải tiếng Việt); @param interrupt ngắt câu đang đọc */
    public static void say(String vi, String en, boolean interrupt) {
        if (!enabled || failed) return;
        if (WIN) { WIN_EX.execute(() -> winSend(interrupt ? "C" : "Q", vi, en)); return; }   // không chặn EDT
        if (interrupt) stopProc();
        EX.execute(() -> run(vi, en));
    }

    public static void stop() {
        if (WIN) WIN_EX.execute(() -> winSend("S", "", "")); else stopProc();
    }

    /** Khởi động sẵn PowerShell ở luồng nền để câu đọc đầu tiên không bị trễ / khựng giao diện. */
    public static void warmUp() {
        if (!WIN || !enabled) return;
        WIN_EX.execute(() -> {
            synchronized (Speaker.class) {
                try { if (ps == null || !ps.isAlive()) startPs(); } catch (Exception e) { failed = true; }
            }
        });
    }

    // ---------------- Windows ----------------
    private static synchronized void winSend(String flag, String vi, String en) {
        try {
            if (ps == null || !ps.isAlive()) startPs();
            psIn.write(flag + "," + b64(vi) + "," + b64(en));
            psIn.newLine(); psIn.flush();
        } catch (Exception e) { failed = true; }
    }

    private static void startPs() throws IOException {
        String script = String.join("\n",
            "$ErrorActionPreference='SilentlyContinue'",
            "Add-Type -AssemblyName System.Speech",
            "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
            "$vi = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'vi*' } | Select-Object -First 1",
            "if ($vi) { $s.SelectVoice($vi.VoiceInfo.Name) }",
            "$s.Volume = 100",
            "while ($true) {",
            "  $l = [Console]::In.ReadLine()",
            "  if ($null -eq $l) { break }",
            "  $p = $l.Split(',')",
            "  if ($p[0] -eq 'C' -or $p[0] -eq 'S') { $s.SpeakAsyncCancelAll() }",
            "  if ($p[0] -ne 'S') {",
            "    $i = 2; if ($vi) { $i = 1 }",
            "    $t = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($p[$i]))",
            "    if ($t) { [void]$s.SpeakAsync($t) }",
            "  }",
            "}");
        String enc = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc);
        pb.redirectErrorStream(true);
        ps = pb.start();
        psIn = new BufferedWriter(new OutputStreamWriter(ps.getOutputStream(), StandardCharsets.US_ASCII));
        final Process p = ps;
        Thread d = new Thread(() -> { try { while (p.getInputStream().read() >= 0) { } } catch (IOException ignored) { } }, "tts-drain");
        d.setDaemon(true); d.start();
        Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    // ---------------- macOS / Linux ----------------
    private static void stopProc() {
        EX.getQueue().clear();
        Process p = cur;
        if (p != null) p.destroy();
    }

    private static void run(String vi, String en) {
        try {
            if (MAC) {
                if (exec(new String[]{"say", "-v", "Linh", vi}) != 0) exec(new String[]{"say", en});
                return;
            }
            String[][] cands = {
                {"espeak-ng", "-v", "vi", vi}, {"espeak", "-v", "vi", vi}, {"spd-say", "-w", "-l", "vi", vi}};
            for (int i = linuxIdx; i < cands.length; i++) {
                try { exec(cands[i]); linuxIdx = i; return; } catch (IOException notInstalled) { /* thử lệnh kế */ }
            }
            failed = true;
        } catch (IOException | InterruptedException e) { failed = failed || (e instanceof IOException); }
    }

    private static int exec(String[] cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        cur = p;
        new Thread(() -> { try { p.getInputStream().readAllBytes(); } catch (IOException ignored) { } }).start();
        if (!p.waitFor(12, TimeUnit.SECONDS)) { p.destroy(); return -1; }
        return p.exitValue();
    }

    // ---------------- đọc số tiếng Việt ----------------
    private static final String[] D = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};

    public static String words(int n) {
        if (n < 0 || n > 100) return String.valueOf(n);
        if (n == 100) return "một trăm";
        if (n < 10) return D[n];
        if (n == 10) return "mười";
        if (n < 20) return "mười " + (n % 10 == 5 ? "lăm" : D[n % 10]);
        int t = n / 10, u = n % 10;
        String s = D[t] + " mươi";
        if (u == 0) return s;
        if (u == 1) return s + " mốt";
        if (u == 5) return s + " lăm";
        return s + " " + D[u];
    }
}
