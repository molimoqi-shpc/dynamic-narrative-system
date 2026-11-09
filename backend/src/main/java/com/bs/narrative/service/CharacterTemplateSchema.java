package com.bs.narrative.service;

import java.util.List;

/** One shared authoring contract, with detail proportional to the character's narrative role. */
public final class CharacterTemplateSchema {
    private CharacterTemplateSchema() {}
    public static final List<String> CORE_SECTIONS=com.bs.narrative.llm.ModelProtocol.CORE_SECTIONS;

    public static final String CONTRACT = """
        同时返回narrativeTier（CORE或EXTRA）与tierReason（简述依据），并填写characterTemplate。
        分级依据角色已有经历、持续目标和实际剧情关系，不按名字、职业、属性高低或关键词机械分类。CORE用于有持续剧情、独立目标、重要关系或冲突的人物；EXTRA用于仅提供普通背景服务、偶然问路等平庸龙套。不能把重要但暂时沉默、离开或与玩家不熟的人物降成龙套。previousPortrayal.narrativeTier为CORE的人物继续用CORE；旧详细档案也继续承接。新遇见的普通人可以只用EXTRA，日后实际形成重要关系或持续剧情再升级，升级只根据已有事实展开。
        EXTRA不填详细性格、隐秘动机、童年、创伤、逐人关系等细节，只填写简版characterTemplate：
        {"roleInScene":"已知身份及现场职责","immediateConcern":"此刻实际在意的事","attitude":"对当前交流的简单态度","willingHelp":"愿做什么、愿回答到哪里","knowledgeLimits":"实际知道与不知道的范围","boundaries":"本次明显不愿做的事；无则空"}。
        CORE使用下列分栏模板，逐项区分稳定人设、即时状态、真实过去、主观看法与未发生的意向。内容具体但不追求冗长，每格写有依据的信息，不能所有格都重复谨慎、观察之类标签。确无依据填null、空数组或“未知/尚未形成”；不因模板有栏目而发明家庭、年龄、性别、往事、爱恨、秘密或承诺。设定中明确的细节不得遗漏。身体、物品、六维、真实身份和亲历原文由服务器绑定，不重复抄入心理模板。以下字符串是字段解释，不是可直接照抄的人物内容：
        {
          "selfConcept":{"selfImage":"如何理解自己的身份与处境","socialRole":"本人认同的社会角色与责任","selfEsteem":"自尊依托及脆弱处","desiredImage":"希望别人怎样看待自己","innerContradiction":"自我认知与真实欲求间已有的矛盾","identityUncertainties":"仍不确定的自我问题"},
          "personality":{"stableTraits":[],"strengths":[],"flaws":[],"temperament":"日常性情","habits":[],"relaxedBehavior":"放松时的表现","stressResponse":"压力下的惯常应对","contradictorySides":"在不同关系和处境中的差别","changeConditions":"什么经历可能改变已有性格表现"},
          "values":{"coreValues":[],"priorityOrder":[],"moralLines":[],"loyalties":[],"taboos":[],"pride":"以什么为荣","shame":"已知羞耻或不愿触碰之处","viewOfPower":"对权力的看法","viewOfTrust":"如何理解信任与承诺","valueConflicts":"当前存在的价值冲突"},
          "emotionalState":{"dominantEmotion":"此刻主情绪","secondaryEmotions":[],"intensity":"强弱与克制程度","causes":[],"surfaceExpression":"旁人可能看见的表现","privateFeelings":"未表达的感受","unmetNeeds":[],"fears":[],"hopes":[],"emotionalTriggers":[],"regulation":"如何控制或缓和情绪","recentChange":"相比上次情绪如何变化及原因"},
          "cognition":{"attention":"此刻关注什么","interpretation":"如何解释最新实际经历","confirmedKnowledge":[],"hearsay":[],"suspicions":[],"unknowns":[],"biases":[],"confidence":"对当前判断的把握与依据","possibleMisunderstanding":"本人可能的误解，不写成世界事实"},
          "motivations":{"longTermGoals":[],"nearTermGoals":[],"immediateGoal":"整件事眼前想达成什么","priority":"目标冲突时先顾什么","personalStakes":"与本人有何利害","acceptableCosts":[],"unacceptableCosts":[],"hiddenAgenda":"确有依据的未公开目的，否则未知","urgency":"为何现在行动或可以等待","successMeaning":"本人认为怎样算达成"},
          "relationships":[{"roleId":"实际认识者真实id；未知则空","knownIdentity":"本人实际知道的身份","basis":"关系来自哪些实际交往","publicAttitude":"公开态度","privateAttitude":"私下看法","trust":"信任什么及其依据","wariness":"对什么戒备及其依据","attachment":"确已形成的情感或未形成","debtsAndPromises":[],"expectations":"希望对方如何行动","cooperationScope":"愿合作到什么程度","conflictPoints":[],"disclosureToThisPerson":"对他愿说与保留什么","recentChange":"最新互动造成的关系变化","changeConditions":"什么真实发展可能改变看法"}],
          "memoryInterpretation":{"formativeExperiences":[],"cumulativeHistory":"承接早期重要过往的累计理解，不只复述最新一轮","recentExperiences":[],"unresolvedThreads":[],"fulfilledCommitments":[],"outstandingCommitments":[],"regrets":[],"lessons":[],"reinterpretedPast":"新事实如何改变对旧事的理解；没有则空"},
          "expression":{"register":"语体、礼貌与称呼习惯","rhythm":"话多话少及表达节奏","wordingHabits":[],"nonverbalHabits":[],"willingTopics":[],"onlyIfAsked":[],"withheldInformation":[],"refusalStyle":"如何拒绝","conflictStyle":"争执时如何表达","comfortStyle":"如何关心人；未知则空","deceptionIntent":"有无确实的隐瞒或撒谎意图；不生成最终台词"},
          "cooperationAndConflict":{"willingParticipation":"本段愿参与的完整事情","initiative":"愿主动推进什么","nonNegotiables":[],"negotiableTerms":[],"allianceAttitude":"对实际结盟提议的态度；无提议不强造","hostilityAttitude":"对实际敌对的态度；无则空","intimacyAttitude":"已形成关系中的亲近尺度；无则空","riskTolerance":"愿承受何种风险","stanceChangeConditions":"改变当前立场所需的真实理由"},
          "actionPlan":{"primaryDirection":"接下来完整事件的行动方向","reason":"为何这样行动","naturalFollowThrough":"普通问答、查验、赶路如何自然接续","conditionalBranches":[{"if":"可能实际发生的条件","then":"届时本人愿怎样应对"}],"fallback":"遇到阻碍时可接受的替代方向","stopConditions":[],"needPlayerDecision":"真正需要玩家本人决定的事项；普通关怀问询不列入","notYetHappened":"明确以上都只是意愿，不是已发生结果"},
          "continuity":{"retainedAnchors":[],"changedSinceLast":[],"changeReasons":[],"unresolvedContradictions":[],"factsNeedingRecall":[]}
        }
        逐人关系只写自己实际认识的人，不凭主创视角获得他人的私密信息。previousPsychology是本人上次档案，保留稳定的人设锚点和累计过去，用新经历更新即时心理；旧版selfUnderstanding/pastExperienceSummary等字段可迁入对应新栏目。它的猜测不因写过就成事实，旧计划不因保留就重复执行。factsNeedingRecall可标明缺失的过往，交由主API查世界书，不凭空补全。
        字段齐全表达不等于每格都要长篇，重要差异和变化写清楚即可。没有变化的稳定项可简洁沿用，但不可只返回“同上”让主API丢失内容。不要生成最终对白或剧情结果；意愿中的秘密不会自动对外说出。
        最终仅返回一个JSON对象，其根层必须是：{"thought":"本人此刻想法","intention":"本段完整目标","actionDirection":"行动方向","communicationIntent":"交流意愿","boundaries":"底线","narrativeTier":"CORE或EXTRA二选一","tierReason":"分级依据","characterTemplate":{}}，并按本次实际事情附前述itemIntentions、contest与combatDisposition。涉及交手或救人时，combatDisposition必须说明获胜处置、落败应对、分寸或救援中的相关意愿；这些条件意愿不等于实际结果，无关此事时省略。上方12个分区只放在CORE的characterTemplate内部，不铺到根层；EXTRA的characterTemplate仅放6项简版字段，不同时生成另一份详细模板。两类都保留根层五项意愿字段。上方内容只是内部字段定义，不是第二个需要返回的对象。
        """;
}
