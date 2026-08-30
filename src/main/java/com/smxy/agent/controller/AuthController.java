package com.smxy.agent.controller;

import com.smxy.agent.auth.AuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 账号认证 REST 接口。
 * token 通过 Authorization: Bearer <token> 传递。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // ========== 注册 ==========
    @PostMapping("/register")
    public Result<Map<String, Object>> register(@RequestBody AuthRequest request) {
        try {
            if (request == null || request.getUsername() == null) {
                return Result.badRequest("参数不完整");
            }
            String err = authService.register(request.getUsername(), request.getPassword());
            if (err != null) return Result.badRequest(err);

            Map<String, Object> data = new HashMap<>();
            data.put("status", "ok");
            return Result.success(data);
        } catch (Exception e) {
            log.error("注册失败: {}", e.getMessage());
            return Result.error("注册失败");
        }
    }

    // ========== 登录 ==========
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody AuthRequest request) {
        try {
            if (request == null || request.getUsername() == null) {
                return Result.badRequest("参数不完整");
            }
            Map<String, Object> out = authService.login(request.getUsername(), request.getPassword());
            if (out == null) return Result.badRequest("用户名或密码错误");
            return Result.success(out);
        } catch (Exception e) {
            log.error("登录失败: {}", e.getMessage());
            return Result.error("登录失败");
        }
    }

    // ========== 当前用户 ==========
    @PostMapping("/me")
    public Result<Map<String, Object>> me(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = authService.validate(bearerToken(authHeader));
        if (username == null) {
            return Result.error(401, "未登录或登录已过期");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("username", username);
        return Result.success(data);
    }

    // ========== 登出 ==========
    @PostMapping("/logout")
    public Result<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        authService.logout(bearerToken(authHeader));
        Map<String, Object> data = new HashMap<>();
        data.put("status", "ok");
        return Result.success(data);
    }

    // ========== 辅助 ==========
    private String bearerToken(String header) {
        if (header == null) return null;
        String t = header.trim();
        if (t.toLowerCase().startsWith("bearer ")) return t.substring(7).trim();
        return t;
    }

    public static class AuthRequest {
        private String username;
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
