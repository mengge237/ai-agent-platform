package com.smxy.agent.websocket.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.smxy.agent.game.GameLLMHelper;
import com.smxy.agent.game.TurtleBank;
import com.smxy.agent.websocket.GameRoomManager;
import com.smxy.agent.websocket.RoomGameLogic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 海龟汤联机：房主知道汤底，其他玩家用「是/否」问题推理、可随时猜汤底。
 * 房主手动判 是/否/无法确定；猜汤底由 LLM 按核心真相判定（离线关键词降级）；
 * 20 问未破 → 房主胜；猜中 → 猜中者胜。
 */
@Slf4j
@Component
public class TurtleRoomLogic implements RoomGameLogic {

    public static final int MAX_QUESTIONS = 20;

    private static final String K_STORY = "turtle.story";
    private static final String K_ANSWER = "turtle.answer";
    private static final String K_KEYWORDS = "turtle.keywords";
    private static final String K_SOURCE = "turtle.source";
    private static final String K_QAS = "turtle.qas";
    private static final String K_PENDING = "turtle.pending";
    private static final String K_COUNT = "turtle.count";

    private static final Set<String> VERDICTS = Set.of("是", "否", "无法确定");

    private final GameRoomManager roomManager;
    private final GameLLMHelper llmHelper;

    public TurtleRoomLogic(GameRoomManager roomManager, GameLLMHelper llmHelper) {
        this.roomManager = roomManager;
        this.llmHelper = llmHelper;
    }

