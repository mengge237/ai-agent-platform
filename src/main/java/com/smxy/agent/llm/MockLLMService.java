package com.smxy.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class MockLLMService implements LLMProvider {

    private static final String[] RESPONSES = {
            "好的，我理解你的问题。让我来帮你分析一下。",
            "这个问题很有意思，我觉得可以从以下几个方面来看...",
            "我明白了，这是一个很好的问题。",
            "让我想想... 我认为答案是肯定的。",
            "根据我的理解，这个问题的关键在于...",
            "我建议你从不同的角度来思考这个问题。",
            "好的，我会尽力帮你解决这个问题。"
    };

    @Override
    public CompletableFuture<String> chat(String message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(500 + (long) (Math.random() * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return RESPONSES[(int) (Math.random() * RESPONSES.length)];
        });
    }

    @Override
    public CompletableFuture<String> chat(String systemPrompt, String message) {
        return chat(message);
    }

    @Override
    public CompletableFuture<String> chat(List<ChatMessage> messages) {
        String lastMessage = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        return chat(lastMessage);
    }

    @Override
    public void chatStream(String message, StreamCallback callback) {
        String response = RESPONSES[(int) (Math.random() * RESPONSES.length)];
        for (char c : response.toCharArray()) {
            callback.onChunk(String.valueOf(c));
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        callback.onComplete();
    }

    @Override
    public String getProviderName() {
        return "mock";
    }
}