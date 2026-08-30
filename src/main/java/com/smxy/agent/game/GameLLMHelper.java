package com.smxy.agent.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smxy.agent.llm.LLMProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 新游戏（海龟汤/问答/成语接龙）的 LLM 调用封装：
 * 真实 LLM 优先，失败返回 null 由各控制器降级到本地题库。
 */
@Slf4j
@Component
public class GameLLMHelper {

    private final LLMProvider llmProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameLLMHelper(LLMProvider llmProvider) {
        this.llmProvider = llmProvider;
    }

    /** 调用 LLM；失败、超时或返回错误文案时返回 null */
    public String ask(String systemPrompt, String userPrompt) {
        try {
            String result = llmProvider.chat(systemPrompt, userPrompt)
                    .get(30, TimeUnit.SECONDS);
            if (result == null || result.isBlank()
                    || result.contains("调用AI服务失败")
                    || result.contains("AI调用失败")) {
                return null;
            }
            return result.trim();
        } catch (Exception e) {
            log.warn("LLM 调用失败，降级本地: {}", e.getMessage());
            return null;
        }
    }

    /** 调用 LLM 并解析 JSON 对象输出（容忍 ```json 代码块围栏） */
    public JsonNode askJson(String systemPrompt, String userPrompt) {
        String raw = ask(systemPrompt, userPrompt);
        if (raw == null) return null;
        return parseJson(raw, '{', '}');
    }

    /** 调用 LLM 并解析 JSON 数组输出 */
    public JsonNode askJsonArray(String systemPrompt, String userPrompt) {
        String raw = ask(systemPrompt, userPrompt);
        if (raw == null) return null;
        return parseJson(raw, '[', ']');
    }

    private JsonNode parseJson(String raw, char open, char close) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            try {
                int s = raw.indexOf(open);
                int en = raw.lastIndexOf(close);
                if (s >= 0 && en > s) {
                    return objectMapper.readTree(raw.substring(s, en + 1));
                }
            } catch (Exception ignored) {
                // 交给调用方降级
            }
            return null;
        }
    }
}
