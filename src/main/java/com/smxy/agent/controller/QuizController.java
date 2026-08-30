package com.smxy.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.smxy.agent.game.GameLLMHelper;
import com.smxy.agent.game.GameSessionStore;
import com.smxy.agent.game.QuizBank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 趣味问答 —— AI 出题 + 判题，一局 5 题。
 * LLM 不可用时降级到本地题库。
 */
@Slf4j
@RestController
@RequestMapping("/api/game/quiz")
@CrossOrigin(origins = "*")
public class QuizController {

    private final GameLLMHelper llmHelper;
    private final GameSessionStore<QuizGame> sessions = new GameSessionStore<>(60 * 60 * 1000L);

    public QuizController(GameLLMHelper llmHelper) {
        this.llmHelper = llmHelper;
    }

    private static final int QUESTIONS_PER_GAME = 5;

    // ========== 开始对局：AI 出题 ==========
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody(required = false) QuizStartRequest request) {
        try {
            String category = request == null ? null : request.getCategory();
            List<QuizBank.Question> questions = null;
            String source = "ai";

            // 优先 LLM 出题
            String catText = (category == null || category.isEmpty()) ? "不限类别" : category + "类别";
            JsonNode node = llmHelper.askJsonArray(
                    "你是一个严谨的趣味问答出题人。请出" + QUESTIONS_PER_GAME + "道中文趣味知识题（" + catText + "），" +
                            "难度递进：前1题简单、中间2题中等、后2题较难。\n" +
                            "硬性要求：\n" +
                            "1. 一轮内题目不得重复或近似重复，考点各不相同；\n" +
                            "2. 每题4个选项互斥且只有唯一正确答案，选项之间不得互相包含或含义重叠；\n" +
                            "3. 题干必须客观中立，不得暗示答案（不得出现正确选项中的关键词）；\n" +
                            "4. answerIndex 必须指向正确选项，题干、答案索引、解析三者严格自洽；\n" +
                            "5. 所有内容必须事实准确、可查证，宁可不答也不编造；\n" +
                            "6. 出不足" + QUESTIONS_PER_GAME + "道也可以接受，但每题都必须合格。\n" +
                            "只输出 JSON 数组，元素格式：" +
                            "{\"question\":\"题目\",\"options\":[\"A选项\",\"B选项\",\"C选项\",\"D选项\"],\"answerIndex\":0,\"explanation\":\"一句话解析（说明为什么对）\"}",
                    "请出题，类别：" + (category == null ? "不限" : category));
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
                    questions.add(QuizBank.of(
                            category == null ? "趣味" : category,
                            q.path("question").asText(),
                            options.toArray(new String[0]),
                            answerIndex,
                            q.path("explanation").asText("")));
                }
                if (questions.size() < 3) questions = null; // 质量不足则降级
            }

            // 降级本地题库
            if (questions == null) {
                List<QuizBank.Question> pool = QuizBank.pickRandom(category, QUESTIONS_PER_GAME);
                if (pool.isEmpty()) {
                    return Result.badRequest("没有该类别的问题");
                }
                questions = pool;
                source = "local";
            }

            String gameId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            sessions.put(gameId, new QuizGame(questions, category == null ? "趣味" : category, source));
            log.info("? 问答开局: {} 来源={} 类别={} 题数={}", gameId, source, category, questions.size());

            Map<String, Object> data = new HashMap<>();
            data.put("gameId", gameId);
            data.put("category", category == null ? "趣味" : category);
            data.put("source", source);
            data.put("total", questions.size());
            data.put("score", 0);
            data.put("questionNo", 1);
            data.put("question", questionMap(questions.get(0)));
            return Result.success(data);
        } catch (Exception e) {
            log.error("问答开局失败: {}", e.getMessage());
            return Result.error("开局失败");
        }
    }

    // ========== 答题 ==========
    @PostMapping("/answer")
    public Result<Map<String, Object>> answer(@RequestBody QuizAnswerRequest request) {
        try {
            if (request == null || request.getGameId() == null || request.getIndex() == null) {
                return Result.badRequest("参数不完整");
            }
            QuizGame game = sessions.get(request.getGameId());
            if (game == null) {
                return Result.notFound("对局不存在或已过期，请重新开始");
            }
            if (game.finished) {
                return Result.badRequest("本局已结束，请开始新对局");
            }

            QuizBank.Question current = game.questions.get(game.current);
            int index = request.getIndex();
            if (index < 0 || index >= current.options.length) {
                return Result.badRequest("选项序号不合法");
            }

            boolean correct = index == current.answerIndex;
            if (correct) game.score++;
            game.current++;
            game.finished = game.current >= game.questions.size();

            Map<String, Object> data = new HashMap<>();
            data.put("correct", correct);
            data.put("correctIndex", current.answerIndex);
            data.put("explanation", current.explanation);
            data.put("score", game.score);
            data.put("total", game.questions.size());
            data.put("finished", game.finished);
            if (!game.finished) {
                data.put("questionNo", game.current + 1);
                data.put("question", questionMap(game.questions.get(game.current)));
            }

            log.info("? 答题: 对局{} 第{}题 {} 得分={}",
                    request.getGameId(), game.current, correct ? "正确" : "错误", game.score);
            return Result.success(data);
        } catch (Exception e) {
            log.error("答题失败: {}", e.getMessage());
            return Result.error("答题失败");
        }
    }

    // ========== 辅助 ==========
    private Map<String, Object> questionMap(QuizBank.Question q) {
        Map<String, Object> map = new HashMap<>();
        map.put("question", q.question);
        map.put("options", q.options);
        return map;
    }

    // ========== 对局状态 ==========
    private static class QuizGame {
        final List<QuizBank.Question> questions;
        final String category;
        final String source;
        int current = 0;
        int score = 0;
        boolean finished = false;

        QuizGame(List<QuizBank.Question> questions, String category, String source) {
            this.questions = questions;
            this.category = category;
            this.source = source;
        }
    }

    // ========== DTO ==========
    public static class QuizStartRequest {
        private String category;

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    public static class QuizAnswerRequest {
        private String gameId;
        private Integer index;

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public Integer getIndex() { return index; }
        public void setIndex(Integer index) { this.index = index; }
    }
}
