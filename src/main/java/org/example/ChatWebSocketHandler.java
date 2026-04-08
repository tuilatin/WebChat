package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket
public class ChatWebSocketHandler {
    private static final Gson GSON = new Gson();
    private static final Map<Integer, Set<Session>> GROUP_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Set<Session>> DM_SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Session, ClientState> CLIENT_STATES = new ConcurrentHashMap<>();

    @OnWebSocketConnect
    public void onConnect(Session session) {
        CLIENT_STATES.put(session, new ClientState("", -1, ""));
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        ClientState state = CLIENT_STATES.remove(session);
        if (state == null) {
            return;
        }
        if (state.groupId() > 0) {
            removeFromGroup(state.groupId(), session);
        }
        if (state.dmPeer() != null && !state.dmPeer().isBlank()) {
            removeFromDm(ChatDatabase.orderedPair(state.username(), state.dmPeer()), session);
        }
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String rawMessage) {
        JsonObject payload = GSON.fromJson(rawMessage, JsonObject.class);
        String type = getString(payload, "type");
        if ("join".equals(type)) {
            handleJoin(session, payload);
            return;
        }
        if ("join_dm".equals(type)) {
            handleJoinDm(session, payload);
            return;
        }
        if ("send".equals(type)) {
            handleSend(session, payload);
        }
    }

    private void handleJoin(Session session, JsonObject payload) {
        String username = normalize(getString(payload, "username"));
        int groupId = parseInt(getString(payload, "groupId"));
        if (username.isBlank() || groupId <= 0) {
            send(session, Map.of("type", "error", "message", "Thong tin join khong hop le"));
            return;
        }
        if (!ChatDatabase.isMember(groupId, username)) {
            send(session, Map.of("type", "error", "message", "Ban khong thuoc group nay"));
            return;
        }

        ClientState oldState = CLIENT_STATES.get(session);
        if (oldState != null && oldState.dmPeer() != null && !oldState.dmPeer().isBlank()) {
            removeFromDm(ChatDatabase.orderedPair(oldState.username(), oldState.dmPeer()), session);
        }
        if (oldState != null && oldState.groupId() > 0 && oldState.groupId() != groupId) {
            removeFromGroup(oldState.groupId(), session);
        }

        CLIENT_STATES.put(session, new ClientState(username, groupId, ""));
        GROUP_SESSIONS.computeIfAbsent(groupId, ignored -> ConcurrentHashMap.newKeySet()).add(session);

        List<ChatDatabase.Message> history = ChatDatabase.getMessages(groupId);
        send(session, Map.of("type", "history", "groupId", groupId, "messages", history));
    }

    private void handleJoinDm(Session session, JsonObject payload) {
        String username = normalize(getString(payload, "username"));
        String peer = normalize(getString(payload, "peer"));
        if (username.isBlank() || peer.isBlank()) {
            send(session, Map.of("type", "error", "message", "Thieu thong tin ban be"));
            return;
        }
        if (!ChatDatabase.areFriends(username, peer)) {
            send(session, Map.of("type", "error", "message", "Hai nguoi chua la ban be"));
            return;
        }

        ClientState oldState = CLIENT_STATES.get(session);
        if (oldState != null && oldState.groupId() > 0) {
            removeFromGroup(oldState.groupId(), session);
        }
        if (oldState != null && oldState.dmPeer() != null && !oldState.dmPeer().isBlank()) {
            removeFromDm(ChatDatabase.orderedPair(oldState.username(), oldState.dmPeer()), session);
        }

        CLIENT_STATES.put(session, new ClientState(username, -1, peer));
        String key = ChatDatabase.orderedPair(username, peer);
        DM_SESSIONS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(session);

        List<ChatDatabase.Message> history = ChatDatabase.getDirectMessages(username, peer);
        send(session, Map.of(
                "type", "dm_history",
                "channelKey", key,
                "peer", peer,
                "messages", history
        ));
    }

