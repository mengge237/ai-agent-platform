package com.smxy.agent.controller;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private int code;

    private String message;

    private T data;

    private long timestamp;

    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data, System.currentTimeMillis());
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(0, message, data, System.currentTimeMillis());
    }

    public static <T> Result<T> success() {
        return new Result<>(0, "success", null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> badRequest(String message) {
        return new Result<>(400, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> notFound(String message) {
        return new Result<>(404, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> internalError(String message) {
        return new Result<>(500, message, null, System.currentTimeMillis());
    }
}