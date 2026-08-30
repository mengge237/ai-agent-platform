package com.smxy.agent.websocket.logic;

import com.smxy.agent.websocket.GameRoomManager;
import com.smxy.agent.websocket.RoomGameLogic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 拆弹小队联机（无 LLM）：炸弹装有一颗引爆密码，小队轮流剪线，
 * 剪断密码线者拆弹成功获胜；剪线次数耗尽则炸弹引爆、全员阵亡（平局）。
 * 区间内还埋有阻碍线：干扰线（剪断不提供区间信息）与熔断线（剪断短路、
 * 额外消耗 1 次剪线）—— 单纯二分法不再是无风险必赢。
 */
@Slf4j
@Component
public class BombRoomLogic implements RoomGameLogic {

    private static final int FULL_MIN = 1;
    private static final int FULL_MAX = 100;

    private static final String K_MIN = "bomb.min";
    private static final String K_MAX = "bomb.max";
    private static final String K_CODE = "bomb.value";
    private static final String K_TURN = "bomb.turn";
    private static final String K_ATTEMPTS = "bomb.attempts";
    private static final String K_MAX_CUTS = "bomb.maxCuts";
    private static final String K_DECOYS = "bomb.decoys";
    private static final String K_FUSES = "bomb.fuses";

    private final GameRoomManager roomManager;

    public BombRoomLogic(GameRoomManager roomManager) {
        this.roomManager = roomManager;
    }

