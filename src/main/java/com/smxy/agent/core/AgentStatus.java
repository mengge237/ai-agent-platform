package com.smxy.agent.core;

public enum AgentStatus {
    CREATED("已创建"),
    RUNNING("运行中"),
    PAUSED("已暂停"),
    STOPPED("已停止"),
    ERROR("错误");

    private final String description;

    AgentStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}