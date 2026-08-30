package com.smxy.agent.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LLMProvider {

    CompletableFuture<String> chat(String message);

    CompletableFuture<String> chat(String systemPrompt, String message);

    CompletableFuture<String> chat(List<ChatMessage> messages);

    void chatStream(String message, StreamCallback callback);

    String getProviderName();

    // ========== 内部类 ==========

    record ChatMessage(String role, String content) {
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }

        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }
    }

    interface StreamCallback {
        void onChunk(String chunk);

        void onComplete();

        void onError(Throwable error);
    }
}