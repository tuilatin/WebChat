package org.example;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;

public final class Database {
    public enum Dialect {
        SQLITE,
        MYSQL,
        POSTGRESQL
    }

    private static final String DB_URL = getEnv("DB_URL", "jdbc:sqlite:chatapp.db");
    private static final String DB_USER = getEnv("DB_USER", "");
    private static final String DB_PASSWORD = getEnv("DB_PASSWORD", "");
    private static final Dialect DIALECT = detectDialect(DB_URL);

    static {
        // Load JDBC drivers for fat JAR compatibility
        try {
            if (DB_URL.startsWith("jdbc:postgresql:")) {
                Class.forName("org.postgresql.Driver");
            } else if (DB_URL.startsWith("jdbc:mysql:")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } else if (DB_URL.startsWith("jdbc:sqlite:")) {
                Class.forName("org.sqlite.JDBC");
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Cannot load JDBC driver", e);
        }
    }

    private Database() {
        // utility class
    }

    public static Connection getConnection() throws SQLException {
        if (DB_USER.isBlank()) {
            return DriverManager.getConnection(DB_URL);
        }
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    public static Dialect dialect() {
        return DIALECT;
    }

    public static boolean isSqlite() {
        return DIALECT == Dialect.SQLITE;
    }

    public static boolean isMySql() {
        return DIALECT == Dialect.MYSQL;
    }

    public static boolean isPostgres() {
        return DIALECT == Dialect.POSTGRESQL;
    }

    public static String idPrimaryKey() {
        return isSqlite()
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : isMySql()
                ? "INT AUTO_INCREMENT PRIMARY KEY"
                : "SERIAL PRIMARY KEY";
    }

    public static String varcharType(int length) {
        return isSqlite() ? "TEXT" : "VARCHAR(" + length + ")";
    }

    public static String timestampDefault() {
        return "TIMESTAMP DEFAULT CURRENT_TIMESTAMP";
    }

    public static String insertIgnore(String sql) {
        if (isSqlite()) {
            return sql.replaceFirst("INSERT INTO", "INSERT OR IGNORE INTO");
        }
        if (isMySql()) {
            return sql.replaceFirst("INSERT INTO", "INSERT IGNORE INTO");
        }
        if (isPostgres()) {
            return sql + " ON CONFLICT DO NOTHING";
        }
        return sql;
    }

    public static String lower(String expression) {
        return "LOWER(" + expression + ")";
    }

    private static String getEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static Dialect detectDialect(String url) {
        if (url == null) {
            return Dialect.SQLITE;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:mysql:")) {
            return Dialect.MYSQL;
        }
        if (lower.startsWith("jdbc:postgresql:")) {
            return Dialect.POSTGRESQL;
        }
        return Dialect.SQLITE;
    }
}
