package com.smxy.agent.websocket.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.smxy.agent.game.GameLLMHelper;
import com.smxy.agent.game.IdiomBank;
import com.smxy.agent.websocket.GameRoomManager;
import com.smxy.agent.websocket.RoomGameLogic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 成语接龙联机：真人轮流接龙，LLM 当裁判（离线降级本地库宽松放行），
 * 基本规则或裁判判定不过 → 犯规，2 犯淘汰；房主可跳过卡壳玩家（记 1 犯）。
 */
@Slf4j
@Component
public class IdiomRoomLogic implements RoomGameLogic {

    private static final String K_CHAIN = "idiom.chain";
    private static final String K_LAST_CHAR = "idiom.lastChar";
    private static final String K_STRIKES = "idiom.strikes";
    private static final String K_TURN = "idiom.turn";
    private static final String K_FIRST = "idiom.first";
    private static final String K_FIRST_MEANING = "idiom.firstMeaning";
    private static final String K_FIRST_SOURCE = "idiom.firstSource";

    private static final int MAX_STRIKES = 2;

    private final GameRoomManager roomManager;
    private final GameLLMHelper llmHelper;

    public IdiomRoomLogic(GameRoomManager roomManager, GameLLMHelper llmHelper) {
        this.roomManager = roomManager;
        this.llmHelper = llmHelper;
    }

    @Override
    public String gameType() {
        return "idiom";
    }

    @Override
    public Map<String, Object> onStart(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.host.equals(playerId)) return RoomGameLogic.error("只有房主可以开始");
            if (room.players.size() < 2) return RoomGameLogic.error("至少需要2名玩家才能开始");

            room.status = "playing";
            room.gameOver = false;
            room.round = 1;
            room.eliminated.clear();
            room.extra.clear();

            // 首词难度：easy 大众成语，hard 生僻冷门成语
            String difficulty = room.difficulty != null ? room.difficulty : "normal";
            String askPrompt = "你是成语接龙玩家。请随机给出一个常见的中文四字成语（只认常见成语，不要生僻词），只输出成语本身，不要其他内容。";
            if ("easy".equals(difficulty)) {
                askPrompt = "你是成语接龙玩家。请随机给出一个最大众最基础的中文四字成语（如画蛇添足级别，所有玩家都认识），只输出成语本身，不要其他内容。";
            } else if ("hard".equals(difficulty)) {
                askPrompt = "你是成语接龙玩家。请随机给出一个生僻冷门的中文四字成语（避免常见成语，优先文学典故类，增加接龙难度），只输出成语本身，不要其他内容。";
            }

            String first = null;
            String meaning = "";
            String source = "ai";
            String aiFirst = llmHelper.ask(askPrompt, "请出题");
            if (aiFirst != null && aiFirst.trim().length() == 4) {
                first = aiFirst.trim();
            } else {
                first = IdiomBank.random();
                source = "local";
            }

            room.extra.put(K_CHAIN, new ArrayList<>(List.of(first)));
            room.extra.put(K_LAST_CHAR, IdiomBank.lastCharOf(first));
            room.extra.put(K_STRIKES, new HashMap<String, Integer>());
            room.extra.put(K_TURN, room.players.get(0));
            room.extra.put(K_FIRST, first);
            room.extra.put(K_FIRST_MEANING, meaning);
            room.extra.put(K_FIRST_SOURCE, source);

