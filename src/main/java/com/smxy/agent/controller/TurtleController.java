package com.smxy.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.smxy.agent.game.GameLLMHelper;
import com.smxy.agent.game.GameSessionStore;
import com.smxy.agent.game.TurtleBank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 海龟汤（情境推理）—— AI 出题，玩家用「是/否」提问推理汤底。
 * LLM 不可用时降级到本地经典题库。
 */
@Slf4j
@RestController
@RequestMapping("/api/game/turtle")
@CrossOrigin(origins = "*")
public class TurtleController {

    private final GameLLMHelper llmHelper;
    private final GameSessionStore<TurtleGame> sessions = new GameSessionStore<>(60 * 60 * 1000L);

    public TurtleController(GameLLMHelper llmHelper) {
        this.llmHelper = llmHelper;
    }

    // ========== 开始对局：AI 出题 ==========
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody(required = false) TurtleStartRequest request) {
        try {
            String gameId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

            String story = null, answer = null, source = "ai";
            String[] keywords = new String[0];

            // 优先 LLM 出题
            JsonNode node = llmHelper.askJson(
                    "你是一个'海龟汤'（情境推理游戏）出题人。请创作一个简短有强烈悬疑感的中文海龟汤谜题。\n" +
                            "要求：\n" +
                            "1. 汤面简短（60字以内），表面平静但暗藏矛盾，能激发玩家提问欲望；\n" +
                            "2. 汤底合理完整、内部自洽、有出人意料的真相，绝不敷衍；\n" +
                            "3. keywords 给出3-5个汤底中最关键的决定性线索词（具体名词短语），这些词将用于判定玩家提问/猜测是否触及真相，必须是汤底里真实出现或直接指向真相的词，不要用'为什么''原因'等无信息量的泛词。\n" +
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

            // 降级本地题库
            if (story == null || story.isEmpty() || answer == null || answer.isEmpty()) {
                TurtleBank.TurtlePuzzle local = TurtleBank.pickRandom();
                story = local.story;
                answer = local.answer;
                keywords = local.keywords;
                source = "local";
            }

            sessions.put(gameId, new TurtleGame(story, answer, keywords, source));
            log.info("[TURTLE] 海龟汤开局: {} 来源={} 汤面={}", gameId, source, story.substring(0, Math.min(20, story.length())));

            Map<String, Object> data = new HashMap<>();
            data.put("gameId", gameId);
            data.put("story", story);
            data.put("source", source);
            data.put("questionCount", 0);
            return Result.success(data);
        } catch (Exception e) {
            log.error("海龟汤开局失败: {}", e.getMessage());
            return Result.error("开局失败");
        }
    }

    // ========== 提问（是/否） ==========
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody TurtleAskRequest request) {
        try {
            if (request == null || request.getGameId() == null || request.getQuestion() == null) {
                return Result.badRequest("参数不完整");
            }
            TurtleGame game = sessions.get(request.getGameId());
            if (game == null) {
                return Result.notFound("对局不存在或已过期，请重新开始");
            }

            String question = request.getQuestion().trim();
            if (question.isEmpty()) {
                return Result.badRequest("问题不能为空");
            }

            game.questionCount++;

            // 优先 LLM 主持
            String verdict = null, hint = null;
            JsonNode node = llmHelper.askJson(
                    "你是'海龟汤'游戏主持人。汤底是：" + game.answer + "\n" +
                            "玩家提问：" + question + "\n" +
                            "只输出 JSON：{\"answer\":\"是或否或无法确定\",\"hint\":\"不超过20字的中文提示，不要直接透露汤底\"}\n" +
                            "判定规则：与汤底一致或可推导出→是；与汤底矛盾→否；汤底未提及且无法推断→无法确定（不要滥用，能判断就判断）。" +
                            "hint 可从时间/地点/人物/动机任一角度指路，但绝不能泄露核心真相。",
                    question);
            if (node != null) {
                verdict = node.path("answer").asText().trim();
                hint = node.path("hint").asText().trim();
            }

            // 降级本地启发式
            if (verdict == null || verdict.isEmpty()) {
                verdict = "无法确定";
                hint = "换个角度：时间？地点？人物？动机？";
                for (String kw : game.keywords) {
                    if (!kw.isEmpty() && question.contains(kw)) {
                        verdict = "是";
                        hint = "你问到了关键线索！";
                        break;
                    }
                }
            }

            Map<String, Object> data = new HashMap<>();
            data.put("answer", verdict);
            data.put("hint", hint == null || hint.isEmpty() ? "" : hint);
            data.put("questionCount", game.questionCount);
            log.info("[TURTLE] 提问: {} -> {}", question.substring(0, Math.min(30, question.length())), verdict);
            return Result.success(data);
        } catch (Exception e) {
            log.error("海龟汤提问失败: {}", e.getMessage());
            return Result.error("提问失败");
        }
    }

    // ========== 猜汤底 ==========
    @PostMapping("/guess")
    public Result<Map<String, Object>> guess(@RequestBody TurtleGuessRequest request) {
        try {
            if (request == null || request.getGameId() == null || request.getGuess() == null) {
                return Result.badRequest("参数不完整");
            }
            TurtleGame game = sessions.get(request.getGameId());
            if (game == null) {
                return Result.notFound("对局不存在或已过期，请重新开始");
            }

            String guess = request.getGuess().trim();
            boolean correct = false;
            String comment = "";

            // 优先 LLM 裁判
            JsonNode node = llmHelper.askJson(
                    "你是'海龟汤'游戏裁判。汤底是：" + game.answer + "\n" +
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
                for (String kw : game.keywords) {
                    if (!kw.isEmpty() && guess.contains(kw)) hits++;
                }
                correct = hits >= Math.max(2, game.keywords.length / 2);
                comment = correct ? "核心真相吻合！" : "还差一点，关键线索没对上";
            }

            Map<String, Object> data = new HashMap<>();
            data.put("correct", correct);
            data.put("comment", comment);
            if (correct) {
                data.put("answer", game.answer);
                sessions.remove(request.getGameId());
            }
            log.info("[TURTLE] 猜汤底: {} -> {}", guess.substring(0, Math.min(30, guess.length())), correct);
            return Result.success(data);
        } catch (Exception e) {
            log.error("海龟汤判定失败: {}", e.getMessage());
            return Result.error("判定失败");
        }
    }

    // ========== 公布汤底 ==========
    @PostMapping("/reveal")
    public Result<Map<String, Object>> reveal(@RequestBody TurtleRevealRequest request) {
        try {
            if (request == null || request.getGameId() == null) {
                return Result.badRequest("参数不完整");
            }
            TurtleGame game = sessions.remove(request.getGameId());
            if (game == null) {
                return Result.notFound("对局不存在或已过期");
            }

            Map<String, Object> data = new HashMap<>();
            data.put("answer", game.answer);
            data.put("story", game.story);
            return Result.success(data);
        } catch (Exception e) {
            log.error("公布汤底失败: {}", e.getMessage());
            return Result.error("公布失败");
        }
    }

    // ========== 对局状态 ==========
    private static class TurtleGame {
        final String story;
        final String answer;
        final String[] keywords;
        final String source;
        int questionCount;

        TurtleGame(String story, String answer, String[] keywords, String source) {
            this.story = story;
            this.answer = answer;
            this.keywords = keywords;
            this.source = source;
        }
    }

    // ========== DTO ==========
    public static class TurtleStartRequest {
        private String category;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    public static class TurtleAskRequest {
        private String gameId;
        private String question;

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
    }

    public static class TurtleGuessRequest {
        private String gameId;
        private String guess;

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public String getGuess() { return guess; }
        public void setGuess(String guess) { this.guess = guess; }
    }

    public static class TurtleRevealRequest {
        private String gameId;

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
    }
}
