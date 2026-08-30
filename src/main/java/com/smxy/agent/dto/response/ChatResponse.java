package com.smxy.agent.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChatResponse {
    private int code;
    private String message;
    private String data;
    private long timestamp;

    public static ChatResponse success(String data) {
        return new ChatResponse(200, "success", data, System.currentTimeMillis());
    }

    public static ChatResponse error(String message) {
        return new ChatResponse(500, message, null, System.currentTimeMillis());
    }
}