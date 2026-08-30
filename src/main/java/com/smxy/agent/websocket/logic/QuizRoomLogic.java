package com.smxy.agent.websocket.logic;

import com.fasterxml.jackson.databind.JsonNode;
import com.smxy.agent.game.GameLLMHelper;
import com.smxy.agent.game.QuizBank;
import com.smxy.agent.websocket.GameRoomManager;
import com.smxy.agent.websocket.RoomGameLogic;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 趣味问答联机：同题竞技，每人每题答一次，全员答完自动揭晓（不下发答案），
 * 房主「下一题」导航，全部答完按积分排名；并列 → draw。
 */
@Slf4j
@Component
public class QuizRoomLogic implements RoomGameLogic {

    private static final int QUESTIONS_PER_GAME = 5;

    private static final String K_QUESTIONS = "quiz.questions";
    private static final String K_SOURCE = "quiz.source";
    private static final String K_CATEGORY = "quiz.category";
    private static final String K_CURRENT = "quiz.current";
    private static final String K_ANSWERS = "quiz.answers";
    private static final String K_SCORES = "quiz.scores";
    private static final String K_REVEALED = "quiz.revealed";

    private final GameRoomManager roomManager;
    private final GameLLMHelper llmHelper;

    public QuizRoomLogic(GameRoomManager roomManager, GameLLMHelper llmHelper) {
        this.roomManager = roomManager;
        this.llmHelper = llmHelper;
    }

