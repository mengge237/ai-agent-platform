package com.smxy.agent.controller;

import com.smxy.agent.game.GameSessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 拆弹专家 —— 纯逻辑玩法，无需 AI。
 * 炸弹的引爆密码藏在 (low, high) 区间内，玩家只有有限次剪线机会：
 * 剪断密码线（猜中密码）即拆弹成功获胜；剪线次数耗尽则炸弹引爆失败。
 * 区间内还随机埋有阻碍线：干扰线（剪断不提供区间信息）与熔断线（剪断短路、
 * 额外消耗 1 次剪线）—— 单纯二分法不再是无风险必赢。
 */
@Slf4j
@RestController
@RequestMapping("/api/game/bomb")
@CrossOrigin(origins = "*")
public class BombController {

    /** 对局 30 分钟过期 */
    private final GameSessionStore<BombGame> sessions = new GameSessionStore<>(30 * 60 * 1000L);

    // ========== 开始对局 ==========
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody(required = false) BombStartRequest request) {
        try {
            int min = request == null || request.getMin() == null ? 1 : request.getMin();
            int max = request == null || request.getMax() == null ? 100 : request.getMax();

            if (min >= max - 1) {
                return Result.badRequest("范围不合法，需要 max 至少比 min 大 2");
            }

            String gameId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            // 引爆密码藏在开区间 (min, max) 内
            int code = min + 1 + (int) (Math.random() * (max - min - 1));

            // 阻碍线数量：按难度缩放（easy 无阻碍，normal 标准密度，hard 密度翻倍）
            String difficulty = request != null && request.getDifficulty() != null ? request.getDifficulty() : "normal";
            if (!"easy".equals(difficulty) && !"hard".equals(difficulty)) difficulty = "normal";
            int range = max - min - 1;
            int available = range - 1;
            int decoyCount;
            int fuseCount;
            if ("easy".equals(difficulty)) {
                decoyCount = 0;
                fuseCount = 0;
            } else {
                int density = "hard".equals(difficulty) ? 10 : 20;
                decoyCount = available <= 0 ? 0 : Math.min(Math.max(2, range / density), available * 2 / 3);
                fuseCount = Math.min(Math.max(1, range / (density * 2)), available - decoyCount);
            }

            // 随机布置阻碍线：不落在密码线上，互不重复
            Random rnd = new Random();
            Set<Integer> decoys = new HashSet<>();
            Set<Integer> fuses = new HashSet<>();
            for (int i = 0; i < decoyCount; i++) decoys.add(pickWire(rnd, min, max, code, decoys, fuses));
            for (int i = 0; i < fuseCount; i++) fuses.add(pickWire(rnd, min, max, code, decoys, fuses));

            // 剪线上限：二分最优次数 + 阻碍半补偿 —— 命中全部阻碍即引爆，纯二分不再稳赢
            int maxCuts = Math.max(4, Math.min(16,
                    (int) Math.ceil(Math.log(range) / Math.log(2))
                            + (decoyCount + fuseCount + 1) / 2));

            sessions.put(gameId, new BombGame(code, min, max, maxCuts, decoys, fuses));
            log.info("[BOMB] 拆弹开局: {} 难度={} 密码区间({},{}) 剪线上限={} 干扰线={} 熔断线={}",
                    gameId, difficulty, min, max, maxCuts, decoyCount, fuseCount);

            Map<String, Object> data = new HashMap<>();
            data.put("gameId", gameId);
            data.put("low", min);
            data.put("high", max);
            data.put("range", max - min);
            data.put("maxCuts", maxCuts);
            data.put("difficulty", difficulty);
            data.put("decoyCount", decoyCount);
            data.put("fuseCount", fuseCount);
            return Result.success(data);
        } catch (Exception e) {
            log.error("拆弹开局失败: {}", e.getMessage());
            return Result.error("开局失败");
        }
    }

    // ========== 剪线 ==========
    @PostMapping("/guess")
    public Result<Map<String, Object>> guess(@RequestBody BombGuessRequest request) {
        try {
            if (request == null || request.getGameId() == null || request.getNumber() == null) {
                return Result.badRequest("参数不完整");
            }

            BombGame game = sessions.get(request.getGameId());
            if (game == null) {
                return Result.notFound("对局不存在或已过期，请重新开始");
            }

            int number = request.getNumber();
            if (number <= game.low || number >= game.high) {
                return Result.badRequest("线号必须在 " + game.low + " 和 " + game.high + " 之间");
            }

            game.attempts++;
            Map<String, Object> data = new HashMap<>();
            data.put("number", number);
            data.put("attempts", game.attempts);
            data.put("cutsLeft", Math.max(0, game.maxCuts - game.attempts));

            // 剪断密码线 → 拆弹成功
            if (number == game.code) {
                sessions.remove(request.getGameId());
                data.put("result", "DEFUSED");
                data.put("code", game.code);
                log.info("[BOMB] 拆弹成功: 对局{} 第{}次剪中密码线", request.getGameId(), game.attempts);
                return Result.success(data);
            }

            // 干扰线：剪断无区间信息，仅消耗 1 次剪线并公开该线
            if (game.decoys.remove(number)) {
                data.put("low", game.low);
                data.put("high", game.high);
                data.put("decoy", number);
                if (game.attempts >= game.maxCuts) {
                    sessions.remove(request.getGameId());
                    data.put("result", "BOOM");
                    data.put("code", game.code);
                    log.info("[BOMB] 剪中干扰线后倒计时耗尽: 对局{} 线号={} 密码={}",
                            request.getGameId(), number, game.code);
                } else {
                    data.put("result", "DECOY");
                    log.info("[BOMB] 剪中干扰线: 对局{} 线号={} 区间不变 剩余{}次",
                            request.getGameId(), number, game.maxCuts - game.attempts);
                }
                return Result.success(data);
            }

            // 安全线/熔断线：收窄密码区间；熔断线短路额外消耗 1 次剪线
            boolean fuseHit = game.fuses.remove(number);
            if (number < game.code) {
                game.low = number;
            } else {
                game.high = number;
            }
            if (fuseHit) {
                game.attempts++;
                data.put("attempts", game.attempts);
                data.put("cutsLeft", Math.max(0, game.maxCuts - game.attempts));
                data.put("fuse", number);
            }
            data.put("low", game.low);
            data.put("high", game.high);

            // 剪线次数耗尽 → 炸弹引爆
            if (game.attempts >= game.maxCuts) {
                sessions.remove(request.getGameId());
                data.put("result", "BOOM");
                data.put("code", game.code);
                log.info("[BOMB] 倒计时耗尽: 对局{} 第{}次 密码={}",
                        request.getGameId(), game.attempts, game.code);
            } else {
                data.put("result", fuseHit ? "FUSE" : "SAFE");
                log.info("[BOMB] 剪{}: 对局{} 线号={} 密码区间[{},{}] 剩余{}次",
                        fuseHit ? "中熔断线" : "安全线", request.getGameId(), number,
                        game.low, game.high, game.maxCuts - game.attempts);
            }

            return Result.success(data);
        } catch (Exception e) {
            log.error("剪线失败: {}", e.getMessage());
            return Result.error("剪线失败");
        }
    }

    // ========== 对局状态 ==========
    private static class BombGame {
        final int code;
        final int maxCuts;
        final Set<Integer> decoys;   // 干扰线：剪断无区间信息
        final Set<Integer> fuses;    // 熔断线：剪断额外消耗 1 次
        int low;
        int high;
        int attempts;

        BombGame(int code, int low, int high, int maxCuts, Set<Integer> decoys, Set<Integer> fuses) {
            this.code = code;
            this.low = low;
            this.high = high;
            this.maxCuts = maxCuts;
            this.decoys = decoys;
            this.fuses = fuses;
        }
    }

    // ========== 辅助 ==========
    /** 随机挑一根阻碍线：不落在密码线上，且与其他阻碍线互不重复 */
    private int pickWire(Random rnd, int min, int max, int code, Set<Integer> decoys, Set<Integer> fuses) {
        int w;
        do {
            w = min + 1 + rnd.nextInt(max - min - 1);
        } while (w == code || decoys.contains(w) || fuses.contains(w));
        return w;
    }

    // ========== DTO ==========
    public static class BombStartRequest {
        private Integer min;
        private Integer max;
        private String difficulty;

        public Integer getMin() { return min; }
        public void setMin(Integer min) { this.min = min; }
        public Integer getMax() { return max; }
        public void setMax(Integer max) { this.max = max; }
        public String getDifficulty() { return difficulty; }
        public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    }

    public static class BombGuessRequest {
        private String gameId;
        private Integer number;

        public String getGameId() { return gameId; }
        public void setGameId(String gameId) { this.gameId = gameId; }
        public Integer getNumber() { return number; }
        public void setNumber(Integer number) { this.number = number; }
    }
}
