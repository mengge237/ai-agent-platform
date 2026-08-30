package com.smxy.agent.core;

import com.smxy.agent.llm.LLMProvider;
import com.smxy.agent.memory.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
public abstract class BaseAgent implements Agent {

    protected final String id;
    protected final String name;
    protected final String type;
    protected AgentStatus status = AgentStatus.CREATED;

    @Autowired(required = false)
    protected LLMProvider llmProvider;

    @Autowired(required = false)
    protected MemoryService memoryService;

    protected Map<String, Object> metadata;

    // 2参数构造函数
    public BaseAgent(String name, String type) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.type = type;
    }

    // 3参数构造函数（支持自定义ID）
    public BaseAgent(String name, String type, String customId) {
        if (customId != null && !customId.isEmpty()) {
            this.id = customId;
        } else {
            this.id = UUID.randomUUID().toString().substring(0, 8);
        }
        this.name = name;
        this.type = type;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public AgentStatus getStatus() {
        return status;
    }

    @Override
    public void start() {
        this.status = AgentStatus.RUNNING;
        log.info("[BOOT] 智能体 [{}] 已启动", name);
    }

    @Override
    public void stop() {
        this.status = AgentStatus.STOPPED;
        log.info("[STOP] 智能体 [{}] 已停止", name);
    }

    @Override
    public CompletableFuture<String> process(String message, Map<String, Object> context) {
        log.debug("▸ 智能体 [{}] 处理消息: {}", name, message);

        if (memoryService != null) {
            memoryService.addToShortTerm(id, message);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String response = doProcess(message, context);
                if (memoryService != null) {
                    memoryService.addToShortTerm(id, response);
                }
                return response;
            } catch (Exception e) {
                log.error("✕ 智能体处理失败", e);
                return "抱歉，处理您的请求时出现了错误：" + e.getMessage();
            }
        });
    }

    @Override
    public CompletableFuture<String> processWithPrompt(String message, String systemPrompt, Map<String, Object> context) {
        if (llmProvider != null) {
            return llmProvider.chat(systemPrompt, message);
        }
        return CompletableFuture.completedFuture("LLM服务不可用");
    }

    @Override
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    protected abstract String doProcess(String message, Map<String, Object> context);
}