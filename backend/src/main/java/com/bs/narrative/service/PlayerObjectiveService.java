package com.bs.narrative.service;

import com.bs.narrative.domain.GameSession;
import com.bs.narrative.domain.PlayerObjective;
import com.bs.narrative.llm.LLMApiException;
import com.bs.narrative.llm.LLMProvider;
import com.bs.narrative.llm.LLMResponse;
import com.bs.narrative.llm.LLMRequestPolicy;
import com.bs.narrative.llm.ModelOutput;
import com.bs.narrative.llm.ModelProtocol;
import com.bs.narrative.llm.FallbackProvider;
import com.bs.narrative.repository.GameSessionRepository;
import com.bs.narrative.repository.PlayerObjectiveRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/** Personal goals may be proposed by a model; completion is evaluated from persisted objective conditions.
 * Missing conditions stay subjective. Only explicit world terminal rules end the session. */
@Service
public class PlayerObjectiveService {
    @org.springframework.beans.factory.annotation.Autowired private WorldSettingService worldSettings;

    private static final Logger log = LoggerFactory.getLogger(PlayerObjectiveService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 进度阈值：≥100 视为 COMPLETED。 */
    private static final int PROGRESS_COMPLETE = 100;

    private final ObjectiveRuleService rules;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final LLMProvider llmProvider;
    private final PlayerObjectiveRepository objRepo;
    private final GameSessionRepository sessionRepo;
    @org.springframework.beans.factory.annotation.Value("${game.story-first:true}") private boolean narrativeGoals;

    public PlayerObjectiveService(LLMProvider llmProvider,
                                  PlayerObjectiveRepository objRepo,
                                  GameSessionRepository sessionRepo,ObjectiveRuleService rules,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.rules=rules;this.jdbc=jdbc;
        this.llmProvider = llmProvider;
        this.objRepo = objRepo;
        this.sessionRepo = sessionRepo;
    }

    // ==================== 初始化 ====================

    /**
     * 在角色创建 selectTalent 完成后调用：根据出生故事 + 天赋生成 2-3 个初始目标。
     *
     * @return 创建的目标列表
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<PlayerObjective> initObjectives(String sessionId, String playerId,
                                                 String birthStory, String talentName) {
        if(narrativeGoals)return initStoryObjectives(sessionId,playerId,birthStory,talentName);
        // 先清空旧目标（防周目继承时残留）
        List<PlayerObjective> existing = objRepo.findByPlayerIdAndSessionId(playerId, sessionId);
        if (!existing.isEmpty()) {
            objRepo.deleteAll(existing);
            objRepo.flush();
            log.info("[Objective] 清空旧目标 sessionId={} count={}", sessionId, existing.size());
        }
        jdbc.update("DELETE FROM game_goal_rule WHERE session_id=? AND owner_id=?",sessionId,playerId);

        // 调 LLM 生成目标
        LLMResponse llmResp;
        boolean usedFallback = false;
        try {
            List<Map<String, String>> msgs = List.of(
                    Map.of("role", "system", "content",
                            worldSettings.prompt(sessionId)+"你是当前世界的目标整理者。根据玩家的出生故事和天赋，"
                            + "生成 2-3 个符合已存在局面、玩家能实际执行的目标。每项必须有rule结构：AND/OR的children、NOT的child，叶子只允许ITEM_HELD(itemName)、AT_SCENE(sceneId)、OBSERVED_SCENE(sceneId)、VISITED_SCENES(count为2至4)、HEARD_FROM(godId)。不要编造物品地点，不以单纯等待时间作为目标。禁止FACILITY_STATE或自造knowledge引用：看清、理解、了解等认知不能变成石碑或场地的物理状态。标题与步骤必须准确描述rule的实际完成条件，不将一次观察夸大为读懂秘密或获知他人真实意图。返回 JSON 格式：\n"
                            + "{\"objectives\":[{\"objective_id\":\"unique_id\","
                            + "\"title\":\"目标标题\",\"current_step\":\"完成方法与条件\",\"rule\":{\"type\":\"VISITED_SCENES\",\"count\":2}}]}\n"
                            + "这些是阶段目标，完成后仍可继续活着游玩，不是世界终局条件。必须可达且不能在开局立即全部成立。"
                            + "这些是角色自己的目的，不安排围绕玩家的固定主线、必成路线或救场资源。"),
                    Map.of("role", "user", "content",
                            "出生故事：" + birthStory + "\n天赋：" + talentName + "\n实际地点："+jdbc.queryForList("SELECT scene_id,location_name,scene_summary FROM game_scene WHERE session_id=?",sessionId)+"\n实际资源："+jdbc.queryForList("SELECT inventory FROM god_profile WHERE session_id=?",sessionId)+"\n实际人物："+jdbc.queryForList("SELECT god_id,god_name FROM god_profile WHERE session_id=? AND god_id<>(SELECT player_god_id FROM game_session WHERE session_id=?)",sessionId,sessionId)+"\n请生成玩家目标。")
            );
            llmResp = llmProvider.chatForJson(ModelProtocol.messages(ModelProtocol.Stage.OBJECTIVES,msgs));
        } catch (LLMApiException e) {
            log.warn("[Objective] LLM 失败，走 Fallback sessionId={}", sessionId);
            llmResp = FallbackProvider.fallbackResponse(
                    fallbackObjectivesJson(),
                    fallbackObjectivesJson());
            usedFallback = true;
        }

        // 解析并落库
        List<PlayerObjective> saved = new ArrayList<>();
        String json = llmResp.parsedJson() != null ? llmResp.parsedJson() : llmResp.content();
        try {
            JsonNode root = com.bs.narrative.llm.ModelOutput.object(json);
            JsonNode arr = root.path("objectives");
            Set<String> seen = new HashSet<>();
            Set<String> conditions=new HashSet<>();
            if (arr.isArray()) {
                int idx = 0;
                for (JsonNode n : arr) {
                    if (idx >= 3) break;  // 最多 3 个
                    if(!supportedInitialRule(n.path("rule")) || !conditions.add(n.path("rule").toString()))continue;
                    PlayerObjective obj = new PlayerObjective();
                    String id = capped(n.path("objective_id").asText("obj_" + idx), 64);
                    if (id.isBlank() || !seen.add(id)) continue;
                    obj.setObjectiveId(id);
                    obj.setPlayerId(playerId);
                    obj.setSessionId(sessionId);
                    obj.setTitle(capped(n.path("title").asText("未知目标"), 100));
                    obj.setStatus("ACTIVE");
                    obj.setProgress(0);
                    obj.setCurrentStep(capped(n.path("current_step").asText(""), 255));
                    obj.setUpdatedAt(LocalDateTime.now());
                    try{rules.define(sessionId,playerId,id,n.path("rule"),false);}catch(IllegalArgumentException invalid){continue;}
                    saved.add(obj);
                    idx++;
                }
            }
        } catch (IllegalArgumentException e) {
            log.warn("[Objective] JSON 解析失败，使用预设目标 sessionId={}", sessionId);
            saved.addAll(savePresetObjectives(sessionId, playerId));
        }

        if (saved.size()<2 || saved.stream().allMatch(o -> {
            var stored=jdbc.queryForObject("SELECT rule_json FROM game_goal_rule WHERE session_id=? AND owner_id=? AND goal_id=?",String.class,sessionId,playerId,o.getObjectiveId());
            return rules.matches(sessionId,playerId,com.bs.narrative.llm.ModelOutput.object(stored));
        })) {
            jdbc.update("DELETE FROM game_goal_rule WHERE session_id=? AND owner_id=?",sessionId,playerId);
            saved.clear();
            saved.addAll(savePresetObjectives(sessionId, playerId));
        }

        saved.replaceAll(objRepo::save);
        objRepo.flush();
        var ending=MAPPER.createObjectNode().put("type","AND");var children=ending.putArray("children");
        for(var objective:saved)children.addObject().put("type","OBJECTIVE_COMPLETED").put("objectiveId",objective.getObjectiveId());
        // Initial errands and milestones are never terminal conditions.
        log.info("[Objective] 初始化完成 sessionId={} count={} fallback={}",
                sessionId, saved.size(), usedFallback);
        return saved;
    }

    private List<PlayerObjective> savePresetObjectives(String sessionId, String playerId) {
        List<PlayerObjective> list = new ArrayList<>();
        PlayerObjective main = new PlayerObjective();
        main.setObjectiveId("main_investigate");
        main.setPlayerId(playerId);
        main.setSessionId(sessionId);
        main.setTitle("看清眼前的处境");
        main.setStatus("ACTIVE");
        main.setProgress(0);
        main.setCurrentStep("实际观察初始场景，了解眼前的人物与环境");
        main.setUpdatedAt(LocalDateTime.now());
        list.add(main);

        PlayerObjective side = new PlayerObjective();
        side.setObjectiveId("side_survive");
        side.setPlayerId(playerId);
        side.setSessionId(sessionId);
        side.setTitle("走出初见之地");
        side.setStatus("ACTIVE");
        side.setProgress(0);
        side.setCurrentStep("亲自探访至少两个不同地点；完成仅是阶段成果，可以继续生活");
        side.setUpdatedAt(LocalDateTime.now());
        list.add(side);
        String scene=jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,playerId);
        rules.define(sessionId,playerId,main.getObjectiveId(),MAPPER.createObjectNode().put("type","OBSERVED_SCENE").put("sceneId",scene),false);
        rules.define(sessionId,playerId,side.getObjectiveId(),MAPPER.createObjectNode().put("type","VISITED_SCENES").put("count",2),false);
        return list;
    }

    private List<PlayerObjective> initStoryObjectives(String sid,String pid,String birth,String talent){
        objRepo.deleteAll(objRepo.findByPlayerIdAndSessionId(pid,sid));objRepo.flush();
        jdbc.update("DELETE FROM game_goal_rule WHERE session_id=? AND owner_id=?",sid,pid);
        var request=MAPPER.createObjectNode().put("birthStory",birth).put("talent",talent);request.set("worldSetting",worldSettings.brief(sid));
        request.set("playerWishes",MAPPER.valueToTree(jdbc.queryForList("SELECT answer_1,answer_2,answer_3 FROM character_creation WHERE session_id=?",sid)));
        JsonNode generated=MAPPER.createObjectNode();
        try{
            var reply=LLMRequestPolicy.STORY_AUTHOR.call(()->llmProvider.chatForJson(ModelProtocol.messages(ModelProtocol.Stage.OBJECTIVES,List.of(
                Map.of("role","system","content","你根据已发表开局与玩家原始所求整理1至3个有故事意义的阶段目标。输出JSON:{\"objectives\":[{\"objective_id\":\"简短编号\",\"title\":\"真正的所求\",\"current_step\":\"目前处境与尚未实现的结果\"}]}。例如带家人脱险并安顿、查明一桩具体旧事，而不是听一句话、拿一袋东西、到过几个地点等琐碎清单。不设系统判定公式，不保证成功，不替玩家决定牺牲或新承诺。不得补造隐秘主线、神器与未发生的事件；完成只是阶段成果，玩家活着仍可继续。无需填rule或通关条件。"),
                Map.of("role","user","content",request.toString())))));
            generated=ModelProtocol.object(ModelProtocol.Stage.OBJECTIVES,reply.parsedJson()!=null?reply.parsedJson():reply.content());
        }catch(LLMApiException|IllegalArgumentException unavailable){log.warn("[Objective] 阶段所求暂用已有处境 sessionId={}",sid);}
        var saved=new ArrayList<PlayerObjective>();var ids=new HashSet<String>();
        for(var row:generated.path("objectives")){
            if(saved.size()>=3)break;String title=row.path("title").asText();if(title.isBlank())continue;
            var goal=new PlayerObjective();String id=capped(row.path("objective_id").asText("goal_"+UUID.randomUUID()),64);if(id.isBlank() || !ids.add(id))continue;
            goal.setObjectiveId(id);goal.setPlayerId(pid);goal.setSessionId(sid);goal.setTitle(capped(title,100));goal.setStatus("ACTIVE");goal.setProgress(0);goal.setCurrentStep(capped(row.path("current_step").asText(),255));goal.setUpdatedAt(LocalDateTime.now());saved.add(goal);
        }
        if(saved.isEmpty()){
            var goal=new PlayerObjective();goal.setObjectiveId("current_life_aim");goal.setPlayerId(pid);goal.setSessionId(sid);goal.setTitle("在当前处境中推进自己的所求");goal.setStatus("ACTIVE");goal.setProgress(0);goal.setCurrentStep("承接已有身世与眼前事件；具体进展随实际经历更新，完成后仍可继续生活。");goal.setUpdatedAt(LocalDateTime.now());saved.add(goal);
        }
        saved.replaceAll(objRepo::save);objRepo.flush();return saved;
    }

    /** Called only after the explicit legacy repair preview has been accepted under a session lock. */
    @Transactional
    public void replaceLegacyWithPreset(String sessionId,String playerId,JsonNode archive) {
        objRepo.deleteAll(objRepo.findByPlayerIdAndSessionId(playerId,sessionId));
        objRepo.flush();
        jdbc.update("DELETE FROM game_goal_rule WHERE session_id=? AND owner_id=?",sessionId,playerId);
        var saved=savePresetObjectives(sessionId,playerId);
        saved.forEach(objRepo::save);objRepo.flush();
        var ending=MAPPER.createObjectNode().put("type","AND");var children=ending.putArray("children");
        for(var objective:saved)children.addObject().put("type","OBJECTIVE_COMPLETED").put("objectiveId",objective.getObjectiveId());
        ending.set("legacyRepair",archive);
        rules.define(sessionId,playerId,"initial_journey_ending",ending,false);
        rules.refresh(sessionId,playerId);
        markSessionVictory(sessionId);
    }

    private String fallbackObjectivesJson() {
        return "{\"objectives\":["
                + "{\"objective_id\":\"main_truth\",\"title\":\"探寻神明之野的真相\","
                + "\"current_step\":\"初入神明世界，需多听多看\"},"
                + "{\"objective_id\":\"side_survive\",\"title\":\"在诸神博弈中存活\","
                + "\"current_step\":\"依据实际身体、环境与风险继续行动\"}"
                + "]}";
    }

    private static String capped(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static boolean supportedInitialRule(JsonNode rule){return supportedInitialRule(rule,0);}
    private static boolean supportedInitialRule(JsonNode rule,int depth){
        if(depth>6 || !rule.isObject())return false;
        String type=rule.path("type").asText();
        if(Set.of("AND","OR").contains(type)){
            if(!rule.path("children").isArray() || rule.path("children").isEmpty())return false;
            for(var child:rule.path("children"))if(!supportedInitialRule(child,depth+1))return false;
            return true;
        }
        if("NOT".equals(type))return supportedInitialRule(rule.path("child"),depth+1);
        return Set.of("ITEM_HELD","AT_SCENE","OBSERVED_SCENE","VISITED_SCENES","HEARD_FROM").contains(type);
    }

    // ==================== 查询 ====================

    /** 查玩家在某 session 的所有目标（按状态/进度排序）。 */
    @Transactional(readOnly = true)
    public List<PlayerObjective> listObjectives(String playerId, String sessionId) {
        List<PlayerObjective> all = objRepo.findByPlayerIdAndSessionId(playerId, sessionId);
        all.sort(Comparator
                .comparing((PlayerObjective o) -> "ACTIVE".equals(o.getStatus()) ? 0 : 1)
                .thenComparing(Comparator.comparingInt(PlayerObjective::getProgress).reversed())
                .thenComparing(PlayerObjective::getObjectiveId));
        return all;
    }

    // ==================== 进度推进 ====================

    /**
     * 动作完成后依据持久化事实刷新目标；不调用模型推测进度。
     *
     * @param playerAction  玩家本回合输入
     * @param outcomeLabel  规则引擎裁决标签
     * @param factsChinese  本回合事实
     * @return 被推进的目标列表
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<PlayerObjective> advanceObjectives(String sessionId, String playerId,
                                                    String playerAction, String outcomeLabel,
                                                    List<String> factsChinese) {
        rules.refresh(sessionId,playerId);
        return objRepo.findByPlayerIdAndSessionId(playerId,sessionId).stream().filter(o->"COMPLETED".equals(o.getStatus())).toList();
    }

    // ==================== 多结局判定 ====================

    /**
     * 多结局判定。
     *
     * @return 结局类型 + 描述：VICTORY（全部目标完成）/ DEFEAT（玩家死亡）/ FAILURE（关键目标失败）/ ONGOING（未结）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evaluateEnding(String sessionId, String playerId, boolean playerDead) {
        List<PlayerObjective> all = objRepo.findByPlayerIdAndSessionId(playerId, sessionId);
        long total = all.size();
        long completed = all.stream().filter(o -> "COMPLETED".equals(o.getStatus())).count();
        long failed = all.stream().filter(o -> "FAILED".equals(o.getStatus())).count();

        Map<String, Object> result = new LinkedHashMap<>();
        if (playerDead) {
            result.put("ending", "DEFEAT");
            result.put("description", "玩家陨落，天命未竟。");
        } else if ("RETIRED".equals(jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sessionId))) {
            result.put("ending", "RETIREMENT");
            result.put("description", "玩家主动选择的归隐生活已在故事中实现。");
        } else {
            result.put("ending", "ONGOING");
            result.put("description", "故事仍在继续。");
        }
        result.put("totalObjectives", total);
        result.put("completedObjectives", completed);
        result.put("failedObjectives", failed);
        return result;
    }

    /** 通关：所有目标完成时，更新会话状态为 VICTORY。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markSessionVictory(String sessionId) {
        sessionRepo.findById(sessionId).ifPresent(s -> {
            // Compatibility entry point: objectives record progress, never end a living player's session.
        });
    }
}