    private void handleSend(Session session, JsonObject payload) {
        ClientState state = CLIENT_STATES.get(session);
        if (state == null || state.username().isBlank()) {
            send(session, Map.of("type", "error", "message", "Chua thiet lap phien"));
            return;
        }

        String content = normalize(getString(payload, "content"));
        if (content.isBlank()) {
            return;
        }

        if (state.groupId() > 0) {
            if (!ChatDatabase.isMember(state.groupId(), state.username())) {
                send(session, Map.of("type", "error", "message", "Ban khong thuoc group nay"));
                return;
            }
            boolean ok = ChatDatabase.addMessage(state.groupId(), state.username(), content);
            if (!ok) {
                send(session, Map.of("type", "error", "message", "Gui tin nhan that bai"));
                return;
            }
            List<ChatDatabase.Message> history = ChatDatabase.getMessages(state.groupId());
            ChatDatabase.Message last = history.isEmpty()
                    ? new ChatDatabase.Message(-1, state.username(), content, "", "text", null, null)
                    : history.get(history.size() - 1);
            broadcast(state.groupId(), Map.of(
                    "type", "message",
                    "groupId", state.groupId(),
                    "message", last
            ));
            return;
        }

        if (state.dmPeer() != null && !state.dmPeer().isBlank()) {
            if (!ChatDatabase.areFriends(state.username(), state.dmPeer())) {
                send(session, Map.of("type", "error", "message", "Khong phai ban be"));
                return;
            }
            boolean ok = ChatDatabase.addDirectMessage(state.username(), state.dmPeer(), state.username(), content);
            if (!ok) {
                send(session, Map.of("type", "error", "message", "Gui tin nhan that bai"));
                return;
            }
            List<ChatDatabase.Message> history = ChatDatabase.getDirectMessages(state.username(), state.dmPeer());
            ChatDatabase.Message last = history.isEmpty()
                    ? new ChatDatabase.Message(-1, state.username(), content, "", "text", null, null)
                    : history.get(history.size() - 1);
            String key = ChatDatabase.orderedPair(state.username(), state.dmPeer());
            broadcastDm(key, Map.of(
                    "type", "dm_message",
                    "channelKey", key,
                    "message", last
            ));
            return;
        }

        send(session, Map.of("type", "error", "message", "Ban chua vao phong chat"));
    }

    private void broadcast(int groupId, Object data) {
        Set<Session> sessions = GROUP_SESSIONS.get(groupId);
        if (sessions == null) {
            return;
        }
        String json = GSON.toJson(data);
        sessions.removeIf(s -> !s.isOpen());
        for (Session s : sessions) {
            try {
                s.getRemote().sendString(json);
            } catch (IOException ignored) {
            }
        }
    }

    private void broadcastDm(String channelKey, Object data) {
        Set<Session> sessions = DM_SESSIONS.get(channelKey);
        if (sessions == null) {
            return;
        }
        String json = GSON.toJson(data);
        sessions.removeIf(s -> !s.isOpen());
        for (Session s : sessions) {
            try {
                s.getRemote().sendString(json);
            } catch (IOException ignored) {
            }
        }
    }

    private void send(Session session, Object data) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.getRemote().sendString(GSON.toJson(data));
        } catch (IOException ignored) {
        }
    }

    private void removeFromGroup(int groupId, Session session) {
        Set<Session> sessions = GROUP_SESSIONS.get(groupId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            GROUP_SESSIONS.remove(groupId);
        }
    }

    private void removeFromDm(String channelKey, Session session) {
        Set<Session> sessions = DM_SESSIONS.get(channelKey);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            DM_SESSIONS.remove(channelKey);
        }
    }

    private String getString(JsonObject payload, String key) {
        if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
            return "";
        }
        return payload.get(key).getAsString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private record ClientState(String username, int groupId, String dmPeer) {
    }
}
