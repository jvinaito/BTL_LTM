-- MySQL 8+. Chạy 1 lần (hoặc để server tự tạo khi khởi động).
-- Tài khoản root / 1234:  ALTER USER 'root'@'localhost' IDENTIFIED BY '1234';
CREATE DATABASE IF NOT EXISTS bombgame CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bombgame;
CREATE TABLE IF NOT EXISTS Players(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32) NOT NULL UNIQUE,
    password_hash CHAR(64)    NOT NULL,
    score         DOUBLE      NOT NULL DEFAULT 0,
    wins          INT         NOT NULL DEFAULT 0,
    played        INT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
