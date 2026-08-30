package com.smxy.agent.websocket;

import com.smxy.agent.llm.ZhipuService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
public class AIService {

    private final ZhipuService zhipuService;

    @Value("${ai.default-provider:mock}")
    private String defaultProvider;

    private boolean useRealLLM = false;
    private String currentProvider = "mock";

    // ========== 词性数据库 ==========

    private static class WordProperties {
        String category;
        List<String> adjectives;
        List<String> verbs;
        List<String> nouns;
        List<String> functions;
        List<String> scenes;

        WordProperties(String category) {
            this.category = category;
            this.adjectives = new ArrayList<>();
            this.verbs = new ArrayList<>();
            this.nouns = new ArrayList<>();
            this.functions = new ArrayList<>();
            this.scenes = new ArrayList<>();
        }
    }

    private final Map<String, WordProperties> wordPropertiesMap = new HashMap<>();

    // ========== 句法模板 ==========

    private static class SentenceTemplate {
        String structure;
        String style;
        int minRound;
        int maxRound;

        SentenceTemplate(String structure, String style, int minRound, int maxRound) {
            this.structure = structure;
            this.style = style;
            this.minRound = minRound;
            this.maxRound = maxRound;
        }
    }

    private final List<SentenceTemplate> civilianStructures = new ArrayList<>();
    private final List<SentenceTemplate> undercoverStructures = new ArrayList<>();

    private final List<String> emotionWords = new ArrayList<>();
    private final List<String> degreeWords = new ArrayList<>();
    private final List<String> conjunctions = new ArrayList<>();

    public AIService(ZhipuService zhipuService) {
        this.zhipuService = zhipuService;
    }

