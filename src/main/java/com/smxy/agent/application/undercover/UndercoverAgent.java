package com.smxy.agent.application.undercover;

import com.smxy.agent.core.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Random;

@Slf4j
@Component
public class UndercoverAgent extends BaseAgent {

    private final Random random = new Random();
    private String myWord;
    private String targetWord;
    private boolean isUndercover;

    // 使用3参数构造函数，传入固定ID
    public UndercoverAgent() {
        super("卧底游戏助手", "GAME", "undercover_agent");
    }

    @PostConstruct
    public void init() {
        this.start();
    }

    public void setGameInfo(String myWord, String targetWord, boolean isUndercover) {
        this.myWord = myWord;
        this.targetWord = targetWord;
        this.isUndercover = isUndercover;
        log.info("[GAME] 游戏信息 - 我的词:{}, 目标词:{}, 是否卧底:{}", myWord, targetWord, isUndercover);
    }

    public String generateDescription() {
        if (llmProvider != null) {
            String prompt = String.format("""
                你是一个玩家，正在玩"谁是卧底"游戏。
                你拿到的词语是：「%s」。
                请用一句话描述这个词语，要求：
                1. 不能直接说出词语中的字
                2. 描述要模糊但又有指向性
                3. 如果你是卧底（目标词是「%s」），要尽量模仿平民的描述方式
                请只输出描述内容，不要有其他解释。
                """, myWord, targetWord);

            try {
                return llmProvider.chat(prompt).join();
            } catch (Exception e) {
                log.warn("LLM调用失败，使用模拟描述");
            }
        }
        return generateMockDescription();
    }

    public String analyzeAndVote(String discussion) {
        if (llmProvider != null) {
            String prompt = String.format("""
                你是一个玩家，正在玩"谁是卧底"游戏。
                你的词语是：「%s」
                你%s卧底。
                其他玩家的发言内容：%s
                请分析谁最可能是卧底，只输出玩家编号（如：玩家1、玩家2等）。
                """, myWord, isUndercover ? "是" : "不是", discussion);

            try {
                return llmProvider.chat(prompt).join();
            } catch (Exception e) {
                log.warn("LLM调用失败，使用模拟投票");
            }
        }
        return "玩家" + (random.nextInt(5) + 1);
    }

    private String generateMockDescription() {
        String[] templates = {
                "这个东西在日常生活中很常见",
                "它给我的感觉很不错",
                "我觉得它很有用",
                "这个东西有独特的特点"
        };

        if (isUndercover) {
            return "这个东西挺有意思的，大家应该都见过";
        } else {
            if (myWord != null && myWord.length() > 2) {
                return String.format("这个东西和'%s'有关", myWord.substring(0, Math.min(2, myWord.length())));
            }
            return templates[random.nextInt(templates.length)];
        }
    }

    @Override
    protected String doProcess(String message, Map<String, Object> context) {
        String action = (String) context.getOrDefault("action", "describe");

        if ("describe".equals(action)) {
            return generateDescription();
        } else if ("vote".equals(action)) {
            String discussion = (String) context.getOrDefault("discussion", "");
            return analyzeAndVote(discussion);
        }
        return "我是卧底游戏助手，当前状态：" + (isUndercover ? "我是卧底" : "我是平民");
    }
}