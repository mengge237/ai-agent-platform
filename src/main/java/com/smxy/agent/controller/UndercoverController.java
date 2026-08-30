package com.smxy.agent.controller;

import com.smxy.agent.application.undercover.UndercoverAgent;
import com.smxy.agent.websocket.GameRoomManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/game/undercover")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UndercoverController {

    private final GameRoomManager roomManager;
    private final UndercoverAgent undercoverAgent;

    @PostMapping("/create")
    public Result<Map<String, Object>> createRoom(@RequestBody UndercoverRequest.CreateRoom request) {
        try {
            log.info("▤ 创建房间请求: players={}, category={}", request.getPlayers(), request.getCategory());

            // 获取房主ID
            String hostId = request.getPlayers() != null && !request.getPlayers().isEmpty()
                    ? request.getPlayers().get(0)
                    : "host-" + System.currentTimeMillis();

            // 构建创建参数
            Map<String, Object> data = new HashMap<>();
            data.put("name", "游戏房间-" + System.currentTimeMillis());
            data.put("maxPlayers", request.getPlayers() != null ? request.getPlayers().size() : 6);
            data.put("aiCount", 0);
            data.put("category", request.getCategory() != null ? request.getCategory() : "水果");
            data.put("password", "");
            data.put("playerName", hostId);

            // 使用 GameRoomManager 创建房间
            Map<String, Object> result = roomManager.createRoom(hostId, data);

            if ("error".equals(result.get("status"))) {
                log.warn("[!] 创建房间失败: {}", result.get("message"));
                return Result.error((String) result.get("message"));
            }

            String roomId = (String) result.get("id");
            log.info("✓ 房间创建成功，roomId: {}", roomId);

            // 获取房间并添加所有玩家
            GameRoomManager.RoomData room = roomManager.getRoom(roomId);

            if (room == null) {
                log.error("✕ 房间创建后无法获取: {}", roomId);
                return Result.error("房间创建失败");
            }

            // 添加所有玩家到房间
            if (request.getPlayers() != null) {
                for (String player : request.getPlayers()) {
                    if (!room.players.contains(player)) {
                        room.players.add(player);
                    }
                }
                log.info("✓ 玩家列表: {}", room.players);
            }

            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("roomId", roomId);
            response.put("players", room.players);
            response.put("civilianWord", room.civilianWord != null ? room.civilianWord : "");
            response.put("undercoverWord", room.undercoverWord != null ? room.undercoverWord : "");
            response.put("undercoverIndex", room.undercoverIndex);
            response.put("playerWords", room.playerWords);
            response.put("status", room.status);

            return Result.success(response);

        } catch (Exception e) {
            log.error("✕ 创建房间异常: {}", e.getMessage(), e);
            return Result.error("创建房间失败: " + e.getMessage());
        }
    }

    @GetMapping("/room/{roomId}")
    public Result<Map<String, Object>> getRoom(@PathVariable String roomId) {
        log.info("[FIND] 查询房间: {}", roomId);

        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) {
            log.warn("[!] 房间不存在: {}", roomId);
            return Result.error("房间不存在");
        }

        log.info("✓ 找到房间: {}, 玩家: {}", roomId, room.players);

        Map<String, Object> response = new HashMap<>();
        response.put("roomId", room.id);
        response.put("players", room.players);
        response.put("civilianWord", room.civilianWord != null ? room.civilianWord : "");
        response.put("undercoverWord", room.undercoverWord != null ? room.undercoverWord : "");
        response.put("undercoverIndex", room.undercoverIndex);
        response.put("playerWords", room.playerWords);
        response.put("status", room.status);
        response.put("gameOver", room.gameOver);
        response.put("round", room.round);
        response.put("eliminated", room.eliminated);
        response.put("spoken", new ArrayList<>(room.spoken));
        response.put("voted", new ArrayList<>(room.voted));
        response.put("votes", room.votes);

        return Result.success(response);
    }

    @PostMapping("/describe")
    public Result<Map<String, String>> generateDescription(@RequestBody UndercoverRequest.Describe request) {
        try {
            GameRoomManager.RoomData room = roomManager.getRoom(request.getRoomId());
            if (room == null) {
                return Result.error("房间不存在");
            }

            String playerId = request.getPlayerId();
            String myWord = room.playerWords.get(playerId);
            boolean isUndercover = room.isUndercover(playerId);

            undercoverAgent.setGameInfo(myWord, room.undercoverWord, isUndercover);
            String description = undercoverAgent.generateDescription();

            Map<String, String> response = new HashMap<>();
            response.put("description", description);
            return Result.success(response);
        } catch (Exception e) {
            log.error("生成描述失败: {}", e.getMessage());
            return Result.error("生成描述失败");
        }
    }

    @PostMapping("/vote")
    public Result<Map<String, String>> vote(@RequestBody UndercoverRequest.Vote request) {
        try {
            GameRoomManager.RoomData room = roomManager.getRoom(request.getRoomId());
            if (room == null) {
                return Result.error("房间不存在");
            }

            String playerId = request.getPlayerId();
            String myWord = room.playerWords.get(playerId);
            boolean isUndercover = room.isUndercover(playerId);

            undercoverAgent.setGameInfo(myWord, room.undercoverWord, isUndercover);
            String votedPlayer = undercoverAgent.analyzeAndVote(request.getDiscussion());

            Map<String, String> response = new HashMap<>();
            response.put("votedPlayer", votedPlayer);
            return Result.success(response);
        } catch (Exception e) {
            log.error("投票失败: {}", e.getMessage());
            return Result.error("投票失败");
        }
    }

    @GetMapping("/room/test")
    public Result<String> test() {
        return Result.success("后端服务正常运行");
    }
}