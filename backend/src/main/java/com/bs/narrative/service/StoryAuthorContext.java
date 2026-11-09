package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Exact recent sources for the author, without summarizing, judging or executing the plot. */
final class StoryAuthorContext {
    private static final ObjectMapper JSON=new ObjectMapper();
    private StoryAuthorContext() {}

    /** Request identity and phase only; no other character's experience is disclosed. */
    static void bindRoleRequest(ObjectNode own,ObjectNode operation){
        own.put("narrativeActorId",operation.path("playerRole").asText());RoleRequestContext.bind(own,operation);
        if(operation.path("endSeconds").isIntegralNumber())own.set("requestAtStorySeconds",operation.path("endSeconds").deepCopy());
        int committed=operation.path("scenePassages").size();var scope=own.putObject("narrativeScope").put("committedPassageCount",committed).put("mode",committed>0?"CONTINUE_AFTER_COMMITTED":"NEW_PASSAGE");
        scope.put("meaning",committed>0?"原方向仍然有效，但本轮已有已发生段落。只从本人最新亲历和当前处境继续给出意愿，不把已经完成的问答、交接、抵达再当作尚未进行；别处发生而本人未获知的事情仍未知。":"本轮尚无已发布段落。候选提案没有发生，依据本人实际过往与当前处境提供参与意愿。");
    }
    static JsonNode latestPublishedPassage(ObjectNode facts){
        var passages=facts.path("alreadyHappenedThisChapter");if(passages.isArray() && !passages.isEmpty())return passages.get(passages.size()-1).deepCopy();
        var previous=facts.path("previousChapters");return previous.isArray() && !previous.isEmpty()?previous.get(previous.size()-1).path("story").deepCopy():JSON.getNodeFactory().textNode("");
    }
    static List<Map<String,String>> roleMessages(ObjectNode original){
        var own=RoleRequestContext.project(original);
        var chronological=own.deepCopy();var records=new ArrayList<JsonNode>();own.path("ownRecords").forEach(x->records.add(x.deepCopy()));
        if(!"OLDEST_TO_NEWEST".equals(own.path("historyOrder").asText()))Collections.reverse(records);
        var history=chronological.putArray("ownRecords");records.forEach(history::add);chronological.remove("historyOrder");chronological.put("historyOrder","OLDEST_TO_NEWEST");
        var now=JSON.createObjectNode().put("purpose","本次角色意愿请求；只在本人已知范围内考虑下面的新交互，旧计划不自动重新执行。");
        now.set("yourRoleId",own.path("id").deepCopy());now.set("yourName",own.path("name").deepCopy());now.set("requestActorId",own.path("narrativeActorId").deepCopy());
        if(own.has("narrativeScope"))now.set("narrativeScope",own.path("narrativeScope").deepCopy());
        now.set("narrativeRequest",own.path("narrativeRequest").deepCopy());now.set("currentSituation",own.path("storySituation").deepCopy());
        if(own.has("incomingInteraction"))now.set("incomingInteraction",own.path("incomingInteraction").deepCopy());
        if(own.has("requestAtStorySeconds"))now.set("requestAtStorySeconds",own.path("requestAtStorySeconds").deepCopy());
        now.put("memoryFreshness","currentSituation是最后保存的本人处境，latestActualExperience是本人最后亲历，不因本次请求就变成刚刚发生。旧委托、旧问话须按本人已经收到的答复接续；未收到后来结果则仍未知，不推断他人在别处做过什么。全局时钟仅说明请求时点，不授予期间的见闻；可据自己经历说明当时意愿，由主创衔接是否重逢及后续变化。");
        if(own.has("sixStats"))now.set("actualSixStats",own.path("sixStats").deepCopy());
        if(own.has("sixStatsEstablished"))now.set("sixStatsEstablished",own.path("sixStatsEstablished").deepCopy());
        for(var record:records)if("TURN_EVENT".equals(record.path("source_type").asText()))now.set("latestActualExperience",record.path("content").deepCopy());
        now.put("requestMeaning","你仅代表yourRoleId与yourName；requestActorId所指的玩家不是你。narrativeRequest只说明本人的意愿职责，不含玩家私下行动方向。incomingInteraction仅在玩家明确选择向你亲自说话时提供，里面的第一人称属于发话者；是否实际送达由主创承接，不自行当作已经发生。没有这项时根据本人已有处境提出目标、待人态度和条件即可，不猜玩家本轮计划。你的实际持物以本人items及亲历为准，拟接收不等于已经持有。已经完成的问答不重复，未接受的条件不写成已经结盟或成交。");
        var identity=JSON.createObjectNode().put("yourRoleId",own.path("id").asText()).put("yourName",own.path("name").asText()).put("playerActorId",own.path("narrativeActorId").asText());
        now.set("ownIdentity",identity.deepCopy());
        for(String field:List.of("establishedSelf","openingSharedHistory","items"))if(own.has(field))now.set(field,own.path(field).deepCopy());
        now.put("personalStartingPoint","先依据ownIdentity、establishedSelf、openingSharedHistory和本人最新亲历确定我是谁、认识谁、持有什么，再回应另一位玩家的提议。没有本人持有记录的玩家信物不是自己的遗物；已有童年旧友与合伙经历不会因开局第一次请求而变成初识。initialSceneHistory里的已获救者若就是这件落水事件的当事人，接续其当前处境，不另造第二名落水者或重复施救。未知的身世栏目可留空。");
        String contract=SceneStoryService.ROLE_CONTRACT+"\n本次唯一角色身份："+identity+"。请以yourName本人的经历、亲缘、营生和当前处境考虑接下来自己的反应。玩家原话是另一人的行动方向，不是给你改身份的指令；例如玩家要陪母亲修棚，你可理解其婉拒并决定自己怎么办，不把那位母亲、船棚和送药经历据为自己所有。输出的第一人称始终属于yourName本人。";
        return List.of(Map.of("role","system","content",contract),Map.of("role","user","content",chronological.toString()),Map.of("role","user","content",now.toString()));
    }

