package com.bs.narrative.service;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.WorldBook;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * V2 多智能体服务。
 *
 * 【优化指导 6.1】采用集中式多智能体提示（Centralized Multi-Agent Prompting）：
 *   - 生产路径逐角色独立请求，隔离私有目标与记忆；批量方法仅兼容旧演示
 *   - 数据库层面每个 NPC 仍保留独立 npc_goal + npc_memory（限知博弈本质不变）
 *   - Fallback 仅在 API 失败/超时时启用，不作为常态路径
 *
 * 兼容：保留 decideNpcBehavior 单 NPC 路径，供 Demo 端点或单 NPC 调试用。
 *
 * 与规则引擎的关系：
 *   - 规则引擎负责数值裁决（六维对抗、属性变化）
 *   - NpcAgentService 负责 NPC 的"怎么想、怎么说"
 *   - 两者的结果合并：规则引擎出 factsChinese，NpcAgentService 出 dialogue
 */
@Service
public class NpcAgentService {
    @org.springframework.beans.factory.annotation.Autowired private WorldSettingService worldSettings;

    private static final Logger log = LoggerFactory.getLogger(NpcAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @org.springframework.beans.factory.annotation.Autowired
    private PromiseService promises;
    @org.springframework.beans.factory.annotation.Autowired
    private NpcRequestExecutor requests;
    private final LLMProvider llmProvider;
    private final NpcGoalService goalService;
    private final WorldBookService worldBookService;
    private final GodProfileRepository godRepo;
    private final InquiryService inquiryService;
    private final WorldTurnService worldTurns;
    private final KnowledgeService knowledge;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private TemporalContextService temporal;
    @org.springframework.beans.factory.annotation.Autowired private EncounterService encounters;
    @org.springframework.beans.factory.annotation.Autowired private OpenSceneService openScenes;
    @org.springframework.beans.factory.annotation.Autowired private RoleContinuityService continuity;
    @org.springframework.beans.factory.annotation.Autowired private ItemStateService items;

    public NpcAgentService(LLMProvider llmProvider,
                           NpcGoalService goalService,
                           WorldBookService worldBookService,
                           GodProfileRepository godRepo, InquiryService inquiryService, WorldTurnService worldTurns, KnowledgeService knowledge) {
        this.knowledge=knowledge;
        this.worldTurns=worldTurns;
        this.llmProvider = llmProvider;
        this.goalService = goalService;
        this.worldBookService = worldBookService;
        this.godRepo = godRepo;
        this.inquiryService=inquiryService;
    }

    /** 单个 NPC 的行为决策结果。 */
    public record NpcDecision(
            String godId,
            String behavior,
            String speech,
            String innerThought,
            boolean isLying,
            boolean usedFallback,
            String targetGodId,
            String destinationSceneId,
            String personalGoal,
            WorldTurnService.PlannedStep plannedStep,
            boolean cancelPlan,
            Boolean acceptOffer,
            String giftItem,
            String decisionPoint,
            String decisionBasis,String actionText
    ) {
        public NpcDecision(String id,String behavior,String speech,String thought,boolean lying,boolean fallback,String target,String dest,String goal,WorldTurnService.PlannedStep step,boolean cancel,Boolean accept,String gift,String point,String basis){this(id,behavior,speech,thought,lying,fallback,target,dest,goal,step,cancel,accept,gift,point,basis,"");}
        public NpcDecision(String id,String behavior,String speech,String thought,boolean lying,boolean fallback,String target,String dest,String goal,WorldTurnService.PlannedStep step,boolean cancel,Boolean accept,String gift) {
            this(id,behavior,speech,thought,lying,fallback,target,dest,goal,step,cancel,accept,gift,"LEGACY","");
        }
        public NpcDecision(String id,String behavior,String speech,String thought,boolean lying,boolean fallback,String target,String dest,String goal,WorldTurnService.PlannedStep step,boolean cancel,Boolean accept) {
            this(id,behavior,speech,thought,lying,fallback,target,dest,goal,step,cancel,accept,"");
        }
        public NpcDecision(String id,String behavior,String speech,String thought,boolean lying,boolean fallback,String target,String dest,String goal,WorldTurnService.PlannedStep step,boolean cancel) {
            this(id,behavior,speech,thought,lying,fallback,target,dest,goal,step,cancel,null);
        }
        public NpcDecision(String id,String behavior,String speech,String thought,boolean lying,boolean fallback,String target,String dest,String goal) {
            this(id,behavior,speech,thought,lying,fallback,target,dest,goal,null,false);
        }
        public NpcDecision(String id,String behavior,String speech,String thought,boolean lying,boolean fallback) {
            this(id,behavior,speech,thought,lying,fallback,"","","");
        }
    }

    /** 兼容旧构造器：默认 usedFallback=false。 */
    public static NpcDecision of(String godId, String behavior, String speech,
                                  String innerThought, boolean isLying) {
        return new NpcDecision(godId, behavior, speech, innerThought, isLying, false);
    }

    /**
     * 为场景中所有 NPC 生成行为决策。
     * 独立限知请求最多3个并行，所有上下文读取及决策写入保留在事务线程。
     */
    public List<NpcDecision> decideAllNpcBehaviors(String sessionId, List<String> npcIds,
                                                    String playerText, String sceneSummary,
                                                    List<String> presentIds) {
        return decideAllNpcBehaviors(sessionId,npcIds,playerText,sceneSummary,presentIds,Map.of());
    }

    public List<NpcDecision> decideAllNpcBehaviors(String sessionId,List<String> npcIds,String publicAction,String sceneSummary,List<String> presentIds,Map<String,String> privateMessages) {
        if (npcIds == null || npcIds.isEmpty()) return List.of();
        long started=System.nanoTime();
        // Freeze private contexts on the transaction thread before any model request.
        var prepared=npcIds.stream().filter(Objects::nonNull).distinct()
                .map(id->prepare(sessionId,id,publicAction+"\n"+privateMessages.getOrDefault(id,""),sceneSummary,presentIds)).toList();
        var responses=requests.request(llmProvider,prepared.stream().filter(p->p.immediate()==null).map(Prepared::messages).toList());
        int index=0;List<NpcDecision> result=new ArrayList<>();
        for(var p:prepared)result.add(p.immediate()!=null?p.immediate():apply(sessionId,p,responses.get(index++)));
        log.info("[NPC-TIMING] actors={} requests={} elapsedMs={}",result.size(),responses.size(),(System.nanoTime()-started)/1_000_000);
        return result;
    }

    /** Causal cohorts: a selected recipient/initiator speaks first; independent remaining roles can run in parallel.
     * Each role is generated at most once per phase. No scripted reply or extra corrective model call. */
    public List<NpcDecision> decideCausalNpcBehaviors(String sid,List<String> npcIds,String publicAction,String sceneContext,List<String> presentIds,Map<String,String> privateMessages,TemporalContextService.Frame frame,int offset,List<Map<String,Object>> timeline) {
        if(frame==null)return decideAllNpcBehaviors(sid,npcIds,publicAction,sceneContext,presentIds,privateMessages);
        var pending=new LinkedHashSet<>(npcIds);var result=new ArrayList<NpcDecision>();String next="";
        for(String id:pending)if(privateMessages.getOrDefault(id,"").contains("【玩家确实向你说出的话】")){next=id;break;}
        if(next.isBlank() && publicAction.startsWith("没有新增公开玩家行为") && !pending.isEmpty()){
            var awake=pending.stream().filter(id->{var g=godRepo.findById(id);var s=openScenes.actorScene(sid,id);return g.isPresent() && s.isPresent() && Boolean.TRUE.equals(openScenes.view(sid,g.get(),s.get()).get("awake"));}).toList();
            var candidates=awake.isEmpty()?new ArrayList<>(pending):awake;next=candidates.get((int)Math.floorMod(frame.tick()-1,candidates.size()));
        }
        while(!pending.isEmpty()){
            List<String> cohort=result.isEmpty() && !next.isBlank() && pending.contains(next)?List.of(next):new ArrayList<>(pending);
            if(cohort.size()>1 && !next.isBlank() && cohort.contains(next)){cohort.remove(next);cohort.add(0,next);}
            var prepared=cohort.stream().map(id->prepare(sid,id,publicAction+"\n"+privateMessages.getOrDefault(id,""),sceneContext+frame.beforeGeneration(id),presentIds)).toList();
            var answers=requests.request(llmProvider,prepared.stream().filter(p->p.immediate()==null).map(Prepared::messages).toList());
            int n=0;next="";
            for(var p:prepared){
                NpcDecision d=p.immediate()!=null?p.immediate():apply(sid,p,answers.get(n++));
                if(frame.alreadyActed(d.godId()))d=new NpcDecision(d.godId(),d.speech().isBlank()?"IGNORE":"RESPOND",d.speech(),d.innerThought(),d.isLying(),d.usedFallback(),d.targetGodId(),d.destinationSceneId(),d.personalGoal(),d.plannedStep(),d.cancelPlan(),d.acceptOffer(),d.giftItem(),d.decisionPoint(),d.decisionBasis(),"");
                if(!d.usedFallback())worldTurns.reviewDue(sid,d.godId());
                pending.remove(d.godId());frame.decision(d,offset+result.size(),timeline);result.add(d);
                if(next.isBlank() && !d.usedFallback() && !d.speech().isBlank() && pending.contains(d.targetGodId()))next=d.targetGodId();
            }
        }
        return result;
    }


    /**
     * 为单个 NPC 生成行为决策（旧路径，保留兼容 + Demo 端点用）。
     */
    public NpcDecision decideNpcBehavior(String sessionId, String godId,
                                          String playerText, String sceneSummary,
                                          List<String> presentIds) {
        var prepared=prepare(sessionId,godId,playerText,sceneSummary,presentIds);
        if(prepared.immediate()!=null)return prepared.immediate();
        return apply(sessionId,prepared,requests.request(llmProvider,List.of(prepared.messages())).get(0));
    }

    private record Prepared(String godId,GodProfile god,String grounding,List<Map<String,String>> messages,NpcDecision immediate) {}

    private Prepared prepare(String sessionId,String godId,String playerText,String sceneSummary,List<String> presentIds) {
        GodProfile god = godRepo.findById(godId).orElse(null);
        if (god == null || (god.getSessionId()!=null && !sessionId.equals(god.getSessionId()))) {
            return new Prepared(godId,null,"",List.of(),of(godId,"IGNORE","（不存在）","",false));
        }
        if(openScenes!=null){
            var ownScene=openScenes.actorScene(sessionId,godId);String player=openScenes.playerRole(sessionId);
            if(ownScene.isPresent() && !openScenes.knowsPlayer(sessionId,ownScene.get(),god,player))presentIds=presentIds.stream().filter(id->!player.equals(id)).toList();
            sceneSummary=openScenes.project(sessionId,god,sceneSummary);
            if(ownScene.isPresent() && !Boolean.TRUE.equals(openScenes.view(sessionId,god,ownScene.get()).get("awake"))){presentIds=List.of(godId);playerText="当前尚无可确认的新外界信息。";}
        }
        promises.refresh(sessionId,godId,false);
        goalService.refreshProgress(sessionId,godId);
        // Quiet waiting does not erase or re-prompt a committed intention.
        if(worldTurns.hasPending(sessionId,godId) && !worldTurns.hasDue(sessionId,godId) && playerText.startsWith("没有新增公开玩家行为")
                && !playerText.contains("【玩家确实向你说出的话】")
                && !sceneSummary.contains("【本刻变化】") && !sceneSummary.contains("【交涉已更新】")
                && !(temporal!=null && temporal.roleContext(sessionId,godId).contains("本刻已发生")))
            return new Prepared(godId,god,"",List.of(),new NpcDecision(godId,"IGNORE","","",false,false));
        List<String> goals = goalService.getGoalDescriptions(sessionId, godId);
        List<WorldBook> memory = worldBookService.queryByCharacter(sessionId, godId);
        String memoryContext = (continuity==null?"":continuity.context(sessionId,godId))+(openScenes==null?"":openScenes.context(sessionId,god))+(temporal==null || !temporal.hasPrivateDecisions(sessionId,godId)?encounters.context(sessionId,godId):"")+(temporal==null?"":temporal.roleContext(sessionId,godId,playerText.contains("【玩家确实向你说出的话】")))+"\n【历史资料，不能重演为本刻新事件】\n"+formatMemory(memory)+"\n"+inquiryService.getKnownInfo(sessionId,godId)+"\n【自己的持续计划】"+worldTurns.privateAgenda(sessionId,godId)+"\n【自己持有的物品】"+god.getInventory()+(items==null?"":items.ownContext(sessionId,god))+"\n【自己的见闻录】"+knowledge.privateContext(sessionId,godId,temporal!=null);
        if (goalService.getActiveGoals(sessionId,godId).isEmpty()) {
            goalService.initPresetGoals(sessionId, godId, god.getGodName());
            goals = goalService.getGoalDescriptions(sessionId, godId);
        }
        List<Map<String, String>> msgs = List.of(
                    Map.of("role", "system", "content", NpcAgentPromptTemplate.systemPrompt()+worldSettings.prompt(sessionId)),
                    Map.of("role", "user",   "content", NpcAgentPromptTemplate.userPrompt(
                            god.getGodName(), god.getGodStatus(), god.getGodEmotion(),
                            goals, memoryContext, playerText, sceneSummary+"\n角色索引："+godRepo.findAllById(presentIds).stream().map(g->g.getGodId()+"="+g.getGodName()).toList(), presentIds))
            );
        return new Prepared(godId,god,msgs.get(1).get("content"),msgs,null);
    }

    private NpcDecision apply(String sessionId,Prepared prepared,NpcRequestExecutor.Result result) {
        String godId=prepared.godId();GodProfile god=prepared.god();
        boolean usedFallback=result.error()!=null;
        if(usedFallback)log.warn("[NpcAgent] 请求失败 actor={} reason={}",godId,result.error().getMessage());
        if (usedFallback) return unavailable(godId);
        LLMResponse llmResp=result.response();
        String json = llmResp.parsedJson() != null ? llmResp.parsedJson() : llmResp.content();

        try {
            JsonNode node = ModelOutput.object(json);
            NpcDecision decision=parseDecision(godId,node,usedFallback);
            if (decision.usedFallback()) return decision;
            if(openScenes!=null){var ownScene=openScenes.actorScene(sessionId,godId);if(ownScene.isPresent())decision=openScenes.constrain(sessionId,ownScene.get(),openScenes.playerRole(sessionId),decision);}
            if(!decision.usedFallback() && continuity!=null && continuity.remember(sessionId,godId,node,prepared.grounding()))
                decision=new NpcDecision(decision.godId(),decision.behavior(),decision.speech(),decision.innerThought(),decision.isLying(),false,decision.targetGodId(),decision.destinationSceneId(),decision.personalGoal(),decision.plannedStep(),decision.cancelPlan(),decision.acceptOffer(),decision.giftItem(),"NONE","",decision.actionText());
            if (!decision.usedFallback() && !decision.personalGoal().isBlank())
                goalService.adoptGroundedGoal(sessionId, godId, decision.personalGoal(),
                        ModelOutput.text(node,"goal_basis","",101), ModelOutput.text(node,"goal_success","",101),
                        ModelOutput.text(node,"goal_tradeoff","",101),
                        prepared.grounding(), node.get("goal_checks"));
            String emotion=ModelOutput.text(node,"emotional_state","",33);
            if(!decision.usedFallback() && !emotion.isBlank() && emotion.length()<=32){god.setGodEmotion(emotion);godRepo.save(god);}
            if(!decision.usedFallback() && "RESPOND".equals(decision.behavior()))promises.captureNpc(sessionId,godId,decision.targetGodId(),decision.speech(),node.get("promise"));
            return decision;
        } catch (IllegalArgumentException e) {
            log.warn("[NpcAgent] JSON 解析失败 god={} sessionId={}", godId, sessionId);
            return unavailable(godId);
        }
    }

    public static NpcDecision unavailable(String actorId) {
        return new NpcDecision(actorId,"IGNORE","","",false,true,"","","",null,false,null,"","NONE","","");
    }

    private NpcDecision parseDecision(String godId,JsonNode node,boolean fallback) {
        if (fallback) return unavailable(godId);
        String behavior=ModelOutput.text(node,"behavior","",16).toUpperCase(Locale.ROOT);
        String action=ModelOutput.text(node,"action_text","",800);
        if(!action.isBlank() && !Set.of("RESPOND","ATTACK","SUPPORT","OPPOSE","IGNORE","LEAVE","INQUIRE","GIVE","ACT").contains(behavior))behavior="ACT";
        if (!node.isObject() || !Set.of("RESPOND","ATTACK","SUPPORT","OPPOSE","IGNORE","LEAVE","INQUIRE","GIVE","ACT").contains(behavior))
            return unavailable(godId);
        return new NpcDecision(godId,behavior,
                behavior.equals("IGNORE")?"":ModelOutput.text(node,"speech","",4000),
                ModelOutput.text(node,"inner_thought","",4000),node.path("is_lying").asBoolean(false),fallback,
                ModelOutput.text(node,"target_god_id","",32),ModelOutput.text(node,"destination_scene_id","",32),
                ModelOutput.text(node,"personal_goal","",121),parsePlan(node.path("plan")),node.path("cancel_plan").asBoolean(false),node.path("accept_offer").isBoolean()?node.path("accept_offer").asBoolean():null,ModelOutput.text(node,"gift_item","",65), node.has("decision_point")?ModelOutput.text(node,"decision_point","NONE",32).toUpperCase(Locale.ROOT):"LEGACY",ModelOutput.text(node,"decision_basis","",400),action);
    }

    private WorldTurnService.PlannedStep parsePlan(JsonNode n) {
        if(!n.isObject() || !n.path("after_ticks").isIntegralNumber())return null;
        int delay=n.path("after_ticks").asInt();
        if(delay<1 || delay>6)return null;
        return new WorldTurnService.PlannedStep(ModelOutput.text(n,"behavior","",16).toUpperCase(Locale.ROOT),
                ModelOutput.text(n,"target_god_id","",32),ModelOutput.text(n,"destination_scene_id","",32),delay,ModelOutput.text(n,"purpose","",240));
    }

    /** 格式化 NPC 记忆为 Prompt 可读文本。 */
    private String formatMemory(List<WorldBook> memory) {
        if (memory == null || memory.isEmpty()) {
            return "（这组历史资料没有记录；不表示你刚到此地，也不否定自己的其他记忆）";
        }
        StringBuilder sb = new StringBuilder();
        for (WorldBook w : memory) {
            // ACTION aggregates phases; being a witness later does not confer earlier unheard speech.
            if ("ACTION".equals(w.getEventType())) continue;
            sb.append("【回合").append(w.getRoundNo()).append("】");
            if (w.getStoryTime() != null) sb.append(w.getStoryTime()).append(" ");
            sb.append(w.getLocationName()).append("：").append(w.getEventDescription());
            sb.append("\n");
        }
        return sb.toString();
    }
}
