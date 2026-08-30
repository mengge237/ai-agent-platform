package com.smxy.agent.game;

import java.util.ArrayList;
import java.util.List;

/**
 * 海龟汤本地经典题库（汤面 / 汤底 / 关键词）。
 * 供单机 TurtleController 与联机 TurtleRoomLogic 共用。
 */
public class TurtleBank {

    public static class TurtlePuzzle {
        public final String story;
        public final String answer;
        public final String[] keywords;

        public TurtlePuzzle(String story, String answer, String[] keywords) {
            this.story = story;
            this.answer = answer;
            this.keywords = keywords;
        }
    }

    private static final String[][] LOCAL_BANK = {
            {"一名男子走进一家餐厅，点了一份海龟汤。他尝了一口后脸色大变，立刻回家自杀了。为什么？",
                    "多年前他和同伴遭遇海难流落荒岛，同伴煮了一碗'海龟汤'救了他的命。今天他尝到真正的海龟汤，发现味道完全不同，意识到当年吃下的其实是同伴的肉，无法承受而自杀。",
                    "海难,荒岛,同伴,海龟汤,味道"},
            {"一名女子住在15楼，每天上班坐电梯下楼。但下班回家时，她只坐到10楼，然后走楼梯上到15楼。为什么？",
                    "女子个子太矮，只能按到10楼的按钮。只有下雨天带伞时，她才能用伞柄按到15楼。",
                    "个子矮,按钮,10楼,下雨,伞"},
            {"雪地里发现一具男尸，周围只有他自己的一串脚印，没有其他痕迹。他是怎么死的？",
                    "男子乘坐的热气球超重即将坠落，众人抽签决定谁跳下去。他抽中短签跳下热气球摔死，所以雪地上只有他一个人的脚印。",
                    "热气球,抽签,超重,跳下"},
            {"男子跳入湖中救溺水的女友，却摸到了'水草'，于是放弃救援游回岸上。多年后他得知湖里根本没有水草，悔恨不已。为什么？",
                    "他摸到的不是水草，而是女友的长发。女友被水草缠住沉在湖底，他错过了救她的机会。",
                    "头发,水草,缠住,湖底"},
            {"沙漠中一行人艰难求生，有人在地上发现了半根火柴，大家看到后愤怒地杀死了其中一名同伴。为什么？",
                    "他们乘坐热气球遇险需要减重，约定抽火柴签、抽到短的跳下去。有人把火柴折成两半让某人必输。发现半根火柴证明抽签被作弊，大家杀死了作弊者。",
                    "热气球,抽签,作弊,火柴"},
            {"母亲去世后，妹妹在葬礼上对一名陌生男子一见钟情。几天后，妹妹杀死了自己的姐姐。为什么？",
                    "妹妹以为再办一次葬礼就能再见到那名男子，于是杀死了姐姐。",
                    "葬礼,男子,再见,姐姐"}
    };

    public static List<TurtlePuzzle> all() {
        List<TurtlePuzzle> list = new ArrayList<>();
        for (String[] row : LOCAL_BANK) {
            list.add(new TurtlePuzzle(row[0], row[1], row[2].split(",")));
        }
        return list;
    }

    public static TurtlePuzzle pickRandom() {
        String[] local = LOCAL_BANK[(int) (Math.random() * LOCAL_BANK.length)];
        return new TurtlePuzzle(local[0], local[1], local[2].split(","));
    }
}
