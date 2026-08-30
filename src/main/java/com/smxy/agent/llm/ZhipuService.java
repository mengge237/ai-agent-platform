package com.smxy.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@Primary
public class ZhipuService implements LLMProvider {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.providers.zhipu.api-key:}")
    private String apiKey;

    @Value("${ai.providers.zhipu.model:glm-4.7-flash}")
    private String model;

    @Value("${ai.providers.zhipu.base-url:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${ai.providers.zhipu.timeout:30000}")
    private long timeout;

    @Override
    public CompletableFuture<String> chat(String message) {
        return chat("你是一个有用的AI助手", message);
    }

    @Override
    public CompletableFuture<String> chat(String systemPrompt, String message) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        messages.add(ChatMessage.user(message));
        return chat(messages);
    }

    @Override
    public CompletableFuture<String> chat(List<ChatMessage> messages) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 构建请求体
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", model);
                requestBody.put("messages", messages.stream()
                        .map(msg -> Map.of("role", msg.role(), "content", msg.content()))
                        .toList());
                requestBody.put("temperature", 0.8);
                requestBody.put("stream", false);

                String jsonBody = objectMapper.writeValueAsString(requestBody);

                // 创建HTTP请求
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofMillis(timeout))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                // 发送请求
                HttpResponse<String> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    String content = root.path("choices").path(0).path("message").path("content").asText();
                    log.debug("✓ 智谱AI调用成功");
                    return content;
                } else {
                    log.error("✕ 智谱AI调用失败，状态码: {}, 响应: {}",
                            response.statusCode(), response.body());
                    return "智谱AI调用失败，请稍后重试";
                }

            } catch (Exception e) {
                log.error("✕ 智谱AI调用异常", e);
                return "调用AI服务失败: " + e.getMessage();
            }
        });
    }

    @Override
    public void chatStream(String message, StreamCallback callback) {
        // 智谱AI流式调用
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", message)
            ));
            requestBody.put("temperature", 0.8);
            requestBody.put("stream", true);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMillis(timeout))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            String body = response.body();
                            // 解析SSE流
                            String[] lines = body.split("\n");
                            StringBuilder fullResponse = new StringBuilder();
                            for (String line : lines) {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);
                                    if ("[DONE]".equals(data)) {
                                        callback.onComplete();
                                        return;
                                    }
                                    try {
                                        JsonNode node = objectMapper.readTree(data);
                                        String content = node.path("choices").path(0)
                                                .path("delta").path("content").asText();
                                        if (content != null && !content.isEmpty()) {
                                            callback.onChunk(content);
                                            fullResponse.append(content);
                                        }
                                    } catch (Exception e) {
                                        // 忽略解析错误
                                    }
                                }
                            }
                            callback.onComplete();
                        } else {
                            callback.onError(new RuntimeException("HTTP " + response.statusCode()));
                        }
                    })
                    .exceptionally(e -> {
                        callback.onError(e);
                        return null;
                    });

        } catch (Exception e) {
            callback.onError(e);
        }
    }

    @Override
    public String getProviderName() {
        return "zhipu";
    }

    /**
     * 同步调用（简化版，用于不需要异步的场景）
     */
    public String chatSync(String systemPrompt, String message) {
        try {
            return chat(systemPrompt, message).get();
        } catch (Exception e) {
            log.error("同步调用失败", e);
            return null;
        }
    }
}