    @Override
    public String gameType() {
        return "quiz";
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

            // 题目难度：easy 全部大众常识，hard 全部冷知识难题
            String difficulty = room.difficulty != null ? room.difficulty : "normal";
            String diffLine;
            if ("easy".equals(difficulty)) {
                diffLine = "整体难度低：全部为大众常识题（小学/日常级别），并保持难度递进。";
            } else if ("hard".equals(difficulty)) {
                diffLine = "整体难度高：全部为冷知识/专业领域难题，并保持难度递进。";
            } else {
                diffLine = "难度递进：前1题简单、中间2题中等、后2题较难。";
            }

            List<QuizBank.Question> questions = null;
            String source = "ai";
            JsonNode node = llmHelper.askJsonArray(
                    "你是一个严谨的趣味问答出题人。请出" + QUESTIONS_PER_GAME + "道中文趣味知识题，" +
                            diffLine + "\n" +
                            "硬性要求：\n" +
                            "1. 一轮内题目不得重复或近似重复，考点各不相同；\n" +
                            "2. 每题4个选项互斥且只有唯一正确答案，选项之间不得互相包含或含义重叠；\n" +
                            "3. 题干必须客观中立，不得暗示答案（不得出现正确选项中的关键词）；\n" +
                            "4. answerIndex 必须指向正确选项，题干、答案索引、解析三者严格自洽；\n" +
                            "5. 所有内容必须事实准确、可查证，宁可不答也不编造；\n" +
                            "6. 出不足" + QUESTIONS_PER_GAME + "道也可以接受，但每题都必须合格。\n" +
                            "只输出 JSON 数组，元素格式：" +
                            "{\"question\":\"题目\",\"options\":[\"A选项\",\"B选项\",\"C选项\",\"D选项\"],\"answerIndex\":0,\"explanation\":\"一句话解析（说明为什么对）\"}",
                    "请出题");
            if (node != null && node.isArray() && node.size() > 0) {
                questions = new ArrayList<>();
                for (JsonNode q : node) {
                    List<String> options = new ArrayList<>();
                    if (q.path("options").isArray()) {
                        for (JsonNode opt : q.path("options")) {
                            options.add(opt.asText());
                        }
                    }
                    if (options.size() < 2) continue;
                    int answerIndex = q.path("answerIndex").asInt(0);
                    if (answerIndex < 0 || answerIndex >= options.size()) continue;
                    questions.add(QuizBank.of("趣味",
                            q.path("question").asText(),
                            options.toArray(new String[0]),
                            answerIndex,
                            q.path("explanation").asText("")));
                }
                if (questions.size() < 3) questions = null; // 质量不足则降级
            }
            if (questions == null) {
                questions = QuizBank.pickRandom(null, QUESTIONS_PER_GAME);
                source = "local";
            }

            Map<String, Integer> scores = new HashMap<>();
            for (String p : room.players) scores.put(p, 0);

            room.extra.put(K_QUESTIONS, questions);
            room.extra.put(K_SOURCE, source);
            room.extra.put(K_CATEGORY, "趣味");
            room.extra.put(K_CURRENT, 0);
            room.extra.put(K_ANSWERS, new HashMap<String, Integer>());
            room.extra.put(K_SCORES, scores);
            room.extra.put(K_REVEALED, false);

            log.info("? 问答开局: {} 难度={} 来源={} 题数={}", room.id, difficulty, source, questions.size());

            List<Map<String, Object>> broadcasts = new ArrayList<>();
            broadcasts.add(Map.of(
                    "type", "quizStarted",
                    "roomId", room.id,
                    "difficulty", difficulty,
                    "total", questions.size(),
                    "source", source,
                    "category", "趣味",
                    "players", new ArrayList<>(room.players),
                    "displayNames", roomManager.buildDisplayNames(room)));
            broadcasts.add(questionMessage(room, 0, questions.get(0)));
            return RoomGameLogic.broadcast(broadcasts, null);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> handleMessage(String type, GameRoomManager.RoomData room,
                                             String playerId, Map<String, Object> data) {
        switch (type) {
            case "quizAnswer":
                return handleAnswer(room, playerId, data);
            case "quizNext":
                return handleNext(room, playerId);
            default:
                return RoomGameLogic.error("未知消息类型: " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleAnswer(GameRoomManager.RoomData room, String playerId,
                                             Map<String, Object> data) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (room.isEliminated(playerId)) return RoomGameLogic.error("你已被淘汰");
            if ((boolean) room.extra.get(K_REVEALED)) return RoomGameLogic.error("本题已揭晓，等待下一题");

            List<QuizBank.Question> questions = (List<QuizBank.Question>) room.extra.get(K_QUESTIONS);
            int current = (int) room.extra.get(K_CURRENT);
            QuizBank.Question q = questions.get(current);
            Map<String, Integer> answers = (Map<String, Integer>) room.extra.get(K_ANSWERS);
            if (answers.containsKey(playerId)) return RoomGameLogic.error("你已经答过本题");

            Object raw = data == null ? null : data.get("index");
            int index = raw instanceof Number ? ((Number) raw).intValue() : -1;
            if (index < 0 || index >= q.options.length) return RoomGameLogic.error("选项序号不合法");

            answers.put(playerId, index);
            int totalPlayers = room.getActivePlayers().size();
            int answeredCount = 0;
            for (String p : room.getActivePlayers()) {
                if (answers.containsKey(p)) answeredCount++;
            }

            // 全员答完 → 自动揭晓
            if (answeredCount >= totalPlayers) {
                return reveal(room, answers, q, current, totalPlayers);
            }
            return RoomGameLogic.broadcast(Map.of(
                    "type", "quizAnswered",
                    "roomId", room.id,
                    "answeredCount", answeredCount,
                    "totalPlayers", totalPlayers));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> reveal(GameRoomManager.RoomData room, Map<String, Integer> answers,
                                       QuizBank.Question q, int current, int totalPlayers) {
        room.extra.put(K_REVEALED, true);
        Map<String, Integer> scores = (Map<String, Integer>) room.extra.get(K_SCORES);

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, Integer> e : answers.entrySet()) {
            String pid = e.getKey();
            boolean correct = e.getValue() == q.answerIndex;
            if (correct) scores.put(pid, scores.getOrDefault(pid, 0) + 1);
            results.add(Map.of(
                    "playerId", pid,
                    "index", e.getValue(),
                    "correct", correct));
        }

        List<Map<String, Object>> broadcasts = new ArrayList<>();
        broadcasts.add(Map.of(
                "type", "quizAnswered",
                "roomId", room.id,
                "answeredCount", totalPlayers,
                "totalPlayers", totalPlayers));
        broadcasts.add(Map.of(
                "type", "quizReveal",
                "roomId", room.id,
                "questionNo", current + 1,
                "correctIndex", q.answerIndex,
                "explanation", q.explanation,
                "results", results,
                "scores", new HashMap<>(scores)));
        log.info("? 第{}题揭晓", current + 1);
        return RoomGameLogic.broadcast(broadcasts, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleNext(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing") || room.gameOver) return RoomGameLogic.error("游戏已结束");
            if (!room.host.equals(playerId)) return RoomGameLogic.error("只有房主可以进入下一题");
            if (!(boolean) room.extra.get(K_REVEALED)) return RoomGameLogic.error("还有玩家未作答");

            List<QuizBank.Question> questions = (List<QuizBank.Question>) room.extra.get(K_QUESTIONS);
            int next = (int) room.extra.get(K_CURRENT) + 1;

            if (next >= questions.size()) {
                return endGame(room);
            }

            room.extra.put(K_CURRENT, next);
            room.extra.put(K_ANSWERS, new HashMap<String, Integer>());
            room.extra.put(K_REVEALED, false);
            return RoomGameLogic.broadcast(questionMessage(room, next, questions.get(next)));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> endGame(GameRoomManager.RoomData room) {
        room.gameOver = true;
        room.status = "ended";

        Map<String, Integer> scores = (Map<String, Integer>) room.extra.get(K_SCORES);
        Map<String, String> names = roomManager.buildDisplayNames(room);
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (String p : room.players) {
            ranking.add(Map.of(
                    "playerId", p,
                    "name", names.getOrDefault(p, p),
                    "score", scores.getOrDefault(p, 0)));
        }
        ranking.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));

        String result;
        String winnerName;
        if (ranking.size() >= 2 &&
                ranking.get(0).get("score").equals(ranking.get(1).get("score"))) {
            result = "draw";
            winnerName = "";
        } else {
            result = (String) ranking.get(0).get("playerId");
            winnerName = (String) ranking.get(0).get("name");
        }

        log.info("? 问答结束: {} 排名={}", room.id, ranking);
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "gameEnded");
        msg.put("roomId", room.id);
        msg.put("gameType", "quiz");
        msg.put("result", result);
        msg.put("winnerName", winnerName);
        msg.put("displayNames", names);
        msg.put("ranking", ranking);
        msg.put("scores", new HashMap<>(scores));
        return RoomGameLogic.broadcast(msg);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> snapshot(GameRoomManager.RoomData room, String playerId) {
        synchronized (room) {
            if (!room.status.equals("playing") && !room.gameOver) return null;

            List<QuizBank.Question> questions = (List<QuizBank.Question>) room.extra.get(K_QUESTIONS);
            if (questions == null) return null;

            Map<String, Integer> answers = (Map<String, Integer>) room.extra.get(K_ANSWERS);
            Map<String, Integer> scores = (Map<String, Integer>) room.extra.get(K_SCORES);
            int current = (int) room.extra.get(K_CURRENT);
            boolean revealed = (boolean) room.extra.get(K_REVEALED);

            Map<String, Object> snap = new HashMap<>();
            snap.put("total", questions.size());
            snap.put("source", room.extra.get(K_SOURCE));
            snap.put("category", room.extra.get(K_CATEGORY));
            snap.put("questionNo", current + 1);
            snap.put("scores", new HashMap<>(scores));
            snap.put("hasAnswered", answers.containsKey(playerId));
            snap.put("answeredCount", answers.size());
            snap.put("revealed", revealed);

            if (!room.gameOver) {
                QuizBank.Question q = questions.get(current);
                // 揭晓前绝不带 correctIndex/explanation
                if (!revealed) {
                    snap.put("question", q.question);
                    snap.put("options", q.options);
                } else {
                    snap.put("question", q.question);
                    snap.put("options", q.options);
                    snap.put("correctIndex", q.answerIndex);
                    snap.put("explanation", q.explanation);
                }
            } else {
                // 终局：带最终排名（与 gameEnded 广播一致）
                Map<String, String> names = roomManager.buildDisplayNames(room);
                List<Map<String, Object>> ranking = new ArrayList<>();
                for (String p : room.players) {
                    ranking.add(Map.of(
                            "playerId", p,
                            "name", names.getOrDefault(p, p),
                            "score", scores.getOrDefault(p, 0)));
                }
                ranking.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
                snap.put("ranking", ranking);
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
            List<String> survivors = room.getActivePlayers();

            // 活人不足 2 → 终局
            if (survivors.size() <= 1) {
                String winner = survivors.isEmpty() ? null : survivors.get(0);
                room.gameOver = true;
                room.status = "ended";
                log.info("? 问答结束（玩家不足）: {}", room.id);
                return RoomGameLogic.broadcast(gameEndedMessage(room, winner));
            }

            // 未揭晓时，离场玩家不阻塞揭晓
            if (!(boolean) room.extra.get(K_REVEALED)) {
                List<QuizBank.Question> questions = (List<QuizBank.Question>) room.extra.get(K_QUESTIONS);
                int current = (int) room.extra.get(K_CURRENT);
                QuizBank.Question q = questions.get(current);
                Map<String, Integer> answers = (Map<String, Integer>) room.extra.get(K_ANSWERS);

                int answeredCount = 0;
                for (String p : survivors) {
                    if (answers.containsKey(p)) answeredCount++;
                }
                if (answeredCount >= survivors.size()) {
                    return reveal(room, answers, q, current, survivors.size());
                }
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

    private Map<String, Object> questionMessage(GameRoomManager.RoomData room, int no, QuizBank.Question q) {
        // 绝不包含 answerIndex/explanation
        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "quizQuestion");
        msg.put("roomId", room.id);
        msg.put("questionNo", no + 1);
        msg.put("question", q.question);
        msg.put("options", q.options);
        return msg;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> gameEndedMessage(GameRoomManager.RoomData room, String winner) {
        Map<String, Integer> scores = (Map<String, Integer>) room.extra.get(K_SCORES);
        Map<String, String> names = roomManager.buildDisplayNames(room);
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (String p : room.players) {
            ranking.add(Map.of(
                    "playerId", p,
                    "name", names.getOrDefault(p, p),
                    "score", scores == null ? 0 : scores.getOrDefault(p, 0)));
        }
        ranking.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));

        Map<String, Object> msg = new HashMap<>();
        msg.put("type", "gameEnded");
        msg.put("roomId", room.id);
        msg.put("gameType", "quiz");
        msg.put("result", winner == null ? "draw" : winner);
        msg.put("winnerName", winner == null ? "" : names.getOrDefault(winner, winner));
        msg.put("displayNames", names);
        msg.put("ranking", ranking);
        return msg;
    }
}
