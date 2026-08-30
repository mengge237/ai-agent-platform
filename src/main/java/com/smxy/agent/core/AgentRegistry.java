package com.smxy.agent.core;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRegistry {

    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    /**
     * 注册智能体
     */
    public void register(Agent agent) {
        agents.put(agent.getId(), agent);
        System.out.println("✓ 智能体已注册: " + agent.getName() + " (" + agent.getType() + ")");
    }

    /**
     * 注销智能体
     */
    public void unregister(String agentId) {
        Agent agent = agents.remove(agentId);
        if (agent != null) {
            agent.stop();
            System.out.println("✕ 智能体已注销: " + agent.getName());
        }
    }

    /**
     * 获取智能体
     */
    public Agent getAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * 获取所有智能体
     */
    public Map<String, Agent> getAllAgents() {
        return new ConcurrentHashMap<>(agents);
    }

    /**
     * 按类型获取智能体
     */
    public Map<String, Agent> getAgentsByType(String type) {
        Map<String, Agent> result = new ConcurrentHashMap<>();
        agents.forEach((id, agent) -> {
            if (agent.getType().equals(type)) {
                result.put(id, agent);
            }
        });
        return result;
    }

    /**
     * 启动所有智能体
     */
    @PostConstruct
    public void startAll() {
        agents.values().forEach(Agent::start);
        System.out.println("[BOOT] 所有智能体已启动，当前数量: " + agents.size());
    }

    /**
     * 停止所有智能体
     */
    public void stopAll() {
        agents.values().forEach(Agent::stop);
    }
}