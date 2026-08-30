package com.smxy.agent.websocket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联机房间游戏逻辑接口（bomb/idiom/turtle/quiz）。
 * <p>
 * 每个实现独占 {@link GameRoomManager.RoomData#extra} 存放对局状态（绝不整体序列化，防泄题）。
 * 返回约定沿用现有 flowManager：
 * <ul>
 *   <li>{@code status=error + message}：错误，由 handler 单播给发起者；</li>
 *   <li>{@code status=success + broadcasts?}：需要广播到整个房间的消息列表；</li>
 *   <li>{@code unicasts?}：[{playerId, message}] 需要单播的消息列表（如海龟汤汤底只给房主）。</li>
 * </ul>
 * 实现方法内自行 {@code synchronized (room)} 串行化对局状态（LLM 调用最坏只堵单房）。
 */
public interface RoomGameLogic {

    /** 游戏类型：bomb | idiom | turtle | quiz */
    String gameType();

    /** 房主开始对局。返回 started 广播（+可能的单播） */
    Map<String, Object> onStart(GameRoomManager.RoomData room, String playerId);

    /** 处理对局内消息（bombGuess/idiomPlay/turtleAsk/quizAnswer 等） */
    Map<String, Object> handleMessage(String type, GameRoomManager.RoomData room,
                                      String playerId, Map<String, Object> data);

    /** 重连快照：按玩家身份返回可见的对局状态（汤底/答案只给有权限的人） */
    Map<String, Object> snapshot(GameRoomManager.RoomData room, String playerId);

    /** 玩家中途离场（已被移出 room.players）：推进回合/判终局/房主转移后补发秘密 */
    Map<String, Object> onPlayerLeft(GameRoomManager.RoomData room, String playerId);

    /** 重置房间：清理 extra 中本游戏的状态 */
    void reset(GameRoomManager.RoomData room);

    // ========== 返回构造辅助 ==========

    static Map<String, Object> ok() {
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        return r;
    }

    static Map<String, Object> broadcast(Map<String, Object> message) {
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        r.put("broadcasts", List.of(message));
        return r;
    }

    static Map<String, Object> broadcast(List<Map<String, Object>> broadcasts,
                                         List<Map<String, Object>> unicasts) {
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        if (broadcasts != null && !broadcasts.isEmpty()) r.put("broadcasts", broadcasts);
        if (unicasts != null && !unicasts.isEmpty()) r.put("unicasts", unicasts);
        return r;
    }

    static Map<String, Object> unicast(String playerId, Map<String, Object> message) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("playerId", playerId);
        entry.put("message", message);
        List<Map<String, Object>> unicasts = new ArrayList<>();
        unicasts.add(entry);
        return broadcast(null, unicasts);
    }

    static Map<String, Object> error(String msg) {
        Map<String, Object> r = new HashMap<>();
        r.put("status", "error");
        r.put("message", msg);
        return r;
    }
}
