package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import spark.utils.IOUtils;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static spark.Spark.*;

public class Main {
    private static final Gson GSON = new Gson();
    private static final Path UPLOAD_DIR = Paths.get("uploads");
    /** Jetty multipart temp dir — must exist on disk (Windows has no /tmp). */
    private static final Path MULTIPART_TMP = Paths.get(System.getProperty("java.io.tmpdir"), "chatapp-multipart");

    public static void main(String[] args) {
        String port = System.getenv("PORT");
        if (port != null && !port.isBlank()) {
            port(Integer.parseInt(port));
        } else {
            port(4567);
        }
        webSocket("/ws/chat", ChatWebSocketHandler.class);
        staticFiles.location("/static");
        AuthDatabase.init();
        ChatDatabase.init();
        ensureUploadDir();
        ensureMultipartTmp();

        post("/api/register", (req, res) -> {
            res.type("application/json");
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String password = normalize(getString(payload, "password"));

            if (username.isBlank() || password.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Vui long nhap day du thong tin"));
            }

            if (password.length() < 6) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Mat khau phai co it nhat 6 ky tu"));
            }

            boolean created = AuthDatabase.register(username, password);
            if (!created) {
                res.status(409);
                return GSON.toJson(Map.of("ok", false, "message", "Ten dang nhap da ton tai"));
            }

            return GSON.toJson(Map.of("ok", true, "message", "Dang ky thanh cong"));
        });

        post("/api/login", (req, res) -> {
            res.type("application/json");
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String password = normalize(getString(payload, "password"));

            if (username.isBlank() || password.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Vui long nhap ten dang nhap va mat khau"));
            }

            boolean authenticated = AuthDatabase.login(username, password);
            if (!authenticated) {
                res.status(401);
                return GSON.toJson(Map.of("ok", false, "message", "Sai ten dang nhap hoac mat khau"));
            }

            return GSON.toJson(Map.of("ok", true, "message", "Dang nhap thanh cong"));
        });

        post("/api/groups/create", (req, res) -> {
            res.type("application/json");
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String groupName = normalize(getString(payload, "groupName"));
            if (username.isBlank() || groupName.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu username hoac ten nhom"));
            }
            ChatDatabase.Group group = ChatDatabase.createGroup(groupName, username);
            if (group == null) {
                res.status(500);
                return GSON.toJson(Map.of("ok", false, "message", "Khong tao duoc nhom"));
            }
            return GSON.toJson(Map.of("ok", true, "group", group));
        });

        post("/api/groups/join", (req, res) -> {
            res.type("application/json");
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String code = normalize(getString(payload, "groupCode")).toUpperCase();
            if (username.isBlank() || code.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu username hoac ma nhom"));
            }
            boolean joined = ChatDatabase.joinGroup(code, username);
            if (!joined) {
                res.status(404);
                return GSON.toJson(Map.of("ok", false, "message", "Khong tim thay nhom"));
            }
            return GSON.toJson(Map.of("ok", true, "message", "Join nhom thanh cong"));
        });