    @Override
    public String gameType() {
        return "turtle";
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

            // 谜题难度：easy 简单日常场景，hard 多重反转深推理
            String difficulty = room.difficulty != null ? room.difficulty : "normal";
            String diffRequirement;
            if ("easy".equals(difficulty)) {
                diffRequirement = "难度定位：简单日常场景，汤底直接自然，适合新手，1-2 步推理即可解开。";
            } else if ("hard".equals(difficulty)) {
                diffRequirement = "难度定位：困难，需要多重反转与深层次推理，汤底出人意料且暗线丰富，真相至少隐藏两层。";
            } else {
                diffRequirement = "难度定位：中等，有一定悬疑感和适当反转。";
            }

            String story = null, answer = null, source = "ai";
            String[] keywords = new String[0];
            JsonNode node = llmHelper.askJson(
                    "你是一个'海龟汤'（情境推理游戏）出题人。请创作一个简短有强烈悬疑感的中文海龟汤谜题。\n" +
                            "要求：\n" +
                            "1. 汤面简短（60字以内），表面平静但暗藏矛盾，能激发玩家提问欲望；\n" +
                            "2. 汤底合理完整、内部自洽、有出人意料的真相，绝不敷衍；\n" +
                            "3. " + diffRequirement + "\n" +
                            "4. keywords 给出3-5个汤底中最关键的决定性线索词（具体名词短语），这些词将用于判定玩家提问/猜测是否触及真相，必须是汤底里真实出现或直接指向真相的词，不要用'为什么''原因'等无信息量的泛词。\n" +
                            "只输出 JSON：{\"story\":\"汤面\",\"answer\":\"汤底\",\"keywords\":[\"线索词1\",\"线索词2\",\"线索词3\"]}",
                    "请出一道海龟汤谜题");
            if (node != null && node.has("story") && node.has("answer")) {
                story = node.path("story").asText().trim();
                answer = node.path("answer").asText().trim();
                if (node.path("keywords").isArray()) {
                    keywords = new String[node.path("keywords").size()];
                    for (int i = 0; i < keywords.length; i++) {
                        keywords[i] = node.path("keywords").get(i).asText();
                    }
                }
            }
            if (story == null || story.isEmpty() || answer == null || answer.isEmpty()) {
                TurtleBank.TurtlePuzzle local = TurtleBank.pickRandom();
                story = local.story;
                answer = local.answer;
                keywords = local.keywords;
                source = "local";
            }

            room.extra.put(K_STORY, story);
            room.extra.put(K_ANSWER, answer);
            room.extra.put(K_KEYWORDS, keywords);
            room.extra.put(K_SOURCE, source);
            room.extra.put(K_QAS, new ArrayList<Map<String, Object>>());
            room.extra.put(K_PENDING, new ArrayList<Map<String, Object>>());
            room.extra.put(K_COUNT, 0);

            log.info("[TURTLE] 海龟汤开局: {} 难度={} 来源={}", room.id, difficulty, source);

            List<Map<String, Object>> broadcasts = new ArrayList<>();
            broadcasts.add(Map.of(
                    "type", "turtleStarted",
                    "roomId", room.id,
                    "difficulty", difficulty,
                    "story", story,
                    "source", source,
                    "players", new ArrayList<>(room.players),
                    "displayNames", roomManager.buildDisplayNames(room),
                    "host", room.host,
                    "maxQuestions", MAX_QUESTIONS));

            // 汤底只单播给房主
            List<Map<String, Object>> unicasts = new ArrayList<>();
            Map<String, Object> secret = new HashMap<>();
            secret.put("type", "turtleHostSecret");
            secret.put("roomId", room.id);
            secret.put("answer", answer);
            secret.put("keywords", keywords);
            Map<String, Object> entry = new HashMap<>();
            entry.put("playerId", room.host);
            entry.put("message", secret);
            unicasts.add(entry);
            return RoomGameLogic.broadcast(broadcasts, unicasts);
        }
    }

    @Override
    public Map<String, Object> handleMessage(String type, GameRoomManager.RoomData room,
                                             String playerId, Map<String, Object> data) {
        switch (type) {
            case "turtleAsk":
                return handleAsk(room, playerId, data);
            case "turtleAnswer":
                return handleAnswer(room, playerId, data);
            case "turtleGuess":
                return handleGuess(room, playerId, data);
            case "turtleReveal":
                return handleReveal(room, playerId);
            default:
                return RoomGameLogic.error("未知消息类型: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleAsk(GameRoomManager.RoomData room, String playerId,
                                          Map<String, Object> data) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (room.isEliminated(playerId)) return RoomGameLogic.error("你已被淘汰");
            if (room.host.equals(playerId)) return RoomGameLogic.error("房主知道汤底，不能提问");

            String question = data == null ? null : (String) data.get("question");
            if (question == null || question.trim().isEmpty()) return RoomGameLogic.error("问题不能为空");
            question = question.trim();

            int count = (int) room.extra.get(K_COUNT);
            if (count >= MAX_QUESTIONS) return RoomGameLogic.error("提问次数已用完，快猜汤底吧");

            count++;
            room.extra.put(K_COUNT, count);

            List<Map<String, Object>> qas = (List<Map<String, Object>>) room.extra.get(K_QAS);
            List<Map<String, Object>> pending = (List<Map<String, Object>>) room.extra.get(K_PENDING);

            Map<String, Object> qa = new HashMap<>();
            qa.put("no", count);
            qa.put("playerId", playerId);
            qa.put("question", question);
            qa.put("answer", "");
            qa.put("hint", "");
            qas.add(qa);

            Map<String, Object> pend = new HashMap<>();
            pend.put("no", count);
            pend.put("playerId", playerId);
            pend.put("question", question);
            pending.add(pend);

            log.info("[TURTLE] 提问{}: {} -> {}", count, playerId, question.substring(0, Math.min(20, question.length())));
            return RoomGameLogic.broadcast(Map.of(
                    "type", "turtleAsked",
                    "roomId", room.id,
                    "no", count,
                    "playerName", playerId,
                    "question", question,
                    "remaining", MAX_QUESTIONS - count));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleAnswer(GameRoomManager.RoomData room, String playerId,
                                             Map<String, Object> data) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (!room.host.equals(playerId)) return RoomGameLogic.error("只有房主可以判定");

            Object rawNo = data == null ? null : data.get("questionNo");
            int no = rawNo instanceof Number ? ((Number) rawNo).intValue() : -1;
            String verdict = data == null ? null : (String) data.get("answer");
            if (verdict == null) return RoomGameLogic.error("参数不完整");
            verdict = verdict.trim();
            if (!VERDICTS.contains(verdict)) return RoomGameLogic.error("判定只能是 是/否/无法确定");
            String hint = data.get("hint") == null ? "" : String.valueOf(data.get("hint")).trim();

            List<Map<String, Object>> pending = (List<Map<String, Object>>) room.extra.get(K_PENDING);
            Map<String, Object> target = null;
            for (Map<String, Object> p : pending) {
                if (((Number) p.get("no")).intValue() == no) { target = p; break; }
            }
            if (target == null) return RoomGameLogic.error("问题不存在或已作答");

            pending.remove(target);
            List<Map<String, Object>> qas = (List<Map<String, Object>>) room.extra.get(K_QAS);
            for (Map<String, Object> qa : qas) {
                if (((Number) qa.get("no")).intValue() == no) {
                    qa.put("answer", verdict);
                    qa.put("hint", hint);
                }
            }

            int count = (int) room.extra.get(K_COUNT);
            List<Map<String, Object>> broadcasts = new ArrayList<>();
            broadcasts.add(Map.of(
                    "type", "turtleAnswered",
                    "roomId", room.id,
                    "no", no,
                    "question", target.get("question"),
                    "answer", verdict,
                    "hint", hint,
                    "remaining", MAX_QUESTIONS - count));

            // 20 问用完且全部作答 → 房主胜（终局附汤底，供全员揭晓）
            if (count >= MAX_QUESTIONS && pending.isEmpty()) {
                endGame(room, room.host);
                Map<String, Object> endMsg = gameEndedMessage(room, room.host);
                endMsg.put("answer", room.extra.get(K_ANSWER));
                broadcasts.add(endMsg);
            }
            log.info("[TURTLE] 判定 #{}: {}", no, verdict);
            return RoomGameLogic.broadcast(broadcasts, null);
        }
    }

    private Map<String, Object> handleGuess(GameRoomManager.RoomData room, String playerId,
                                            Map<String, Object> data) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (room.isEliminated(playerId)) return RoomGameLogic.error("你已被淘汰");
            if (room.host.equals(playerId)) return RoomGameLogic.error("房主知道汤底，不能猜");

            String guess = data == null ? null : (String) data.get("guess");
            if (guess == null || guess.trim().isEmpty()) return RoomGameLogic.error("猜测不能为空");
            guess = guess.trim();

            String answer = (String) room.extra.get(K_ANSWER);
            String[] keywords = (String[]) room.extra.get(K_KEYWORDS);
            boolean correct = false;
            String comment = "";

            JsonNode node = llmHelper.askJson(
                    "你是'海龟汤'游戏裁判。汤底是：" + answer + "\n" +
                            "玩家的猜测：" + guess + "\n" +
                            "判定玩家是否猜中了核心真相：只有点出核心因果（谁/发生了什么/为什么）才算对；只猜对个别细节或大方向含糊不算对。" +
                            "只输出 JSON：{\"correct\":true或false,\"comment\":\"不超过30字点评，若不对需指出还缺什么\"}",
                    guess);
            if (node != null) {
                correct = node.path("correct").asBoolean(false);
                comment = node.path("comment").asText("");
            } else {
                // 降级关键词匹配
                int hits = 0;
                for (String kw : keywords) {
                    if (kw != null && !kw.isEmpty() && guess.contains(kw)) hits++;
                }
                correct = hits >= Math.max(2, keywords.length / 2);
                comment = correct ? "核心真相吻合！" : "还差一点，关键线索没对上";
            }

            List<Map<String, Object>> broadcasts = new ArrayList<>();
            broadcasts.add(Map.of(
                    "type", "turtleGuessResult",
                    "roomId", room.id,
                    "playerName", playerId,
                    "guess", guess,
                    "correct", correct,
                    "comment", comment));

            if (correct) {
                endGame(room, playerId);
                Map<String, Object> end = gameEndedMessage(room, playerId);
                end.put("answer", answer);
                broadcasts.add(end);
                log.info("[TURTLE] {} 猜中汤底！", playerId);
            }
            return RoomGameLogic.broadcast(broadcasts, null);
        }
    }

    private Map<String, Object> handleReveal(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (!room.host.equals(playerId)) return RoomGameLogic.error("只有房主可以公布汤底");

            endGame(room, room.host);
            String answer = (String) room.extra.get(K_ANSWER);
            List<Map<String, Object>> broadcasts = new ArrayList<>();
            broadcasts.add(Map.of(
                    "type", "turtleRevealed",
                    "roomId", room.id,
                    "answer", answer,
                    "story", room.extra.get(K_STORY)));
            broadcasts.add(gameEndedMessage(room, room.host));
            return RoomGameLogic.broadcast(broadcasts, null);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> snapshot(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing")) return null;
            Map<String, Object> snap = new HashMap<>();
            snap.put("story", room.extra.get(K_STORY));
            snap.put("source", room.extra.get(K_SOURCE));
            snap.put("questionCount", room.extra.get(K_COUNT));
            snap.put("maxQuestions", MAX_QUESTIONS);
            snap.put("host", room.host);
            snap.put("qas", new ArrayList<>((List<Map<String, Object>>) room.extra.get(K_QAS)));
            snap.put("alive", room.getActivePlayers());
            snap.put("eliminated", new ArrayList<>(room.eliminated));
            // 汤底/待判列表只给房主
            if (playerId.equals(room.host)) {
                snap.put("answer", room.extra.get(K_ANSWER));
                snap.put("keywords", room.extra.get(K_KEYWORDS));
                snap.put("pending", new ArrayList<>((List<Map<String, Object>>) room.extra.get(K_PENDING)));
            }
            return snap;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> onPlayerLeft(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return null;
            if (room.isEliminated(playerId)) return null;

            room.eliminated.add(playerId);
            List<Map<String, Object>> unicasts = new ArrayList<>();
            List<Map<String, Object>> broadcasts = new ArrayList<>();

            // 房主掉线：房主已由 handler 转移，给新房主补发汤底
            boolean hostChanged = !playerId.equals(room.host);
            if (hostChanged) {
                Map<String, Object> secret = new HashMap<>();
                secret.put("type", "turtleHostSecret");
                secret.put("roomId", room.id);
                secret.put("answer", room.extra.get(K_ANSWER));
                secret.put("keywords", room.extra.get(K_KEYWORDS));
                Map<String, Object> entry = new HashMap<>();
                entry.put("playerId", room.host);
                entry.put("message", secret);
                unicasts.add(entry);
                log.info("[TURTLE] 房主离线，汤底转交新主持: {}", room.host);
            }

            // 活人不足 2 → 房主胜
            if (room.getActivePlayers().size() <= 1) {
                endGame(room, room.host);
                broadcasts.add(gameEndedMessage(room, room.host));
            }
            return RoomGameLogic.broadcast(broadcasts.isEmpty() ? null : broadcasts,
                    unicasts.isEmpty() ? null : unicasts);
        }
    }

    @Override
    public void reset(GameRoomManager.RoomData room) {
        synchronized (room) {
            room.extra.clear();
        }
    }

    // ========== 辅助 ==========

    private void endGame(GameRoomManager.RoomData room, String winner) {
        room.gameOver = true;
        room.status = "ended";
        log.info("[TURTLE] 海龟汤结束: {} 胜者={}", room.id, winner);
    }

    private Map<String, Object> gameEndedMessage(GameRoomManager.RoomData room, String winner) {
        Map<String, String> names = roomManager.buildDisplayNames(room);
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "gameEnded");
        msg.put("roomId", room.id);
        msg.put("gameType", "turtle");
        msg.put("result", winner == null ? "draw" : winner);
        msg.put("winnerName", winner == null ? "" : names.getOrDefault(winner, winner));
        msg.put("displayNames", names);
        return msg;
    }
}
