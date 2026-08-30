package com.smxy.agent.controller;

import com.smxy.agent.core.Agent;
import com.smxy.agent.core.AgentRegistry;
import com.smxy.agent.dto.request.ChatRequest;
import com.smxy.agent.dto.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AgentController {

    private final AgentRegistry agentRegistry;

    @GetMapping("/list")
    public Map<String, Object> listAgents() {
        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("data", agentRegistry.getAllAgents().values().stream()
                .map(agent -> Map.of(
                        "id", agent.getId(),
                        "name", agent.getName(),
                        "type", agent.getType(),
                        "status", agent.getStatus().name()
                ))
                .toList());
        return response;
    }

    @PostMapping("/chat/{agentId}")
    public CompletableFuture<ChatResponse> chat(
            @PathVariable String agentId,
            @RequestBody ChatRequest request) {

        Agent agent = agentRegistry.getAgent(agentId);

        if (agent == null) {
            return CompletableFuture.completedFuture(
                    ChatResponse.error("智能体不存在: " + agentId));
        }

        return agent.process(request.getMessage(), request.getContext())
                .thenApply(response -> ChatResponse.success(response));
    }

    @GetMapping("/status/{agentId}")
    public Map<String, Object> getStatus(@PathVariable String agentId) {
        Agent agent = agentRegistry.getAgent(agentId);

        Map<String, Object> response = new HashMap<>();
        if (agent != null) {
            response.put("code", 200);
            response.put("data", Map.of(
                    "id", agent.getId(),
                    "name", agent.getName(),
                    "status", agent.getStatus().name()
            ));
        } else {
            response.put("code", 404);
            response.put("message", "智能体不存在");
        }
        return response;
    }

    @GetMapping("/by-name/{name}")
    public Map<String, Object> getAgentByName(@PathVariable String name) {
        Map<String, Object> response = new HashMap<>();

        Agent foundAgent = agentRegistry.getAllAgents().values().stream()
                .filter(agent -> agent.getName().equals(name))
                .findFirst()
                .orElse(null);

        if (foundAgent != null) {
            response.put("code", 200);
            response.put("data", Map.of(
                    "id", foundAgent.getId(),
                    "name", foundAgent.getName(),
                    "type", foundAgent.getType(),
                    "status", foundAgent.getStatus().name()
            ));
        } else {
            response.put("code", 404);
            response.put("message", "未找到名称为 " + name + " 的智能体");
        }
        return response;
    }

    @GetMapping("/by-type/{type}")
    public Map<String, Object> getAgentsByType(@PathVariable String type) {
        Map<String, Object> response = new HashMap<>();

        var agents = agentRegistry.getAgentsByType(type);

        response.put("code", 200);
        response.put("data", agents.values().stream()
                .map(agent -> Map.of(
                        "id", agent.getId(),
                        "name", agent.getName(),
                        "type", agent.getType(),
                        "status", agent.getStatus().name()
                ))
                .toList());
        return response;
    }
}