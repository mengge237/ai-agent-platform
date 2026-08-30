package com.smxy.agent.application;

import com.smxy.agent.core.BaseAgent;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

@Component
public class SimpleChatAgent extends BaseAgent {

    public SimpleChatAgent() {
        super("聊天助手", "CHAT");
    }

    @PostConstruct
    public void init() {
        this.start();
    }

    @Override
    protected String doProcess(String message, Map<String, Object> context) {
        // 简单的响应逻辑
        if (message.contains("你好") || message.contains("嗨")) {
            return "你好！很高兴见到你！有什么我可以帮你的吗？";
        } else if (message.contains("名字")) {
            return "我是" + this.name + "，一个AI智能体助手。";
        } else if (message.contains("天气")) {
            return "抱歉，我暂时无法获取实时天气信息。不过你可以看看窗外！";
        } else if (message.contains("谢谢")) {
            return "不客气！很高兴能帮到你。";
        } else {
            return "收到你的消息：「" + message + "」。我是一个简单的AI助手，正在学习更多功能。";
        }
    }
}