package com.smxy.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AiAgentPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiAgentPlatformApplication.class, args);
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║                                                          ║");
        System.out.println("║   ■ MPOSTOR 系统已启动                                   ║");
        System.out.println("║   访问: http://localhost:8080                            ║");
        System.out.println("║   [WS] WebSocket: ws://localhost:8080/ws/game             ║");
        System.out.println("║   [AI] AI提供商: 智谱AI (GLM-4-Flash)                    ║");
        System.out.println("║                                                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }
}