    @Override
    public String gameType() {
        return "bomb";
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
            room.extra.put(K_MIN, FULL_MIN);
            room.extra.put(K_MAX, FULL_MAX);
            int code = FULL_MIN + new Random().nextInt(FULL_MAX);
            room.extra.put(K_CODE, code);
            room.extra.put(K_TURN, room.players.get(0));
            room.extra.put(K_ATTEMPTS, 0);

            // 阻碍线按难度布置：easy 无阻碍，normal 2干扰+1熔断，hard 3干扰+2熔断
            String difficulty = room.difficulty != null ? room.difficulty : "normal";
            int decoyCount = "easy".equals(difficulty) ? 0 : ("hard".equals(difficulty) ? 3 : 2);
            int fuseCount = "easy".equals(difficulty) ? 0 : ("hard".equals(difficulty) ? 2 : 1);
            Random rnd = new Random();
            Set<Integer> decoys = new HashSet<>();
            Set<Integer> fuses = new HashSet<>();
            for (int i = 0; i < decoyCount; i++) decoys.add(pickWire(rnd, code, decoys, fuses));
            for (int i = 0; i < fuseCount; i++) fuses.add(pickWire(rnd, code, decoys, fuses));
            room.extra.put(K_DECOYS, decoys);
            room.extra.put(K_FUSES, fuses);

            // 剪线上限：小队预算 + 阻碍补偿（easy 无补偿，normal +2，hard +3）
            int maxCuts = 4 + 2 * room.players.size() + decoyCount / 2 + fuseCount;
            room.extra.put(K_MAX_CUTS, maxCuts);

            log.info("[BOMB] 拆弹小队开局: {} 难度={} 密码区间[{},{}] 剪线上限={} 干扰线={} 熔断线={}",
                    room.id, difficulty, FULL_MIN, FULL_MAX, maxCuts, decoyCount, fuseCount);
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "bombStarted");
            msg.put("roomId", room.id);
            msg.put("min", FULL_MIN);
            msg.put("max", FULL_MAX);
            msg.put("maxCuts", maxCuts);
            msg.put("difficulty", difficulty);
            msg.put("decoyCount", decoys.size());
            msg.put("fuseCount", fuses.size());
            msg.put("players", new ArrayList<>(room.players));
            msg.put("displayNames", roomManager.buildDisplayNames(room));
            msg.put("currentTurn", room.players.get(0));
            return RoomGameLogic.broadcast(msg);
        }
    }

    @Override
    public Map<String, Object> handleMessage(String type, GameRoomManager.RoomData room,
                                             String playerId, Map<String, Object> data) {
        if ("bombGuess".equals(type)) return handleGuess(room, playerId, data);
        return RoomGameLogic.error("未知消息类型: " + type);
    }

    private Map<String, Object> handleGuess(GameRoomManager.RoomData room, String playerId,
                                            Map<String, Object> data) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (room.isEliminated(playerId)) return RoomGameLogic.error("你已离场");

            String turn = (String) room.extra.get(K_TURN);
            if (!playerId.equals(turn)) return RoomGameLogic.error("还没轮到你剪线");

            int min = (int) room.extra.get(K_MIN);
            int max = (int) room.extra.get(K_MAX);
            int code = (int) room.extra.get(K_CODE);
            int attempts = (int) room.extra.get(K_ATTEMPTS);
            int maxCuts = (int) room.extra.get(K_MAX_CUTS);

            Object raw = data == null ? null : data.get("number");
            int number;
            if (raw instanceof Number) {
                number = ((Number) raw).intValue();
            } else if (raw instanceof String) {
                try {
                    number = Integer.parseInt(((String) raw).trim());
                } catch (NumberFormatException e) {
                    return RoomGameLogic.error("请输入有效线号");
                }
            } else {
                return RoomGameLogic.error("请输入有效线号");
            }
            if (number < min || number > max) {
                return RoomGameLogic.error("线号需在 " + min + "~" + max + " 之间");
            }

            room.extra.put(K_ATTEMPTS, attempts + 1);

            // ===== 剪断密码线：拆弹成功，剪者获胜 =====
            if (number == code) {
                endGame(room, playerId);

                Map<String, Object> update = new HashMap<>();
                update.put("type", "bombUpdate");
                update.put("roomId", room.id);
                update.put("result", "DEFUSED");
                update.put("number", number);
                update.put("low", min);
                update.put("high", max);
                update.put("attempts", attempts + 1);
                update.put("cutsLeft", Math.max(0, maxCuts - attempts - 1));
                update.put("code", code);

                List<Map<String, Object>> broadcasts = new ArrayList<>();
                broadcasts.add(update);
                broadcasts.add(gameEndedMessage(room, playerId));
                log.info("[BOMB] {} 剪断密码线 {}，拆弹成功", playerId, number);
                return RoomGameLogic.broadcast(broadcasts, null);
            }

            // ===== 干扰线：剪断无区间信息，仅消耗 1 次剪线并公开该线 =====
            @SuppressWarnings("unchecked")
            Set<Integer> decoys = (Set<Integer>) room.extra.get(K_DECOYS);
            if (decoys.remove(number)) {
                int cutsLeft = maxCuts - attempts - 1;
                Map<String, Object> decoy = new HashMap<>();
                decoy.put("type", "bombUpdate");
                decoy.put("roomId", room.id);
                decoy.put("number", number);
                decoy.put("decoy", number);
                decoy.put("low", min);
                decoy.put("high", max);
                decoy.put("attempts", attempts + 1);
                decoy.put("cutsLeft", Math.max(0, cutsLeft));

                // 剪线次数耗尽：炸弹引爆，全员阵亡
                if (cutsLeft <= 0) {
                    endGame(room, null);
                    decoy.put("result", "BOOM");
                    decoy.put("code", code);
                    decoy.put("nextPlayer", "");
                    List<Map<String, Object>> broadcasts = new ArrayList<>();
                    broadcasts.add(decoy);
                    broadcasts.add(gameEndedMessage(room, null));
                    log.info("[BOMB] 剪中干扰线后剪线耗尽，炸弹引爆: {}", room.id);
                    return RoomGameLogic.broadcast(broadcasts, null);
                }

                String next = nextAliveAfter(room, playerId);
                room.extra.put(K_TURN, next);
                decoy.put("result", "DECOY");
                decoy.put("nextPlayer", next);
                decoy.put("eliminated", "");
                log.info("[BOMB] {} 剪中干扰线 {}，区间不变", playerId, number);
                return RoomGameLogic.broadcast(decoy);
            }

            // ===== 安全线/熔断线：收窄密码区间；熔断线短路额外消耗 1 次剪线 =====
            @SuppressWarnings("unchecked")
            Set<Integer> fuses = (Set<Integer>) room.extra.get(K_FUSES);
            boolean fuseHit = fuses.remove(number);
            if (number > code) {
                room.extra.put(K_MAX, number - 1);
            } else {
                room.extra.put(K_MIN, number + 1);
            }
            int newMin = (int) room.extra.get(K_MIN);
            int newMax = (int) room.extra.get(K_MAX);
            int totalAttempts = attempts + 1 + (fuseHit ? 1 : 0);
            if (fuseHit) room.extra.put(K_ATTEMPTS, totalAttempts);
            int cutsLeft = maxCuts - totalAttempts;

            Map<String, Object> safe = new HashMap<>();
            safe.put("type", "bombUpdate");
            safe.put("roomId", room.id);
            safe.put("number", number);
            safe.put("low", newMin);
            safe.put("high", newMax);
            safe.put("attempts", totalAttempts);
            safe.put("cutsLeft", cutsLeft);
            if (fuseHit) safe.put("fuse", number);

            // ===== 剪线次数耗尽：炸弹引爆，全员阵亡 =====
            if (cutsLeft <= 0) {
                endGame(room, null);
                safe.put("result", "BOOM");
                safe.put("code", code);
                safe.put("nextPlayer", "");
                List<Map<String, Object>> broadcasts = new ArrayList<>();
                broadcasts.add(safe);
                broadcasts.add(gameEndedMessage(room, null));
                log.info("[BOMB] 剪线次数耗尽，炸弹引爆: {}", room.id);
                return RoomGameLogic.broadcast(broadcasts, null);
            }

            String next = nextAliveAfter(room, playerId);
            room.extra.put(K_TURN, next);
            safe.put("result", fuseHit ? "FUSE" : "SAFE");
            safe.put("nextPlayer", next);
            safe.put("eliminated", "");
            return RoomGameLogic.broadcast(safe);
        }
    }

    @Override
    public Map<String, Object> snapshot(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing")) return null; // 等待/结束态由基础字段描述
            Map<String, Object> snap = new HashMap<>();
            snap.put("min", room.extra.get(K_MIN));
            snap.put("max", room.extra.get(K_MAX));
            snap.put("attempts", room.extra.get(K_ATTEMPTS));
            Object maxCuts = room.extra.get(K_MAX_CUTS);
            if (maxCuts != null) {
                snap.put("maxCuts", maxCuts);
                snap.put("cutsLeft", Math.max(0, (int) maxCuts - (int) room.extra.get(K_ATTEMPTS)));
            }
            snap.put("currentTurn", room.extra.get(K_TURN));
            snap.put("alive", room.getActivePlayers());
            snap.put("eliminated", new ArrayList<>(room.eliminated));
            // 只给阻碍线数量，不泄露位置（与不泄露密码同理）
            Object decoys = room.extra.get(K_DECOYS);
            if (decoys != null) snap.put("decoyCount", ((Set<?>) decoys).size());
            Object fuses = room.extra.get(K_FUSES);
            if (fuses != null) snap.put("fuseCount", ((Set<?>) fuses).size());
            return snap; // 绝不包含炸弹密码
        }
    }

    @Override
    public Map<String, Object> onPlayerLeft(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return null;
            if (room.isEliminated(playerId)) return null; // 已离场者不影响对局

            room.eliminated.add(playerId);
            List<String> survivors = room.getActivePlayers();

            if (survivors.size() <= 1) {
                String winner = survivors.isEmpty() ? null : survivors.get(0);
                endGame(room, winner);
                return RoomGameLogic.broadcast(gameEndedMessage(room, winner));
            }

            String turn = (String) room.extra.get(K_TURN);
            if (playerId.equals(turn)) {
                String next = nextAliveAfter(room, playerId);
                room.extra.put(K_TURN, next);
                Object maxCuts = room.extra.get(K_MAX_CUTS);
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "bombUpdate");
                msg.put("roomId", room.id);
                msg.put("result", "LEFT");
                msg.put("number", -1);
                msg.put("low", room.extra.get(K_MIN));
                msg.put("high", room.extra.get(K_MAX));
                msg.put("eliminated", playerId);
                msg.put("attempts", room.extra.get(K_ATTEMPTS));
                msg.put("cutsLeft", Math.max(0, (int) maxCuts - (int) room.extra.get(K_ATTEMPTS)));
                msg.put("nextPlayer", next);
                return RoomGameLogic.broadcast(msg);
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

    /** 随机挑一根阻碍线：不落在密码线上，且与其他阻碍线互不重复 */
    private int pickWire(Random rnd, int code, Set<Integer> decoys, Set<Integer> fuses) {
        int w;
        do {
            w = FULL_MIN + rnd.nextInt(FULL_MAX);
        } while (w == code || decoys.contains(w) || fuses.contains(w));
        return w;
    }

    /** 从 playerId 的下一位开始，循环找下一个在场玩家 */
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
        log.info("[BOMB] 拆弹结束: {} 胜者={}", room.id, winner);
    }

    private Map<String, Object> gameEndedMessage(GameRoomManager.RoomData room, String winner) {
        Map<String, String> names = roomManager.buildDisplayNames(room);
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (String p : room.players) {
            ranking.add(Map.of(
                    "playerId", p,
                    "name", names.getOrDefault(p, p),
                    "alive", !room.eliminated.contains(p)));
        }
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "gameEnded");
        msg.put("roomId", room.id);
        msg.put("gameType", "bomb");
        msg.put("result", winner == null ? "draw" : winner);
        msg.put("winnerName", winner == null ? "" : names.getOrDefault(winner, winner));
        msg.put("displayNames", names);
        msg.put("ranking", ranking);
        return msg;
    }
}
