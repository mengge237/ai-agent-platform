package com.smxy.agent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.smxy.agent.game.GameLLMHelper;
import com.smxy.agent.game.GameSessionStore;
import com.smxy.agent.game.IdiomBank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 成语接龙 —— 玩家与 AI 轮流接龙。
 * AI 负责校验成语真实性并接龙；LLM 不可用时降级到本地成语库。
 */
@Slf4j
@RestController
@RequestMapping("/api/game/idiom")
@CrossOrigin(origins = "*")
public class IdiomController {

    private final GameLLMHelper llmHelper;
    private final GameSessionStore<IdiomGame> sessions = new GameSessionStore<>(60 * 60 * 1000L);

    public IdiomController(GameLLMHelper llmHelper) {
        this.llmHelper = llmHelper;
    }

    // ========== 开始对局 ==========
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody(required = false) IdiomStartRequest request) {
        try {
            String gameId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String first = request == null || request.getFirst() == null ? "" : request.getFirst().trim();
            String meaning = "";
            String source = "ai";

            if (first.isEmpty()) {
                // AI 随机出一个开头成语
                String aiFirst = llmHelper.ask(
                        "你是成语接龙玩家。请随机给出一个常见的中文四字成语，只输出成语本身，不要其他内容。",
                        "请出题");
                if (aiFirst != null && aiFirst.length() == 4) {
                    first = aiFirst;
                } else {
                    first = IdiomBank.random();
                    source = "local";
                }
            } else {
                if (first.length() != 4) {
                    return Result.badRequest("请输入四字成语");
                }
                // 校验自定义开头
                JsonNode node = llmHelper.askJson(
                        "你是成语专家。判断下面的词是否为真实成语。只输出 JSON：{\"isIdiom\":true或false,\"meaning\":\"含义（10字内）\"}\n" +
                                "判定从严：只认常见、有出处的四字成语；生僻的、网络造词、胡乱拼凑的四字词一律判 false。",
                        first);
                if (node != null && !node.path("isIdiom").asBoolean(false)) {
                    return Result.badRequest("「" + first + "」不是常见成语，请换一个");
                }
                if (node != null && node.has("meaning")) {
                    meaning = node.path("meaning").asText("");
                }
                if (node == null && !IdiomBank.contains(first)) {
                    // AI 离线且不在本地库：按四字放行
                    source = "unverified";
                }
            }

            IdiomGame game = new IdiomGame();
            game.used.add(first);
            game.lastChar = IdiomBank.lastCharOf(first);

            sessions.put(gameId, game);
            log.info("[IDIOM] 成语接龙开局: {} 首词={} 来源={}", gameId, first, source);

            Map<String, Object> data = new HashMap<>();
            data.put("gameId", gameId);
            data.put("firstIdiom", first);
            data.put("lastChar", game.lastChar);
            data.put("meaning", meaning);
            data.put("source", source);
            return Result.success(data);
        } catch (Exception e) {
            log.error("成语接龙开局失败: {}", e.getMessage());
            return Result.error("开局失败");
        }
    }

    // ========== 玩家出成语，AI 校验并接龙 ==========
    @PostMapping("/play")
    public Result<Map<String, Object>> play(@RequestBody IdiomPlayRequest request) {
        try {
            if (request == null || request.getGameId() == null || request.getIdiom() == null) {
                return Result.badRequest("参数不完整");
            }
            IdiomGame game = sessions.get(request.getGameId());
            if (game == null) {
                return Result.notFound("对局不存在或已过期，请重新开始");
            }

            String idiom = request.getIdiom().trim();
            Map<String, Object> data = new HashMap<>();
            data.put("playerIdiom", idiom);

            // ===== 基本规则校验 =====
            if (idiom.length() != 4) {
                data.put("valid", false);
                data.put("reason", "必须是四字成语");
                return Result.success(data);
            }
            if (!idiom.startsWith(game.lastChar)) {
                data.put("valid", false);
                data.put("reason", "必须以「" + game.lastChar + "」开头");
                return Result.success(data);
            }
            if (game.used.contains(idiom)) {
                data.put("valid", false);
                data.put("reason", "成语「" + idiom + "」已经用过，不能重复");
                return Result.success(data);
            }

            // ===== AI 校验 + 接龙 =====
            boolean valid = false;
            String reason = "";
            String meaning = "";
            String aiIdiom = null;

            JsonNode node = llmHelper.askJson(
                    "你是成语接龙的裁判兼对手。上一个成语的末字是「" + game.lastChar + "」。\n" +
                            "玩家出的成语：「" + idiom + "」\n" +
                            "规则：必须是真实常见的四字成语、必须以「" + game.lastChar + "」开头、不能重复。\n" +
                            "裁判从严：生僻的、网络造词、胡乱拼凑的一律判 invalid 并说明原因。\n" +
                            "接龙只接常见成语，接不上就诚实输出 SURRENDER，不要生造词语。\n" +
                            "只输出 JSON：{\"valid\":true或false,\"reason\":\"若无效给出简短原因\",\"meaning\":\"成语含义（10字内）\",\"reply\":\"若玩家有效，接一个以玩家成语末字「" + IdiomBank.lastCharOf(idiom) + "」开头的常见成语，只输出成语本身；接不上输出 SURRENDER\"}",
                    idiom);

            if (node != null) {
                valid = node.path("valid").asBoolean(false);
                reason = node.path("reason").asText("");
                meaning = node.path("meaning").asText("");
                String reply = node.path("reply").asText("").trim().replaceAll("[\\s\"'，。！？]", "");
                if (valid && !"SURRENDER".equals(reply) && reply.length() == 4) {
                    aiIdiom = reply;
                }
            } else {
                // 降级：本地库校验（不在库内则宽松放行，保持可玩）
                valid = true;
                if (!IdiomBank.contains(idiom)) {
                    reason = "AI 离线，未能核验成语真实性";
                }
            }

            if (!valid) {
                data.put("valid", false);
                data.put("reason", reason.isEmpty() ? "这不是常见成语" : reason);
                return Result.success(data);
            }

            // ===== 玩家成语有效 =====
            game.used.add(idiom);
            game.lastChar = IdiomBank.lastCharOf(idiom);
            data.put("valid", true);
            data.put("meaning", meaning);

            // AI 接龙（降级：本地库查找）
            if (aiIdiom == null) {
                aiIdiom = IdiomBank.findNext(game.lastChar, game.used);
            }

            if (aiIdiom != null) {
                game.used.add(aiIdiom);
                game.lastChar = IdiomBank.lastCharOf(aiIdiom);
                data.put("aiIdiom", aiIdiom);
                data.put("lastChar", game.lastChar);
                data.put("gameOver", false);
            } else {
                data.put("aiSurrendered", true);
                data.put("gameOver", true);
                data.put("reason", "AI 接不上「" + game.lastChar + "」开头的成语，认输！");
                sessions.remove(request.getGameId());
            }

            log.info("[IDIOM] 接龙: 玩家={} AI={}", idiom, aiIdiom == null ? "(认输)" : aiIdiom);
            return Result.success(data);
        } catch (Exception e) {
            log.error("成语接龙失败: {}", e.getMessage());
            return Result.error("接龙失败");
        }
    }

    // ========== 对局状态 ==========
    private static class IdiomGame {
        final Set<String> used = new HashSet<>();
        String lastChar;
    }

    // ========== DTO ==========
    public static class IdiomStartRequest {
        private String first;

        public String getFirst() { return first; }
        public void setFirst(String first) { this.first = first; }
    }

    public static class IdiomPlayRequest {
        private String gameId;
        private String idiom;

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public String getIdiom() { return idiom; }
        public void setIdiom(String idiom) { this.idiom = idiom; }
    }
}
