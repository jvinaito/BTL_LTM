package com.bombgame.server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Xử lý 1 client: đăng nhập, thách đấu, chuyển lệnh vào phòng chơi. */
public class ClientHandler extends Thread {
    private final Socket sock;
    private PrintWriter out;
    volatile String name;
    volatile double score;
    volatile int wins;
    volatile boolean busy;
    volatile GameRoom room;
    volatile String invitedBy, invitedTarget;

    ClientHandler(Socket s) { this.sock = s; setDaemon(true); }

    synchronized void send(String s) { if (out != null) out.println(s); }

    void refreshStats() { double[] st = PlayerDao.stats(name); score = st[0]; wins = (int) st[1]; }

    @Override public void run() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
            out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true);
            String line;
            while ((line = in.readLine()) != null) {
                try { handle(line.split("\\|", -1)); }
                catch (Exception e) { send("ERROR|" + e.getMessage()); }
            }
        } catch (IOException ignored) {
        } finally { cleanup(); }
    }

    private void handle(String[] m) {
        if (name == null) {
            if (m.length < 3) return;
            String u = m[1].trim().toLowerCase(), p = m[2];
            if (m[0].equals("REGISTER")) {
                if (!u.matches("[a-z0-9_]{3,16}") || p.length() < 3) { send("LOGIN_FAIL|Tên 3-16 ký tự (a-z, 0-9, _), mật khẩu ≥ 3 ký tự"); return; }
                send(PlayerDao.register(u, p) ? "REGISTER_OK" : "LOGIN_FAIL|Tên đã tồn tại");
            } else if (m[0].equals("LOGIN")) {
                if (!PlayerDao.login(u, p)) { send("LOGIN_FAIL|Sai tên đăng nhập hoặc mật khẩu"); return; }
                synchronized (GameServer.class) {
                    if (GameServer.ONLINE.containsKey(u)) { send("LOGIN_FAIL|Tài khoản đang đăng nhập ở nơi khác"); return; }
                    name = u; GameServer.ONLINE.put(u, this);
                }
                refreshStats();
                send("LOGIN_OK|" + u);
                GameServer.broadcastPlayers();
            }
            return;
        }
        switch (m[0]) {
            case "CHALLENGE" -> challenge(m[1]);
            case "ACCEPT" -> accept(m[1]);
            case "REJECT" -> reject(m[1]);
            case "GUESS" -> { GameRoom r = room; if (r != null) r.guess(this, Integer.parseInt(m[1])); }
            case "LEAVE" -> { GameRoom r = room; if (r != null) r.leave(this); }
            case "REMATCH" -> { GameRoom r = room; if (r != null) r.rematch(this, m[1].equals("1")); }
            case "BOARD" -> send("BOARD|" + PlayerDao.leaderboard());
            default -> { }
        }
    }

    private void challenge(String target) {
        synchronized (GameServer.class) {
            ClientHandler t = GameServer.ONLINE.get(target);
            if (t == null || t == this || t.busy || busy) { send("INVITE_FAIL|Đối thủ không rảnh"); return; }
            busy = t.busy = true; invitedTarget = target; t.invitedBy = name;
            t.send("INVITE|" + name);
        }
        GameServer.broadcastPlayers();
    }

    private void accept(String from) {
        synchronized (GameServer.class) {
            ClientHandler f = GameServer.ONLINE.get(from);
            if (f == null || !name.equals(f.invitedTarget)) { busy = false; invitedBy = null; }
            else {
                f.invitedTarget = null; invitedBy = null;
                GameRoom r = new GameRoom(f, this);
                f.room = room = r;
                r.start();
            }
        }
        GameServer.broadcastPlayers();
    }

    private void reject(String from) {
        synchronized (GameServer.class) {
            ClientHandler f = GameServer.ONLINE.get(from);
            if (f != null && name.equals(f.invitedTarget)) { f.invitedTarget = null; f.busy = false; f.send("INVITE_REJECTED|" + name); }
            invitedBy = null; busy = false;
        }
        GameServer.broadcastPlayers();
    }

    private void cleanup() {
        if (name != null) {
            GameServer.ONLINE.remove(name);
            GameRoom r = room;
            if (r != null) r.leave(this);
            synchronized (GameServer.class) {
                if (invitedBy != null) {
                    ClientHandler f = GameServer.ONLINE.get(invitedBy);
                    if (f != null) { f.busy = false; f.invitedTarget = null; f.send("INVITE_REJECTED|" + name); }
                }
                if (invitedTarget != null) {
                    ClientHandler t = GameServer.ONLINE.get(invitedTarget);
                    if (t != null) { t.busy = false; t.invitedBy = null; }
                }
            }
            GameServer.broadcastPlayers();
        }
        try { sock.close(); } catch (IOException ignored) { }
    }
}
