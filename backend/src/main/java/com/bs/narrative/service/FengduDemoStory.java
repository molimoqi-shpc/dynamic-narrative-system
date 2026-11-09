package com.bs.narrative.service;

import com.bs.narrative.dto.ActionResponse;
import com.bs.narrative.dto.EngineDemoResponse;
import com.bs.narrative.dto.EngineDemoResponse.Beat;
import com.bs.narrative.dto.EngineDemoResponse.CastMember;
import com.bs.narrative.dto.EngineDemoResponse.Story;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authored illustration of one fixed result. No LLM, persistence or gameplay decisions. */
public final class FengduDemoStory {
    private FengduDemoStory() {}

    public static EngineDemoResponse illustrate(ActionResponse result,
                                                List<Map<String, Object>> initialRelationships,
                                                Map<String,Object> analysis) {
        List<CastMember> cast = List.of(
                new CastMember("player_01", "沈砚", "示例主角 · 滞留丰都的旅人",
                        "就连月涝灾向雨神讨一个说法。",
                        "尚无雨神故意致灾的实证，却想借灾情逼对方认责；开口时把握十足，并不等于真有胜算。"),
                new CastMember("rain_god", "雨神", "被当街指控的掌雨者",
                        "要求指控者拿出证据，澄清当场指责。",
                        "不肯无凭无据地认罪，也警惕博物神借此插手；没有因为得到解围便与他结盟。"),
                new CastMember("museum_god", "博物神", "在旁听完争执的第三人",
                        "劝止争执，让双方把话说清楚。",
                        "表面替雨神解围，实际借沈砚的证据缺口夺走谈话主导权。与雨神有旧往来，也有背刺前史；这次的援手并非单纯好意。")
        );
        List<Beat> beats = List.of(
                new Beat("accusation", "缘起", "player_01", "沈砚",
                        "雨水沿屋檐落成一线。沈砚拦在雨神面前，把声音抬得足以让街边的人听清。",
                        "雨神，丰都涝灾这笔账我就赖你头上！满城的人都等着一句交代，你还要躲到什么时候？",
                        "只要他先退让一句，这场质问就能由我掌握。总不能让人一直困在水里，却连问都不敢问。",
                        "沈砚选择公开挑衅，想借灾情取得主动。此次比较的是闻，开口的自信不会额外增加属性。", null),
                new Beat("demand-evidence", "当面对峙", "rain_god", "雨神",
                        "雨神停住脚步，没有顺着他的责问辩解，反而看着他等了片刻。",
                        "我就在这里。你说涝灾是我所为，凭的是什么？是亲眼所见，还是只因我掌雨，便该替所有雨水认罪？",
                        "他想让我先解释，再从解释里找把柄。先问清他知道多少，比跟着他的怒气走更有用。",
                        "雨神闻高于沈砚，察觉了指控中的空缺，把交锋拉回证据；这不等于已经证明雨神与涝灾无关。", null),
                new Beat("exposed-gap", "当面对峙", "player_01", "沈砚",
                        "沈砚指向积水的街面，话头比方才急了一些，却始终没有说出亲见致灾的经过。",
                        "你掌着雨，难道还能说全不相干？城里已经淹成这样，你总该先做些什么！",
                        "我确实没有见他动手。可眼下若承认凭据不足，这场质问便要落下风。",
                        "沈砚把“谁造成灾害”换成“谁该出手救灾”，未能回答雨神的追问。双方争论的责任与救助不是同一件事。", null),
                new Beat("offer-help", "借援夺势", "museum_god", "博物神",
                        "一直站在一旁的博物神走近半步，抬手隔开两人的视线，语气比他们都温和。",
                        "丰都还泡在水里，二位总不能先在这里争出一场灾。雨神不必急着辩，我替你把这句话问清。",
                        "沈砚急着逼人认账，雨神急着洗清指控。谁先替他们定下该答什么，谁便能接过这场争执。",
                        "第三人以替雨神解围的姿态介入；他的目的在于取得主导，不是自动替任何一方无条件行动。", null),
                new Beat("turn-the-question", "借援夺势", "museum_god", "博物神",
                        "博物神转向沈砚。他没有讨论雨势，只把方才被绕过去的问题重新摆在两人之间。",
                        "沈砚，你可曾亲眼见过雨神故意淹城？若见过，就说出当时的经过；若没有，求他救灾是一回事，当街定他的罪，是另一回事。",
                        "让他自己承认没有凭据，比我替雨神反驳更干净。雨神得了解围，我则成了那个能让争执停下来的人。",
                        "这一步才是算计的实施：借救援的名义改写交锋方式，使主角必须回应自己的证据缺口。没有交换物品，也没有造成伤害。", null),
                new Beat("forced-concession", "借援夺势", "player_01", "沈砚",
                        "沈砚张了张口，最终收回了指向雨神的手。雨声没有停，方才那句断言却再也接不下去。",
                        "我没有亲眼见过。方才说得太满，我收回那句定罪的话。但灾情总是真的，我问的事也不能就此算了。",
                        "我本想逼雨神给交代，如今却先被逼着解释自己。博物神看似劝架，每一句都把我往他定好的位置上推。",
                        "主角的挑衅被反制，并非因为被扣血或被迫交出道具；这场交锋的失败表现为失去主动并撤回无据指控。", null),
                new Beat("relief-with-reserve", "结果余波", "rain_god", "雨神",
                        "雨神望了博物神一眼，随后重新看向沈砚。声音已缓下来，但没有许下任何新的约定。",
                        "求援可以说，指控也可以说，只是不能把它们混作一句。今日到这里。往后若有实据，尽可再来问我。",
                        "他确实替我挡了这一遭，却也让我只能顺着他的问法收场。我没有忘记旧事，更不会因此便把他当作盟友。",
                        "雨神获得当场解围，最终得利者仍是博物神。旧关系是有方向的，不能把博物神对雨神的数值倒读成雨神对博物神的信任。", null),
                new Beat("deny-involvement", "结果余波", "museum_god", "博物神",
                        "见沈砚仍望着自己，博物神把手收回袖中，向旁边退开，仿佛只是恰巧路过。",
                        "别这样看我。我没出手，此事与我无关。我不过请你们把话说清楚。",
                        "沈砚如今会忌惮我，雨神也见到了我介入后的分量。表面的援手已经做足，至于我为何出面，就不必由我亲口承认了。",
                        "他没有挥拳，却已通过发问改变结果。“出手”在这里指介入交锋；把自己说成无关旁观者，是这段对白中的具体谎言。",
                        "博物神刚才主动截住争执、替雨神发问，并迫使沈砚收回指控；“此事与我无关”与这些公开行动矛盾。此标记只属于这一句。"),
                new Beat("after-the-exchange", "结果余波", "player_01", "沈砚",
                        "沈砚没有再追着争辩。他退到檐下，看着雨神离开，也看着博物神重新站回雨声之外。",
                        "我记住了。下次开口，我会先弄清自己究竟知道什么。",
                        "输掉的不只是一句口角。我对雨神更有芥蒂，对博物神更难信任；而城里的水，还在那里。",
                        "当场交锋有了结果，灾害本身尚未解决。后续可以追查、求援或另作选择，示例没有擅自补上治水结局。", null)
        );
        Story story = new Story("丰都雨争 · 借援夺势",
                "丰都连月阴雨，街巷积水未退。旅人沈砚当街拦住雨神，要求他对涝灾给个交代。博物神站在一旁，听完了两人的第一轮争执。此段演示的是这一次当面对峙，不是整场水患的结局。",
                cast, beats,
                "沈砚的公开指控因缺少凭据而受挫，雨神暂时脱开了当场责难。博物神表面帮雨神解围，实际掌握了双方的应答节奏，成为这次交锋的得利者。沈砚收回了指控，也加深了对两人的芥蒂。争执停了，雨仍未歇；如何处理涝灾，依然是他们接下来要面对的事。");

        // Preserve the old endpoint's response fields; replace only its placeholder dialogue.
        var lines = beats.stream().map(b -> new ActionResponse.NarrativeLine(
                b.speakerId(), b.speech(), b.innerThought())).toList();
        Map<String, Boolean> lieFlags = new LinkedHashMap<>();
        for (Beat beat : beats) lieFlags.merge(beat.speakerId(), beat.lieReason() != null, Boolean::logicalOr);
        Map<String, Object> snapshot = new LinkedHashMap<>(result.ruleSnapshot());
        snapshot.put("initialRelationships", initialRelationships);
        ActionResponse illustrated = new ActionResponse(result.logId(), result.outcomeType(), result.outcomeLabel(),
                result.intervenerId(), lines, lieFlags, result.factsChinese(), snapshot, result.token(),
                result.objectives(), result.npcDecisions());
        return new EngineDemoResponse(illustrated, story, analysis);
    }
}