    @PostConstruct
    public void init() {
        // ===== 水果 =====
        WordProperties fruit = new WordProperties("水果");
        fruit.adjectives.addAll(Arrays.asList("新鲜", "香甜", "多汁", "可口", "诱人", "脆甜", "软糯", "清爽"));
        fruit.verbs.addAll(Arrays.asList("品尝", "食用", "购买", "采摘", "清洗", "削皮", "分享", "享用"));
        fruit.nouns.addAll(Arrays.asList("水果", "果肉", "味道", "口感", "果香", "甜味"));
        fruit.functions.addAll(Arrays.asList("补充营养", "解渴消暑", "提供能量", "制作甜点", "作为零食"));
        fruit.scenes.addAll(Arrays.asList("早餐", "下午茶", "聚会", "野餐", "饭后", "运动后"));
        wordPropertiesMap.put("水果", fruit);

        // ===== 动物 =====
        WordProperties animal = new WordProperties("动物");
        animal.adjectives.addAll(Arrays.asList("威猛", "灵动", "温顺", "可爱", "敏捷", "强壮", "优雅", "机敏"));
        animal.verbs.addAll(Arrays.asList("奔跑", "捕猎", "休息", "玩耍", "迁徙", "咆哮", "亲近"));
        animal.nouns.addAll(Arrays.asList("森林", "草原", "动物园", "领地", "种群", "栖息地"));
        animal.functions.addAll(Arrays.asList("生态平衡", "陪伴人类", "观赏价值", "科学研究", "保护意义"));
        animal.scenes.addAll(Arrays.asList("野外", "动物园", "纪录片", "自然", "保护区"));
        wordPropertiesMap.put("动物", animal);

        // ===== 电子产品 =====
        WordProperties electronic = new WordProperties("电子产品");
        electronic.adjectives.addAll(Arrays.asList("智能", "便捷", "高效", "轻薄", "强大", "省电", "高清", "流畅"));
        electronic.verbs.addAll(Arrays.asList("操作", "使用", "充电", "连接", "设置", "升级", "体验"));
        electronic.nouns.addAll(Arrays.asList("屏幕", "芯片", "电池", "系统", "应用", "网络"));
        electronic.functions.addAll(Arrays.asList("通讯联络", "娱乐休闲", "办公学习", "信息获取", "创作设计"));
        electronic.scenes.addAll(Arrays.asList("日常生活", "工作", "旅途中", "学习中", "娱乐时"));
        wordPropertiesMap.put("电子产品", electronic);

        // ===== 交通工具 =====
        WordProperties vehicle = new WordProperties("交通工具");
        vehicle.adjectives.addAll(Arrays.asList("快速", "舒适", "安全", "环保", "便捷", "平稳", "经济", "宽敞"));
        vehicle.verbs.addAll(Arrays.asList("驾驶", "乘坐", "出行", "停靠", "启动", "加速", "转弯"));
        vehicle.nouns.addAll(Arrays.asList("道路", "车站", "方向盘", "引擎", "交通", "旅程"));
        vehicle.functions.addAll(Arrays.asList("日常通勤", "长途旅行", "物流运输", "应急救援", "观光游览"));
        vehicle.scenes.addAll(Arrays.asList("上下班", "旅行", "出差", "接送", "货运"));
        wordPropertiesMap.put("交通工具", vehicle);

        // ===== 食物 =====
        WordProperties food = new WordProperties("食物");
        food.adjectives.addAll(Arrays.asList("美味", "营养", "丰富", "诱人", "暖和", "新鲜", "多样", "可口"));
        food.verbs.addAll(Arrays.asList("烹饪", "品尝", "享用", "准备", "烘焙", "调味", "分享"));
        food.nouns.addAll(Arrays.asList("餐桌", "厨房", "食材", "菜谱", "营养", "风味"));
        food.functions.addAll(Arrays.asList("补充能量", "享受美味", "社交聚餐", "文化传承", "疗愈身心"));
        food.scenes.addAll(Arrays.asList("家庭聚餐", "餐厅", "节日", "宴会", "日常"));
        wordPropertiesMap.put("食物", food);

        // ===== 职业 =====
        WordProperties profession = new WordProperties("职业");
        profession.adjectives.addAll(Arrays.asList("专业", "敬业", "严谨", "负责", "细致", "耐心", "高效", "创新"));
        profession.verbs.addAll(Arrays.asList("工作", "服务", "解决问题", "指导", "治疗", "设计", "管理"));
        profession.nouns.addAll(Arrays.asList("办公室", "医院", "学校", "客户", "设备", "文件"));
        profession.functions.addAll(Arrays.asList("救死扶伤", "传道授业", "创造价值", "维护正义", "服务社会"));
        profession.scenes.addAll(Arrays.asList("工作中", "会议", "培训", "咨询", "紧急情况"));
        wordPropertiesMap.put("职业", profession);

        // ===== 娱乐 =====
        WordProperties entertainment = new WordProperties("娱乐");
        entertainment.adjectives.addAll(Arrays.asList("精彩", "有趣", "轻松", "欢快", "感人", "刺激", "幽默", "动人"));
        entertainment.verbs.addAll(Arrays.asList("欣赏", "观看", "参与", "体验", "感受", "分享", "沉浸"));
        entertainment.nouns.addAll(Arrays.asList("舞台", "银幕", "观众", "旋律", "剧情", "现场"));
        entertainment.functions.addAll(Arrays.asList("放松心情", "增长见识", "情感共鸣", "社交互动", "文化体验"));
        entertainment.scenes.addAll(Arrays.asList("电影院", "剧院", "家中", "聚会", "音乐厅"));
        wordPropertiesMap.put("娱乐", entertainment);

        // ===== 地点 =====
        WordProperties place = new WordProperties("地点");
        place.adjectives.addAll(Arrays.asList("悠久", "宏伟", "宁静", "繁华", "壮丽", "神秘", "古老", "现代"));
        place.verbs.addAll(Arrays.asList("游览", "参观", "探访", "居住", "留影", "漫步", "探索"));
        place.nouns.addAll(Arrays.asList("建筑", "街道", "广场", "公园", "博物馆", "古迹"));
        place.functions.addAll(Arrays.asList("旅游观光", "文化交流", "历史见证", "商业活动", "休闲散步"));
        place.scenes.addAll(Arrays.asList("旅行", "学习", "购物", "约会", "日常"));
        wordPropertiesMap.put("地点", place);

        // ===== 颜色 =====
        WordProperties color = new WordProperties("颜色");
        color.adjectives.addAll(Arrays.asList("明亮", "柔和", "鲜艳", "温暖", "冷静", "深沉", "清新", "绚丽"));
        color.verbs.addAll(Arrays.asList("搭配", "绘制", "展示", "调和", "涂刷", "装饰", "点缀"));
        color.nouns.addAll(Arrays.asList("调色板", "彩虹", "面料", "画作", "自然", "服装"));
        color.functions.addAll(Arrays.asList("表达情感", "美化环境", "视觉传达", "文化象征", "艺术创作"));
        color.scenes.addAll(Arrays.asList("设计", "装修", "绘画", "时尚", "自然景观"));
        wordPropertiesMap.put("颜色", color);

        // ===== 句式模板 =====

        civilianStructures.add(new SentenceTemplate(
                "这个{名词}看起来{形容词}，{场景}的时候经常见到", "cautious", 1, 1
        ));
        civilianStructures.add(new SentenceTemplate(
                "我觉得这个{名词}挺{形容词}的，{动词}起来很方便", "cautious", 1, 1
        ));
        civilianStructures.add(new SentenceTemplate(
                "大家都知道这个{名词}，主要是用来{功能}的", "cautious", 1, 2
        ));
        civilianStructures.add(new SentenceTemplate(
                "我平时{动词}这个{名词}，感觉{形容词}而且实用", "normal", 2, 3
        ));
        civilianStructures.add(new SentenceTemplate(
                "这个{名词}最{形容词}的地方在于它能{功能}，我经常用它", "normal", 2, 3
        ));
        civilianStructures.add(new SentenceTemplate(
                "说到这个{名词}，我就想到{场景}的时候那种{形容词}的感觉", "normal", 2, 4
        ));
        civilianStructures.add(new SentenceTemplate(
                "这个{名词}有很多种，我比较喜欢那种{形容词}的，{功能}效果特别好", "aggressive", 4, -1
        ));
        civilianStructures.add(new SentenceTemplate(
                "我觉得这个{名词}是生活中不可缺少的，不管在{场景}还是{场景2}都很有用", "aggressive", 4, -1
        ));
        civilianStructures.add(new SentenceTemplate(
                "这个{名词}的{形容词}程度真的很高，我{动词}过很多次了", "aggressive", 5, -1
        ));

        undercoverStructures.add(new SentenceTemplate(
                "这个{名词}我好像听说过，但具体{功能}不太清楚", "cautious", 1, 1
        ));
        undercoverStructures.add(new SentenceTemplate(
                "这个{名词}和另一个东西有点像，我经常搞混它们", "cautious", 1, 1
        ));
        undercoverStructures.add(new SentenceTemplate(
                "哦，这个{名词}啊，我印象中应该是{形容词}的东西", "cautious", 1, 2
        ));
        undercoverStructures.add(new SentenceTemplate(
                "这个{名词}确实挺{形容词}的，不过我见过的可能不太一样", "normal", 2, 3
        ));
        undercoverStructures.add(new SentenceTemplate(
                "说到{场景}的时候用这个{名词}，应该还不错", "normal", 2, 3
        ));
        undercoverStructures.add(new SentenceTemplate(
                "这个{名词}的{功能}功能还可以，但我更常用另一种", "normal", 2, 4
        ));
        undercoverStructures.add(new SentenceTemplate(
                "这个{名词}在一些场合还是挺重要的，比如{场景}的时候", "aggressive", 4, -1
        ));
        undercoverStructures.add(new SentenceTemplate(
                "我觉得这个{名词}的{形容词}之处在于它和常规的不太一样", "aggressive", 4, -1
        ));
        undercoverStructures.add(new SentenceTemplate(
                "虽然大家说这个{名词}很{形容词}，但我总觉得它有点特别", "aggressive", 5, -1
        ));

        emotionWords.addAll(Arrays.asList(
                "挺", "非常", "特别", "真的", "确实", "感觉", "觉得", "好像", "大概", "也许",
                "其实", "反正", "说实话", "我觉得", "我认为", "大家都知道", "我猜"
        ));

        degreeWords.addAll(Arrays.asList(
                "很", "非常", "特别", "超级", "相当", "比较", "有点", "稍微", "极其", "格外"
        ));

        conjunctions.addAll(Arrays.asList(
                "而且", "但是", "因为", "所以", "虽然", "不过", "于是", "然后", "其实", "毕竟"
        ));

        useRealLLM = !"mock".equalsIgnoreCase(defaultProvider);
        currentProvider = defaultProvider;

        log.info("✓ AI服务初始化完成, 模式: {}, 词性类别: {}, 平民句式: {}, 卧底句式: {}",
                useRealLLM ? "真实LLM(" + currentProvider + ")" : "Mock",
                wordPropertiesMap.size(),
                civilianStructures.size(),
                undercoverStructures.size()
        );
    }

