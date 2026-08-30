package com.smxy.agent.websocket;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class GameRoomManager {

    private final Map<String, RoomData> rooms = new ConcurrentHashMap<>();

    /** playerId -> 账号用户名，由 GameWebSocketHandler 在 auth 时写入 */
    private final Map<String, String> displayNames = new ConcurrentHashMap<>();

    private static final String[] AI_NAMES = {"小白", "小蓝", "小绿", "小黄", "小红", "小橙", "小紫", "小灰", "小粉", "小棕"};
    private static final String[] PRESET_ROOM_NAMES = {"欢乐房", "高手房", "新手村", "休闲区", "竞技场", "娱乐房", "朋友局", "挑战房"};
    private static final String[] PRESET_CATEGORIES = {"水果", "动物", "食物", "娱乐", "地点", "颜色", "职业", "交通工具"};

    public static class RoomData {
        public String id, name, category, host, status, password;
        /** 游戏类型: undercover | bomb | idiom | turtle | quiz */
        public String gameType;
        /** 难度: easy | normal | hard（创建房间时选择，各游戏按自己的方式解读） */
        public String difficulty = "normal";
        /** 各联机游戏的对局状态，由对应的 RoomGameLogic 独占，绝不整体序列化（防泄题） */
        public Map<String, Object> extra;
        public int maxPlayers, aiCount, round, currentSpeakerIndex, undercoverIndex;
        public boolean gameOver, hasPassword, isPreset;
        public List<String> players, eliminated;
        public Set<String> spoken, voted;
        public Map<String, String> votes, playerWords;
        public String civilianWord, undercoverWord;
        public List<Map<String, Object>> descriptions;
        public long createdAt;

        public RoomData() {
            this.players = new ArrayList<>();
            this.eliminated = new ArrayList<>();
            this.spoken = new HashSet<>();
            this.voted = new HashSet<>();
            this.votes = new HashMap<>();
            this.playerWords = new HashMap<>();
            this.descriptions = new ArrayList<>();
            this.status = "waiting";
            this.createdAt = System.currentTimeMillis();
            this.isPreset = false;
            this.gameOver = false;
            this.undercoverIndex = -1;
            this.round = 1;
            this.currentSpeakerIndex = 0;
            this.gameType = "undercover";
            this.extra = new ConcurrentHashMap<>();
        }

        public List<String> getActivePlayers() {
            List<String> active = new ArrayList<>();
            for (String p : players) {
                if (!eliminated.contains(p)) active.add(p);
            }
            return active;
        }

        public boolean isUndercover(String playerId) {
            return players.indexOf(playerId) == undercoverIndex;
        }

        public boolean isEliminated(String playerId) {
            return eliminated.contains(playerId);
        }

        public boolean isSpoken(String playerId) {
            return spoken.contains(playerId);
        }

        public boolean isVoted(String playerId) {
            return voted.contains(playerId);
        }

        public int getRealPlayerCount() {
            int count = 0;
            for (String p : players) {
                if (!p.startsWith("AI-")) count++;
            }
            return count;
        }
    }

    // ========== 系统预制房间 ==========
    @PostConstruct
    public void initPresetRooms() {
        for (int i = 0; i < 4; i++) {
            String roomId = "PRESET-" + (i + 1);
            RoomData room = new RoomData();
            room.id = roomId;
            room.name = PRESET_ROOM_NAMES[i % PRESET_ROOM_NAMES.length];
            room.maxPlayers = 6;
            room.aiCount = 3;
            room.category = PRESET_CATEGORIES[i % PRESET_CATEGORIES.length];
            room.host = "系统";
            room.isPreset = true;
            room.status = "waiting";

            for (int j = 0; j < 3; j++) {
                room.players.add("AI-" + AI_NAMES[j % AI_NAMES.length]);
            }

            rooms.put(roomId, room);
            log.info("[HOME] 预制房间创建: {} ({})", room.name, roomId);
        }
        log.info("✓ 系统预制房间初始化完成: {} 个", rooms.size());
    }

    // ========== 创建房间 ==========
    public Map<String, Object> createRoom(String playerId, Map<String, Object> data) {
        String existingRoom = findPlayerRoom(playerId);
        if (existingRoom != null) {
            RoomData existing = rooms.get(existingRoom);
            if (existing != null && !existing.status.equals("ended")) {
                return error("你已在房间 " + existing.name + " 中，不能重复创建");
            }
        }

        String roomId = "ROOM-" + System.currentTimeMillis();
        String name = data != null ? (String) data.getOrDefault("name", "未命名") : "未命名";
        int maxPlayers = Math.max(2, Math.min(10, data != null ? (int) data.getOrDefault("maxPlayers", 6) : 6));
        int aiCount = data != null ? (int) data.getOrDefault("aiCount", 1) : 1;
        String category = data != null ? (String) data.getOrDefault("category", "水果") : "水果";
        String password = data != null ? (String) data.getOrDefault("password", "") : "";
        String gameType = data != null ? (String) data.getOrDefault("gameType", "undercover") : "undercover";
        // 难度：创建房间时选择，非法值回退 normal
        String difficulty = data != null ? (String) data.getOrDefault("difficulty", "normal") : "normal";
        if (!"easy".equals(difficulty) && !"hard".equals(difficulty)) difficulty = "normal";

        RoomData room = new RoomData();
        room.id = roomId;
        room.name = name;
        room.maxPlayers = maxPlayers;
        room.gameType = gameType;
        room.category = category;
        room.difficulty = difficulty;
        room.host = playerId;
        room.players.add(playerId);

        if (password != null && !password.trim().isEmpty()) {
            room.password = password.trim();
            room.hasPassword = true;
        }

        if ("undercover".equals(gameType)) {
            room.aiCount = Math.max(aiCount, 1);
            for (int i = 0; i < room.aiCount && room.players.size() < maxPlayers; i++) {
                room.players.add("AI-" + AI_NAMES[i % AI_NAMES.length]);
            }
        } else {
            // 联机对战游戏：真人同房，不填充 AI
            room.aiCount = 0;
        }

        rooms.put(roomId, room);
        log.info("[HOME] 创建房间: {} ({}人, {} AI)", roomId, maxPlayers, room.aiCount);
        return buildRoomInfo(room);
    }

    // ========== 查找玩家所在房间 ==========
    public String findPlayerRoom(String playerId) {
        for (RoomData room : rooms.values()) {
            if (room.players.contains(playerId) && !room.status.equals("ended") && !room.gameOver) {
                return room.id;
            }
        }
        return null;
    }

    // ========== 加入房间 ==========
    public Map<String, Object> joinRoom(String playerId, Map<String, Object> data) {
        String roomId = data != null ? (String) data.get("roomId") : null;
        String password = data != null ? (String) data.get("password") : null;

        if (roomId == null) return error("房间ID不能为空");

        String existingRoom = findPlayerRoom(playerId);
        if (existingRoom != null && !existingRoom.equals(roomId)) {
            RoomData existing = rooms.get(existingRoom);
            if (existing != null) {
                if (!existing.status.equals("ended")) {
                    return error("你已在房间 " + existing.name + " 中，不能加入其他房间");
                }
                // 已结束房间的幽灵成员：加入新房间前清理，保持一人一房
                existing.players.remove(playerId);
            }
        }

        RoomData room = rooms.get(roomId);
        if (room == null) return error("房间不存在");

        if (room.hasPassword && room.password != null) {
            if (password == null || !room.password.equals(password)) {
                return error("密码错误");
            }
        }

        if (room.status.equals("ended") || room.gameOver) return error("游戏已结束");
        if (room.status.equals("playing")) return error("游戏进行中");
        if (room.players.size() >= room.maxPlayers) return error("房间已满");

        if (!room.players.contains(playerId)) {
            room.players.add(playerId);
        }

        Map<String, Object> result = buildRoomInfo(room);
        result.put("roomId", roomId);
        return result;
    }

    // ========== 退出房间 ==========
    public Map<String, Object> leaveRoom(String roomId, String playerId) {
        RoomData room = rooms.get(roomId);
        if (room == null) return error("房间不存在");

        if (!room.players.contains(playerId)) return error("玩家不在房间中");
        if (playerId.startsWith("AI-")) return error("AI不能主动退出");

        if (room.isPreset) {
            room.players.remove(playerId);
            room.spoken.remove(playerId);
            room.voted.remove(playerId);
            room.votes.remove(playerId);
            log.info("[EXIT] 玩家离开预制房间: {} -> {}", playerId, roomId);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已离开房间");
            result.put("roomId", roomId);
            return result;
        }

        room.players.remove(playerId);
        room.spoken.remove(playerId);
        room.voted.remove(playerId);
        room.votes.remove(playerId);

        if (room.players.isEmpty() || room.host.equals(playerId)) {
            String newHost = transferHost(roomId);
            if (newHost == null) {
                rooms.remove(roomId);
                log.info("[DEL] 房间已删除: {}", roomId);
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("message", "房间已解散");
                result.put("roomId", roomId);
                return result;
            }
            log.info("[HOST] 房主转移: {} -> {}", roomId, newHost);
        }

        log.info("[EXIT] 玩家离开房间: {} -> {}", playerId, roomId);
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "已离开房间");
        result.put("roomId", roomId);
        return result;
    }

    // ========== 关闭房间（房主） ==========
    public Map<String, Object> closeRoom(String roomId, String playerId) {
        RoomData room = rooms.get(roomId);
        if (room == null) return error("房间不存在");
        if (!room.host.equals(playerId)) return error("只有房主可以关闭房间");
        if (room.isPreset) return error("预制房间不能关闭");

        rooms.remove(roomId);
        log.info("[DEL] 房间已关闭: {}", roomId);
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "房间已关闭");
        result.put("roomId", roomId);
        return result;
    }

    // ========== 踢人 ==========
    public Map<String, Object> kickPlayer(String roomId, String kickerId, String targetId) {
        RoomData room = rooms.get(roomId);
        if (room == null) return error("房间不存在");

        if (!room.host.equals(kickerId)) return error("只有房主可以踢人");
        if (kickerId.equals(targetId)) return error("不能踢自己");
        if (targetId.startsWith("AI-")) return error("不能踢AI玩家");
        if (!room.players.contains(targetId)) return error("玩家不在房间中");

        room.players.remove(targetId);
        room.spoken.remove(targetId);
        room.voted.remove(targetId);
        room.votes.remove(targetId);

        log.info("[KICK] 玩家被踢: {} 被 {} 踢出", targetId, kickerId);

        Map<String, Object> result = buildRoomInfo(room);
        result.put("roomId", roomId);
        result.put("kickedPlayer", targetId);
        return result;
    }

    // ========== 转移房主 ==========
    public String transferHost(String roomId) {
        RoomData room = rooms.get(roomId);
        if (room == null) return null;

        List<String> realPlayers = new ArrayList<>();
        for (String p : room.players) {
            if (!p.startsWith("AI-")) {
                realPlayers.add(p);
            }
        }

        if (realPlayers.isEmpty()) {
            return null;
        }

        String newHost = realPlayers.get(0);
        room.host = newHost;
        return newHost;
    }

    // ========== 获取房间 ==========
    public RoomData getRoom(String roomId) {
        RoomData room = rooms.get(roomId);
        if (room == null) {
            log.warn("[!] getRoom: 房间不存在, roomId={}, 当前房间列表: {}", roomId, rooms.keySet());
        } else {
            log.debug("✓ getRoom: 找到房间, roomId={}, status={}", roomId, room.status);
        }
        return room;
    }

    public void removeRoom(String roomId) {
        rooms.remove(roomId);
        log.info("[DEL] 删除房间: {}", roomId);
    }

    public List<Map<String, Object>> buildRoomList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RoomData room : rooms.values()) {
            list.add(buildRoomInfo(room));
        }
        return list;
    }

    public Map<String, Object> buildRoomInfo(RoomData room) {
        Map<String, Object> info = new HashMap<>();
        info.put("id", room.id);
        info.put("name", room.name);
        info.put("players", room.players);
        info.put("maxPlayers", room.maxPlayers);
        info.put("aiCount", room.aiCount);
        info.put("category", room.category);
        info.put("status", room.status);
        info.put("host", room.host);
        info.put("gameOver", room.gameOver);
        info.put("hasPassword", room.hasPassword);
        info.put("createdAt", room.createdAt);
        info.put("realPlayerCount", room.getRealPlayerCount());
        info.put("isPreset", room.isPreset);
        info.put("round", room.round);
        info.put("gameType", room.gameType != null ? room.gameType : "undercover");
        info.put("difficulty", room.difficulty != null ? room.difficulty : "normal");
        info.put("displayNames", buildDisplayNames(room));
        info.put("civilianWord", room.civilianWord != null ? room.civilianWord : "");
        info.put("undercoverWord", room.undercoverWord != null ? room.undercoverWord : "");
        info.put("undercoverIndex", room.undercoverIndex);
        info.put("playerWords", room.playerWords);
        info.put("eliminated", room.eliminated);
        info.put("spoken", new ArrayList<>(room.spoken));
        info.put("voted", new ArrayList<>(room.voted));
        info.put("votes", room.votes);
        return info;
    }

    /** 房间内各玩家的显示名（playerId -> 账号用户名，未知时回退 playerId 本身） */
    public Map<String, String> buildDisplayNames(RoomData room) {
        Map<String, String> names = new HashMap<>();
        for (String p : room.players) {
            names.put(p, displayNames.getOrDefault(p, p));
        }
        return names;
    }

    // ========== 显示名（账号）登记 ==========
    public void setDisplayName(String playerId, String username) {
        if (playerId != null && username != null && !username.isEmpty()) {
            displayNames.put(playerId, username);
        }
    }

    public void removeDisplayName(String playerId) {
        displayNames.remove(playerId);
    }

    private Map<String, Object> error(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "error");
        result.put("message", msg);
        return result;
    }

    public String[] generateWordPair(String category) {
        return generateWordPair(category, "normal");
    }

    /**
     * 卧底词对按难度选取：
     * easy —— 差异明显的词对；hard —— 形容词强化的近似词对（如 冰镇西瓜/常温西瓜）。
     */
    public String[] generateWordPair(String category, String difficulty) {
        // 类别缺省/未知时回退「食物」，且 Map.ofEntries 不允许 null 键，必须先兜底
        String cat = category != null ? category : "食物";
        if ("easy".equals(difficulty)) {
            return EASY_WORD_BANK.getOrDefault(cat, new String[]{"牛奶", "豆浆"});
        }
        if ("hard".equals(difficulty)) {
            return HARD_ADJ_WORD_BANK.getOrDefault(cat, new String[]{"冰镇豆浆", "常温豆浆"});
        }
        return NORMAL_WORD_BANK.getOrDefault(cat, new String[]{"牛奶", "豆浆"});
    }

    /** 普通难度：常规近似词对 */
    private static final Map<String, String[]> NORMAL_WORD_BANK = Map.ofEntries(
            Map.entry("水果", new String[]{"苹果", "桃子"}),
            Map.entry("家禽", new String[]{"鸡", "鸭"}),
            Map.entry("电子产品", new String[]{"手机", "平板电脑"}),
            Map.entry("动物", new String[]{"老虎", "狮子"}),
            Map.entry("交通工具", new String[]{"汽车", "火车"}),
            Map.entry("颜色", new String[]{"红色", "粉色"}),
            Map.entry("食物", new String[]{"汉堡", "三明治"}),
            Map.entry("职业", new String[]{"医生", "护士"}),
            Map.entry("娱乐", new String[]{"电影", "电视剧"}),
            Map.entry("地点", new String[]{"图书馆", "书店"}));

    /** 简单难度：同类但差异明显的词对 */
    private static final Map<String, String[]> EASY_WORD_BANK = Map.ofEntries(
            Map.entry("水果", new String[]{"西瓜", "葡萄"}),
            Map.entry("家禽", new String[]{"鸡", "鹅"}),
            Map.entry("电子产品", new String[]{"手机", "电视机"}),
            Map.entry("动物", new String[]{"老虎", "金鱼"}),
            Map.entry("交通工具", new String[]{"汽车", "飞机"}),
            Map.entry("颜色", new String[]{"红色", "黑色"}),
            Map.entry("食物", new String[]{"汉堡", "饺子"}),
            Map.entry("职业", new String[]{"医生", "司机"}),
            Map.entry("娱乐", new String[]{"电影", "广播"}),
            Map.entry("地点", new String[]{"图书馆", "医院"}));

    /** 困难难度：形容词强化的近似词对，需要更细腻的描述才能区分 */
    private static final Map<String, String[]> HARD_ADJ_WORD_BANK = Map.ofEntries(
            Map.entry("水果", new String[]{"冰镇西瓜", "常温西瓜"}),
            Map.entry("家禽", new String[]{"白羽鸡", "黑羽鸡"}),
            Map.entry("电子产品", new String[]{"折叠手机", "直板手机"}),
            Map.entry("动物", new String[]{"长毛兔", "短毛兔"}),
            Map.entry("交通工具", new String[]{"燃油汽车", "电动汽车"}),
            Map.entry("颜色", new String[]{"浅蓝色", "深蓝色"}),
            Map.entry("食物", new String[]{"麻辣火锅", "清汤火锅"}),
            Map.entry("职业", new String[]{"小学老师", "中学老师"}),
            Map.entry("娱乐", new String[]{"黑白电影", "彩色电影"}),
            Map.entry("地点", new String[]{"城市公园", "郊野公园"}));
}