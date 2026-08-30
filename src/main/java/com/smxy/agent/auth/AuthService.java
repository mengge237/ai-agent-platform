package com.smxy.agent.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 轻量认证服务（无 Spring Security）：
 * 注册（SHA-256 + 盐）、登录（发 UUID token，24h）、校验、登出。
 */
@Slf4j
@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[\\w\\u4e00-\\u9fa5]{2,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserStore userStore;
    private final TokenStore tokenStore;

    public AuthService(UserStore userStore, TokenStore tokenStore) {
        this.userStore = userStore;
        this.tokenStore = tokenStore;
    }

    /** 注册。成功返回 null，失败返回错误信息 */
    public String register(String username, String password) {
        if (username == null || username.trim().isEmpty()) return "用户名不能为空";
        username = username.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return "用户名需 2-20 位中文/字母/数字/下划线";
        }
        if (password == null || password.length() < 4 || password.length() > 32) {
            return "密码需 4-32 位";
        }
        if (userStore.exists(username)) return "用户名已存在";

        String salt = newSalt();
        userStore.put(username, salt, sha256(salt + password));
        log.info("[AUTH] 注册账号: {}", username);
        return null;
    }

    /** 登录。成功返回 {token, username}，失败返回 null */
    public Map<String, Object> login(String username, String password) {
        if (username == null || password == null) return null;
        username = username.trim();
        UserStore.UserRecord rec = userStore.get(username);
        if (rec == null) return null;
        if (!sha256(rec.salt + password).equals(rec.passwordHash)) return null;

        String token = UUID.randomUUID().toString().replace("-", "");
        tokenStore.put(token, username);
        log.info("[AUTH] 登录成功: {}", username);

        Map<String, Object> out = new HashMap<>();
        out.put("token", token);
        out.put("username", username);
        return out;
    }

    /** 校验 token，返回用户名；无效/过期返回 null */
    public String validate(String token) {
        return token == null ? null : tokenStore.validate(token);
    }

    public void logout(String token) {
        if (token != null) {
            tokenStore.remove(token);
        }
    }

    // ========== 辅助 ==========
    private String newSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
