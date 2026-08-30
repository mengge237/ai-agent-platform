package com.smxy.agent.memory;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MemoryService {

    // 短期记忆：按智能体ID存储最近对话
    private final Map<String, Deque<MemoryEntry>> shortTermMemory = new ConcurrentHashMap<>();

    // 长期记忆：按智能体ID存储重要信息
    private final Map<String, List<MemoryEntry>> longTermMemory = new ConcurrentHashMap<>();

    private static final int MAX_SHORT_TERM_SIZE = 20;

    /**
     * 添加到短期记忆
     */
    public void addToShortTerm(String agentId, String content) {
        Deque<MemoryEntry> memory = shortTermMemory.computeIfAbsent(agentId, k -> new ArrayDeque<>());

        memory.addFirst(new MemoryEntry(content, MemoryType.SHORT_TERM));

        // 保持最大容量
        while (memory.size() > MAX_SHORT_TERM_SIZE) {
            memory.removeLast();
        }

        System.out.println("▤ [短期记忆] " + agentId + ": " + content);
    }

    /**
     * 添加到长期记忆
     */
    public void addToLongTerm(String agentId, String content) {
        List<MemoryEntry> memory = longTermMemory.computeIfAbsent(agentId, k -> new ArrayList<>());
        memory.add(new MemoryEntry(content, MemoryType.LONG_TERM));

        System.out.println("[MEM] [长期记忆] " + agentId + ": " + content);
    }

    /**
     * 获取短期记忆
     */
    public List<String> getShortTermMemory(String agentId) {
        Deque<MemoryEntry> memory = shortTermMemory.get(agentId);
        if (memory == null) return Collections.emptyList();

        return memory.stream()
                .map(MemoryEntry::getContent)
                .limit(10)
                .toList();
    }

    /**
     * 获取所有记忆（上下文）
     */
    public String getContext(String agentId) {
        Deque<MemoryEntry> memory = shortTermMemory.get(agentId);
        if (memory == null || memory.isEmpty()) return "";

        StringBuilder context = new StringBuilder("历史对话：\n");
        int count = 0;
        for (MemoryEntry entry : memory) {
            if (count++ >= 10) break;
            context.append("- ").append(entry.getContent()).append("\n");
        }
        return context.toString();
    }

    /**
     * 清除智能体的记忆
     */
    public void clearMemory(String agentId) {
        shortTermMemory.remove(agentId);
        longTermMemory.remove(agentId);
        System.out.println("[DEL] 已清除智能体记忆: " + agentId);
    }

    /**
     * 记忆条目
     */
    private static class MemoryEntry {
        private final String content;
        private final MemoryType type;
        private final long timestamp;

        public MemoryEntry(String content, MemoryType type) {
            this.content = content;
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }

        public String getContent() { return content; }
        public MemoryType getType() { return type; }
        public long getTimestamp() { return timestamp; }
    }

    /**
     * 记忆类型枚举
     */
    private enum MemoryType {
        SHORT_TERM, LONG_TERM
    }
}