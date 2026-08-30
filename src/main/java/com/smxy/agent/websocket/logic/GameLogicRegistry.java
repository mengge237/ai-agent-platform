package com.smxy.agent.websocket.logic;

import com.smxy.agent.websocket.RoomGameLogic;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联机游戏逻辑注册表：按 gameType 分发到对应的 RoomGameLogic 实现。
 * 卧底（undercover）不在其中，继续走 GameFlowManager 原链路。
 */
@Component
public class GameLogicRegistry {

    private final Map<String, RoomGameLogic> logics = new HashMap<>();

    public GameLogicRegistry(List<RoomGameLogic> logicList) {
        for (RoomGameLogic logic : logicList) {
            logics.put(logic.gameType(), logic);
        }
    }

    /** 未知类型返回 null */
    public RoomGameLogic get(String gameType) {
        return gameType == null ? null : logics.get(gameType);
    }
}
