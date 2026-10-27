package com.bombgame.server;

import java.sql.*;

/** Kết nối MySQL (user root / 1234) và tự khởi tạo DB + bảng. */
public final class Database {
    private static final String OPTS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
    private static final String URL = "jdbc:mysql://localhost:3306/bombgame" + OPTS + "&createDatabaseIfNotExist=true";
    private static final String USER = "root", PASS = "1234";
    private Database() {}

    public static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    public static void init() throws SQLException {
        try (Connection c = get(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS Players(" +
                "id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(32) NOT NULL UNIQUE, password_hash CHAR(64) NOT NULL," +
                "score DOUBLE NOT NULL DEFAULT 0, wins INT NOT NULL DEFAULT 0, played INT NOT NULL DEFAULT 0," +
                "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }
}
