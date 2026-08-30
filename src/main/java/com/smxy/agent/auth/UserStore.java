package com.smxy.agent.auth;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存账号表：username -> {salt, passwordHash}。
 * 密码不存明文（SHA-256 + 每用户随机盐）。
 */
@Component
public class UserStore {

    public static class UserRecord {
        public final String salt;
        public final String passwordHash;

        public UserRecord(String salt, String passwordHash) {
            this.salt = salt;
            this.passwordHash = passwordHash;
        }
    }

    private final Map<String, UserRecord> users = new ConcurrentHashMap<>();

    public boolean exists(String username) {
        return users.containsKey(username);
    }

    public UserRecord get(String username) {
        return users.get(username);
    }

    public void put(String username, String salt, String passwordHash) {
        users.put(username, new UserRecord(salt, passwordHash));
    }

    public int size() {
        return users.size();
    }
}
