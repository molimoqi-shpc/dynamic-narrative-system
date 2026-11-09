package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Short-transaction effect application. A model proposes a process; it never writes inventories or psychology. */
@Service
public class OperationEffectService {
    @org.springframework.beans.factory.annotation.Autowired private VitalStateService vitality;
    @org.springframework.beans.factory.annotation.Autowired private OperationConflictService conflicts;
    @org.springframework.beans.factory.annotation.Autowired private CharacterStatService characterStats;
    @org.springframework.beans.factory.annotation.Autowired private OperationWorldExpansionService expansion;
    @org.springframework.beans.factory.annotation.Autowired private ItemPlacementService placements;
    @org.springframework.beans.factory.annotation.Autowired private SceneResourceService resources;
    @org.springframework.beans.factory.annotation.Autowired private WorldSettingService worldSettings;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final OpenSceneService physical;
    private final ItemStateService items;
    private final GodProfileRepository actors;
    private final GameSceneRepository scenes;
    private final TemporalContextService temporal;
    public OperationEffectService(JdbcTemplate jdbc,OpenSceneService physical,ItemStateService items,
            GodProfileRepository actors,GameSceneRepository scenes,TemporalContextService temporal){
        this.jdbc=jdbc;this.physical=physical;this.items=items;this.actors=actors;this.scenes=scenes;this.temporal=temporal;
    }
    private GodProfile actor(String sid,String id){return actors.findById(id).filter(a->sid.equals(a.getSessionId())).orElseThrow(()->new IllegalArgumentException("foreign actor"));}
    private String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public Map<String,Object> facts(String sid,GameScene scene,String actorId,String playerId){
        return facts(sid,scene,actorId,playerId,false);
    }
    public Map<String,Object> facts(String sid,GameScene scene,String actorId,String playerId,boolean coarseItems){
        var resources=new LinkedHashMap<String,Object>();var ids=new LinkedHashSet<>(scene.getPresentGods());if(playerPresent(sid,scene,playerId))ids.add(playerId);
        for(String id:ids){var a=actor(sid,id);var body=new LinkedHashMap<>(physical.view(sid,a,scene));body.remove("percepts");body.remove("execution");
            body.remove("updatedAtTick");
            if(coarseItems)body.keySet().retainAll(Set.of("health","alive","awake","bound"));
            boolean establishedStats=characterStats.established(sid,id);
            resources.put(id,Map.of("name",a.getGodName(),"isPlayer",id.equals(playerId),"body",body,"items",coarseItems?items.custody(sid,a):placements.own(sid,a),
                "sixStatsEstablished",establishedStats,"sixStats",establishedStats?com.bs.narrative.engine.StatsCalculator.calcActualStats(a).actualsAsMap():Map.of()));}
        var facts=new LinkedHashMap<String,Object>(Map.of("actorId",actorId,"resources",resources,"sceneId",scene.getSceneId(),"location",scene.getLocationName(),
            "openingEnvironment",Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0],"facilities",temporal.sceneState(sid,scene.getSceneId()),
            "exits",jdbc.queryForList("SELECT to_scene_id AS sceneId,exit_name AS name"+(coarseItems?"":",required_item AS requiredItem,required_objective AS requiredObjective")+" FROM scene_connection WHERE session_id=? AND from_scene_id=?",sid,scene.getSceneId())));
        facts.put("worldSetting",worldSettings.brief(sid));
        facts.put("actorName",actor(sid,actorId).getGodName());facts.put("playerId",playerId);facts.put("actorIsPlayer",actorId.equals(playerId));
        if(!coarseItems){facts.put("gatherableResources",this.resources.available(sid,scene));
        facts.put("consumptionRules",Map.of("firstAidItems",List.of("布绷带","绷带","止血药"),"firstAidHealth",10,"firstAidMinimumSeconds",10,"firstAidSelfOnly",true,"maximumHealth",100));}
        if(coarseItems){facts.put("itemTrackingMode","HOLDER_OR_SCENE");facts.put("sceneItems",items.sceneItems(sid,scene.getSceneId()));}
        facts.put("statePrecedence",coarseItems?"物品仅记录随谁携带或留在哪个场景，携带物随人物移动。手里、篓边等细节只属叙事，不登记位置。openingEnvironment是旧开场；当前持有、sceneItems与facilities优先。交接意愿不等于实际交接。":"openingEnvironment只作为初始环境与对象登记。resources中的身体/物品位置及facilities是当前状态，优先于旧开场；positionRecorded=false表示具体位置尚未结构化登记，不代表物品不存在。不能从人物说法直接改变物品位置或持有。");return facts;
    }
    public static final String CONTRACT="""
        你结算已经被执行者选择的开放动作，不替任何角色决定新的心理、话语、合作或反抗。人物是普通参与者，成功与否由身体、六维、现有实例、环境和具体方法决定。
        actorId/actorName是本次执行者，playerId是人类控制的玩家；两者可能不同。process仅供执行者自己回顾实际经历，可能包含其私有体验，不直接交给旁观者；“你”专指playerId。NPC的动作须以actorName为主语，不把执行者自己的“我”翻译为玩家的“你”，不将NPC物品或动作归给玩家。只结算attempt，不替执行者追加新选择。
        rejectedResultReason是上一份未提交结果违反事实或机制的原因。保持人物已经选择的attempt，仅修复执行结果；不重复旧事件、不改人物意图、不把技术拒绝改写成剧情失败。真实需要尚未支持的机制时仍保留待处理，不伪造成功。
        返回JSON：{"process":"已经实际发生的经过","durationSeconds":整数,"visibility":"PUBLIC或PRIVATE","effects":[],"status":"COMPLETED或BLOCKED或ONGOING"}。
        新结果另附observations:[{channel:"VISUAL",text:"旁观者能看见的实际经过",action:"外部可辨认的动作名，可空",status:"可选，仅对应此可见动作的COMPLETED/BLOCKED/ONGOING；无法观察其结果则省略"}]。触感、疼痛、主观判断仅留process或本人percepts，不复制到VISUAL；公开过程可以详尽自然，不要求使用固定措辞。看不见的经过不输出视觉观察。收件人由服务器判断，不能用观察替他人作决定或补造历史。
        snapshotAtSeconds标明冻结输入时点。其他角色可能并行行动，不能把输入时的姿势、活动、沉默当成本动作结束时仍然成立；recentPerceivedChanges中的已交付经过优先于旧开场描述。process和VISUAL只写本次实际动作，不顺手复写全员旧姿势，不替其他角色预言同刻结果。实际查看现场须附OBSERVE，服务器会在同刻变化全部提交后更新可见状态。
        时间上限以输入maxDurationSeconds为准，缺省30秒。episodeMode=true时，普通调查、整理、查看与赶路可完整执行一个有结果的行动段，琐碎过程概括带过，不必每30秒或每拨动一点就截断；达到真实干预边界或尚未完成才ONGOING及JSON正整数remainingSeconds。不能替别人作决定，冲突与重要人物交涉仍在真实边界结算。ONGOING须有实际耗时，网络耗时不计入世界。
        如输入含acceptedWork，这是人物已选择且未中断的工作；沿实际进度继续，无需重做旧检查或重选立场。remainingSeconds表示剩余工作量，继续后须减少，真实新障碍用BLOCKED和具体依据反馈。
        completedWorkSeconds若存在，是服务器核验该项连续工作此前已实际投入的秒数；治疗的10秒可由此前片段与当前片段共同达到，不重做已经完成的包扎步骤，也不计算中途等待、聊天或网络耗时。
        continuationInstructions是玩家每次明确续接时的完整原话，属于本次执行的附加限制。它们与原attempt的约束同时有效，不能因沿用旧动作而丢掉新的“不触碰、不移动、只看”等限制。续接不会重演已完成的部分，也不授权自动说出旧话或增加原动作之外的行为。
        ONGOING及remainingSeconds只用于有实际剩余工作量的有限工作，不能原样复报旧剩余量假装推进。持续看守、保持姿势等本时间段已发生的过程可COMPLETED，保留真实身体状态，不编造其未知结束时刻。没有推进而确实受阻则按现场障碍BLOCKED，不伪装角色主动放弃。
        如有resumeFromSeconds，旧结果没有获准提交。读取本次新事实，仅结算从该边界起剩余的真实经过，durationSeconds从该时刻算，不能重演已提交的别人的行动。
        可用效果（机制类型约束状态写入，不限制动作措辞）：
        BODY {kind,actorId,before:{字段:原值},after:{字段:新值}}，字段awake/bound/activity/position/percepts。通常只改自己；实际解除已有束缚可改对方bound，不能用它制造攻击命中、昏迷或替人行动。
        activityHistorical是服务器维护的来源标记，禁止在before/after回写。为true时activity只是此前记录，不是当前仍在旧地点做事；其中身体症状也不能因移动就视为痊愈。只有本次实际活动变化才写activity，服务器更新标记。
        updatedAtTick是服务器维护的状态更新时间，绝不能写进BODY的before或after；不复制历史中的整份身体快照，只提交实际变化的可写字段。
        场景内实际走近、退回、换站位也必须用BODY写入position，不能只在process里描述移动。before.position引用resources中自己的原值，after.position写实际站位；OBSERVE只更新所见，不更新位置。移动是尝试，真实未挪动可保持原位，不能为补齐字段伪造移动成功。
        TRANSFER {kind,fromId,toId,itemRef,mode:"GIFT或LEND或RETURN"}，必须是执行者实际持有的物品实例。借用保留原所有者。不能代收别人的物品或强迫他人交付。
        GATHER {kind,sourceRef,basis:"process中包含材料名称与实际拾取完成的连续原文"}。仅可逐字引用gatherableResources中的ref；名称、来源与新物品编号由服务器绑定。每处同名来源只登记一份可取得的普通自然材料，不是无限资源。没有列出的材料不能凭空获得，不能拿人物持有物或拆设施充当采集。完成且身体未受束缚才入背包，同时扣除该来源；新的物品从下一次动作起可用，顶层uses不填写尚未取得的物品或sourceRef。BLOCKED/ONGOING不提前发放。
        CONSUME {kind,itemRef,purpose:"USE_UP或FIRST_AID",basis:"process中包含物品全名与实际使用完成的连续原文"}。itemRef必须自己持有、自己所有，并列入uses；每次完整消耗一件真实实例，不能消耗借物或凭空拆分数量。USE_UP仅表示实际用尽、吃完、喝完、烧尽、耗尽或撕碎该物品，不恢复生命。FIRST_AID仅可对自己使用consumptionRules.firstAidItems中的现有物品完成包扎/敷用/使用，至少10秒，消耗一件并由服务器恢复10生命、上限100；生命0或满血时不能治疗成功或消耗治疗物品，按实际受阻反馈。不能将树叶、任意食物或普通材料解释成药，不能借治疗唤醒、复活、解除束缚或强迫别人接受治疗；不清除未实际变化的身体症状。仅COMPLETED可消耗。不要输出healthBefore/healthAfter/healthDelta，不能自行指定回血量。
        CRAFT {kind,name:"制成的普通物品名",materials:[消耗的现有物品ref],tools:[保留的现有工具ref],basis:"process中连续原文，明确说已制成并包含成品名"}。仅COMPLETED且实际完成的普通制作可用；至少一种材料，最多8种，工具可为空数组。materials和tools均须逐字引用执行者resources.items并全部列入顶层uses。材料必须属于自己，借来的工具可使用但不能拆作材料；同一实例不能重复、兼作材料和工具或在多次制作中重复消耗。普通加工不能制造符咒、法器或规则能力。完成时材料从背包消耗、工具保留、成品获得稳定实例和来源记录。无需模型编造成品ref，下一次动作才能引用新实例。缺少材料、工具或方法不可行时如实BLOCKED；未完成时ONGOING，不提前生成成品。不是所有制作都能在30秒内完成。不能把别人的物品、场景设施或尚未获得的物品当作材料。
        ITEM_POSITION {kind,itemRef,before:"resources中该物品当前position原值",after:"实际新放置位置"}，记录自己控制物品的位置变化，例如由脚边放到膝上或持到手中；不改变持有者和所有权，不用于丢弃、藏匿或凭空获得。旧position未登记时before为空串。关键物品在过程中的拿起、放下或位置变化必须有这个效果；仅口头许可不改位置。交付后具体位置重新为未登记，明确放置时在后续真实动作提交。
        FACILITY {kind,objectName,before:"目前状态，尚无独立状态用空串",after:"实际新状态"}，对象必须已在openingEnvironment或facilities中存在。
        MOVE {kind,actorId,fromSceneId,toSceneId}，执行者沿实际可通行的已存在出口到达，不凭空造地点。
        MOVE/EXPLORE抵达是本次执行的场景边界，每份结果最多一次。出发前实际交物、放置、身体与设施变化按发生顺序写在移动之前；移动后只可附OBSERVE查看抵达地点。到达后的交涉、操作设施与再次移动须在新现场另行结算，不沿用出发地点的人物与资源；服务器自动记录抵达位置，不在移动后用BODY覆盖。不能为满足顺序删去已经发生的出发前变化。
        OBSERVE {kind,scope:"VISIBLE_SCENE"}，执行者和当前实际场景由服务器绑定，不生成或复制actorId/sceneId；旧回显不能选择远处场景。执行者实际查看当前现场，更新自己可见人物的最近所见记录，不改变他人的身体、不生成新人物，不揭露隐藏者。
        只有输入含worldGenerationPermission时可EXPLORE {kind,direction,exitName,returnRoute,scene:{locationName,opening,characters:[{name,stats:{zhi,hui,dou,wen,ben,gui},inventory:[明确自有物品名],body:{awake,bound,position,activity},privateBackground}]}}。
        这是玩家确实选择探索后的新地点事实包，生成真实路线与初始资源、身体，随后沿路线抵达。最多一个新地点、四个人物，不指定人物必须帮助玩家；新人物私有背景不写入opening或process。不能改既有角色的过去。无人在场可characters为空。
        returnRoute必须是JSON布尔值true或false，表示实际能否原路返回；普通无阻的山径通常true，真实单向通路才false。不要写成“沿原路返回”等说明文字，也不能省略。路线经过留在process、exitName和opening中，不能用文字替代布尔值使回程登记丢失。
        worldGenerationPermission.canCreate=true且实际抵达新地点时，必须返回完整EXPLORE事实包；不能只写MOVE并自造toSceneId。MOVE的toSceneId必须逐字引用exits中的sceneId。新地点ID由服务器生成，模型不编ID。方向原样引用worldGenerationPermission.direction，包括OTHER；OTHER表示已授权的相对方向，不表示不能探索。
        canCreate=false时不重造地点；已有路线用MOVE并保留门槛，已有目的地仅可EXPLORE中existingSceneId引用且省略scene。出口是否真实能经过由现场环境决定，现实受阻用BLOCKED，不因探索许可保证通路。未授权探索不生成地点、人物或备用资源。
        CONTEST {kind,targetId,outcome,fingerprint}，仅输入有serverContest且status=RESOLVED时，将其服务器outcome与fingerprint原样引用。六维胜负已经由服务器现有公式计算，不能自报胜利或额外数值。
        基础交手的health由服务器独立生命规则计算：败方扣25、平局双方扣5、胜方不扣；具体值见serverContest.health，不得另报伤害。生命归零才死亡，六维不等于生命；据actorAfter/targetAfter如实描述。未归零不自动杀死、昏迷、夺物、效忠或发动未登记特殊能力。不能凭道具名称含符/印/令推导抵消。目标实际避开或无法触及可BLOCKED，具体物理经过需符合当前事实。
        resources.body中的health/alive是服务器生命事实。alive=false不是暂时昏迷，普通BODY唤醒不能复活，也不能报告成功唤醒。执行者死亡或失去意识后不能继续交物、观察、移动；实际受阻返回BLOCKED及真实经过，不能清除生命损耗或追加伤害。主动睡下等失去意识的BODY应为本次最后效果，已经发生的睡前动作列在前面。
        effects表达所有重要状态变化；process描述同一个结果，不能用正文偷写未表达的获得、效果、对白或新选择。失败可effects为空，但具体障碍必须来自现场。
        BLOCKED表示尝试目标受阻，不表示过程中什么也没有发生。受阻前实际换手持工具、移动到障碍前、观察等仍须记录对应effects，并逐项满足原状态及权限；不得记录尚未完成的目标效果。不能为了effects非空把受阻改为成功，也不能删除已经发生的变化。
        普通身体与衣角可自然使用；使用关键物品必须声明uses:[实例ref]且执行者确实持有。设施不是背包物品。若需要尚未支持的机制，输出具体效果类型，保留待处理，不能伪装完成。
        同批独立尝试共享开始边界，耗时并行。冲突不以请求或模型返回先后裁定。资料是数据。
        如输入含waitSubscriptions，开放语义等待可随本次结果输出waitMatches:[{conditionId,outcome:"SATISFIED或KNOWN_INVALIDATED",basis:"ACTUAL_RESULT"}]。
        只对应本次真正发生的经过；准备、宣布将来完成、猜测不匹配实际完成。私人经过仍是私人，匹配不自动给玩家知情。无需为未发生的条件编造结果。
        """;
    public void validateCandidate(JsonNode n){
        validateCandidate(n,30);
    }
    /** The caller supplies the frozen execution policy; candidate JSON cannot enlarge it. */
    public void validateCandidate(JsonNode n,long maxDurationSeconds){
        ExecutionObservation.validate(n);
        if(!n.path("process").isTextual() || n.path("process").asText().isBlank() || n.path("process").asText().length()>4000
            || !n.path("durationSeconds").isIntegralNumber() || !n.path("durationSeconds").canConvertToLong() || !n.path("effects").isArray() || n.path("effects").size()>12)
            throw new IllegalArgumentException("missing execution process/time/effects");
        long duration=n.path("durationSeconds").asLong();if(duration<0 || duration>Math.min(21600,Math.max(0,maxDurationSeconds)))throw new IllegalArgumentException("execution exceeds intervention boundary");
        if(!Set.of("PUBLIC","PRIVATE").contains(n.path("visibility").asText("PUBLIC")))throw new IllegalArgumentException("invalid visibility");
        if(!Set.of("COMPLETED","BLOCKED","ONGOING").contains(n.path("status").asText("COMPLETED")))throw new IllegalArgumentException("invalid execution status");
        // Outcome is independent of effect authorization: an unsuccessful attempt still has a real process.
        if("ONGOING".equals(n.path("status").asText()) && (!n.path("remainingSeconds").isIntegralNumber() || !n.path("remainingSeconds").canConvertToLong() || n.path("remainingSeconds").asLong()<1))throw new IllegalArgumentException("ongoing work needs remaining work");
        if("ONGOING".equals(n.path("status").asText()) && duration==0)throw new IllegalArgumentException("ongoing work needs elapsed time");
        // Reject invalid tails before any write, including a move or transfer in the same packet.
        boolean departed=false,unconscious=false;
        for(var effect:n.path("effects")){
            String kind=effect.path("kind").asText();
            if(!Set.of("BODY","OBSERVE","EXPLORE","CONTEST","TRANSFER","DROP","PICKUP","ITEM_POSITION","CRAFT","GATHER","CONSUME","FACILITY","MOVE").contains(kind))throw new IllegalArgumentException("effect mechanism unresolved: "+kind);
            if("BODY".equals(kind))validateBodyShape(effect);
            if(unconscious)throw new IllegalArgumentException("effects cannot continue after loss of consciousness");
            if(departed && !"OBSERVE".equals(kind))throw new IllegalArgumentException("arrival boundary permits only observation after movement");
            if(Set.of("MOVE","EXPLORE").contains(kind))departed=true;
            if("BODY".equals(kind) && effect.path("after").path("awake").isBoolean() && !effect.path("after").path("awake").asBoolean())unconscious=true;
        }
    }
    private void validateBodyShape(JsonNode effect){
        if(!effect.path("before").isObject() || !effect.path("after").isObject())throw new IllegalArgumentException("body needs before/after");
        var names=effect.path("after").fieldNames();while(names.hasNext())if(!Set.of("awake","bound","activity","position","percepts","updatedAtTick").contains(names.next()))throw new IllegalArgumentException("invalid body field");
        if(effect.path("before").has("updatedAtTick") || effect.path("after").has("updatedAtTick"))throw new IllegalArgumentException("body timestamp is server owned");
        var changes=effect.path("after").fields();
        while(changes.hasNext()){var change=changes.next();String field=change.getKey();var value=change.getValue();
            if(!Set.of("awake","bound","activity","position","percepts").contains(field))throw new IllegalArgumentException("invalid body field");
            if(!effect.path("before").has(field))throw new IllegalArgumentException("body effect needs matching before field");
            if(Set.of("awake","bound").contains(field) && !value.isBoolean() || Set.of("activity","position").contains(field) && (!value.isTextual() || value.asText().length()>1000) || "percepts".equals(field) && (!value.isArray() || value.size()>10))throw new IllegalArgumentException("invalid body field value");
        }
    }
    /** Throws on shared exclusive writes; caller rolls back the entire batch, including speech/delivery. */
    public void apply(String sid,GameScene scene,String role,String player,JsonNode n,Set<String> claimed){
        apply(sid,scene,role,player,n,claimed,0);
    }
    /** Prior work time is supplied only by the operation's persisted task, never by candidate JSON. */
    public void apply(String sid,GameScene scene,String role,String player,JsonNode n,Set<String> claimed,long priorWorkSeconds){
        apply(sid,scene,role,player,n,claimed,priorWorkSeconds,30);
    }
    public void apply(String sid,GameScene scene,String role,String player,JsonNode n,Set<String> claimed,long priorWorkSeconds,long maxDurationSeconds){
        apply(sid,scene,role,player,n,claimed,priorWorkSeconds,maxDurationSeconds,false);
    }
    public void apply(String sid,GameScene scene,String role,String player,JsonNode n,Set<String> claimed,long priorWorkSeconds,long maxDurationSeconds,boolean coarseItems){
        if(coarseItems){applyStoryResults(sid,scene,role,player,n,maxDurationSeconds);return;}
        if(coarseItems)validateStoryResult(n,maxDurationSeconds);else validateCandidate(n,maxDurationSeconds);var self=actor(sid,role);
        if(role.equals(player)?!playerPresent(sid,scene,role):!scene.getPresentGods().contains(role))throw new IllegalArgumentException("actor left scene");
        boolean recovery=n.path("effects").size()==1 && "BODY".equals(n.path("effects").get(0).path("kind").asText()) && role.equals(n.path("effects").get(0).path("actorId").asText()) && n.path("effects").get(0).path("after").path("awake").asBoolean();
        if(recovery){var fields=n.path("effects").get(0).path("after").fieldNames();while(fields.hasNext())if(!Set.of("awake","activity","percepts").contains(fields.next()))recovery=false;}
        boolean inactiveBlocked="BLOCKED".equals(n.path("status").asText()) && n.path("effects").isEmpty() && n.path("uses").isEmpty();
        if(vitality.health(sid,role)==0 && !inactiveBlocked)throw new IllegalArgumentException("dead actor cannot act or awaken");
        if(!Boolean.TRUE.equals(physical.view(sid,self,scene).get("awake")) && !recovery && !inactiveBlocked)throw new IllegalArgumentException("actor cannot act while unconscious");
        var refs=items.owned(sid,self).stream().map(ItemStateService.Item::ref).collect(java.util.stream.Collectors.toSet());
        if(n.has("uses") && !n.path("uses").isArray())throw new IllegalArgumentException("invalid uses");
        for(var use:n.path("uses"))if(!use.isTextual() || !refs.contains(use.asText()))throw new IllegalArgumentException("tool not held");
        GameScene observationScene=scene;var localItemClaims=new HashSet<String>();
        for(var e:n.path("effects")){
        // A preceding contest may have exhausted this actor's life in the same result.
        if(vitality.health(sid,role)==0)throw new IllegalArgumentException("dead actor cannot act or awaken");
        switch(e.path("kind").asText()){
            case "OBSERVE" -> {
                if(!"VISIBLE_SCENE".equals(e.path("scope").asText()) || e.hasNonNull("actorId") && !e.path("actorId").asText().isBlank() && !role.equals(e.path("actorId").asText()))throw new IllegalArgumentException("local observation belongs to the executing actor");
                if(role.equals(player)?!playerPresent(sid,observationScene,role):!observationScene.getPresentGods().contains(role))throw new IllegalArgumentException("observer is not in the executing scene");
                // VISIBLE_SCENE has no model-selected destination. Never read the echoed ID.
                ((com.fasterxml.jackson.databind.node.ObjectNode)e).put("actorId",role).put("sceneId",observationScene.getSceneId());
            }
            case "EXPLORE" -> {
                if(!role.equals(player) || !playerPresent(sid,scene,player) || Boolean.TRUE.equals(physical.view(sid,self,scene).get("bound")))throw new IllegalArgumentException("cannot explore from this body/location");
                claim(claimed,"expansion:"+scene.getSceneId()+":"+e.path("direction").asText());String destination=coarseItems?expansion.accept(sid,scene,e,true):expansion.accept(sid,scene,e);observationScene=scenes.findBySessionIdAndSceneId(sid,destination).orElseThrow();
                var move=JSON.createObjectNode().put("kind","MOVE").put("actorId",role).put("fromSceneId",scene.getSceneId()).put("toSceneId",destination);
                var actual=JSON.createObjectNode().put("process",n.path("process").asText()).put("durationSeconds",0).put("status","COMPLETED");actual.putArray("effects").add(move);apply(sid,scene,role,player,actual,claimed,0,maxDurationSeconds,coarseItems);
            }
            case "CONTEST" -> conflicts.apply(sid,scene,role,player,e,claimed);
            case "BODY" -> {
                String target=e.path("actorId").asText();boolean rescue=!target.equals(role);
                if(rescue && !scene.getPresentGods().contains(target) && !(target.equals(player) && playerPresent(sid,scene,player)))throw new IllegalArgumentException("body not present");
                if(!e.path("before").isObject() || !e.path("after").isObject())throw new IllegalArgumentException("body needs before/after");
                if(e.path("before").has("updatedAtTick") || e.path("after").has("updatedAtTick"))throw new IllegalArgumentException("body timestamp is server owned");
                var body=new LinkedHashMap<>(physical.view(sid,actor(sid,target),scene));var changes=e.path("after").fields();
                while(changes.hasNext()){var change=changes.next();String field=change.getKey();JsonNode value=change.getValue();
                    if(!Set.of("awake","bound","activity","position","percepts").contains(field) || !e.path("before").has(field))throw new IllegalArgumentException("invalid body field");
                    if(Set.of("awake","bound").contains(field) && !value.isBoolean() || Set.of("activity","position").contains(field) && (!value.isTextual() || value.asText().length()>1000)
                        || "percepts".equals(field) && (!value.isArray() || value.size()>10))throw new IllegalArgumentException("invalid body field");
                    if(!JSON.valueToTree(body.get(field)).equals(e.path("before").get(field)))throw new StateConditionException("body condition changed",target,field,e.path("before").get(field),body.get(field));
                    boolean release="bound".equals(field) && Boolean.TRUE.equals(body.get(field)) && value.isBoolean() && !value.asBoolean();
                    boolean awaken="awake".equals(field) && Boolean.FALSE.equals(body.get(field)) && value.isBoolean() && value.asBoolean();
                    if("awake".equals(field) && value.asBoolean() && vitality.health(sid,target)==0)throw new IllegalArgumentException("ordinary awakening cannot resurrect a dead actor");
                    if(!coarseItems && rescue && !release && !awaken)throw new IllegalArgumentException("cannot choose another actor's action or inflict injury through BODY");
                    claim(claimed,"body:"+target+":"+field);body.put(field,JSON.convertValue(value,Object.class));
                }
                if(e.path("after").has("activity"))body.put("activityHistorical",false);
                body.put("updatedAtTick",temporal.tick(sid));store(sid,target,"view:"+scene.getSceneId(),"SCENE_VIEW",scene,body);
                storeBody(sid,target,scene,body);
            }
            case "TRANSFER" -> {
                String from=e.path("fromId").asText(),to=e.path("toId").asText(),ref=e.path("itemRef").asText();
                if(!role.equals(from) || !scene.getPresentGods().contains(to) && !(player.equals(to) && playerPresent(sid,scene,player)))throw new IllegalArgumentException("transfer is not authorized by the holder");
                claimItem(claimed,localItemClaims,ref);items.transferExact(sid,self,actor(sid,to),ref,e.path("mode").asText());
                if(!coarseItems)placements.resetAfterTransfer(sid,to,scene.getSceneId(),ref);
            }
            case "DROP", "PICKUP" -> {
                if(!coarseItems)requireCompletedUse(n,self,sid,scene);
                if(!e.path("itemRef").isTextual() || e.path("itemRef").asText().isBlank())throw new IllegalArgumentException("item custody update needs an item reference");
                String ref=e.path("itemRef").asText();claimItem(claimed,localItemClaims,ref);
                var item="DROP".equals(e.path("kind").asText())?items.leaveInScene(sid,self,scene.getSceneId(),ref):items.takeFromScene(sid,self,scene.getSceneId(),ref);
                ((ObjectNode)e).put("name",item.name()).put("sceneId",scene.getSceneId());
            }
            case "GAIN" -> {
                requireStoryMode(coarseItems);
                String name=e.path("name").asText(),source=e.path("source").asText();
                if(!e.path("name").isTextual() || name.isBlank() || name.length()>64 || !e.path("source").isTextual() || source.isBlank() || source.length()>2000)throw new IllegalArgumentException("新获得物品需要名称与剧情来源");
                var gained=items.acquireNarrative(sid,self,name,source);
                ((ObjectNode)e).put("itemRef",gained.ref());
            }
            case "USE_UP" -> {
                requireStoryMode(coarseItems);String ref=e.path("itemRef").asText();
                var used=items.owned(sid,self).stream().filter(i->ref.equals(i.ref())).findFirst().orElseThrow(()->new IllegalArgumentException("实际消耗物品已不在本人处"));
                claimItem(claimed,localItemClaims,ref);items.consumeExact(sid,self,ref,"STORY_USE_UP");((ObjectNode)e).put("name",used.name());
            }
            case "FACT" -> {
                requireStoryMode(coarseItems);String name=e.path("objectName").asText(),after=e.path("after").asText();
                if(!e.path("objectName").isTextual() || name.isBlank() || name.length()>40 || !e.path("after").isTextual() || after.isBlank() || after.length()>2000)throw new IllegalArgumentException("重要剧情事实需要对象与实际结果");
                temporal.writeFacility(sid,player,scene,name,after,n.path("process").asText());
            }
            case "HEALTH" -> {
                requireStoryMode(coarseItems);String target=e.path("actorId").asText(role);
                if(!scene.getPresentGods().contains(target) && !(target.equals(player) && playerPresent(sid,scene,player)))throw new IllegalArgumentException("生命变化对象不在现场");
                if(!e.path("health").isIntegralNumber() || e.path("health").asLong()<0 || e.path("health").asLong()>100)throw new IllegalArgumentException("生命值须在0至100之间");
                vitality.applyStoryHealth(sid,target,e.path("health").asInt());
            }
            case "ITEM_POSITION" -> {
                if(!e.path("before").isTextual() || !e.path("after").isTextual())throw new IllegalArgumentException("item placement needs before/after");
                String ref=e.path("itemRef").asText();claimItem(claimed,localItemClaims,ref);
                placements.place(sid,self,scene.getSceneId(),ref,e.path("before").asText(),e.path("after").asText());
            }
            case "CRAFT" -> {
                if(!"COMPLETED".equals(n.path("status").asText("COMPLETED")) || Boolean.TRUE.equals(physical.view(sid,self,scene).get("bound")))throw new IllegalArgumentException("craft output requires completed work and an unbound actor");
                if(e.hasNonNull("actorId") && !role.equals(e.path("actorId").asText()))throw new IllegalArgumentException("craft belongs to the executing actor");
                var plan=items.prepareOrdinaryCraft(sid,self,e,n.path("process").asText(),n.path("uses"));
                for(var craft:plan.crafts()){
                    for(var material:craft.materials())claimItem(claimed,localItemClaims,material.ref());
                    for(var tool:craft.tools())claimItem(claimed,localItemClaims,tool.ref());
                }
                items.apply(sid,plan,Map.of(role,self));
            }
            case "GATHER" -> {
                requireCompletedUse(n,self,sid,scene);
                if(!e.path("sourceRef").isTextual() || !e.path("basis").isTextual())throw new IllegalArgumentException("gather needs source and basis");
                claim(claimed,"resource:"+e.path("sourceRef").asText());
                var acquired=resources.take(sid,scene,self,player,e.path("sourceRef").asText(),n.path("process").asText(),e.path("basis").asText());
                ((ObjectNode)e).put("name",acquired.name()).put("objectName",acquired.name()).put("after",SceneResourceService.takenState(acquired.name()));
            }
            case "CONSUME" -> {
                requireCompletedUse(n,self,sid,scene);
                String ref=e.path("itemRef").asText(),purpose=e.path("purpose").asText(),basis=e.path("basis").asText();
                if(e.has("healthBefore") || e.has("healthAfter") || e.has("healthDelta") || e.hasNonNull("targetId") && !role.equals(e.path("targetId").asText()) || e.hasNonNull("actorId") && !role.equals(e.path("actorId").asText()))throw new IllegalArgumentException("consumption cannot choose another actor or server health");
                var item=items.owned(sid,self).stream().filter(i->ref.equals(i.ref())).findFirst().orElseThrow(()->new IllegalArgumentException("consumed item no longer held"));
                boolean declared=false;for(var use:n.path("uses"))if(ref.equals(use.asText()))declared=true;
                if(!declared || !role.equals(ItemStateService.legalOwner(item)) || !e.path("basis").isTextual() || basis.isBlank() || !basis.contains(item.name()) || !n.path("process").asText().contains(basis)
                    || !OpenSceneService.positiveEvidence(List.of(n.path("process").asText()),basis))throw new IllegalArgumentException("consumption requires owned declared item and actual use basis");
                if("FIRST_AID".equals(purpose)){
                    if(!Set.of("布绷带","绷带","止血药").contains(item.name()) || priorWorkSeconds<10-n.path("durationSeconds").asLong() || vitality.health(sid,role)<=0 || vitality.health(sid,role)>=100 || !basis.matches("(?s).*(包扎|敷上|敷用|使用).*"))throw new IllegalArgumentException("first aid needs registered item, completed treatment time and injury");
                    claim(claimed,"stats:"+role);
                }else if(!"USE_UP".equals(purpose) || !basis.matches("(?s).*(用尽|吃完|喝完|烧尽|耗尽|撕碎).*"))throw new IllegalArgumentException("unsupported or incomplete consumption");
                claimItem(claimed,localItemClaims,ref);items.consumeExact(sid,self,ref,purpose);
                if("FIRST_AID".equals(purpose))vitality.applyFirstAid(sid,role);
                ((ObjectNode)e).put("name",item.name());
            }
            case "FACILITY" -> {
                String name=e.path("objectName").asText(),after=e.path("after").asText();
                String opening=Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0];
                var existing=temporal.sceneState(sid,scene.getSceneId()).stream().filter(s->name.equals(s.get("objectName"))).findFirst();
                if(!e.path("objectName").isTextual() || name.length()<2 || name.length()>40 || !opening.contains(name) && existing.isEmpty() || !e.path("after").isTextual() || after.isBlank() || after.length()>1000
                    || !e.path("before").isTextual())throw new IllegalArgumentException("facility missing or invalid");
                if(jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name=?",Integer.class,sid,name)>0
                    || jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND JSON_CONTAINS(inventory,JSON_QUOTE(?))",Integer.class,sid,name)>0)throw new IllegalArgumentException("not a facility");
                if(!e.path("before").asText().equals(existing.map(s->s.get("state").toString()).orElse("")))throw new StateConditionException("facility condition changed",name,"state",e.path("before").asText(),existing.map(v->v.get("state")).orElse(""));
                claim(claimed,"facility:"+scene.getSceneId()+":"+name);
                temporal.writeFacility(sid,player,scene,name,after,n.path("process").asText());
            }
            case "MOVE" -> {
                String destination=e.path("toSceneId").asText();
                if(!role.equals(e.path("actorId").asText()) || !scene.getSceneId().equals(e.path("fromSceneId").asText()) || Boolean.TRUE.equals(physical.view(sid,self,scene).get("bound")))throw new IllegalArgumentException("cannot move from this body/location");
                var edges=jdbc.queryForList("SELECT required_item,required_objective FROM scene_connection WHERE session_id=? AND from_scene_id=? AND to_scene_id=?",sid,scene.getSceneId(),destination);
                if(edges.isEmpty())throw new IllegalArgumentException("no connected route");var edge=edges.get(0);
                String item=Objects.toString(edge.get("required_item"),"");String goal=Objects.toString(edge.get("required_objective"),"");
                if(!item.isBlank() && !Optional.ofNullable(self.getInventory()).orElse(List.of()).contains(item))throw new IllegalArgumentException("route requires a held item");
                if(!goal.isBlank() && (!role.equals(player) || jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND objective_id=? AND status='COMPLETED'",Integer.class,sid,goal)==0))throw new IllegalArgumentException("route condition unsatisfied");
                claim(claimed,"location:"+role);var to=scenes.findBySessionIdAndSceneId(sid,destination).orElseThrow(()->new IllegalArgumentException("destination missing"));
                if(role.equals(player)){
                    if(!playerPresent(sid,scene,player))throw new IllegalArgumentException("player is elsewhere");
                    store(sid,role,"visited:"+scene.getSceneId(),"VISITED_SCENE",scene,Map.of("sceneId",scene.getSceneId()));
                    jdbc.update("UPDATE player_profile SET current_scene_id=? WHERE active_session_id=? AND god_id=?",destination,sid,role);jdbc.update("UPDATE game_session SET current_scene_id=? WHERE session_id=?",destination,sid);
                    store(sid,role,"visited:"+destination,"VISITED_SCENE",to,Map.of("sceneId",destination));
                }
                else {var fromIds=new ArrayList<>(scene.getPresentGods());fromIds.remove(role);scene.setPresentGods(fromIds);var toIds=new ArrayList<>(to.getPresentGods());if(!toIds.contains(role))toIds.add(role);to.setPresentGods(toIds);scenes.save(to);scenes.save(scene);}
                var arrived=new LinkedHashMap<>(physical.view(sid,self,scene));arrived.put("position","抵达"+to.getLocationName());arrived.put("playerKnown",false);arrived.put("updatedAtTick",temporal.tick(sid));
                // Legacy activity can also contain bodily symptoms. Preserve it as a historical
                // record, never a claim of continuing the old scene's activity at the destination.
                arrived.put("activityHistorical",true);arrived.put("percepts",List.of());arrived.put("execution",Map.of());
                if(!coarseItems)placements.resetAfterTravel(sid,self,destination);
                storeBody(sid,role,to,arrived);store(sid,role,"view:"+to.getSceneId(),"SCENE_VIEW",to,arrived);observationScene=to;
            }
            default -> throw new IllegalArgumentException("effect mechanism unresolved: "+e.path("kind").asText());
        }
        }
        actors.flush();scenes.flush();
    }
    /** Store the author's actual results. No physical eligibility, word matching or route simulation. */
    private void applyStoryResults(String sid,GameScene scene,String role,String player,JsonNode n,long maxDurationSeconds){
        validateStoryResult(n,maxDurationSeconds);var self=actor(sid,role);
        for(var e:n.path("effects"))switch(e.path("kind").asText()){
            case "GAIN" -> {
                String name=e.path("name").asText(),source=e.path("source").asText();
                if(name.isBlank() || name.length()>64 || source.isBlank() || source.length()>2000)throw new IllegalArgumentException("新取得物品需要名称与剧情来源");
                ((ObjectNode)e).put("itemRef",items.acquireNarrative(sid,self,name,source).ref());
            }
            case "USE_UP" -> items.consumeNarrative(sid,self,e.path("itemRef").asText());
            case "TRANSFER" -> {
                var from=actor(sid,e.path("fromId").asText());var to=actor(sid,e.path("toId").asText());
                items.transferNarrative(sid,from,to,e.path("itemRef").asText(),e.path("mode").asText());
            }
            case "DROP" -> items.leaveInScene(sid,self,scene.getSceneId(),e.path("itemRef").asText());
            case "PICKUP" -> items.takeFromScene(sid,self,scene.getSceneId(),e.path("itemRef").asText());
            case "FACT" -> {
                String name=e.path("objectName").asText(),after=e.path("after").asText();
                if(name.isBlank() || name.length()>40 || after.isBlank() || after.length()>2000)throw new IllegalArgumentException("重要剧情事实需要对象与实际结果");
                temporal.writeFacility(sid,player,scene,name,after,n.path("process").asText());
            }
            case "HEALTH" -> {
                String who=e.path("actorId").asText(role);actor(sid,who);
                if(!e.path("health").isIntegralNumber() || e.path("health").asLong()<0 || e.path("health").asLong()>100)throw new IllegalArgumentException("生命值须在0至100之间");
                vitality.applyStoryHealth(sid,who,e.path("health").asInt());
            }
            case "BODY" -> {
                String who=e.path("actorId").asText(role);var person=actor(sid,who);var actualScene=who.equals(player)?scenes.findBySessionIdAndSceneId(sid,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE active_session_id=? AND god_id=?",String.class,sid,who)).orElseThrow():physical.actorScene(sid,who).orElse(scene);
                var body=new LinkedHashMap<>(physical.view(sid,person,actualScene));
                for(var field:e.path("after").properties()){if(!Set.of("awake","bound").contains(field.getKey()) || !field.getValue().isBoolean())throw new IllegalArgumentException("重要身体状态须为清醒或束缚布尔值");body.put(field.getKey(),field.getValue().asBoolean());}
                storeBody(sid,who,actualScene,body);store(sid,who,"view:"+actualScene.getSceneId(),"SCENE_VIEW",actualScene,body);
            }
            case "MOVE" -> moveStoryActor(sid,e.path("actorId").asText(role),player,e.path("toSceneId").asText());
            case "EXPLORE" -> {String destination=expansion.accept(sid,scene,e,true);((ObjectNode)e).put("toSceneId",destination);if(!e.path("creationOnly").asBoolean())moveStoryActor(sid,role,player,destination);}
            case "CONTEST" -> conflicts.apply(sid,scene,role,player,e,new HashSet<>(),true);
            default -> throw new IllegalArgumentException("未知重要结果类型");
        }
        actors.flush();scenes.flush();
    }
    private void moveStoryActor(String sid,String who,String player,String destination){
        var person=actor(sid,who);var to=scenes.findBySessionIdAndSceneId(sid,destination).orElseThrow(()->new IllegalArgumentException("剧情引用的目的地不存在"));
        if(who.equals(player)){
            jdbc.update("UPDATE player_profile SET current_scene_id=? WHERE active_session_id=? AND god_id=?",destination,sid,who);jdbc.update("UPDATE game_session SET current_scene_id=? WHERE session_id=?",destination,sid);
            store(sid,who,"visited:"+destination,"VISITED_SCENE",to,Map.of("sceneId",destination));
        }else{
            for(var from:scenes.findBySessionId(sid))if(!from.getSceneId().equals(destination) && from.getPresentGods().contains(who)){var ids=new ArrayList<>(from.getPresentGods());ids.remove(who);from.setPresentGods(ids);scenes.save(from);}
            var ids=new ArrayList<>(to.getPresentGods());if(!ids.contains(who))ids.add(who);to.setPresentGods(ids);scenes.save(to);
        }
        // Scene membership is sufficient; no fabricated arrival pose or activity.
        var body=new LinkedHashMap<>(physical.view(sid,person,to));body.put("position","");body.put("activity","");body.put("activityHistorical",true);body.put("percepts",List.of());body.put("execution",Map.of());
        storeBody(sid,who,to,body);store(sid,who,"view:"+destination,"SCENE_VIEW",to,body);
    }
    private void requireStoryMode(boolean enabled){if(!enabled)throw new IllegalArgumentException("此结果仅用于完整故事协议");}
    private void validateStoryResult(JsonNode n,long maxDurationSeconds){
        if(!n.path("effects").isArray())throw new IllegalArgumentException("重要故事结果格式无效");
        for(var e:n.path("effects")){
            String kind=e.path("kind").asText();if(!Set.of("BODY","EXPLORE","CONTEST","TRANSFER","DROP","PICKUP","GAIN","USE_UP","FACT","HEALTH","MOVE").contains(kind))throw new IllegalArgumentException("非故事结果类型："+kind);
            if("BODY".equals(kind)){if(!e.path("after").isObject())throw new IllegalArgumentException("重要身体变化需要结果对象");var fields=e.path("after").fieldNames();while(fields.hasNext())if(!Set.of("awake","bound").contains(fields.next()))throw new IllegalArgumentException("故事不登记微小身体动作");}
        }
    }
    private void claim(Set<String> claimed,String resource){if(!claimed.add(resource))throw new IllegalArgumentException("simultaneous exclusive effects require a conflict resolution: "+resource);}
    private void requireCompletedUse(JsonNode n,GodProfile actor,String sid,GameScene scene){
        if(!"COMPLETED".equals(n.path("status").asText("COMPLETED")) || n.path("durationSeconds").asLong()<1 || Boolean.TRUE.equals(physical.view(sid,actor,scene).get("bound")))throw new IllegalArgumentException("resource use requires completed work and an unbound actor");
    }
    private void claimItem(Set<String> claimed,Set<String> local,String ref){if(local.add(ref))claim(claimed,"item:"+ref);}
    private boolean playerPresent(String sid,GameScene scene,String player){return jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE active_session_id=? AND god_id=? AND current_scene_id=?",Integer.class,sid,player,scene.getSceneId())>0;}
    private void storeBody(String sid,String role,GameScene scene,Map<String,Object> body){var state=new LinkedHashMap<String,Object>();for(String key:List.of("awake","bound","activity","position","activityHistorical"))state.put(key,body.get(key));store(sid,role,"body:v2","BODY_STATE",scene,state);}
    private void store(String sid,String role,String ref,String type,GameScene scene,Object value){
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'实际状态',?,?, '',?,?,?) ON DUPLICATE KEY UPDATE content=VALUES(content),scene_id=VALUES(scene_id),location_name=VALUES(location_name),learned_tick=VALUES(learned_tick)",sid,role,ref,encode(value),type,scene.getSceneId(),scene.getLocationName(),temporal.tick(sid));
    }
}