        post("/api/groups/:groupId/leave", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            String raw = req.body();
            JsonObject payload = raw == null || raw.isBlank()
                    ? new JsonObject()
                    : GSON.fromJson(raw, JsonObject.class);
            String username = normalize(getString(payload, "username"));
            if (groupId <= 0 || username.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu thong tin"));
            }
            if (!ChatDatabase.isMember(groupId, username)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Ban khong thuoc nhom nay"));
            }
            boolean left = ChatDatabase.leaveGroup(groupId, username);
            if (!left) {
                res.status(500);
                return GSON.toJson(Map.of("ok", false, "message", "Roi nhom that bai"));
            }
            return GSON.toJson(Map.of("ok", true, "message", "Da roi nhom"));
        });

        get("/api/groups/:groupId/members", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            String username = normalize(req.queryParams("username"));
            if (groupId <= 0 || username.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu thong tin"));
            }
            if (!ChatDatabase.isMember(groupId, username)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Ban khong thuoc nhom nay"));
            }
            String viewerRole = ChatDatabase.isGroupAdmin(groupId, username) ? "admin" : "member";
            return GSON.toJson(Map.of(
                    "ok", true,
                    "members", ChatDatabase.getGroupMembers(groupId),
                    "viewerRole", viewerRole
            ));
        });

        post("/api/groups/:groupId/rename", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String groupName = normalize(getString(payload, "groupName"));
            if (groupId <= 0 || username.isBlank() || groupName.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu thong tin"));
            }
            if (!ChatDatabase.renameGroup(groupId, username, groupName)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Chi admin moi duoc doi ten nhom"));
            }
            return GSON.toJson(Map.of("ok", true, "message", "Da doi ten nhom"));
        });

        post("/api/groups/:groupId/invite", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String targetUsername = normalize(getString(payload, "targetUsername"));
            if (groupId <= 0 || username.isBlank() || targetUsername.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu thong tin"));
            }
            String target = AuthDatabase.resolveUsername(targetUsername);
            if (target == null) {
                res.status(404);
                return GSON.toJson(Map.of("ok", false, "message", "Khong tim thay nguoi dung nay"));
            }
            if (ChatDatabase.isMember(groupId, target)) {
                return GSON.toJson(Map.of("ok", true, "message", "Nguoi nay da trong nhom"));
            }
            if (!ChatDatabase.inviteMember(groupId, username, target)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Chi admin moi duoc moi thanh vien"));
            }
            return GSON.toJson(Map.of("ok", true, "message", "Moi thanh vien thanh cong"));
        });

        post("/api/groups/:groupId/kick", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String targetUsername = normalize(getString(payload, "targetUsername"));
            if (groupId <= 0 || username.isBlank() || targetUsername.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu thong tin"));
            }
            if (!ChatDatabase.kickMember(groupId, username, targetUsername)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Khong du quyen kick thanh vien"));
            }
            return GSON.toJson(Map.of("ok", true, "message", "Da kick thanh vien"));
        });

        post("/api/groups/:groupId/role", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String targetUsername = normalize(getString(payload, "targetUsername"));
            String role = normalize(getString(payload, "role"));
            if (groupId <= 0 || username.isBlank() || targetUsername.isBlank() || role.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu thong tin"));
            }
            if (!ChatDatabase.updateMemberRole(groupId, username, targetUsername, role)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Khong du quyen doi role"));
            }
            return GSON.toJson(Map.of("ok", true, "message", "Da cap nhat role"));
        });

        post("/api/friends", (req, res) -> {
            res.type("application/json");
            try {
                String raw = req.body();
                if (raw == null || raw.isBlank()) {
                    res.status(400);
                    return GSON.toJson(Map.of("ok", false, "message", "Thieu du lieu request"));
                }
                JsonObject payload = GSON.fromJson(raw, JsonObject.class);
                String username = normalize(getString(payload, "username"));
                String friend = normalize(getString(payload, "friendUsername"));
                if (username.isBlank() || friend.isBlank()) {
                    res.status(400);
                    return GSON.toJson(Map.of("ok", false, "message", "Thieu ten dang nhap"));
                }
                String friendDb = AuthDatabase.resolveUsername(friend);
                if (friendDb == null) {
                    res.status(404);
                    return GSON.toJson(Map.of("ok", false, "message", "Khong tim thay nguoi dung nay"));
                }
                String userDb = AuthDatabase.resolveUsername(username);
                if (userDb == null) {
                    res.status(401);
                    return GSON.toJson(Map.of("ok", false, "message", "Tai khoan khong hop le"));
                }
                if (userDb.equalsIgnoreCase(friendDb)) {
                    res.status(400);
                    return GSON.toJson(Map.of("ok", false, "message", "Khong the them chinh minh"));
                }
                if (ChatDatabase.areFriends(userDb, friendDb)) {
                    return GSON.toJson(Map.of("ok", true, "message", "Hai nguoi da la ban be"));
                }
                boolean added = ChatDatabase.addFriendship(userDb, friendDb);
                if (!added) {
                    return GSON.toJson(Map.of("ok", true, "message", "Hai nguoi da la ban be"));
                }
                return GSON.toJson(Map.of("ok", true, "message", "Da them ban be"));
            } catch (com.google.gson.JsonSyntaxException e) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "JSON khong hop le"));
            } catch (Exception e) {
                res.status(500);
                return GSON.toJson(Map.of("ok", false, "message", "Loi xu ly: " + e.getClass().getSimpleName()));
            }
        });

        get("/api/friends", (req, res) -> {
            res.type("application/json");
            String username = normalize(req.queryParams("username"));
            if (username.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu username"));
            }
            return GSON.toJson(Map.of("ok", true, "friends", ChatDatabase.getFriends(username)));
        });

        get("/api/dm/messages", (req, res) -> {
            res.type("application/json");
            String username = normalize(req.queryParams("username"));
            String peer = normalize(req.queryParams("peer"));
            if (username.isBlank() || peer.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu thong tin"));
            }
            if (!ChatDatabase.areFriends(username, peer)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Hai nguoi chua la ban be"));
            }
            return GSON.toJson(Map.of(
                    "ok", true,
                    "messages", ChatDatabase.getDirectMessages(username, peer)
            ));
        });

        post("/api/dm/messages", (req, res) -> {
            res.type("application/json");
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String peer = normalize(getString(payload, "peer"));
            String content = normalize(getString(payload, "content"));
            if (username.isBlank() || peer.isBlank() || content.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Noi dung khong hop le"));
            }
            if (!ChatDatabase.areFriends(username, peer)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Hai nguoi chua la ban be"));
            }
            boolean sent = ChatDatabase.addDirectMessage(username, peer, username, content);
            if (!sent) {
                res.status(500);
                return GSON.toJson(Map.of("ok", false, "message", "Gui that bai"));
            }
            return GSON.toJson(Map.of("ok", true));
        });

        post("/api/dm/files", (req, res) -> {
            res.type("application/json");
            req.attribute("org.eclipse.jetty.multipartConfig",
                    new MultipartConfigElement(MULTIPART_TMP.toAbsolutePath().toString()));
            Part filePart;
            try {
                filePart = resolveFilePart(req.raw());
            } catch (Exception e) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Loi doc multipart: " + e.getMessage()));
            }
            String username = normalize(req.raw().getParameter("username"));
            String peer = normalize(req.raw().getParameter("peer"));
            if (username.isBlank() || peer.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu username hoac ban be"));
            }
            if (!ChatDatabase.areFriends(username, peer)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Hai nguoi chua la ban be"));
            }
            if (filePart == null) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Khong tim thay file"));
            }
            String originalName = extractFileName(filePart);
            if (originalName.isBlank()) {
                originalName = "upload.bin";
            }
            String extension = getExtension(originalName);
            String storedName = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
            Path target = UPLOAD_DIR.resolve(storedName);
            try (InputStream in = filePart.getInputStream()) {
                Files.copy(in, target);
            }
            try {
                filePart.delete();
            } catch (Exception ignored) {
            }
            long size = Files.size(target);
            if (size == 0) {
                Files.deleteIfExists(target);
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "File rong"));
            }
            String fileUrl = "/api/files/" + storedName;
            String messageType = resolveMessageType(originalName);
            String content = switch (messageType) {
                case "voice" -> "[Voice]";
                case "image" -> "[Image]";
                default -> "[File]";
            };
            boolean saved = ChatDatabase.addDirectMessage(username, peer, username, content, messageType, fileUrl, originalName);
            if (!saved) {
                res.status(500);
                return GSON.toJson(Map.of("ok", false, "message", "Khong luu duoc tin nhan"));
            }
            return GSON.toJson(Map.of(
                    "ok", true,
                    "fileUrl", fileUrl,
                    "fileName", originalName,
                    "messageType", messageType
            ));
        });

        get("/api/groups", (req, res) -> {
            res.type("application/json");
            String username = normalize(req.queryParams("username"));
            if (username.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu username"));
            }
            return GSON.toJson(Map.of(
                    "ok", true,
                    "groups", ChatDatabase.getGroupsByUser(username)
            ));
        });

        get("/api/groups/:groupId/messages", (req, res) -> {
            res.type("application/json");
            String username = normalize(req.queryParams("username"));
            int groupId = parseGroupId(req.params("groupId"));
            if (username.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thieu username"));
            }
            if (groupId <= 0) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "groupId khong hop le"));
            }
            if (!ChatDatabase.isMember(groupId, username)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Ban khong thuoc nhom nay"));
            }
            return GSON.toJson(Map.of(
                    "ok", true,
                    "messages", ChatDatabase.getMessages(groupId)
            ));
        });

        post("/api/groups/:groupId/messages", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            JsonObject payload = GSON.fromJson(req.body(), JsonObject.class);
            String username = normalize(getString(payload, "username"));
            String content = normalize(getString(payload, "content"));
            if (groupId <= 0) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "groupId khong hop le"));
            }
            if (username.isBlank() || content.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Noi dung khong hop le"));
            }
            if (!ChatDatabase.isMember(groupId, username)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Ban khong thuoc nhom nay"));
            }
            boolean sent = ChatDatabase.addMessage(groupId, username, content);
            if (!sent) {
                res.status(500);
                return GSON.toJson(Map.of("ok", false, "message", "Gui tin nhan that bai"));
            }
            return GSON.toJson(Map.of("ok", true));
        });

        post("/api/groups/:groupId/files", (req, res) -> {
            res.type("application/json");
            int groupId = parseGroupId(req.params("groupId"));
            req.attribute("org.eclipse.jetty.multipartConfig",
                    new MultipartConfigElement(MULTIPART_TMP.toAbsolutePath().toString()));

            Part filePart;
            try {
                filePart = resolveFilePart(req.raw());
            } catch (Exception e) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Loi doc multipart: " + e.getMessage()));
            }

            String username = normalize(req.raw().getParameter("username"));
            if (groupId <= 0 || username.isBlank()) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Thong tin upload khong hop le"));
            }
            if (!ChatDatabase.isMember(groupId, username)) {
                res.status(403);
                return GSON.toJson(Map.of("ok", false, "message", "Ban khong thuoc nhom nay"));
            }

            if (filePart == null) {
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "Khong tim thay file trong request"));
            }

            String originalName = extractFileName(filePart);
            if (originalName.isBlank()) {
                originalName = "upload.bin";
            }
            String extension = getExtension(originalName);
            String storedName = UUID.randomUUID() + (extension.isBlank() ? "" : "." + extension);
            Path target = UPLOAD_DIR.resolve(storedName);
            try (InputStream in = filePart.getInputStream()) {
                Files.copy(in, target);
            }
            try {
                filePart.delete();
            } catch (Exception ignored) {
            }

            long size = Files.size(target);
            if (size == 0) {
                Files.deleteIfExists(target);
                res.status(400);
                return GSON.toJson(Map.of("ok", false, "message", "File rong"));
            }

            String fileUrl = "/api/files/" + storedName;
            String messageType = resolveMessageType(originalName);
            String content = switch (messageType) {
                case "voice" -> "[Voice]";
                case "image" -> "[Image]";
                default -> "[File]";
            };
            boolean saved = ChatDatabase.addMessage(groupId, username, content, messageType, fileUrl, originalName);
            if (!saved) {
                res.status(500);
                return GSON.toJson(Map.of("ok", false, "message", "Khong luu duoc tin nhan file"));
            }
            return GSON.toJson(Map.of(
                    "ok", true,
                    "fileUrl", fileUrl,
                    "fileName", originalName,
                    "messageType", messageType
            ));
        });

        get("/api/files/:name", (req, res) -> {
            String fileName = sanitizeFileName(req.params("name"));
            Path filePath = UPLOAD_DIR.resolve(fileName).normalize();
            if (!filePath.startsWith(UPLOAD_DIR) || !Files.exists(filePath)) {
                res.status(404);
                return "Not found";
            }
            String contentType = Files.probeContentType(filePath);
            if (contentType == null || contentType.isBlank()) {
                contentType = guessContentTypeFromExtension(fileName);
            }
            res.type(contentType);
            try (InputStream in = Files.newInputStream(filePath)) {
                IOUtils.copy(in, res.raw().getOutputStream());
            }
            return res.raw();
        });

        get("/", (req, res) -> {
            res.redirect("/auth/login.html");
            return null;
        });

        init();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String getString(JsonObject payload, String key) {
        if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
            return "";
        }
        return payload.get(key).getAsString();
    }

    private static int parseGroupId(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void ensureUploadDir() {
        try {
            if (!Files.exists(UPLOAD_DIR)) {
                Files.createDirectories(UPLOAD_DIR);
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot create upload dir", e);
        }
    }

    private static void ensureMultipartTmp() {
        try {
            if (!Files.exists(MULTIPART_TMP)) {
                Files.createDirectories(MULTIPART_TMP);
            }
        } catch (Exception e) {
            throw new RuntimeException("Cannot create multipart temp dir", e);
        }
    }

    /**
     * Prefer part named "file", else first non-empty file part.
     */
    private static Part resolveFilePart(HttpServletRequest raw) throws Exception {
        Part named = raw.getPart("file");
        if (named != null && named.getSize() > 0) {
            return named;
        }
        for (Part p : raw.getParts()) {
            if (p == null || p.getName() == null) {
                continue;
            }
            if (!"file".equalsIgnoreCase(p.getName())) {
                continue;
            }
            if (p.getSize() > 0) {
                return p;
            }
        }
        for (Part p : raw.getParts()) {
            if (p != null && p.getSubmittedFileName() != null && !p.getSubmittedFileName().isBlank() && p.getSize() > 0) {
                return p;
            }
        }
        return named;
    }

    private static String extractFileName(Part part) {
        String submitted = part.getSubmittedFileName();
        if (submitted != null && !submitted.isBlank()) {
            return sanitizeFileName(submitted);
        }
        String cd = part.getHeader("content-disposition");
        if (cd == null || cd.isBlank()) {
            return "";
        }
        for (String piece : cd.split(";")) {
            String p = piece.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("filename=")) {
                String fn = p.substring("filename=".length()).trim();
                if (fn.startsWith("\"") && fn.endsWith("\"") && fn.length() >= 2) {
                    fn = fn.substring(1, fn.length() - 1);
                }
                return sanitizeFileName(fn);
            }
        }
        return "";
    }

    private static String resolveMessageType(String originalName) {
        if (isAudioFile(originalName)) {
            return "voice";
        }
        if (isImageFile(originalName)) {
            return "image";
        }
        return "file";
    }

    private static boolean isImageFile(String fileName) {
        String ext = getExtension(fileName);
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("jfif")
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp") || ext.equals("svg")
                || ext.equals("ico") || ext.equals("tiff") || ext.equals("tif");
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        return fileName.replace("\\", "_").replace("/", "_").replace("..", "_");
    }

    private static String getExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isAudioFile(String fileName) {
        String ext = getExtension(fileName);
        return ext.equals("webm") || ext.equals("mp3") || ext.equals("wav") || ext.equals("ogg") || ext.equals("m4a");
    }

    private static String guessContentTypeFromExtension(String fileName) {
        String ext = getExtension(fileName);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "jfif" -> "image/jpeg";
            case "tif", "tiff" -> "image/tiff";
            case "svg" -> "image/svg+xml";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "html", "htm" -> "text/html";
            case "json" -> "application/json";
            case "zip" -> "application/zip";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }
}