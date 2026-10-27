package com.bombgame.client;

import javax.swing.SwingUtilities;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class NetClient {
    private Socket s;
    private PrintWriter out;

    public boolean connected() { return s != null && !s.isClosed(); }

    public void connect(String host, int port, Consumer<String[]> handler) throws IOException {
        s = new Socket();
        s.connect(new InetSocketAddress(host, port), 4000);
        out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
        final Socket sock = s;
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] m = line.split("\\|", -1);
                    SwingUtilities.invokeLater(() -> handler.accept(m));
                }
            } catch (IOException ignored) { }
            SwingUtilities.invokeLater(() -> { if (sock == s) handler.accept(new String[]{"DISCONNECTED"}); });
        }, "net-reader");
        t.setDaemon(true);
        t.start();
    }

    public void send(String m) { if (out != null) out.println(m); }

    public void close() { try { if (s != null) s.close(); } catch (IOException ignored) { } s = null; }
}