            log.info("[IDIOM] 成语接龙开局: {} 难度={} 首词={} 来源={}", room.id, difficulty, first, source);
            return RoomGameLogic.broadcast(Map.of(
                    "type", "idiomStarted",
                    "roomId", room.id,
                    "difficulty", difficulty,
                    "firstIdiom", first,
                    "lastChar", IdiomBank.lastCharOf(first),
                    "meaning", meaning,
                    "source", source,
                    "players", new ArrayList<>(room.players),
                    "displayNames", roomManager.buildDisplayNames(room),
                    "currentTurn", room.players.get(0)));
        }
    }

    @Override
    public Map<String, Object> handleMessage(String type, GameRoomManager.RoomData room,
                                             String playerId, Map<String, Object> data) {
        switch (type) {
            case "idiomPlay":
                return handlePlay(room, playerId, data);
            case "idiomSkip":
                return handleSkip(room, playerId);
            default:
                return RoomGameLogic.error("未知消息类型: " + type);
        }
    }

    private Map<String, Object> handlePlay(GameRoomManager.RoomData room, String playerId,
                                           Map<String, Object> data) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (room.isEliminated(playerId)) return RoomGameLogic.error("你已被淘汰");
            if (!playerId.equals(room.extra.get(K_TURN))) return RoomGameLogic.error("还没轮到你");

            String idiom = data == null ? null : (String) data.get("idiom");
            if (idiom == null) return RoomGameLogic.error("参数不完整");
            idiom = idiom.trim();

            @SuppressWarnings("unchecked")
            List<String> chain = (List<String>) room.extra.get(K_CHAIN);
            String lastChar = (String) room.extra.get(K_LAST_CHAR);

            // ===== 基本规则：犯规 =====
            if (idiom.length() != 4) {
                return foul(room, playerId, idiom, "必须是四字成语", false);
            }
            if (!idiom.startsWith(lastChar)) {
                return foul(room, playerId, idiom, "必须以「" + lastChar + "」开头", false);
            }
            if (chain.contains(idiom)) {
                return foul(room, playerId, idiom, "成语「" + idiom + "」已经用过，不能重复", false);
            }

            // ===== LLM 裁判（离线宽松放行，保持可玩） =====
            String meaning = "";
            JsonNode node = llmHelper.askJson(
                    "你是成语接龙的裁判。上一个成语的末字是「" + lastChar + "」。\n" +
                            "玩家出的成语：「" + idiom + "」\n" +
                            "规则：必须是真实常见的四字成语、必须以「" + lastChar + "」开头、不能重复。\n" +
                            "裁判从严：生僻的、网络造词、胡乱拼凑的一律判 valid=false 并说明原因。\n" +
                            "只输出 JSON：{\"valid\":true或false,\"reason\":\"若无效给出简短原因\",\"meaning\":\"成语含义（10字内）\"}",
                    idiom);
            if (node != null) {
                if (!node.path("valid").asBoolean(false)) {
                    String reason = node.path("reason").asText("");
                    return foul(room, playerId, idiom,
                            reason.isEmpty() ? "这不是常见成语" : reason, false);
                }
                meaning = node.path("meaning").asText("");
            }

            // ===== 有效接龙 =====
            chain.add(idiom);
            String newLast = IdiomBank.lastCharOf(idiom);
            room.extra.put(K_LAST_CHAR, newLast);
            String next = nextAliveAfter(room, playerId);
            room.extra.put(K_TURN, next);

            log.info("[IDIOM] {} 接龙: {}（末字「{}」）", playerId, idiom, newLast);
            return RoomGameLogic.broadcast(Map.of(
                    "type", "idiomUpdate",
                    "roomId", room.id,
                    "valid", true,
                    "playerName", playerId,
                    "idiom", idiom,
                    "meaning", meaning,
                    "lastChar", newLast,
                    "strikes", new HashMap<>((Map<String, Integer>) room.extra.get(K_STRIKES)),
                    "eliminated", "",
                    "nextPlayer", next));
        }
    }

    private Map<String, Object> handleSkip(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (!room.host.equals(playerId)) return RoomGameLogic.error("只有房主可以跳过");
            String turn = (String) room.extra.get(K_TURN);
            return foul(room, turn, "", "超时被房主跳过", true);
        }
    }

    /** 记 1 次犯规；2 犯淘汰；被淘汰后推进回合；活人不足 2 判终局 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> foul(GameRoomManager.RoomData room, String playerId, String idiom,
                                     String reason, boolean skipped) {
        Map<String, Integer> strikes = (Map<String, Integer>) room.extra.get(K_STRIKES);
        int count = strikes.getOrDefault(playerId, 0) + 1;
        strikes.put(playerId, count);

        List<Map<String, Object>> broadcasts = new ArrayList<>();
        boolean eliminatedNow = count >= MAX_STRIKES;

        if (eliminatedNow) {
            room.eliminated.add(playerId);
            log.info("[IDIOM] {} 犯规 {} 次被淘汰: {}", playerId, count, reason);
        }

        List<String> survivors = room.getActivePlayers();
        if (survivors.size() <= 1) {
            String winner = survivors.isEmpty() ? null : survivors.get(0);
            endGame(room, winner);
            broadcasts.add(Map.of(
                    "type", "idiomUpdate",
                    "roomId", room.id,
                    "valid", false,
                    "playerName", playerId,
                    "idiom", idiom,
                    "reason", reason,
                    "skipped", skipped,
                    "strikes", new HashMap<>(strikes),
                    "eliminated", eliminatedNow ? playerId : "",
                    "nextPlayer", ""));
            broadcasts.add(gameEndedMessage(room, winner));
            return RoomGameLogic.broadcast(broadcasts, null);
        }

        String next = nextAliveAfter(room, playerId);
        room.extra.put(K_TURN, next);
        broadcasts.add(Map.of(
                "type", "idiomUpdate",
                "roomId", room.id,
                "valid", false,
                "playerName", playerId,
                "idiom", idiom,
                "reason", reason,
                "skipped", skipped,
                "strikes", new HashMap<>(strikes),
                "eliminated", eliminatedNow ? playerId : "",
                "nextPlayer", next));
        return RoomGameLogic.broadcast(broadcasts, null);
    }

    @Override
    public Map<String, Object> snapshot(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing")) return null;
            Map<String, Object> snap = new HashMap<>();
            snap.put("firstIdiom", room.extra.get(K_FIRST));
            snap.put("firstMeaning", room.extra.get(K_FIRST_MEANING));
            snap.put("firstSource", room.extra.get(K_FIRST_SOURCE));
            snap.put("chain", new ArrayList<>((List<String>) room.extra.get(K_CHAIN)));
            snap.put("lastChar", room.extra.get(K_LAST_CHAR));
            snap.put("strikes", new HashMap<>((Map<String, Integer>) room.extra.get(K_STRIKES)));
            snap.put("currentTurn", room.extra.get(K_TURN));
            snap.put("alive", room.getActivePlayers());
            snap.put("eliminated", new ArrayList<>(room.eliminated));
            return snap;
        }
    }

    @Override
    public Map<String, Object> onPlayerLeft(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return null;
            if (room.isEliminated(playerId)) return null;

            room.eliminated.add(playerId);
            @SuppressWarnings("unchecked")
            Map<String, Integer> strikes = (Map<String, Integer>) room.extra.get(K_STRIKES);
            strikes.remove(playerId);

            List<String> survivors = room.getActivePlayers();
            if (survivors.size() <= 1) {
                String winner = survivors.isEmpty() ? null : survivors.get(0);
                endGame(room, winner);
                return RoomGameLogic.broadcast(gameEndedMessage(room, winner));
            }

            if (playerId.equals(room.extra.get(K_TURN))) {
                String next = nextAliveAfter(room, playerId);
                room.extra.put(K_TURN, next);
                return RoomGameLogic.broadcast(Map.of(
                        "type", "idiomUpdate",
                        "roomId", room.id,
                        "result", "left",
                        "playerName", playerId,
                        "strikes", new HashMap<>(strikes),
                        "nextPlayer", next));
            }
            return null;
        }
    }

    @Override
    public void reset(GameRoomManager.RoomData room) {
        synchronized (room) {
            room.extra.clear();
        }
    }

    // ========== 辅助 ==========

    private String nextAliveAfter(GameRoomManager.RoomData room, String playerId) {
        List<String> players = room.players;
        int idx = players.indexOf(playerId);
        for (int i = 1; i <= players.size(); i++) {
            String candidate = players.get((idx + i) % players.size());
            if (!room.eliminated.contains(candidate)) return candidate;
        }
        return playerId;
    }

    private void endGame(GameRoomManager.RoomData room, String winner) {
        room.gameOver = true;
        room.status = "ended";
        log.info("[IDIOM] 成语接龙结束: {} 胜者={}", room.id, winner);
    }

    private Map<String, Object> gameEndedMessage(GameRoomManager.RoomData room, String winner) {
        Map<String, String> names = roomManager.buildDisplayNames(room);
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "gameEnded");
        msg.put("roomId", room.id);
        msg.put("gameType", "idiom");
        msg.put("result", winner == null ? "draw" : winner);
        msg.put("winnerName", winner == null ? "" : names.getOrDefault(winner, winner));
        msg.put("displayNames", names);
        return msg;
    }
}
