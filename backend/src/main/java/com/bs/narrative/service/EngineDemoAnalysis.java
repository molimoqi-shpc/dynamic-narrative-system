package com.bs.narrative.service;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.engine.ConflictResolver;
import com.bs.narrative.engine.StatFormulaBook;
import com.bs.narrative.engine.StatsCalculator;
import java.util.List;
import java.util.Map;

/** Read-only teaching material; invokes existing pure formulas, never any gameplay operation. */
public final class EngineDemoAnalysis {
    private EngineDemoAnalysis() {}

    public static Map<String,Object> describe(List<GodProfile> initialCast) {
        return Map.of(
                "process", List.of(
                        point("先有本人意愿", "角色 API 根据本人的经历、性格、关系、已知消息和当前处境提出下一步行动方向。沈砚想追问责任，雨神想反驳指控，博物神想插手。角色可以犹疑、拒绝、隐瞒或另有所图；不是统一听玩家命令。"),
                        point("分清想法与能力", "意愿说明想做什么，六维与现场说明怎样去做。观察到的痕迹、已有知识和推理必须分开：看见沈砚回避证据，不等于知道雨灾真相；想救人，也不等于已经救到。属性差距要体现在具体行动中，不靠每句话报数来替代情节。"),
                        point("由主 API 写全部实际经过", "主 API 综合玩家方向、相关角色资料与本人意愿、世界观、前文和世界书记忆，生成动作、对白、转折与后果。角色 API 不单独宣布已经发生的新历史。一般问答与自然反应继续写下去；新的结盟、敌对、表白等重大选择才交给玩家。"),
                        point("交手时引用已有公式", "双方确实发生实质交手且六维已经确立时，用现有实际斗比较确定胜负。公式参考不是已经开打的历史，主 API 仍需决定是否发生交手，并结合双方的分寸、过去和现场写清过程。普通观察、询问与旅行无需逐项战斗判定。"),
                        point("把结果接入后文", "真正发生的交手、重要伤势、关系变化与剧情事实保存后，进入后续角色资料和世界书上下文。失败不能只显示一个数值就遗忘：人物会记住被质疑、被解围或被算计的经历；水患尚未解决，也不会因为口角结束而被写成天下太平。")
                ),
                "behaviors", List.of(
                        behavior("player_01", "沈砚", "借公开质问让雨神表态，并争取灾情中的主动权。",
                                "亲见街巷积水，但没有亲见雨神故意致灾；掌雨者在场是事实，掌雨者有罪仍只是他提出的判断。他不知道另外两人的真实内心。",
                                "用“灾情严重、你掌着雨”把求援与定罪绑在一起，提高对方沉默的代价。被追问凭据时转而要求救灾，试图保住原先的声势。",
                                "论证缺口容易被抓住；声调和自信不能补出证据。闻较低使他较晚察觉话题主导权正在转移，但不表示他完全没有观察能力。",
                                "承认没有亲见并收回过满的指控，却保留继续调查与求援的方向。失势与对两人的芥蒂应当进入后续理解，而不是假装这次交锋没有发生。",
                                "可以先求援、询问雨势缘由、寻找亲历者或核实线索。这些只是可选方向，不能承诺换一句措辞就必然成功；雨神也有拒绝与保留的自由。"),
                        behavior("rain_god", "雨神", "阻止无凭据定罪，保住自己对谈话的解释权。",
                                "知道沈砚尚未当众给出实据，也听出了他将责任与救助混在一起。没有因为闻较高就获得沈砚的全部记忆，更没有凭属性证明自己无罪。",
                                "先问亲见与凭据，不跟着对方的怒气逐句自辩。把争论从情绪拉回可回答的问题；博物神介入后接受了有利的问法，同时保留警惕。",
                                "完全回避灾情会让求援者更不满；让第三人代为发问也会让出部分话语权。得到解围不是免费获得新盟友。",
                                "暂时脱开当场责难，灾害责任仍待查明。雨神没有被写成无条件认罪、治好涝灾或与博物神结盟。",
                                "也可以解释自己已知的雨势、提出核实办法或拒绝继续争论；实际选择要服从本人资料与处境，不能因为高属性就固定采取最完美策略。"),
                        behavior("museum_god", "博物神", "借劝解与救援的姿态介入，取得主导交锋的机会。",
                                "听见沈砚回避实据，知道双方都在争取谈话主动。与雨神有旧往来和背刺前史，这些属于自己的过去；他不需要凭空知道涝灾幕后真相。",
                                "先以温和姿态获得发言位置，再把双方都绕不开的证据问题交给沈砚，让主角亲口承认缺口。最后淡化自己的介入，保留表面中立的形象。",
                                "若被看穿，沈砚会更不信任他；雨神也未必愿意受这份人情。能够介入、愿意介入和最后得利是三个不同问题，不能合并成自动成功。",
                                "这局的收益是局面与话语权，不是凭空多出金钱、宝物或一份盟约。“我与此事无关”只在那句具体台词上揭示矛盾，其他真话不一起贴谎标。",
                                "可以保持旁观、直接求助或公开提出交换条件。当前丰都例子的旧版算计分支用来说明反转；正式故事的第三人是否帮忙，仍由其本人意愿交给主 API 创作。")
                ),
                "dimensions", List.of(
                        dimension("zhi", "知", "已有知识与信息储备。知道哪些事，与能否推理出新解释不是同一项能力。",
                                "知道掌雨、灾情、地方旧事等相关背景，能提出更有依据的问题；信息不足时应询问与核实。沈砚的指控缺口不会因为知高就自动变成证据。",
                                "可运用本人确实掌握的敌人习惯、器物或经验。知还会参与其他维度的有限协同，但不直接给每次攻击额外加一段伤害。",
                                "不凭空知道陌生人的秘密，不把世界书里作者可查的内容直接变成人物已知。"),
                        dimension("hui", "慧", "推理、辨析、比较解释与把知识用于解题。",
                                "辨认“造成灾害”与“应当救灾”的逻辑区别，察觉偷换问题并设计有效追问。高慧者也要区分结论、推测与尚待核实之处。",
                                "慧与归对实际斗提供有限协同，可体现战术、应变与节奏控制。具体战术写法交给主 API，不能再额外乘一次慧，把已经算好的斗重复放大。",
                                "不读心，不把合理怀疑写成确定真相；低慧也不是故意写蠢。"),
                        dimension("dou", "斗", "交手能力的数值基准，表现为力量运用、身手、步法和反应。",
                                "这次主要是当面对峙，没有因为斗高就必须拔刀，也没有把嘴上退让写成身体受伤。身体技艺可在需要时自然表现。",
                                "实质交手比较双方实际斗；它已经含根基与慧、归协同。差值与阈值决定攻方胜、守方胜或平局，过程要写出怎样占优或受制。",
                                "高斗不是无敌，不凭数值创造未确立的法术；战败不是自动死亡。"),
                        dimension("wen", "闻", "观察、察觉细节与感知局势，重点在本人能实际感知的范围。",
                                "雨神更快看见质问里的空缺，博物神注意到双方话题僵住，沈砚较晚意识到自己被引导。要写出具体发现及影响，而不只说“目光敏锐”。",
                                "可先留意对手姿态、逼近的人或环境变化；闻侧重发现，慧侧重解释。当前交手公式不因闻高而另加一笔隐藏命中率或固定先手。",
                                "闻高不隔墙读心，不保证看破所有谎言；旧例以闻比较口角，不代表正式故事每句话都进行闻检定。"),
                        dimension("ben", "本", "已有能力的根基、感悟与掌握深度，随世界观体现为训练、修习或经验。",
                                "表现为理解既有传承或技能的要领。本不是财富，不决定是否收钱，也不让角色突然获得世界观之外的新力量。",
                                "基础本进入根基倍率 M=1+0.6×本/(本+50)，上限本100对应1.4倍；知、慧、斗、闻会应用这个倍率。本与归自身不重复乘它。",
                                "看双方表中的实际值时已经包含根基，不再给主 API 二次加成；实际本可因协同略高于基础上限100。"),
                        dimension("gui", "归", "坚持本人信念、承诺、选择的意志与自制，不等于对玩家的好感。",
                                "面对质问、诱惑或失势时决定怎样坚持与权衡。博物神的意志可能用于自己的谋划，沈砚也可在认错后仍坚持核实灾情。",
                                "与慧共同对实际斗提供有限协同，可表现为压力下保持动作与判断。恐惧、认输、留情与救援仍结合人设和现场创作。",
                                "高归不强制服从玩家，低归不必然背叛，归为0也不等于死亡；充分的新事实能让坚定的人合理改变态度。")
                ),
                "profiles", initialCast.stream().map(EngineDemoAnalysis::profile).toList(),
                "combatRule", Map.of(
                        "difference", "D = 攻方实际斗 − 守方实际斗。这里使用已含根基和协同的实际值，不直接比较基础斗。",
                        "threshold", "T = max("+ConflictResolver.DIFF_CLEAR_WIN+", round("+ConflictResolver.RELATIVE_WIN_RATE+" × (攻方实际斗 + 守方实际斗)))。双方越强，明确胜出的差值要求也会相应提高。",
                        "decision", "D ≥ T：攻方胜；D ≤ −T：守方胜；−T < D < T：平局。边界相等时已经形成明确胜负。公式不预设伤势、死亡或谁愿意留情。"),
                "combatCases", List.of(
                        combat("sparring", "切磋占优：胜出不等于受伤", "一场已经同意点到为止的练习。只比较交手能力，看看胜负与伤势为何分开。",
                                fighter("练习者",8,9,40,12,20,7), fighter("陪练者",35,26,22,30,50,20),
                                "攻方的实际斗差达到胜出阈值，可以写出拿住空隙、迫使对方认输或点到即止。双方原本愿意克制，完全可以没有生命损伤；不能看见“胜”便自动补上重伤。"),
                        combat("hostile", "敌对落败：先胜负，再看处置", "借用沈砚与雨神开场六维做独立对照；这不是说雨中争辩实际变成了战斗。",
                                fighter("沈砚",8,9,10,12,20,7), fighter("雨神",35,26,22,30,50,20),
                                "攻方实际斗处于明显下风，发生交手时应承接守方胜。接下来由胜者的性格、过去、目的与现场决定放行、制服、俘获、索取或杀害等处置；有人愿救也不保证及时赶到或成功。"),
                        combat("draw", "实力接近：平局仍有过程", "双方采用相同的已确立六维，展示差值没有达到正负阈值时如何理解。",
                                fighter("攻方",20,30,30,20,50,30), fighter("守方",20,30,30,20,50,30),
                                "平局表示本次交手未形成明确胜负，可以相持、分开、被环境打断或主动停手。不能只因阈值未过就写成毫无过程，也不表示双方必定扣血或必须无限打到死亡。"),
                        combat("mastery", "基础斗相同：根基改变实际值", "双方基础斗30、慧50、归50相同，仅将基础本从0改为100；其余基础值也相同。",
                                fighter("根基尚浅者",20,50,30,20,0,50), fighter("根基深厚者",20,50,30,20,100,50),
                                "慧与归的协同倍率同为1.06，根基倍率分别为1和1.4。实际斗由32变为45，于是同样的基础斗也会形成可比较的能力差距。这是实际值公式的一次计算，不是写故事时再乘一次本。"),
                        combat("synergy", "辅助有用：协同保持有限", "双方基础斗30、本50相同；攻方慧与归为0，守方慧与归为50，其余基础值保持一致。",
                                fighter("缺少辅助者",20,0,30,20,50,0), fighter("辅助均衡者",20,50,30,20,50,50),
                                "协同使实际斗出现差别，但其对斗的上限系数只有12%，不会无限堆叠，也不保证跨过胜负阈值。这个例子仍可能平局，恰好说明“有作用”不等于“只靠辅助项便必胜”。")
                ),
                "consequences", List.of(
                        point("胜负只解决交手结果", "双方六维已确立且实际交手时，D=攻方实际斗−守方实际斗；T=max(10,round(0.10×双方实际斗之和))。D≥T攻胜，D≤−T守胜，其他情况平局。数值未知时不拿中性占位数装作真实能力。"),
                        point("败后处置来自人物本人", "角色资料里的交手目的、克制分寸、获胜后想如何处置、落败反应、留情条件与救援意愿，交给主 API 写完整经过。切磋可以无伤认输；敌对失败可能被放走、制服、俘获或杀害，不能把所有失败统一成扣血后继续闲聊。"),
                        point("救援不是系统保命", "旁人先要有自己的理由与援救意愿，主 API 再根据现场写出是否出手、能否赶上及成败。愿救不等于救成，想杀也不等于已经杀成；不能凭空塞入救星替玩家消除所有后果。"),
                        point("生命与能力分开记录", "生命值记录实际伤势与恢复，真实死亡才保存生命0；认输、失去战力、被绑住或归为0都不等于死亡。胜负参考不预设伤害，真实发生的重要受伤、治疗与死亡才保存对应生命变化。"),
                        point("能力结算与长期记忆", "当前交手结算中，胜方基础斗+2、闻+1，败方斗−2、闻−1；平局双方基础斗−1。这些不是固定扣血，也不是人物忘掉失败的理由。只有交手真正发生并保存后才生效，表中对照不会修改任何角色。")
                ),
                "boundaries", List.of(
                        "以上是讲解与假设对照，切换人物、公式或战斗案例不会发起游戏行动，不会改变任何存档。",
                        "丰都雨争展示早期第三方算计样例；它的符咒关键词、背刺优先级与干预分，不是当前故事强制角色出手的通用规则。",
                        "当前故事由角色 API 给本人资料与行动意愿，再由主 API 结合玩家方向、世界观和前文编写所有实际故事。六维辅助表现能力，不替角色决定结盟、爱慕、背叛或是否救人。",
                        "这些战斗案例只对照现有实际斗公式；没有新增先手、暴击、隐藏命中率、强制救援、地点许可或时间审查。"
                )
        );
    }

