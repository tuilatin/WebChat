package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ChatDatabase {
    public static void init() {
        String idColumn = Database.idPrimaryKey();
        String usernameColumn = Database.varcharType(255);
        String groupNameColumn = Database.varcharType(255);
        String groupCodeColumn = Database.varcharType(64);
        String messageTypeColumn = Database.varcharType(50);
        String fileFieldColumn = Database.varcharType(255);
        String timestampColumn = Database.timestampDefault();

        String groupsSql = """
                CREATE TABLE IF NOT EXISTS chat_groups (
                    id %s,
                    name %s NOT NULL,
                    code %s NOT NULL UNIQUE,
                    owner_username %s NOT NULL,
                    created_at %s
                )
                """.formatted(idColumn, groupNameColumn, groupCodeColumn, usernameColumn, timestampColumn);
        String membersSql = """
                CREATE TABLE IF NOT EXISTS group_members (
                    group_id INTEGER NOT NULL,
                    username %s NOT NULL,
                    joined_at %s,
                    PRIMARY KEY (group_id, username)
                )
                """.formatted(usernameColumn, timestampColumn);
        String messagesSql = """
                CREATE TABLE IF NOT EXISTS group_messages (
                    id %s,
                    group_id INTEGER NOT NULL,
                    username %s NOT NULL,
                    content TEXT NOT NULL,
                    message_type %s NOT NULL DEFAULT 'text',
                    file_url %s,
                    file_name %s,
                    created_at %s
                )
                """.formatted(idColumn, usernameColumn, messageTypeColumn, fileFieldColumn, fileFieldColumn, timestampColumn);
        String friendshipsSql = """
                CREATE TABLE IF NOT EXISTS friendships (
                    user_a %s NOT NULL,
                    user_b %s NOT NULL,
                    created_at %s,
                    PRIMARY KEY (user_a, user_b),
                    CHECK (user_a < user_b)
                )
                """.formatted(usernameColumn, usernameColumn, timestampColumn);
        String dmSql = """
                CREATE TABLE IF NOT EXISTS direct_messages (
                    id %s,
                    user_a %s NOT NULL,
                    user_b %s NOT NULL,
                    sender %s NOT NULL,
                    content TEXT NOT NULL,
                    message_type %s NOT NULL DEFAULT 'text',
                    file_url %s,
                    file_name %s,
                    created_at %s
                )
                """.formatted(idColumn, usernameColumn, usernameColumn, usernameColumn, messageTypeColumn, fileFieldColumn, fileFieldColumn, timestampColumn);

        try (Connection conn = Database.getConnection()) {
            try (PreparedStatement s1 = conn.prepareStatement(groupsSql);
                 PreparedStatement s2 = conn.prepareStatement(membersSql);
                 PreparedStatement s3 = conn.prepareStatement(messagesSql);
                 PreparedStatement s4 = conn.prepareStatement(friendshipsSql);
                 PreparedStatement s5 = conn.prepareStatement(dmSql)) {
                s1.execute();
                s2.execute();
                s3.execute();
                s4.execute();
                s5.execute();
            }
            ensureColumn(conn, "group_messages", "message_type", "TEXT NOT NULL DEFAULT 'text'");
            ensureColumn(conn, "group_messages", "file_url", "TEXT");
            ensureColumn(conn, "group_messages", "file_name", "TEXT");
            ensureColumn(conn, "direct_messages", "message_type", "TEXT NOT NULL DEFAULT 'text'");
            ensureColumn(conn, "direct_messages", "file_url", "TEXT");
            ensureColumn(conn, "direct_messages", "file_name", "TEXT");
        } catch (SQLException e) {
            throw new RuntimeException("Cannot initialize chat database", e);
        }
    }

    public static String orderedPair(String u1, String u2) {
        if (u1 == null) {
            u1 = "";
        }
        if (u2 == null) {
            u2 = "";
        }
        if (u1.compareTo(u2) <= 0) {
            return u1 + "|" + u2;
        }
        return u2 + "|" + u1;
    }

    public static boolean addFriendship(String user, String friend) {
        if (user == null || friend == null || user.isBlank() || friend.isBlank()) {
            return false;
        }
        user = user.trim();
        friend = friend.trim();
        if (user.equals(friend)) {
            return false;
        }
        String a = user.compareTo(friend) < 0 ? user : friend;
        String b = user.compareTo(friend) < 0 ? friend : user;
        String sql = Database.insertIgnore("INSERT INTO friendships (user_a, user_b) VALUES (?, ?)");
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, a);
            stmt.setString(2, b);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            return false;
        }
    }

    public static List<String> getFriends(String username) {
        String sql = """
                SELECT CASE WHEN user_a = ? THEN user_b ELSE user_a END AS friend
                FROM friendships
                WHERE user_a = ? OR user_b = ?
                ORDER BY %s
                """.formatted(Database.lower("friend"));
        List<String> list = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.setString(2, username);
            stmt.setString(3, username);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(rs.getString("friend"));
                }
            }
        } catch (SQLException ignored) {
        }
        return list;
    }

    public static boolean areFriends(String user, String peer) {
        if (user == null || peer == null || user.isBlank() || peer.isBlank()) {
            return false;
        }
        user = user.trim();
        peer = peer.trim();
        if (user.equals(peer)) {
            return false;
        }
        String a = user.compareTo(peer) < 0 ? user : peer;
        String b = user.compareTo(peer) < 0 ? peer : user;
        String sql = "SELECT 1 FROM friendships WHERE user_a = ? AND user_b = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, a);
            stmt.setString(2, b);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean addDirectMessage(String user, String peer, String sender, String content) {
        return addDirectMessage(user, peer, sender, content, "text", null, null);
    }

    public static boolean addDirectMessage(String user, String peer, String sender, String content,
                                           String messageType, String fileUrl, String fileName) {
        if (!areFriends(user, peer)) {
            return false;
        }
        String a = user.compareTo(peer) < 0 ? user : peer;
        String b = user.compareTo(peer) < 0 ? peer : user;
        String sql = """
                INSERT INTO direct_messages (user_a, user_b, sender, content, message_type, file_url, file_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, a);
            stmt.setString(2, b);
            stmt.setString(3, sender);
            stmt.setString(4, content);
            stmt.setString(5, messageType);
            stmt.setString(6, fileUrl);
            stmt.setString(7, fileName);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public static List<Message> getDirectMessages(String user, String peer) {
        if (!areFriends(user, peer)) {
            return List.of();
        }
        String a = user.compareTo(peer) < 0 ? user : peer;
        String b = user.compareTo(peer) < 0 ? peer : user;
        String sql = """
                SELECT id, sender, content, created_at, message_type, file_url, file_name
                FROM direct_messages
                WHERE user_a = ? AND user_b = ?
                ORDER BY id ASC
                LIMIT 200
                """;
        List<Message> messages = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, a);
            stmt.setString(2, b);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(new Message(
                            rs.getInt("id"),
                            rs.getString("sender"),
                            rs.getString("content"),
                            rs.getString("created_at"),
                            rs.getString("message_type"),
                            rs.getString("file_url"),
                            rs.getString("file_name")
                    ));
                }
            }
        } catch (SQLException ignored) {
        }
        return messages;
    }

    public static Group createGroup(String groupName, String ownerUsername) {
        String code = generateCode();
        String insertGroup = "INSERT INTO chat_groups (name, code, owner_username) VALUES (?, ?, ?)";
        String addMember = "INSERT INTO group_members (group_id, username) VALUES (?, ?)";
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement groupStmt = conn.prepareStatement(insertGroup, Statement.RETURN_GENERATED_KEYS)) {
                groupStmt.setString(1, groupName);
                groupStmt.setString(2, code);
                groupStmt.setString(3, ownerUsername);
                groupStmt.executeUpdate();
                try (ResultSet keys = groupStmt.getGeneratedKeys()) {
                    if (!keys.next()) {
                        conn.rollback();
                        return null;
                    }
                    int groupId = keys.getInt(1);
                    try (PreparedStatement memberStmt = conn.prepareStatement(addMember)) {
                        memberStmt.setInt(1, groupId);
                        memberStmt.setString(2, ownerUsername);
                        memberStmt.executeUpdate();
                    }
                    conn.commit();
                    return new Group(groupId, groupName, code, ownerUsername);
                }
            } catch (SQLException e) {
                conn.rollback();
                return null;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public static boolean leaveGroup(int groupId, String username) {
        String delMember = "DELETE FROM group_members WHERE group_id = ? AND username = ?";
        String countSql = "SELECT COUNT(*) FROM group_members WHERE group_id = ?";
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = conn.prepareStatement(delMember)) {
                    stmt.setInt(1, groupId);
                    stmt.setString(2, username);
                    int n = stmt.executeUpdate();
                    if (n == 0) {
                        conn.rollback();
                        return false;
                    }
                }
                int remaining;
                try (PreparedStatement stmt = conn.prepareStatement(countSql)) {
                    stmt.setInt(1, groupId);
                    try (ResultSet rs = stmt.executeQuery()) {
                        remaining = rs.next() ? rs.getInt(1) : 0;
                    }
                }
                if (remaining == 0) {
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM group_messages WHERE group_id = ?")) {
                        stmt.setInt(1, groupId);
                        stmt.executeUpdate();
                    }
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM chat_groups WHERE id = ?")) {
                        stmt.setInt(1, groupId);
                        stmt.executeUpdate();
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                return false;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean joinGroup(String code, String username) {
        String find = "SELECT id FROM chat_groups WHERE code = ?";
        String join = Database.insertIgnore("INSERT INTO group_members (group_id, username) VALUES (?, ?)");
        try (Connection conn = Database.getConnection();
             PreparedStatement findStmt = conn.prepareStatement(find)) {
            findStmt.setString(1, code);
            try (ResultSet rs = findStmt.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                int groupId = rs.getInt("id");
                try (PreparedStatement joinStmt = conn.prepareStatement(join)) {
                    joinStmt.setInt(1, groupId);
                    joinStmt.setString(2, username);
                    joinStmt.executeUpdate();
                    return true;
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static List<Group> getGroupsByUser(String username) {
        String sql = """
                SELECT g.id, g.name, g.code, g.owner_username
                FROM chat_groups g
                JOIN group_members gm ON gm.group_id = g.id
                WHERE gm.username = ?
                ORDER BY g.id DESC
                """;
        List<Group> groups = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    groups.add(new Group(
                            rs.getInt("id"),
                            rs.getString("name"),
                            rs.getString("code"),
                            rs.getString("owner_username")
                    ));
                }
            }
        } catch (SQLException ignored) {
        }
        return groups;
    }

    public static boolean isMember(int groupId, String username) {
        String sql = "SELECT 1 FROM group_members WHERE group_id = ? AND username = ?";
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, groupId);
            stmt.setString(2, username);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public static boolean addMessage(int groupId, String username, String content) {
        return addMessage(groupId, username, content, "text", null, null);
    }

    public static boolean addMessage(int groupId, String username, String content, String messageType, String fileUrl, String fileName) {
        String sql = """
                INSERT INTO group_messages (group_id, username, content, message_type, file_url, file_name)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, groupId);
            stmt.setString(2, username);
            stmt.setString(3, content);
            stmt.setString(4, messageType);
            stmt.setString(5, fileUrl);
            stmt.setString(6, fileName);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public static List<Message> getMessages(int groupId) {
        String sql = """
                SELECT id, username, content, created_at, message_type, file_url, file_name
                FROM group_messages
                WHERE group_id = ?
                ORDER BY id ASC
                LIMIT 200
                """;
        List<Message> messages = new ArrayList<>();
        try (Connection conn = Database.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, groupId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    messages.add(new Message(
                            rs.getInt("id"),
                            rs.getString("username"),
                            rs.getString("content"),
                            rs.getString("created_at"),
                            rs.getString("message_type"),
                            rs.getString("file_url"),
                            rs.getString("file_name")
                    ));
                }
            }
        } catch (SQLException ignored) {
        }
        return messages;
    }

    private static String generateCode() {
        String seed = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        if (seed.length() > 8) {
            return seed.substring(seed.length() - 8);
        }
        return ("00000000" + seed).substring(seed.length());
    }

    private static void ensureColumn(Connection conn, String table, String column, String definition) throws SQLException {
        if (!Database.isSqlite()) {
            return;
        }
        String pragma = "PRAGMA table_info(" + table + ")";
        try (PreparedStatement stmt = conn.prepareStatement(pragma);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (PreparedStatement alter = conn.prepareStatement(
                "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition)) {
            alter.execute();
        }
    }

    public record Group(int id, String name, String code, String ownerUsername) {}

    public record Message(
            int id,
            String username,
            String content,
            String createdAt,
            String messageType,
            String fileUrl,
            String fileName
    ) {}
}
