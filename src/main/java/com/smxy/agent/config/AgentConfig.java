package com.smxy.agent.config;

import com.smxy.agent.application.SimpleChatAgent;
import com.smxy.agent.core.AgentRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final AgentRegistry agentRegistry;
    private final SimpleChatAgent simpleChatAgent;

    @PostConstruct
    public void registerAgents() {
        // 注册智能体
        agentRegistry.register(simpleChatAgent);
        log.info("[TARGET] 智能体配置完成，已注册 {} 个智能体", agentRegistry.getAllAgents().size());
    }
}