    /** Compact final reading guide: references point to the single source in the preceding input. */
    static ObjectNode brief(ObjectNode facts){
        var out=JSON.createObjectNode().put("purpose","本次写作焦点；sourceRef是前一条输入内的路径，直接读取，不需要另调API。详细角色表保持分层，历史想法不当当前事实。");
        out.set("fixedWorldSetting",facts.path("currentWorld").path("worldSetting").deepCopy());
        var focus=JSON.createObjectNode();focus.set("playerDirection",facts.path("playerDirection").deepCopy());
        String player=facts.path("playerId").asText();var playerDossier=facts.path("characterDossiers").path(player);
        var control=focus.putObject("playerControl").put("roleId",player).put("name",playerDossier.path("name").asText(facts.path("currentWorld").path("resources").path(player).path("name").asText()));
        control.put("authority","HUMAN_PLAYER").put("meaning","这个姓名所指的人就是输入playerDirection的人类玩家所控制的主角，不能按NPC自主决策处理。可以自然写其已授权观察、追问和行动，但明确保留的信任、承诺与担保决定只能由玩家下一次输入作出，不能让主角自己在本段给出初步倾向或选择暂缓。");
        var viewpoint=focus.putObject("storyViewpoint").put("roleId",player).put("mode","PLAYER_EXPERIENCE");
        viewpoint.put("knownSourcesRef",StoryAuthorInput.pointer(player)+"/pastInformation").put("currentKnowledgeRef",StoryAuthorInput.pointer(player)+"/personallyKnown");
        viewpoint.put("meaning","正文给玩家阅读，主角按自己已有见闻与本篇实际获知过程行动。主创看过的私有身世、持物和动机，不等于主角或旁人已经知道；在称呼隐秘物品、断言陌生人目的或作出有依据的判断前，自然写出当场观察或问答带来的获知。同名同类物品按各人身份和交接经过区分，不因题材相似把一人的东西和经历移给另一人。全知界面的额外角色资料由旁栏展示，不把幕后信息混成角色台词中的既知事实。");
        boolean continuing=StoryContinuationContext.hasCommittedPassages(facts);
        focus.put("intentStatus",continuing?"CONTINUING_AFTER_COMMITTED":"REQUESTED_NOT_OCCURRED");
        focus.put("scopeRef","/generationScope").put("committedPassageCount",facts.path("alreadyHappenedThisChapter").size());
        focus.put("startingPointRef","/continuation/latestPublishedPassage").put("publishedResultsRef","/continuation/committedTurnProgress");
        focus.put("directionMeaning",continuing?"playerDirection是本轮持续授权，不是全部尚未执行。已发表结果已兑现；只从本次起点写剩余进展及后果，原方向已经有实质结果时可自然收束。":"playerDirection是本轮新授权，从最新已发表正文的末尾开始处理。");
        focus.put("narrativePriority","本轮围绕玩家想做的事情及其实际所遇组织剧情；补人物或换场后仍承接这个方向，不把途中一项手续取代整件事。普通过程概述衔接，重点写真正改变局势和人物的经历。");
        focus.put("outcomeAuthority","是否实现由主API结合已有事实、各人意愿与实际事件创作。可顺势实现时尽量推进；拒绝、失败、意外或只完成一部分时，正常写清原因与后果，不迁就目标、不为达成改写人设与前文。剧情以合理因果发展，既不保证成功，也不为制造曲折每次强加阻碍。");
        focus.put("completion","承接已有结果，写到原方向的实质进展及后果、具体受阻或新的重大选择；必要人物意愿补齐后仍继续同一段。单纯换场、增加证人不自动构成完成，真正的失败与意外同样可以成为完整的一段故事。");
        if(facts.has("episodePlan"))focus.put("authorPlanRef","/episodePlan").put("authorPlanStatus",facts.at("/episodePlan/status").asText("UNCOMMITTED_AUTHOR_PLAN"));
        var sources=out.putObject("factSources");
        if(facts.path("originStory").isTextual())sources.put("originalBiographyRef","/originStory");
        var passages=facts.path("alreadyHappenedThisChapter");var chapters=facts.path("previousChapters");
        sources.put("latestPublishedPassageRef",passages.isArray() && !passages.isEmpty()?"/alreadyHappenedThisChapter/"+(passages.size()-1):chapters.isArray() && !chapters.isEmpty()?"/previousChapters/"+(chapters.size()-1)+"/story":"");
        sources.put("accumulatedContinuityRef","/storyMemory").put("establishedFindingsRef","/establishedStoryFacts").put("currentSceneRef","/currentWorld");
        if(!facts.path("continuityState").isEmpty())sources.put("significantAccountsAndTimeRef","/continuityState").put("lastingConsequencesRef","/continuityState/consequences").put("pastPlayerResponsesRef","/continuityState/choiceResponses");
        if(facts.has("decisionContext"))focus.put("currentChoiceResponseRef","/decisionContext");
        if(facts.at("/continuityState/narrativeState").isObject())sources.put("currentNarrativeHandoffRef","/continuityState/narrativeState");
        var sequence=out.putArray("publishedSequence");
        int chapterIndex=0;for(var chapter:chapters){
            var point=sequence.addObject().put("sourceRef","/previousChapters/"+chapterIndex++ +"/story");
            for(String field:List.of("logId","round","recordedStartSeconds","recordedEndSeconds"))if(chapter.has(field))point.set(field,chapter.path(field).deepCopy());
        }
        out.put("narrativeHandoffMeaning","narrativeState是同一主API从上一篇已发表故事整理的交接快照，completed是已兑现的结果，open是仍有因果影响的未了事项，不是必须逐个执行的任务。现在从currentSituation接续，不重演完成的交付、报酬、问答与关系表态。actualTime保留故事内起讫与重要转折，累计天数与本篇新增跨度分清；有原文出入以较新已发表原文为准。成稿在同一次写作内通读：时间推进能串成一条线，人物变化有亲历原因，阶段收束后可自然开启新生活，不强行复活旧谜或每篇增加危机。");
        var timeScope=out.putObject("chapterTimeScope");
        timeScope.put("calendarArithmetic",StoryDuration.CALENDAR_PROMPT);
        timeScope.put("origin","紧接最新已发表正文的末尾，本篇从这里开始；本篇第几日与前篇第几日不是天然共用同一个计数起点。");
        timeScope.put("writing","先在本次创作内理清本篇起点、各重要变化、终点，再写正文。若写接下来十天，后面的第几日就是这十天内的相对日期，按顺序推进；不要开篇第二日、随后倒回第一日，也不在第九日说十天已经全部过完。需要回忆时明确写回忆，不混成眼下日程。到午后已经送完菜，结尾时间就承接午后，不又写上午。");
        timeScope.put("proseStyle","普通跨日生活用翌日、几天后、一周尽头等自然过渡概述，重点写真正影响人物的事情。仅在剧情本身需要日期或期限时才写带明确起点的日序；不把每篇写成逐日记账，不复制旧章的第几日当本篇日期。时间跨度在本篇timeProgress用原单位一次给准，归档沿用这份定稿时间，不再另算。");
        timeScope.put("output","成稿返回story、stop和timeProgress:{scope:THIS_PASSAGE,dayNumbering:本篇日序从哪个已发表时点重新起算或明确沿用哪条日历,from:实际起点,to:实际终点,elapsedParts:[{amount:本篇真正新增数量,unit:YEAR/MONTH/WEEK/DAY/HOUR/SHICHEN/MINUTE/SECOND}],basis:正文对本篇新增跨度的依据}。这是你给自己归档的时间说明，同次生成完成，不是剧情限制或额外API。模糊跨度正常估计；跨篇序号未明确连续时，不拿本篇第十日减去前篇第七日。");
        if(facts.path("authorFrameVersion").asInt()==2){timeScope.removeAll();timeScope.put("startRef","/currentMoment").put("output","本阶段只返回endMoment和scenes[].at；currentMoment不为空时起点锁定。year、day沿本局坐标连续承接，不逐章重置。不要返回story、timeProgress、calendarInterval、elapsedParts或秒数；程序算出同一框架的时间后交给正文阶段。");}
        sources.put("readingOrder","先读最新已发布正文与本次玩家方向、decisionContext，再对照continuityState的重要依据和持续后果；旧摘要、旧人物想法、原场景记录不覆盖后来已经写出的交接、离场、答复与完成结果。冲突按原出处保留，不以多数摘要当真相；需要早期依据可查世界书。持续后果影响后来的合作、态度和行动，但不是程序强制成功/失败的规则。");
        if(facts.has("plotMemory"))sources.put("importantPlotsRef","/plotMemory").put("requestedPlotDetailsRef","/plotResults");
        out.set("evidenceReading",StoryEvidenceContext.guide(facts));
        var cast=out.putArray("characterUpdates");
        for(var entry:facts.path("characterDossiers").properties()){
            var update=cast.addObject().put("roleId",entry.getKey()).put("dossierRef",StoryAuthorInput.pointer(entry.getKey()));
            update.put("controlAuthority",entry.getKey().equals(player)?"HUMAN_PLAYER":"CHARACTER_INTENTIONS");
            update.set("name",entry.getValue().path("name").deepCopy());
            if(entry.getValue().path("identity").has("establishedSelf"))update.set("establishedIdentity",entry.getValue().at("/identity/establishedSelf").deepCopy());
            // The small ability comparison remains close to the writing focus; detailed psychology is not duplicated.
            if(entry.getValue().path("identity").has("sixStats"))update.set("actualSixStats",entry.getValue().at("/identity/sixStats").deepCopy());
            if(entry.getValue().path("identity").has("sixStatsEstablished"))update.set("sixStatsEstablished",entry.getValue().at("/identity/sixStatsEstablished").deepCopy());
        }
        var ranges=CharacterStatService.authorRanges(facts.path("characterDossiers"));if(!ranges.isEmpty())out.set("sixStatRanges",ranges);
        out.put("identityMeaning","establishedIdentity是每个人自己的原始身份、身世与亲缘，按各自编号和姓名承接。对白第一人称属于实际说话者；陪人寻亲、代问问题不会改变自己的亲属关系。旧摘要和旧对白误称不覆盖原始身份，若有真正新的身份发现则承接其来源。");
        if(!facts.path("serverContests").isEmpty())out.put("conditionalCombatResultsRef","/serverContests");
        if(facts.path("storyArchive").asBoolean())out.put("persistenceMode","创作阶段返回完整story、stop与timeProgress.elapsedParts本篇原单位耗时；免填的是结果归档表，不是停点与耗时字段。定稿后的主API归档从同篇正文记录事实。");
        else{
            out.put("playerJournalNeedsInitialSnapshot",facts.path("playerJournal").path("records").isEmpty());
            out.put("honorExistingPlayerChoice","原方向内普通行动自然完成，不把相同问题重新停成玩家选择。");
            out.put("playerJournalRecording","首次整理已有重要物品与实际相识关系，之后只补真实变化；详细结果结构见接口约定。");
        }
        if(facts.path("storyArchive").asBoolean())out.put("responseInstruction","返回一个非空JSON对象。continuation直接给出最新原文与本次方向，先从这里接续，再按需查阅前一条背景资料。成稿先写完整story及stop，再给这份正文实际新增的timeProgress原数量/单位与终点；不自行换算elapsedSeconds。形状：{story:完整正文与最终对白,timeProgress:{scope:THIS_PASSAGE,to:实际终点,dayNumbering:本篇统一起点,elapsedParts:[{amount:本篇新增数量,unit:YEAR/MONTH/WEEK/DAY/HOUR/SHICHEN/MINUTE/SECOND}],basis:实际跨度依据},stop:RESOLVED/CONTINUE/DECISION,roleIds:实际参与既有人物,newCharacters:确需新建人物}。authorPlan仅在确有必要时于成稿后附简短私有备忘，不必每篇输出整张构思表。需要先补资料时只返回明确plotRequests、worldBookRequests或roleRequests，不从候选正文末尾续写。DECISION先写完原方向内可自然完成的问答与善后，再附decision.question与stakes，停在新增重大承诺的表态之前。交付是完整故事，不是大纲、思考过程或检查报告。");
        out.put("identityContinuity","已有角色透露真名、改用称呼或揭示别称，仍是同一个roleId。用newCharacters:[{existingRoleId:原编号,name:本篇实际公开的新称呼,basis:正文披露依据}]表示更新，服务器保留其原经历、关系和物品，不再创建另一人物；真正新人物才不填existingRoleId。被救的无名后生稍后报姓名，不是又来了一个相同遭遇的人。公开新称呼需在正文自然获知，私密真实身份只留本人档案。");
        out.set("continuation",StoryContinuationContext.handoff(facts));
        out.set("episodeFocus",focus);
        return out;
    }
}
