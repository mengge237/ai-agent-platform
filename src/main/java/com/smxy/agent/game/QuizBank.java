package com.smxy.agent.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 趣味问答本地题库（类别 / 题目 / 选项 / 正确索引 / 解析）。
 * 供单机 QuizController 与联机 QuizRoomLogic 共用。
 */
public class QuizBank {

    public static class Question {
        public final String category;
        public final String question;
        public final String[] options;
        public final int answerIndex;
        public final String explanation;

        public Question(String category, String question, String[] options, int answerIndex, String explanation) {
            this.category = category;
            this.question = question;
            this.options = options;
            this.answerIndex = answerIndex;
            this.explanation = explanation;
        }
    }

    private static final Object[][] LOCAL_BANK = {
            {"AI知识", "大语言模型中的 'LLM' 是什么缩写？", new String[]{"Large Language Model", "Low Level Machine", "Logical Learning Module", "Linked Language Matrix"}, 0, "LLM = Large Language Model，即大语言模型。"},
            {"AI知识", "AlphaGo 首次战胜人类顶级围棋选手是在哪一年？", new String[]{"2014", "2016", "2018", "2020"}, 1, "2016年 AlphaGo 击败李世石。"},
            {"AI知识", "'机器学习'中的'过拟合'（Overfitting）指的是？", new String[]{"模型在训练集表现好但泛化差", "模型训练时间过长", "模型参数过多导致内存溢出", "模型在测试集表现太好"}, 0, "过拟合指模型死记训练数据，对新数据表现差。"},
            {"AI知识", "让计算机理解人类语言的研究领域叫？", new String[]{"NLP 自然语言处理", "CPU 中央处理", "GPS 全球定位", "OCR 文字识别"}, 0, "NLP = Natural Language Processing。"},
            {"科学常识", "光在真空中的传播速度约为？", new String[]{"30万公里/秒", "3万公里/秒", "300万公里/秒", "3000公里/秒"}, 0, "约 299,792,458 米/秒。"},
            {"科学常识", "人体最大的器官是？", new String[]{"肝脏", "皮肤", "大脑", "肺"}, 1, "皮肤是人体最大的器官。"},
            {"科学常识", "水的化学式是？", new String[]{"H2O2", "CO2", "H2O", "O2"}, 2, "水是 H2O。"},
            {"科学常识", "地球自转一圈大约需要？", new String[]{"12小时", "24小时", "365天", "30天"}, 1, "约 23小时56分，通常说 24 小时。"},
            {"脑筋急转弯", "什么动物你打死了它，却流了你的血？", new String[]{"蟑螂", "蚊子", "苍蝇", "蜜蜂"}, 1, "蚊子吸了你的血。"},
            {"脑筋急转弯", "什么东西天气越热它爬得越高？", new String[]{"爬山的人", "温度计", "气球", "太阳"}, 1, "温度计里的水银柱。"},
            {"脑筋急转弯", "什么东西越洗越脏？", new String[]{"毛巾", "水", "衣服", "肥皂"}, 1, "水越洗越脏。"},
            {"传统文化", "'床前明月光'的下一句是？", new String[]{"低头思故乡", "疑是地上霜", "举头望明月", "处处闻啼鸟"}, 1, "李白《静夜思》：床前明月光，疑是地上霜。"},
            {"传统文化", "十二生肖中排在第一的是？", new String[]{"龙", "虎", "鼠", "牛"}, 2, "子鼠排第一。"},
            {"传统文化", "'但愿人长久，千里共婵娟'出自哪位词人？", new String[]{"李白", "苏轼", "李清照", "辛弃疾"}, 1, "苏轼《水调歌头·明月几时有》。"},
            {"传统文化", "中国四大发明不包括？", new String[]{"造纸术", "指南针", "地动仪", "印刷术"}, 2, "地动仪是张衡的发明，不属于四大发明。"}
    };

    /** 按类别取题（category 为 null/空 表示不限），随机打乱，最多 count 题 */
    public static List<Question> pickRandom(String category, int count) {
        List<Question> pool = new ArrayList<>();
        for (Object[] q : LOCAL_BANK) {
            if (category == null || category.isEmpty() || category.equals(q[0])) {
                pool.add(new Question((String) q[0], (String) q[1], (String[]) q[2], (int) q[3], (String) q[4]));
            }
        }
        Collections.shuffle(pool);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    /** 供 room 逻辑生成 LLM 题目的合格题目对象 */
    public static Question of(String category, String question, String[] options, int answerIndex, String explanation) {
        return new Question(category, question, options, answerIndex, explanation);
    }
}
