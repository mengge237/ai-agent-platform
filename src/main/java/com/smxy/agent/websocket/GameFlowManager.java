package com.smxy.agent.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BiConsumer;

@Slf4j
@Component
public class GameFlowManager {

    private final GameRoomManager roomManager;
    private final AIService aiService;

    public GameFlowManager(GameRoomManager roomManager, AIService aiService) {
        this.roomManager = roomManager;
        this.aiService = aiService;
    }

    public Map<String, Object> startGame(String roomId, String playerId) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) {
            log.error("✕ startGame: 房间不存在, roomId={}", roomId);
            return error("房间不存在");
        }
        if (!room.host.equals(playerId)) return error("只有房主可以开始");
        if (room.status.equals("playing")) return error("游戏已开始");
        if (room.status.equals("ended")) return error("游戏已结束");
        if (room.players.isEmpty()) return error("没有玩家");

        // 1人也能玩
        if (room.players.size() == 1) {
            String[] aiNames = {"小白", "小蓝", "小绿", "小黄", "小红"};
            String aiName = "AI-" + aiNames[new Random().nextInt(aiNames.length)];
            if (!room.players.contains(aiName)) {
                room.players.add(aiName);
                room.aiCount = Math.max(room.aiCount, 1);
                log.info("[AI] 自动添加AI玩家: {} (仅1人时)", aiName);
            }
        }

        room.status = "playing";
        // 卧底词对按难度选取：困难 = 形容词强化的近似词对
        String[] wordPair = roomManager.generateWordPair(room.category, room.difficulty);
        room.civilianWord = wordPair[0];
        room.undercoverWord = wordPair[1];

        Random random = new Random();
        room.undercoverIndex = random.nextInt(room.players.size());

        for (int i = 0; i < room.players.size(); i++) {
            String player = room.players.get(i);
            room.playerWords.put(player, i == room.undercoverIndex ? room.undercoverWord : room.civilianWord);
        }

        room.round = 1;
        room.currentSpeakerIndex = 0;
        room.spoken.clear();
        room.voted.clear();
        room.votes.clear();
        room.eliminated.clear();
        room.descriptions.clear();
        room.gameOver = false;

        log.info("[GAME] 游戏开始: {} ({}人, 卧底: {})", roomId, room.players.size(), room.players.get(room.undercoverIndex));
        log.info("[DATA] 当前房间列表: {}", roomManager.buildRoomList().stream().map(m -> m.get("id")).toList());

        Map<String, Object> result = new HashMap<>();
        result.put("players", room.players);
        result.put("civilianWord", room.civilianWord);
        result.put("undercoverWord", room.undercoverWord);
        result.put("undercoverIndex", room.undercoverIndex);
        result.put("playerWords", room.playerWords);
        result.put("maxPlayers", room.maxPlayers);
        result.put("aiCount", room.aiCount);
        result.put("difficulty", room.difficulty != null ? room.difficulty : "normal");
        result.put("status", "success");

        return result;
    }

    // ========== 重置房间 ==========
    public Map<String, Object> resetRoom(String roomId, String playerId) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) return error("房间不存在");
        if (!room.host.equals(playerId)) return error("只有房主可以重置");

        room.status = "waiting";
        room.gameOver = false;
        room.round = 1;
        room.currentSpeakerIndex = 0;
        room.spoken.clear();
        room.voted.clear();
        room.votes.clear();
        room.eliminated.clear();
        room.descriptions.clear();
        room.playerWords.clear();
        room.civilianWord = null;
        room.undercoverWord = null;
        room.undercoverIndex = -1;

        log.info("> 房间重置: {}", roomId);
        return Map.of("status", "success", "message", "房间已重置");
    }

    public void startNewRound(String roomId, BiConsumer<String, Map<String, Object>> broadcaster) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null || room.gameOver || room.status.equals("ended")) return;

        room.spoken.clear();
        room.voted.clear();
        room.votes.clear();
        room.currentSpeakerIndex = 0;

        broadcaster.accept(roomId, Map.of("type", "roundStarted", "round", room.round, "roomId", roomId));
        moveToNextSpeaker(roomId, broadcaster);
    }

    public Map<String, Object> submitSpeak(String roomId, String playerId, String content) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) return error("房间不存在");
        if (room.gameOver || room.status.equals("ended")) return error("游戏已结束");
        if (!room.status.equals("playing")) return error("游戏未开始");
        if (room.isEliminated(playerId)) return error("你已被淘汰");
        if (room.isSpoken(playerId)) return error("你已经发言过了");

        room.spoken.add(playerId);
        Map<String, Object> desc = new HashMap<>();
        desc.put("player", playerId);
        desc.put("content", content);
        desc.put("round", room.round);
        room.descriptions.add(desc);

        log.info("▸ {}: {}", playerId, content);
        return Map.of("status", "success");
    }

    public void afterSpeak(String roomId, BiConsumer<String, Map<String, Object>> broadcaster) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null || room.gameOver || room.status.equals("ended")) return;

        List<String> remaining = new ArrayList<>();
        for (String p : room.getActivePlayers()) {
            if (!room.isSpoken(p)) remaining.add(p);
        }

        if (remaining.isEmpty()) {
            broadcaster.accept(roomId, Map.of("type", "speakPhaseEnded", "roomId", roomId));
            doEnterVote(roomId, broadcaster);
        } else {
            String nextPlayer = remaining.get(0);
            room.currentSpeakerIndex = room.players.indexOf(nextPlayer);
            broadcaster.accept(roomId, Map.of("type", "turnChanged", "index", room.currentSpeakerIndex, "player", nextPlayer, "roomId", roomId));

            if (nextPlayer.startsWith("AI-")) {
                scheduleAISpeak(roomId, nextPlayer, broadcaster);
            }
        }
    }

    public Map<String, Object> enterVote(String roomId, String playerId, BiConsumer<String, Map<String, Object>> broadcaster) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) return error("房间不存在");
        if (!room.host.equals(playerId)) return error("只有房主可以进入投票");
        if (room.gameOver || room.status.equals("ended")) return error("游戏已结束");

        room.voted.clear();
        room.votes.clear();
        broadcaster.accept(roomId, Map.of("type", "votePhaseStarted", "roomId", roomId));
        scheduleAIVotes(roomId, broadcaster);
        return Map.of("status", "success");
    }

    private void doEnterVote(String roomId, BiConsumer<String, Map<String, Object>> broadcaster) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null || room.gameOver || room.status.equals("ended")) return;

        room.voted.clear();
        room.votes.clear();
        broadcaster.accept(roomId, Map.of("type", "votePhaseStarted", "roomId", roomId));
        scheduleAIVotes(roomId, broadcaster);
    }

    public Map<String, Object> submitVote(String roomId, String playerId, String target) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) return error("房间不存在");
        if (room.gameOver || room.status.equals("ended")) return error("游戏已结束");
        if (!room.status.equals("playing")) return error("游戏未开始");
        if (room.isEliminated(playerId)) return error("你已被淘汰");
        if (room.isEliminated(target)) return error("目标已被淘汰");
        if (playerId.equals(target)) return error("不能投自己");
        if (room.isVoted(playerId)) return error("你已经投过票了");

        room.voted.add(playerId);
        room.votes.put(playerId, target);
        return Map.of("status", "success");
    }

    public void afterVote(String roomId, BiConsumer<String, Map<String, Object>> broadcaster) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null || room.gameOver || room.status.equals("ended")) return;

        if (room.voted.size() >= room.getActivePlayers().size()) {
            publishVoteResult(roomId, broadcaster);
        }
    }

    public Map<String, Object> executeElimination(String roomId, String playerId) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) return error("房间不存在");
        if (!room.host.equals(playerId)) return error("只有房主可以执行淘汰");
        if (room.gameOver || room.status.equals("ended")) return error("游戏已结束");

        Map<String, Integer> voteCounts = new HashMap<>();
        List<String> active = room.getActivePlayers();
        for (String p : active) voteCounts.put(p, 0);
        for (String target : room.votes.values()) {
            voteCounts.put(target, voteCounts.getOrDefault(target, 0) + 1);
        }

        int maxVotes = 0;
        List<String> topPlayers = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                topPlayers.clear();
                topPlayers.add(entry.getKey());
            } else if (entry.getValue() == maxVotes && maxVotes > 0) {
                topPlayers.add(entry.getKey());
            }
        }

        if (topPlayers.isEmpty() || maxVotes == 0) {
            room.round++;
            return Map.of("status", "success", "nextRound", true);
        }

        String eliminated = topPlayers.get(new Random().nextInt(topPlayers.size()));
        room.eliminated.add(eliminated);

        List<String> remaining = room.getActivePlayers();
        List<String> remainingUndercover = new ArrayList<>();
        for (String p : remaining) {
            if (room.players.indexOf(p) == room.undercoverIndex) {
                remainingUndercover.add(p);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("eliminated", eliminated);

        if (remainingUndercover.isEmpty()) {
            room.status = "ended";
            room.gameOver = true;
            result.put("gameEnded", "civilian");
        } else if (remaining.size() == 1 && remainingUndercover.size() == 1) {
            room.status = "ended";
            room.gameOver = true;
            result.put("gameEnded", "undercover");
        } else {
            room.round++;
            result.put("nextRound", true);
        }

        return result;
    }

    private void publishVoteResult(String roomId, BiConsumer<String, Map<String, Object>> broadcaster) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null) return;

        Map<String, Integer> voteCounts = new HashMap<>();
        for (String p : room.getActivePlayers()) voteCounts.put(p, 0);
        for (String target : room.votes.values()) {
            voteCounts.put(target, voteCounts.getOrDefault(target, 0) + 1);
        }

        broadcaster.accept(roomId, Map.of("type", "voteResult", "votes", voteCounts, "roomId", roomId));
    }

    private void moveToNextSpeaker(String roomId, BiConsumer<String, Map<String, Object>> broadcaster) {
        GameRoomManager.RoomData room = roomManager.getRoom(roomId);
        if (room == null || room.gameOver || room.status.equals("ended")) return;

        List<String> active = room.getActivePlayers();
        if (active.isEmpty()) return;

        String nextPlayer = null;
        for (String p : active) {
            if (!room.isSpoken(p)) { nextPlayer = p; break; }
        }

        if (nextPlayer == null) {
            broadcaster.accept(roomId, Map.of("type", "speakPhaseEnded", "roomId", roomId));
            doEnterVote(roomId, broadcaster);
            return;
        }

        room.currentSpeakerIndex = room.players.indexOf(nextPlayer);
        broadcaster.accept(roomId, Map.of("type", "turnChanged", "index", room.currentSpeakerIndex, "player", nextPlayer, "roomId", roomId));

        if (nextPlayer.startsWith("AI-")) {
            scheduleAISpeak(roomId, nextPlayer, broadcaster);
        }
    }

    // ========== AI调度 ==========

    public void scheduleAISpeak(String roomId, String player, BiConsumer<String, Map<String, Object>> broadcaster) {
        new Thread(() -> {
            try {
                Thread.sleep(1000 + (long) (Math.random() * 1500));

                GameRoomManager.RoomData room = roomManager.getRoom(roomId);
                if (room == null || room.gameOver || room.status.equals("ended")) return;
                if (room.isSpoken(player) || room.isEliminated(player)) return;

                boolean isUndercover = room.isUndercover(player);
                String description = aiService.generateSpeak(isUndercover, room.playerWords.get(player), room.category, room.round);

                room.spoken.add(player);
                Map<String, Object> desc = new HashMap<>();
                desc.put("player", player);
                desc.put("content", description);
                desc.put("round", room.round);
                room.descriptions.add(desc);

                broadcaster.accept(roomId, Map.of("type", "speakSubmitted", "playerName", player, "content", description, "roomId", roomId, "isAI", true));
                log.info("[AI] [AI] {}: {}", player, description);

                afterSpeak(roomId, broadcaster);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    public void scheduleAIVotes(String roomId, BiConsumer<String, Map<String, Object>> broadcaster) {
        new Thread(() -> {
            try {
                Thread.sleep(2000);

                GameRoomManager.RoomData room = roomManager.getRoom(roomId);
                if (room == null || room.gameOver || room.status.equals("ended")) return;

                List<String> aiPlayers = new ArrayList<>();
                for (String p : room.getActivePlayers()) {
                    if (p.startsWith("AI-") && !room.isVoted(p)) aiPlayers.add(p);
                }

                for (String aiPlayer : aiPlayers) {
                    Thread.sleep(800 + (long) (Math.random() * 1000));
                    room = roomManager.getRoom(roomId);
                    if (room == null || room.gameOver || room.status.equals("ended")) break;
                    if (room.isVoted(aiPlayer)) continue;

                    String target = aiService.decideVote(room.players, room.eliminated, room.votes, aiPlayer, room.round);
                    if (target == null || target.equals(aiPlayer) || room.isEliminated(target)) {
                        List<String> targets = new ArrayList<>(room.getActivePlayers());
                        targets.remove(aiPlayer);
                        if (targets.isEmpty()) continue;
                        target = targets.get(new Random().nextInt(targets.size()));
                    }

                    room.voted.add(aiPlayer);
                    room.votes.put(aiPlayer, target);
                    broadcaster.accept(roomId, Map.of("type", "voteSubmitted", "playerName", aiPlayer, "target", target, "roomId", roomId, "isAI", true));
                    log.info("[AI] [AI] {} 投票给 {}", aiPlayer, target);

                    if (room.voted.size() >= room.getActivePlayers().size()) {
                        publishVoteResult(roomId, broadcaster);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private Map<String, Object> error(String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "error");
        result.put("message", msg);
        return result;
    }
}