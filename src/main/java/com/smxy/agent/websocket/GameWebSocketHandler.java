package com.smxy.agent.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smxy.agent.auth.AuthService;
import com.smxy.agent.websocket.logic.GameLogicRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> playerSessionMap = new ConcurrentHashMap<>();
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingRemovals = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /** 会话 -> 已认证用户名 */
    private final Map<String, String> sessionAccounts = new ConcurrentHashMap<>();
    /** 玩家ID -> 绑定账号用户名（防冒充：首个绑定者独占） */
    private final Map<String, String> playerAccounts = new ConcurrentHashMap<>();

    private final GameRoomManager roomManager;
    private final GameFlowManager flowManager;
    private final AuthService authService;
    private final GameLogicRegistry logicRegistry;

    public GameWebSocketHandler(GameRoomManager roomManager, GameFlowManager flowManager,
                                AuthService authService, GameLogicRegistry logicRegistry) {
        this.roomManager = roomManager;
        this.flowManager = flowManager;
        this.authService = authService;
        this.logicRegistry = logicRegistry;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        sessionLocks.put(session.getId(), new Object());
        log.info("✓ WebSocket连接: {}", session.getId());
        sendMessage(session, createResponse("connected", "连接成功", null));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("收到消息: {}", payload);

        try {
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);
            String type = (String) msg.get("type");
            String playerId = (String) msg.get("playerId");

            if (playerId != null) {
                playerSessionMap.put(playerId, session.getId());
                ScheduledFuture<?> pending = pendingRemovals.remove(playerId);
                if (pending != null) pending.cancel(false);
            }

            switch (type) {
                case "join":
                    handleJoin(session, playerId, msg);
                    break;
                case "auth":
                    handleAuth(session, msg);
                    break;
                case "createRoom":
                    handleCreateRoom(session, playerId, msg);
                    break;
                case "joinRoom":
                    handleJoinRoom(session, playerId, msg);
                    break;
                case "listRooms":
                    handleListRooms(session);
                    break;
                case "startGame":
                    handleStartGame(session, playerId, msg);
                    break;
                case "speak":
                    handleSpeak(session, playerId, msg);
                    break;
                case "enterVote":
                    handleEnterVote(session, playerId, msg);
                    break;
                case "vote":
                    handleVote(session, playerId, msg);
                    break;
                case "eliminate":
                    handleEliminate(session, playerId, msg);
                    break;
                case "kickPlayer":
                    handleKickPlayer(session, playerId, msg);
                    break;
                case "leaveRoom":
                    handleLeaveRoom(session, playerId, msg);
                    break;
                case "closeRoom":
                    handleCloseRoom(session, playerId, msg);
                    break;
                case "resetRoom":
                    handleResetRoom(session, playerId, msg);
                    break;
                case "rejoinRoom":
                    handleRejoinRoom(session, playerId, msg);
                    break;
                case "bombGuess":
                case "idiomPlay":
                case "idiomSkip":
                case "turtleAsk":
                case "turtleAnswer":
                case "turtleGuess":
                case "turtleReveal":
                case "quizAnswer":
                case "quizNext":
                    handleGameAction(session, playerId, msg);
                    break;
                case "heartbeat":
                    sendMessage(session, createResponse("pong", "pong", null));
                    break;
                default:
                    sendMessage(session, createResponse("error", "未知消息类型: " + type, null));
            }
        } catch (Exception e) {
            log.error("处理消息失败: err={}", e.getMessage(), e);
            sendMessage(session, createResponse("error", e.getMessage(), null));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        sessionLocks.remove(sessionId);
        sessionAccounts.remove(sessionId);

        String playerId = null;
        for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
            if (entry.getValue().equals(sessionId)) {
                playerId = entry.getKey();
                break;
            }
        }
        if (playerId != null) {
            playerSessionMap.remove(playerId);
            final String pid = playerId;
            ScheduledFuture<?> existing = pendingRemovals.remove(pid);
            if (existing != null) existing.cancel(false);
            Runnable task = () -> {
                if (!playerSessionMap.containsKey(pid)) {
                    notifyPlayerLeft(pid);
                    pendingRemovals.remove(pid);
                    log.info("[BYE] 玩家超时离开: {}", pid);
                }
            };
            ScheduledFuture<?> future = scheduler.schedule(task, 5, TimeUnit.SECONDS);
            pendingRemovals.put(pid, future);
        }
        log.info("✕ WebSocket断开: {}", sessionId);
    }

    // ========== 玩家离开通知 ==========
    private void notifyPlayerLeft(String playerId) {
        for (Map<String, Object> info : roomManager.buildRoomList()) {
            String roomId = (String) info.get("id");
            GameRoomManager.RoomData room = roomManager.getRoom(roomId);
            if (room == null) continue;
            if (room.players.contains(playerId) && !playerId.startsWith("AI-")) {
                room.players.remove(playerId);
                if (room.spoken != null) room.spoken.remove(playerId);
                if (room.voted != null) room.voted.remove(playerId);
                if (room.votes != null) room.votes.remove(playerId);

                if (room.host.equals(playerId)) {
                    String newHost = roomManager.transferHost(room.id);
                    if (newHost == null) {
                        if (!room.isPreset) {
                            roomManager.removeRoom(room.id);
                            broadcastRoomList();
                            continue;
                        }
                    }
                }
                broadcastToRoom(room.id, Map.of("type", "playerLeft", "playerName", playerId, "roomId", room.id));

                // 新游戏：离场推进回合/判终局（卧底走原 afterSpeak 路径，此处不处理）
                if (room.status.equals("playing") && !"undercover".equals(room.gameType)) {
                    RoomGameLogic logic = logicRegistry.get(room.gameType);
                    if (logic != null) {
                        deliver(null, room.id, logic.onPlayerLeft(room, playerId));
                    }
                }
                broadcastRoomList();
                // 玩家可能同时存在于多个房间（如已结束的旧房），继续清理，不能 break
            }
        }
    }

    // ========== 消息处理 ==========

    private void handleJoin(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String playerName = data != null ? (String) data.get("playerName") : "玩家";

        if (playerId == null || playerId.isEmpty()) {
            String randomStr = String.valueOf(Math.random()).substring(2, 6).toUpperCase();
            playerId = "P" + System.currentTimeMillis() + randomStr;
        }

        playerSessionMap.put(playerId, session.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("playerId", playerId);
        result.put("playerName", playerName);

        sendMessage(session, createResponse("joinSuccess", "加入成功", result));
        log.info("◉ 玩家加入: {} ({})", playerName, playerId);
        broadcastRoomList();
    }

    // ========== 认证 ==========
    private void handleAuth(WebSocketSession session, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String token = data != null ? (String) data.get("token") : null;
        String username = authService.validate(token);
        if (username == null) {
            sendMessage(session, createResponse("error", "token无效或已过期", null));
            return;
        }
        sessionAccounts.put(session.getId(), username);
        Map<String, Object> out = new HashMap<>();
        out.put("username", username);
        sendMessage(session, createResponse("authSuccess", "认证成功", out));
        log.info("[AUTH] WS认证成功: {} -> {}", session.getId(), username);
    }

    /**
     * 门禁：会话必须先 auth。bindPlayer=true 时把 playerId 与该账号绑定（首个绑定者独占，防冒充）。
     * 通过返回用户名，未通过返回 null（错误已发送）。
     */
    private String requireAccount(WebSocketSession session, String playerId, boolean bindPlayer) {
        String username = sessionAccounts.get(session.getId());
        if (username == null) {
            sendMessage(session, createResponse("error", "请先登录（发送 auth 消息）", null));
            return null;
        }
        if (bindPlayer && playerId != null && !playerId.isEmpty() && !playerId.startsWith("AI-")) {
            String owner = playerAccounts.putIfAbsent(playerId, username);
            if (owner != null && !owner.equals(username)) {
                sendMessage(session, createResponse("error", "玩家ID已被其他账号占用，请刷新页面", null));
                return null;
            }
            roomManager.setDisplayName(playerId, username);
        }
        return username;
    }

    private void handleCreateRoom(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");

        if (playerId == null || playerId.isEmpty()) {
            String randomStr = String.valueOf(Math.random()).substring(2, 6).toUpperCase();
            playerId = "P" + System.currentTimeMillis() + randomStr;
            playerSessionMap.put(playerId, session.getId());
        }
        if (requireAccount(session, playerId, true) == null) return;

        Map<String, Object> result = roomManager.createRoom(playerId, data);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }
        sendMessage(session, createResponse("roomCreated", "房间创建成功", result));
        broadcastRoomList();
        log.info("[HOME] 房间创建: {}", result.get("id"));
    }

    private void handleJoinRoom(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");

        if (playerId == null || playerId.isEmpty()) {
            String randomStr = String.valueOf(Math.random()).substring(2, 6).toUpperCase();
            playerId = "P" + System.currentTimeMillis() + randomStr;
            playerSessionMap.put(playerId, session.getId());
        }
        if (requireAccount(session, playerId, true) == null) return;

        Map<String, Object> result = roomManager.joinRoom(playerId, data);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }
        playerSessionMap.put(playerId, session.getId());
        sendMessage(session, createResponse("roomJoined", "加入成功", result));
        broadcastToRoom((String) result.get("roomId"),
                Map.of("type", "playerJoined", "playerName", playerId, "roomId", result.get("roomId")));
        broadcastRoomList();
        log.info("◉ 加入房间: {} -> {}", playerId, result.get("roomId"));
    }

    private void handleListRooms(WebSocketSession session) {
        broadcastRoomList();
    }

    private void handleStartGame(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;

        if (playerId == null || playerId.isEmpty()) {
            for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
                if (entry.getValue().equals(session.getId())) {
                    playerId = entry.getKey();
                    break;
                }
            }
            if (playerId == null || playerId.isEmpty()) {
                sendMessage(session, createResponse("error", "玩家ID无效，请刷新页面", null));
                return;
            }
        }
        if (requireAccount(session, playerId, true) == null) return;

        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) {
            sendMessage(session, createResponse("error", "房间不存在", null));
            return;
        }

        // 非卧底游戏：分发到对应 RoomGameLogic
        if (!"undercover".equals(room.gameType)) {
            RoomGameLogic logic = logicRegistry.get(room.gameType);
            if (logic == null) {
                sendMessage(session, createResponse("error", "未知游戏类型: " + room.gameType, null));
                return;
            }
            Map<String, Object> result = logic.onStart(room, playerId);
            if ("error".equals(result.get("status"))) {
                sendMessage(session, createResponse("error", (String) result.get("message"), null));
                return;
            }
            deliver(session, roomId, result);
            broadcastRoomList();
            return;
        }

        Map<String, Object> result = flowManager.startGame(roomId, playerId);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }
        Map<String, Object> startMsg = new HashMap<>();
        startMsg.put("type", "gameStarted");
        startMsg.put("roomId", roomId);
        startMsg.putAll(result);
        broadcastToRoom(roomId, startMsg);
        broadcastRoomList();
        flowManager.startNewRound(roomId, this::broadcastToRoom);
    }

    /**
     * 新游戏（bomb/idiom/turtle/quiz）对局内消息的统一入口：
     * 按房间 gameType 找到逻辑实现并交付广播/单播。
     */
    private void handleGameAction(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;
        String type = (String) msg.get("type");

        if (roomId == null) {
            sendMessage(session, createResponse("error", "房间ID不能为空", null));
            return;
        }

        if (playerId == null || playerId.isEmpty()) {
            for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
                if (entry.getValue().equals(session.getId())) {
                    playerId = entry.getKey();
                    break;
                }
            }
            if (playerId == null || playerId.isEmpty()) {
                sendMessage(session, createResponse("error", "玩家ID无效，请刷新页面", null));
                return;
            }
        }
        if (requireAccount(session, playerId, true) == null) return;

        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) {
            sendMessage(session, createResponse("error", "房间不存在", null));
            return;
        }
        RoomGameLogic logic = logicRegistry.get(room.gameType);
        if (logic == null) {
            sendMessage(session, createResponse("error", "该房间没有可用的游戏逻辑", null));
            return;
        }

        Map<String, Object> result = logic.handleMessage(type, room, playerId, data);
        deliver(session, roomId, result);
    }

    /** 交付逻辑返回的广播（整个房间）与单播（指定玩家） */
    @SuppressWarnings("unchecked")
    private void deliver(WebSocketSession session, String roomId, Map<String, Object> result) {
        if (result == null) return;
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }
        Object broadcasts = result.get("broadcasts");
        if (broadcasts instanceof List) {
            for (Object b : (List<Object>) broadcasts) {
                if (b instanceof Map) {
                    broadcastToRoom(roomId, (Map<String, Object>) b);
                }
            }
        }
        Object unicasts = result.get("unicasts");
        if (unicasts instanceof List) {
            for (Object u : (List<Object>) unicasts) {
                if (!(u instanceof Map)) continue;
                Map<String, Object> entry = (Map<String, Object>) u;
                String pid = (String) entry.get("playerId");
                String sessionId = playerSessionMap.get(pid);
                if (sessionId == null) continue;
                WebSocketSession target = sessions.get(sessionId);
                if (target != null && target.isOpen()) {
                    sendMessage(target, (Map<String, Object>) entry.get("message"));
                }
            }
        }
    }

    private void handleSpeak(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;
        String content = data != null ? (String) data.get("content") : null;

        if (playerId == null || playerId.isEmpty()) {
            for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
                if (entry.getValue().equals(session.getId())) {
                    playerId = entry.getKey();
                    break;
                }
            }
            if (playerId == null || playerId.isEmpty()) {
                sendMessage(session, createResponse("error", "玩家ID无效，请刷新页面", null));
                return;
            }
        }

        Map<String, Object> result = flowManager.submitSpeak(roomId, playerId, content);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }
        broadcastToRoom(roomId, Map.of("type", "speakSubmitted", "playerName", playerId, "content", content, "roomId", roomId));
        flowManager.afterSpeak(roomId, this::broadcastToRoom);
    }

    private void handleEnterVote(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;

        if (playerId == null || playerId.isEmpty()) {
            for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
                if (entry.getValue().equals(session.getId())) {
                    playerId = entry.getKey();
                    break;
                }
            }
            if (playerId == null || playerId.isEmpty()) {
                sendMessage(session, createResponse("error", "玩家ID无效，请刷新页面", null));
                return;
            }
        }

        Map<String, Object> result = flowManager.enterVote(roomId, playerId, this::broadcastToRoom);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
        }
    }

    private void handleVote(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;
        String target = data != null ? (String) data.get("target") : null;

        if (playerId == null || playerId.isEmpty()) {
            for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
                if (entry.getValue().equals(session.getId())) {
                    playerId = entry.getKey();
                    break;
                }
            }
            if (playerId == null || playerId.isEmpty()) {
                sendMessage(session, createResponse("error", "玩家ID无效，请刷新页面", null));
                return;
            }
        }

        Map<String, Object> result = flowManager.submitVote(roomId, playerId, target);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }
        broadcastToRoom(roomId, Map.of("type", "voteSubmitted", "playerName", playerId, "target", target, "roomId", roomId));
        flowManager.afterVote(roomId, this::broadcastToRoom);
    }

    private void handleEliminate(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;

        if (playerId == null || playerId.isEmpty()) {
            for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
                if (entry.getValue().equals(session.getId())) {
                    playerId = entry.getKey();
                    break;
                }
            }
            if (playerId == null || playerId.isEmpty()) {
                sendMessage(session, createResponse("error", "玩家ID无效，请刷新页面", null));
                return;
            }
        }

        Map<String, Object> result = flowManager.executeElimination(roomId, playerId);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }
        if (result.containsKey("eliminated")) {
            broadcastToRoom(roomId, Map.of("type", "playerEliminated", "playerName", result.get("eliminated"), "roomId", roomId));
        }
        if (result.containsKey("gameEnded")) {
            broadcastToRoom(roomId, Map.of("type", "gameEnded", "result", result.get("gameEnded"), "roomId", roomId));
            broadcastRoomList();
        } else if (result.containsKey("nextRound")) {
            flowManager.startNewRound(roomId, this::broadcastToRoom);
        }
    }

    // ========== 踢人 ==========
    private void handleKickPlayer(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;
        String targetId = data != null ? (String) data.get("targetId") : null;

        if (roomId == null || targetId == null) {
            sendMessage(session, createResponse("error", "参数不完整", null));
            return;
        }
        if (requireAccount(session, playerId, true) == null) return;

        Map<String, Object> result = roomManager.kickPlayer(roomId, playerId, targetId);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }

        String kickedPlayer = (String) result.get("kickedPlayer");
        broadcastToRoom(roomId, Map.of("type", "playerKicked", "playerName", kickedPlayer, "roomId", roomId));
        broadcastRoomList();

        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room != null && room.status.equals("playing")) {
            if (!"undercover".equals(room.gameType)) {
                RoomGameLogic logic = logicRegistry.get(room.gameType);
                if (logic != null) {
                    deliver(session, roomId, logic.onPlayerLeft(room, targetId));
                }
            } else {
                flowManager.afterSpeak(roomId, this::broadcastToRoom);
            }
        }

        log.info("[KICK] 踢人: {} 被 {} 踢出", targetId, playerId);
    }

    // ========== 退出房间 ==========
    private void handleLeaveRoom(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;

        if (roomId == null) {
            sendMessage(session, createResponse("error", "房间ID不能为空", null));
            return;
        }
        if (requireAccount(session, playerId, true) == null) return;

        Map<String, Object> result = roomManager.leaveRoom(roomId, playerId);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }

        broadcastToRoom(roomId, Map.of("type", "playerLeft", "playerName", playerId, "roomId", roomId));

        // 新游戏：离场推进回合/判终局（房间可能已随房主离场解散，先查）
        GameRoomManager.RoomData roomAfter = roomManager.getRoom(roomId);
        if (roomAfter != null && roomAfter.status.equals("playing")
                && !"undercover".equals(roomAfter.gameType)) {
            RoomGameLogic logic = logicRegistry.get(roomAfter.gameType);
            if (logic != null) {
                deliver(session, roomId, logic.onPlayerLeft(roomAfter, playerId));
            }
        }
        broadcastRoomList();

        if ("房间已解散".equals(result.get("message"))) {
            broadcastRoomList();
        }

        sendMessage(session, createResponse("leaveSuccess", "已离开房间", result));
        log.info("[EXIT] 玩家退出: {} 离开 {}", playerId, roomId);
    }

    // ========== 关闭房间 ==========
    private void handleCloseRoom(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;

        if (roomId == null) {
            sendMessage(session, createResponse("error", "房间ID不能为空", null));
            return;
        }
        if (requireAccount(session, playerId, true) == null) return;

        // 权限预检：closeRoom 会删除房间，广播必须发生在删除之前
        GameRoomManager.RoomData closing = roomManager.getRoom(roomId);
        if (closing == null) {
            sendMessage(session, createResponse("error", "房间不存在", null));
            return;
        }
        if (!closing.host.equals(playerId)) {
            sendMessage(session, createResponse("error", "只有房主可以关闭房间", null));
            return;
        }
        if (closing.isPreset) {
            sendMessage(session, createResponse("error", "预制房间不能关闭", null));
            return;
        }

        // 先通知房内全员（对局页/大厅都可处理），再移除并刷新列表
        broadcastToRoom(roomId, Map.of("type", "roomClosed", "roomId", roomId));
        Map<String, Object> result = roomManager.closeRoom(roomId, playerId);
        broadcastRoomList();
        sendMessage(session, createResponse("roomClosed", "房间已关闭", result));
        log.info("[DEL] 房间关闭: {} 被 {} 关闭", roomId, playerId);
    }

    // ========== 重置房间 ==========
    private void handleResetRoom(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;

        if (roomId == null) {
            sendMessage(session, createResponse("error", "房间ID不能为空", null));
            return;
        }
        if (requireAccount(session, playerId, true) == null) return;

        Map<String, Object> result = flowManager.resetRoom(roomId, playerId);
        if ("error".equals(result.get("status"))) {
            sendMessage(session, createResponse("error", (String) result.get("message"), null));
            return;
        }

        // 新游戏：清理本类型的对局状态（通用字段已由 flowManager 复位）
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room != null) {
            RoomGameLogic logic = logicRegistry.get(room.gameType);
            if (logic != null) logic.reset(room);
        }

        broadcastToRoom(roomId, Map.of("type", "roomReset", "roomId", roomId));
        broadcastRoomList();
        sendMessage(session, createResponse("roomReset", "房间已重置", result));
        log.info("> 房间重置: {}", roomId);
    }

    // ========== ✓ 修复：重连房间 ==========
    private void handleRejoinRoom(WebSocketSession session, String playerId, Map<String, Object> msg) {
        Map<String, Object> data = (Map<String, Object>) msg.get("data");
        String roomId = data != null ? (String) data.get("roomId") : null;

        // 如果playerId为空，从消息中获取
        if (playerId == null || playerId.isEmpty()) {
            playerId = (String) msg.get("playerId");
        }

        // 如果还是为空，从session映射中获取
        if (playerId == null || playerId.isEmpty()) {
            for (Map.Entry<String, String> entry : playerSessionMap.entrySet()) {
                if (entry.getValue().equals(session.getId())) {
                    playerId = entry.getKey();
                    break;
                }
            }
        }

        if (roomId == null) {
            sendMessage(session, createResponse("error", "房间ID不能为空", null));
            return;
        }

        if (playerId == null || playerId.isEmpty()) {
            sendMessage(session, createResponse("error", "玩家ID无效", null));
            return;
        }
        if (requireAccount(session, playerId, true) == null) return;

        // 确保playerId映射到当前session
        playerSessionMap.put(playerId, session.getId());

        // 打印所有房间ID
        log.info("[DATA] 当前所有房间ID: {}", roomManager.buildRoomList().stream().map(m -> m.get("id")).toList());

        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) {
            log.warn("[!] 重连失败，房间不存在: {}", roomId);
            // 尝试通过roomId查找
            log.info("[FIND] 尝试查找所有房间...");
            for (Map<String, Object> info : roomManager.buildRoomList()) {
                String id = (String) info.get("id");
                GameRoomManager.RoomData r = roomManager.getRoom(id);
                if (r != null) {
                    log.info("  房间: {} (状态: {})", r.id, r.status);
                }
            }
            sendMessage(session, createResponse("error", "房间不存在", null));
            return;
        }

        // 检查玩家是否在房间中
        if (!room.players.contains(playerId)) {
            log.warn("[!] 重连失败，玩家不在房间中: {} -> {}", playerId, roomId);
            sendMessage(session, createResponse("error", "玩家不在房间中", null));
            return;
        }

        // 发送当前游戏状态
        Map<String, Object> result = new HashMap<>();
        result.put("roomId", roomId);
        result.put("players", room.players);
        result.put("civilianWord", room.civilianWord);
        result.put("undercoverWord", room.undercoverWord);
        result.put("undercoverIndex", room.undercoverIndex);
        result.put("playerWords", room.playerWords);
        result.put("round", room.round);
        result.put("status", room.status);
        result.put("gameOver", room.gameOver);
        result.put("eliminated", room.eliminated);
        result.put("spoken", new ArrayList<>(room.spoken));
        result.put("voted", new ArrayList<>(room.voted));
        result.put("votes", room.votes);
        result.put("gameType", room.gameType != null ? room.gameType : "undercover");
        result.put("displayNames", roomManager.buildDisplayNames(room));
        result.put("host", room.host);
        result.put("name", room.name);
        result.put("maxPlayers", room.maxPlayers);
        result.put("difficulty", room.difficulty != null ? room.difficulty : "normal");

        // 新游戏：按玩家身份合并可见快照（汤底/答案只给有权限的人）
        RoomGameLogic logic = logicRegistry.get(room.gameType);
        if (logic != null) {
            Map<String, Object> snap = logic.snapshot(room, playerId);
            if (snap != null) {
                for (Map.Entry<String, Object> e : snap.entrySet()) {
                    result.put(e.getKey(), e.getValue());
                }
            }
        }

        sendMessage(session, createResponse("rejoinSuccess", "重连成功", result));

        broadcastToRoom(roomId, Map.of("type", "playerRejoined", "playerName", playerId, "roomId", roomId));

        log.info("> 玩家重连成功: {} -> {}", playerId, roomId);
    }

    // ========== 广播工具 ==========

    private void broadcastToRoom(String roomId, Map<String, Object> message) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) return;
        // 快照遍历：joinRoom/离场清理可能在广播期间并发修改 room.players
        for (String playerId : new ArrayList<>(room.players)) {
            String sessionId = playerSessionMap.get(playerId);
            if (sessionId != null) {
                WebSocketSession session = sessions.get(sessionId);
                if (session != null && session.isOpen()) {
                    sendMessage(session, message);
                }
            }
        }
    }

    private void broadcastRoomList() {
        Map<String, Object> msg = Map.of("type", "roomList", "rooms", roomManager.buildRoomList());
        for (WebSocketSession session : sessions.values()) {
            if (session.isOpen()) {
                sendMessage(session, msg);
            }
        }
    }

    private void sendMessage(WebSocketSession session, Map<String, Object> message) {
        if (session == null || !session.isOpen()) return;

        String sessionId = session.getId();
        Object lock = sessionLocks.get(sessionId);
        if (lock == null) {
            lock = new Object();
            sessionLocks.put(sessionId, lock);
        }

        synchronized (lock) {
            try {
                String json = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.error("发送消息失败: {}", e.getMessage());
            }
        }
    }

    private Map<String, Object> createResponse(String type, String message, Object data) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", type);
        result.put("message", message);
        result.put("data", data != null ? data : new HashMap<>());
        return result;
    }
}