    private static Map<String,String> point(String title,String detail) {
        return Map.of("title",title,"detail",detail);
    }
    private static Map<String,String> behavior(String id,String name,String goal,String known,String choice,
                                               String risk,String consequence,String alternative) {
        return Map.of("id",id,"name",name,"goal",goal,"known",known,"choice",choice,
                "risk",risk,"consequence",consequence,"alternative",alternative);
    }
    private static Map<String,String> dimension(String key,String label,String meaning,
                                                String sceneUse,String combatUse,String boundary) {
        return Map.of("key",key,"label",label,"meaning",meaning,"sceneUse",sceneUse,
                "combatUse",combatUse,"boundary",boundary);
    }
    private static Map<String,Object> profile(GodProfile p) {
        Map<String,Object> full=StatFormulaBook.describe(p);
        return Map.of("id",p.getGodId(),"name",p.getGodName(),"formulaBook",Map.of(
                "version",full.get("version"),"title",full.get("title"),
                "common",full.get("common"),"stats",full.get("stats")));
    }
    private static GodProfile fighter(String name,int z,int h,int d,int w,int b,int g) {
        GodProfile p=new GodProfile();p.setGodId(name);p.setGodName(name);
        p.setBaseZhi(z);p.setBaseHui(h);p.setBaseDou(d);p.setBaseWen(w);p.setBaseBen(b);p.setBaseGui(g);
        return p;
    }
    private static Map<String,Object> combat(String id,String title,String context,GodProfile a,GodProfile b,
                                             String interpretation) {
        int attack=StatsCalculator.calcActualStats(a).actualDou(),defend=StatsCalculator.calcActualStats(b).actualDou();
        int difference=attack-defend,threshold=ConflictResolver.clearWinThreshold(attack,defend);
        String outcome=difference>=threshold?"ATTACKER_WIN":difference<=-threshold?"DEFENDER_WIN":"DRAW";
        boolean draw="DRAW".equals(outcome),aWins="ATTACKER_WIN".equals(outcome);
        return Map.of("id",id,"title",title,"context",context,
                "attacker",profile(a),"defender",profile(b),
                "comparison",Map.of("actorActualDou",attack,"targetActualDou",defend,
                        "difference",difference,"threshold",threshold,"outcome",outcome,
                        "outcomeLabel",draw?"平局":aWins?"攻方胜":"守方胜",
                        "differenceFormula",attack+" − "+defend+" = "+difference,
                        "thresholdFormula","max("+ConflictResolver.DIFF_CLEAR_WIN+", round("+ConflictResolver.RELATIVE_WIN_RATE+" × ("+attack+" + "+defend+"))) = "+threshold),
                "interpretation",interpretation,
                "statDeltas",List.of(
                        Map.of("name",a.getGodName(),"changes",Map.of("dou",draw?-1:aWins?2:-2,"wen",draw?0:aWins?1:-1)),
                        Map.of("name",b.getGodName(),"changes",Map.of("dou",draw?-1:aWins?-2:2,"wen",draw?0:aWins?-1:1)))
        );
    }
}
