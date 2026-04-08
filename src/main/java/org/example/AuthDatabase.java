package org.example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AuthDatabase {
    public static void init() {
        String sql = """
                CREATE TABLE IF NOT EXISTS users (
                    id %s,
                    username %s NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    created_at %s
                )
                """.formatted(
                Database.idPrimaryKey(),
                Database.varcharType(255),
                Database.timestampDefault()
        );
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        } catch (SQLException e) {
            throw new RuntimeException("Cannot initialize database", e);
        }
    }

    public static boolean register(String username, String rawPassword) {
        String sql = "INSERT INTO users (username, password_hash) VALUES (?, ?)";
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, hash(rawPassword));
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean userExists(String username) {
        return resolveUsername(username) != null;
    }

    /**
     * Exact username as stored in DB, or null if no row matches.
     */
    public static String resolveUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String sql = "SELECT username FROM users WHERE " + Database.lower("username") + " = " + Database.lower("?") + " LIMIT 1";
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString("username");
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public static boolean login(String username, String rawPassword) {
        String sql = "SELECT password_hash FROM users WHERE " + Database.lower("username") + " = " + Database.lower("?") + " LIMIT 1";
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username.trim());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                String storedHash = rs.getString("password_hash");
                return storedHash.equals(hash(rawPassword));
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot hash password", e);
        }
    }
}
