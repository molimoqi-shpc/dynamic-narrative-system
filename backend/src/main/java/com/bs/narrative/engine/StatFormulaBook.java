package com.bs.narrative.engine;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.engine.enums.StatKey;
import java.util.*;

/** Player-facing formulas and worked values, using the exact engine coefficients. */
public final class StatFormulaBook {
    private StatFormulaBook() {}
    public static Map<String,Object> describe(GodProfile profile) {
        var values=StatsCalculator.calcActualStats(profile);
        Map<String,Object> book=new LinkedHashMap<>();
        book.put("version",StatsCalculator.FORMULA_VERSION);
        book.put("title","六维公式 V2 · 成长、感悟与协同");
        book.put("references",List.of(
                Map.of("title","D&D：基础能力、修正与检定","url","https://www.dndbeyond.com/sources/dnd/br-2024/playing-the-game"),
                Map.of("title","博德之门 3：对话检定与玩法平衡","url","https://baldursgate3.game/news/community-update-11-inspiration-freedom-pacifism_17"),
                Map.of("title","极乐迪斯科：技能协作与叙事战斗","url","https://discoelysium.com/devblog/2018/05/03/combat-in-disco-elysium")));
        book.put("common",List.of(
                "基础值小于 0 按 0；本上限 100，其余基础值上限 10000。只在最终实际值处四舍五入。",
                "C(x)=x（x≤100）；C(x)=100+50×ln(1+(x−100)/50)（x>100）。ln 为自然对数，高属性仍增长，但收益递减。",
                "M(本)=1+"+StatsCalculator.MASTERY_WEIGHT+"×本/(本+"+StatsCalculator.SUPPORT_SCALE+")。根基加成上限 1.4 倍。",
                "H(u,v)=2×C(u)×C(v)/(C(u)+C(v))；分母为 0 时 H=0。",
                "S(u,v)=H(u,v)/(H(u,v)+"+StatsCalculator.SUPPORT_SCALE+")。协同介于 0 与 1 之间；任一辅助基础值为 0 时协同为 0。",
                "本与归不乘 M。本是能力根基，归是忠诚意志，不代表财富或诡计。"));
        List<Map<String,Object>> stats=new ArrayList<>();
        for(StatKey key:StatKey.values()) {
            var pair=StatsCalculator.supportingKeys(key);
            double mastery=StatsCalculator.usesMastery(key)?StatsCalculator.mastery(values.baseBen()):1;
            double synergy=StatsCalculator.support(values.baseOf(pair[0]),values.baseOf(pair[1]));
            Map<String,Object> detail=new LinkedHashMap<>();
            detail.put("key",key.pinyin);detail.put("label",key.zhLabel);
            detail.put("description",switch(key) {
                case ZHI -> "情报量：掌握的信息与知识，由推理和局势感知辅助。";
                case HUI -> "推理解题：理解信息、辨析线索，由知识和局势感知辅助。";
                case DOU -> "战斗力基准：自身战力为主，推理与意志提供有限协同。";
                case WEN -> "局势感知：发现消息与感知局势，由知识和推理辅助。";
                case BEN -> "能力根基：知识与推理促进感悟；自身不重复乘根基加成。";
                case GUI -> "忠诚意志：知识与推理辅助意志；不会因根基提高而被直接放大。";
            });
            detail.put("formula","实际"+key.zhLabel+"=round(C("+key.zhLabel+")"
                    +(StatsCalculator.usesMastery(key)?"×M(本)":"")
                    +"×(1+"+StatsCalculator.supportWeight(key)+"×S("+pair[0].zhLabel+","+pair[1].zhLabel+")))");
            detail.put("base",values.baseOf(key));detail.put("actual",values.actualOf(key));
            detail.put("growthValue",StatsCalculator.growth(values.baseOf(key)));
            detail.put("masteryMultiplier",mastery);detail.put("synergy",synergy);
            detail.put("synergyMultiplier",1+StatsCalculator.supportWeight(key)*synergy);
            detail.put("supportNames",List.of(pair[0].zhLabel,pair[1].zhLabel));
            detail.put("supportBases",List.of(values.baseOf(pair[0]),values.baseOf(pair[1])));
            detail.put("unrounded",StatsCalculator.unrounded(values,key));
            stats.add(detail);
        }
        book.put("stats",stats);
        book.put("checks",List.of(
                "当前交手：差值=攻方实际斗−守方实际斗；阈值 T=max(10,round("+ConflictResolver.RELATIVE_WIN_RATE+"×(双方实际斗之和)))。差值≥T 攻方胜，≤−T 守方胜，其余平局。未确立六维的人物不使用中性占位数结算。",
                "当前故事中，知影响知识运用、慧影响推理、闻影响观察、本提供公式中的感悟加成、归体现意志。主创结合人物所知与意愿写经过，不按属性强制角色服从或泄露秘密。",
                "旧引擎演示的干预分：能力=0.4×100×A/(A+50)，意愿与利益各不超过30；这不是当前故事强制第三方出手的规则。",
                "旧独立打听服务：P=0.65×实际闻+0.35×实际慧；重要度上限=floor(2+8×P/(P+60))，限制2～10，只筛选公开信息。当前游玩的问人打听由角色API与主创处理，不把该筛选公式宣称为每次对白的结算。",
                "旧独立打听的记忆评分=round(45+45×实际慧/(实际慧+40)+5×实际知/(实际知+40))，范围45～95；评分不等于实际消息的正确率。",
                "死亡依据独立生命值归零，不因归为0或六维总和为0自动死亡。基础本上限100；实际值可因知识协同略高于100。"));
        return book;
    }
}
