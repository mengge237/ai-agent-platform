package com.smxy.agent.auth;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存登录 token 表：token -> {username, expireAt}，24 小时 TTL，访问时惰性清理。
 */
@Component
public class TokenStore {

    public static class TokenRecord {
        public final String username;
        public final long expireAt;

        public TokenRecord(String username, long expireAt) {
            this.username = username;
            this.expireAt = expireAt;
        }
    }

    public static final long TTL_MILLIS = 24 * 60 * 60 * 1000L;

    private final Map<String, TokenRecord> tokens = new ConcurrentHashMap<>();

    public void put(String token, String username) {
        tokens.put(token, new TokenRecord(username, System.currentTimeMillis() + TTL_MILLIS));
    }

    /** 校验 token，返回用户名；无效或过期返回 null */
    public String validate(String token) {
        TokenRecord rec = tokens.get(token);
        if (rec == null) return null;
        if (System.currentTimeMillis() > rec.expireAt) {
            tokens.remove(token);
            return null;
        }
        return rec.username;
    }

    public void remove(String token) {
        tokens.remove(token);
    }

    public int size() {
        return tokens.size();
    }
}
