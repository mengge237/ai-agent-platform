package com.smxy.agent.core;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface Agent {
    String getId();
    String getName();
    String getType();
    CompletableFuture<String> process(String message, Map<String, Object> context);
    CompletableFuture<String> processWithPrompt(String message, String systemPrompt, Map<String, Object> context);
    AgentStatus getStatus();
    void start();
    void stop();
    Map<String, Object> getMetadata();
}