    // ========== 核心方法 ==========

    public String generateSpeak(boolean isUndercover, String word, String category, int round) {
        // ✓ 优先使用智谱AI
        if (useRealLLM && zhipuService != null) {
            try {
                String systemPrompt = buildSpeakSystemPrompt(isUndercover, category);
                String userPrompt = String.format("我的词语是：%s，当前是第%d轮。请用一句简短自然的中文描述这个词语。", word, round);
                String result = zhipuService.chatSync(systemPrompt, userPrompt);
                if (result != null && !result.isEmpty() && !result.contains("失败")) {
                    log.info("[AI] 智谱AI生成发言: {}", result);
                    return result;
                }
            } catch (Exception e) {
                log.warn("[!] 智谱AI调用失败，降级到Mock: {}", e.getMessage());
            }
        }
        // 降级到Mock
        return generateWithMock(isUndercover, round);
    }

    public String decideVote(List<String> players, List<String> eliminated,
                             Map<String, String> votes, String selfPlayer, int round) {
        // ✓ 优先使用智谱AI
        if (useRealLLM && zhipuService != null) {
            try {
                String systemPrompt = "你是一个'谁是卧底'游戏的AI玩家。你清楚自己手中的词和自己的身份。请根据各玩家发言与自己词的关联度、与其他玩家描述的一致性推理谁最可能是卧底，选择最可疑的玩家。只返回玩家名字，不要其他内容。";
                String userPrompt = String.format(
                        "存活玩家：%s，已淘汰玩家：%s，当前轮次：%d。请选择一名最可能是卧底的玩家。",
                        String.join(", ", players), String.join(", ", eliminated), round
                );
                String result = zhipuService.chatSync(systemPrompt, userPrompt);
                if (result != null && !result.isEmpty()) {
                    // 清理结果，只保留玩家名字
                    String votedPlayer = result.trim().replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9-]", "");
                    if (players.contains(votedPlayer) && !eliminated.contains(votedPlayer) && !votedPlayer.equals(selfPlayer)) {
                        log.info("[AI] 智谱AI投票决策: {}", votedPlayer);
                        return votedPlayer;
                    }
                }
            } catch (Exception e) {
                log.warn("[!] 智谱AI投票失败，降级到Mock: {}", e.getMessage());
            }
        }
        // 降级到Mock
        return decideVoteWithMock(players, eliminated, selfPlayer);
    }

    public String generateVoteReason(String target, int round) {
        if (useRealLLM && zhipuService != null) {
            try {
                String systemPrompt = "你是一个'谁是卧底'游戏的AI玩家。请基于刚才的发言风格与线索，用一句话给出自然的口语化投票理由，不要提及自己是AI。";
                String userPrompt = String.format("我投票给 %s，因为我觉得他是卧底。请帮我生成一句自然的投票理由。", target);
                String result = zhipuService.chatSync(systemPrompt, userPrompt);
                if (result != null && !result.isEmpty() && !result.contains("失败")) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("[!] 智谱AI生成投票理由失败，降级到Mock: {}", e.getMessage());
            }
        }
        return generateDynamicVoteReason(target, round);
    }

    // ========== 构建提示词 ==========

    private String buildSpeakSystemPrompt(boolean isUndercover, String category) {
        String role = isUndercover ? "卧底" : "平民";
        String extra = isUndercover ?
                "你是卧底，你的词语与平民不同。你要完美伪装成平民：描述要贴合你自己的词、像平民一样自然自信，绝不能直接说出自己的词，也绝不能提到平民词的明显同义词，不能自曝身份。" :
                "你是平民，你的词语是大家都知道的。忠于自己的词，描述与词强关联、真实自然但适度模糊，绝不能直接说出词语本身，也不要用一眼能猜中的同义词。";
        return String.format(
                "你是一个'谁是卧底'游戏的AI玩家。你的身份是【%s】，词语类别是【%s】。\n" +
                        "%s\n" +
                        "请用一句口语化、自然的中文描述你的词语，15-40字，不能直接说出词语本身。只返回描述内容。",
                role, category, extra
        );
    }

    // ========== Mock实现 ==========

    private String generateWithMock(boolean isUndercover, int round) {
        List<SentenceTemplate> templates = isUndercover ? undercoverStructures : civilianStructures;

        List<SentenceTemplate> suitable = new ArrayList<>();
        for (SentenceTemplate t : templates) {
            if (round >= t.minRound && (t.maxRound == -1 || round <= t.maxRound)) {
                suitable.add(t);
            }
        }

        if (suitable.isEmpty()) {
            suitable.add(templates.get(templates.size() - 1));
        }

        SentenceTemplate selected = suitable.get(
                ThreadLocalRandom.current().nextInt(suitable.size())
        );

        // 获取一个随机类别
        List<String> keys = new ArrayList<>(wordPropertiesMap.keySet());
        String categoryKey = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
        WordProperties props = wordPropertiesMap.get(categoryKey);

        String adj = randomFromList(props.adjectives);
        String verb = randomFromList(props.verbs);
        String noun = randomFromList(props.nouns);
        String func = randomFromList(props.functions);
        String scene1 = randomFromList(props.scenes);
        String scene2 = randomFromList(props.scenes);
        String emotion = randomFromList(emotionWords);
        String degree = randomFromList(degreeWords);
        String conj = randomFromList(conjunctions);

        String result = selected.structure
                .replace("{形容词}", adj)
                .replace("{动词}", verb)
                .replace("{名词}", noun)
                .replace("{功能}", func)
                .replace("{场景}", scene1)
                .replace("{场景2}", scene2 != null ? scene2 : scene1)
                .replace("{情感词}", emotion)
                .replace("{程度词}", degree)
                .replace("{连接词}", conj);

        if (isUndercover && ThreadLocalRandom.current().nextBoolean()) {
            String[] hesitations = {"嗯...", "怎么说呢...", "让我想想...", "其实..."};
            result = hesitations[ThreadLocalRandom.current().nextInt(hesitations.length)] + result;
        }

        String[] suffixes = {
                "，大概就是这样",
                "，你们觉得呢",
                "，反正我是这么想的",
                "，应该没错吧",
                "，差不多就是这个意思",
                "，你们说是不是",
                "，这很难描述清楚"
        };
        String suffix = suffixes[ThreadLocalRandom.current().nextInt(suffixes.length)];

        if (ThreadLocalRandom.current().nextBoolean() && result.length() < 30) {
            String[] starters = {"其实", "我觉得", "说实话", "大家都知道", "我个人的感觉是"};
            result = starters[ThreadLocalRandom.current().nextInt(starters.length)] + "，" + result;
        }

        return result + suffix;
    }

    private String decideVoteWithMock(List<String> players, List<String> eliminated,
                                      String selfPlayer) {
        List<String> targets = new ArrayList<>(players);
        targets.remove(selfPlayer);
        targets.removeAll(eliminated);

        if (targets.isEmpty()) {
            targets = new ArrayList<>(players);
            targets.remove(selfPlayer);
        }

        return targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
    }

    private String generateDynamicVoteReason(String target, int round) {
        String[] templates = {
                "我觉得{target}的发言有点可疑，和其他人不太一致",
                "我怀疑{target}是卧底，他的描述{程度词}模糊",
                "{target}的发言和其他人不太一样，显得{程度词}刻意",
                "我注意到{target}一直在回避具体描述，{连接词}这很可疑",
                "{target}好像不太了解这个东西，{连接词}可能是卧底",
                "凭直觉，我觉得{target}有问题，他的{形容词}描述太刻意了",
                "{target}的描述太模糊了，像是在掩饰什么，{连接词}我投他",
                "我对比了大家的发言，{target}最可疑，{连接词}我选择他"
        };

        String[] adjs = {"非常", "特别", "比较", "相当", "有点"};
        String[] degs = {"非常", "特别", "比较", "相当", "有点"};
        String[] cons = {"所以", "因此", "而且", "因为", "于是"};

        String template = templates[ThreadLocalRandom.current().nextInt(templates.length)];

        return template
                .replace("{target}", target)
                .replace("{形容词}", adjs[ThreadLocalRandom.current().nextInt(adjs.length)])
                .replace("{程度词}", degs[ThreadLocalRandom.current().nextInt(degs.length)])
                .replace("{连接词}", cons[ThreadLocalRandom.current().nextInt(cons.length)]);
    }

    private String randomFromList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "东西";
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}