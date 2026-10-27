package com.bombgame.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.HexFormat;
import java.util.Locale;

public final class PlayerDao {
    private PlayerDao() {}

    static String hash(String p) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static boolean register(String u, String p) {
        String sql = "INSERT IGNORE INTO Players(username,password_hash) VALUES(?,?)";
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, u); ps.setString(2, hash(p));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static boolean login(String u, String p) {
        try (Connection c = Database.get();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM Players WHERE username=? AND password_hash=?")) {
            ps.setString(1, u); ps.setString(2, hash(p));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** @return {score, wins} */
    public static double[] stats(String u) {
        try (Connection c = Database.get(); PreparedStatement ps = c.prepareStatement("SELECT score,wins FROM Players WHERE username=?")) {
            ps.setString(1, u);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? new double[]{rs.getDouble(1), rs.getInt(2)} : new double[]{0, 0}; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public static void addResult(String u, double pts) {
        try (Connection c = Database.get();
             PreparedStatement ps = c.prepareStatement("UPDATE Players SET score=score+?, wins=wins+?, played=played+1 WHERE username=?")) {
            ps.setDouble(1, pts); ps.setInt(2, pts == 1.0 ? 1 : 0); ps.setString(3, u);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    /** Bảng xếp hạng: tổng điểm giảm dần, rồi số trận thắng giảm dần. Định dạng: name,score,wins,played;... */
    public static String leaderboard() {
        StringBuilder sb = new StringBuilder();
        try (Connection c = Database.get(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT username,score,wins,played FROM Players ORDER BY score DESC, wins DESC, played ASC LIMIT 20")) {
            while (rs.next()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(rs.getString(1)).append(',').append(String.format(Locale.ROOT, "%.1f", rs.getDouble(2)))
                  .append(',').append(rs.getInt(3)).append(',').append(rs.getInt(4));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
        return sb.toString();
    }
}
