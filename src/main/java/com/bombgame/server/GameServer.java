package com.bombgame.server;

import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Server trung tâm: quản lý người chơi online và mở luồng cho từng client. */
public class GameServer {
    public static final int PORT = 9999;
    static final Map<String, ClientHandler> ONLINE = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Database.init();
        try (ServerSocket ss = new ServerSocket(PORT)) {
            System.out.println("[BombServer] Đang lắng nghe cổng " + PORT);
            while (true) new ClientHandler(ss.accept()).start();
        }
    }

    static void broadcastPlayers() {
        String s = ONLINE.values().stream().filter(h -> h.name != null)
            .map(h -> h.name + "," + String.format(Locale.ROOT, "%.1f", h.score) + "," + h.wins + "," + (h.busy ? "BUSY" : "FREE"))
            .collect(Collectors.joining(";"));
        ONLINE.values().forEach(h -> h.send("PLAYERS|" + s));
    }
}
