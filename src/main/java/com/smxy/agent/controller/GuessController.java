package com.smxy.agent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/game/guess")
@CrossOrigin(origins = "*")
public class GuessController {

    // ========== 词库 ==========
    private static final String[] WORD_POOL = {
            "苹果", "香蕉", "橘子", "西瓜", "草莓", "葡萄", "芒果", "桃子", "梨子", "樱桃",
            "老虎", "狮子", "大象", "熊猫", "孔雀", "海豚", "鲸鱼", "企鹅", "骆驼", "长颈鹿",
            "电脑", "手机", "电视", "冰箱", "空调", "洗衣机", "微波炉", "打印机", "路由器", "电风扇",
            "医生", "教师", "律师", "会计", "护士", "工程师", "程序员", "设计师", "摄影师", "飞行员",
            "春天", "夏天", "秋天", "冬天", "四季", "雨水", "惊蛰", "谷雨", "小满", "芒种",
            "快乐", "悲伤", "愤怒", "平静", "激动", "温暖", "寒冷", "炎热", "凉爽", "宁静",
            "中国", "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "西安",
            "篮球", "足球", "网球", "乒乓", "游泳", "跑步", "登山", "瑜伽", "骑行", "滑雪"
    };

    // ========== 获取随机词语 ==========
    @GetMapping("/word")
    public Result<Map<String, Object>> getRandomWord() {
        try {
            String word = WORD_POOL[(int) (Math.random() * WORD_POOL.length)];
            log.info("[TARGET] 生成随机词: {}", word);

            Map<String, Object> response = new HashMap<>();
            response.put("word", word);
            response.put("length", word.length());

            return Result.success(response);
        } catch (Exception e) {
            log.error("生成随机词失败: {}", e.getMessage());
            return Result.error("生成失败");
        }
    }

    // ========== 猜词验证 ==========
    @PostMapping("/check")
    public Result<Map<String, Object>> checkGuess(@RequestBody GuessRequest request) {
        try {
            String guess = request.getGuess();
            String target = request.getTarget();

            if (guess == null || target == null) {
                return Result.badRequest("参数不完整");
            }

            if (guess.length() != target.length()) {
                return Result.error("字数不匹配，需要 " + target.length() + " 个字");
            }

            Map<String, Object> result = checkMatch(guess, target);
            log.info("[FIND] 猜词: {} -> {}", guess, result);

            return Result.success(result);
        } catch (Exception e) {
            log.error("猜词验证失败: {}", e.getMessage());
            return Result.error("验证失败: " + e.getMessage());
        }
    }

    // ========== 匹配逻辑 ==========
    private Map<String, Object> checkMatch(String guess, String target) {
        Map<String, Object> result = new HashMap<>();

        char[] guessChars = guess.toCharArray();
        char[] targetChars = target.toCharArray();

        boolean[] targetUsed = new boolean[targetChars.length];
        boolean[] guessUsed = new boolean[guessChars.length];

        int positionMatch = 0;
        int characterMatch = 0;

        // 先检查位置完全正确的
        for (int i = 0; i < Math.min(guessChars.length, targetChars.length); i++) {
            if (guessChars[i] == targetChars[i]) {
                positionMatch++;
                targetUsed[i] = true;
                guessUsed[i] = true;
            }
        }

        // 再检查字符正确但位置不对的
        for (int i = 0; i < guessChars.length; i++) {
            if (guessUsed[i]) continue;
            for (int j = 0; j < targetChars.length; j++) {
                if (targetUsed[j]) continue;
                if (guessChars[i] == targetChars[j]) {
                    characterMatch++;
                    targetUsed[j] = true;
                    guessUsed[i] = true;
                    break;
                }
            }
        }

        boolean isCorrect = guess.equals(target);

        // 生成提示
        String hint;
        if (isCorrect) {
            hint = "[WIN] 完全正确！太棒了！";
        } else if (positionMatch == target.length()) {
            hint = "✓ 所有字位置都正确！但字数不对？";
        } else if (positionMatch > 0 && characterMatch > 0) {
            hint = "✓ " + positionMatch + " 个字位置正确，" + characterMatch + " 个字正确但位置不对";
        } else if (positionMatch > 0) {
            hint = "✓ " + positionMatch + " 个字位置正确";
        } else if (characterMatch > 0) {
            hint = "> " + characterMatch + " 个字正确但位置不对";
        } else {
            hint = "✕ 没有匹配的字符，再想想其他词";
        }

        result.put("isCorrect", isCorrect);
        result.put("hint", hint);
        result.put("positionMatch", positionMatch);
        result.put("characterMatch", characterMatch);
        result.put("guess", guess);
        result.put("target", target);

        return result;
    }

    // ========== 获取提示（AI辅助） ==========
    @PostMapping("/hint")
    public Result<Map<String, String>> getHint(@RequestBody HintRequest request) {
        try {
            String guess = request.getGuess();
            String target = request.getTarget();

            if (guess == null || target == null) {
                return Result.badRequest("参数不完整");
            }

            // 生成AI提示
            String hint = generateAIHint(guess, target);

            Map<String, String> response = new HashMap<>();
            response.put("hint", hint);

            return Result.success(response);
        } catch (Exception e) {
            log.error("生成提示失败: {}", e.getMessage());
            return Result.error("生成提示失败");
        }
    }

    // ========== AI提示生成 ==========
    private String generateAIHint(String guess, String target) {
        // 基于匹配结果生成更智能的提示
        Map<String, Object> result = checkMatch(guess, target);
        boolean isCorrect = (boolean) result.get("isCorrect");
        int positionMatch = (int) result.get("positionMatch");
        int characterMatch = (int) result.get("characterMatch");

        if (isCorrect) {
            return "[WIN] 太棒了！你已经猜对了！";
        }

        // 生成分类提示
        String[] categories = {"水果", "动物", "电子产品", "职业", "季节", "心情", "城市", "运动"};
        String category = categories[(int) (Math.random() * categories.length)];

        if (positionMatch == 0 && characterMatch == 0) {
            return "> 完全不对，试试其他" + category + "相关的词";
        } else if (positionMatch > 0 && characterMatch == 0) {
            return "> 有 " + positionMatch + " 个字位置正确，继续想想！";
        } else if (positionMatch == 0 && characterMatch > 0) {
            return "> 有 " + characterMatch + " 个字是对的，但位置不对，调整一下顺序";
        } else {
            return "> 有 " + positionMatch + " 个字位置正确，" + characterMatch + " 个字位置不对，加油！";
        }
    }

    // ========== DTO类 ==========
    public static class GuessRequest {
        private String guess;
        private String target;

        public String getGuess() { return guess; }
        public void setGuess(String guess) { this.guess = guess; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
    }

    public static class HintRequest {
        private String guess;
        private String target;

        public String getGuess() { return guess; }
        public void setGuess(String guess) { this.guess = guess; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
    }
}