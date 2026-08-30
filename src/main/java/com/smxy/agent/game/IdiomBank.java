package com.smxy.agent.game;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 成语本地库（AI 离线时的校验/接龙兜底）。
 * 供单机 IdiomController 与联机 IdiomRoomLogic 共用。
 */
public class IdiomBank {

    public static final List<String> IDIOMS = Arrays.asList(
            "一心一意", "意气风发", "发奋图强", "强词夺理", "理直气壮", "壮志凌云", "云开雾散",
            "三心二意", "意味深长", "长驱直入", "入木三分", "分秒必争", "争先恐后", "后来居上",
            "上行下效", "勇往直前", "前赴后继", "继往开来", "来龙去脉", "明察秋毫", "毫发无损",
            "损人利己", "惊天动地", "地大物博", "博学多才", "才高八斗", "斗转星移", "移花接木",
            "木已成舟", "画蛇添足", "足智多谋", "谋事在人", "人定胜天", "天马行空", "空穴来风",
            "风和日丽", "井底之蛙", "龙飞凤舞", "舞文弄墨", "墨守成规", "虎头蛇尾", "百发百中",
            "中西合璧", "花好月圆", "圆木警枕", "枕戈待旦", "祸从口出", "出类拔萃", "出人意料",
            "料事如神", "神出鬼没", "没齿难忘", "忘恩负义", "义不容辞", "辞旧迎新", "新陈代谢",
            "画龙点睛", "惊弓之鸟", "鸟语花香", "百花齐放", "放虎归山", "山清水秀", "秀外慧中",
            "心花怒放", "苦尽甘来", "手舞足蹈", "胸有成竹", "津津有味", "举世闻名", "名不虚传"
    );

    public static boolean contains(String idiom) {
        return IDIOMS.contains(idiom);
    }

    public static String random() {
        return IDIOMS.get((int) (Math.random() * IDIOMS.size()));
    }

    public static String lastCharOf(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(s.length() - 1);
    }

    /** 从本地库找以 lastChar 开头、且未使用过的成语；找不到返回 null */
    public static String findNext(String lastChar, Set<String> used) {
        for (String local : IDIOMS) {
            if (local.startsWith(lastChar) && (used == null || !used.contains(local))) {
                return local;
            }
        }
        return null;
    }
}
