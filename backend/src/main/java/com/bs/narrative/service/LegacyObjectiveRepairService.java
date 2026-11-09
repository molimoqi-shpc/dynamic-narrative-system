package com.bs.narrative.service;

import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit replacement of one known obsolete opening-goal format, never an inference of cognition. */
@Service
public class LegacyObjectiveRepairService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> OBSOLETE_STATES=Set.of("退路已探明","刻痕已辨","四方意图已明");
    private final JdbcTemplate jdbc;
    private final SessionStateService checkpoints;
    private final PlayerObjectiveService objectives;
    private final ObjectiveRuleService rules;

    public LegacyObjectiveRepairService(JdbcTemplate jdbc,SessionStateService checkpoints,
                                       PlayerObjectiveService objectives,ObjectiveRuleService rules){
        this.jdbc=jdbc;this.checkpoints=checkpoints;this.objectives=objectives;this.rules=rules;
    }
    private JsonNode parse(Object value){try{return JSON.readTree(value.toString());}catch(Exception e){return JSON.nullNode();}}
    private Map<String,Object> unavailable(String reason){return Map.of("available",false,"reason",reason);}

    @Transactional(readOnly=true)
    public Map<String,Object> preview(String sid,String pid){
        var sessions=jdbc.queryForList("SELECT status FROM game_session WHERE session_id=? AND player_id=?",sid,pid);
        if(sessions.isEmpty())throw new BusinessException(ErrorCode.PARAM_INVALID,"会话不存在或不属于当前玩家");
        if(!"PLAYING".equals(sessions.get(0).get("status")))return unavailable("当前会话不在游玩中");
        var profiles=jdbc.queryForList("SELECT current_scene_id FROM player_profile WHERE player_id=? AND active_session_id=?",pid,sid);
        if(profiles.isEmpty())return unavailable("请先恢复此存档");
        var runtime=jdbc.queryForMap("SELECT branch_epoch,revision,active_operation FROM game_runtime WHERE session_id=?",sid);
        if(runtime.get("active_operation")!=null)return unavailable("请先完成或取消待处理操作");
        var goals=jdbc.queryForList("SELECT objective_id,title,status,progress,current_step FROM player_objective WHERE session_id=? AND player_id=? ORDER BY objective_id",sid,pid);
        var definitions=jdbc.queryForList("SELECT goal_id,rule_json,terminal_flag FROM game_goal_rule WHERE session_id=? AND owner_id=? ORDER BY goal_id",sid,pid);
        if(goals.size()!=3 || definitions.size()!=4 || goals.stream().anyMatch(g->!"ACTIVE".equals(g.get("status"))))return unavailable("不属于可重订的旧版开局目标");
        var byId=new HashMap<String,JsonNode>();for(var row:definitions)byId.put(row.get("goal_id").toString(),parse(row.get("rule_json")));
        var ending=byId.get("initial_journey_ending");
        if(ending==null || !"AND".equals(ending.path("type").asText()) || ending.path("children").size()!=3)return unavailable("终局结构不匹配");
        var ids=new HashSet<String>();for(var goal:goals)ids.add(goal.get("objective_id").toString());
        var references=new HashSet<String>();
        for(var child:ending.path("children")){
            if(!"OBJECTIVE_COMPLETED".equals(child.path("type").asText()))return unavailable("终局条件包含其他约束");
            references.add(child.path("objectiveId").asText());
        }
        if(!ids.equals(references))return unavailable("终局引用不匹配");
        for(var definition:definitions){
            boolean terminal=Boolean.TRUE.equals(definition.get("terminal_flag")) || definition.get("terminal_flag") instanceof Number n && n.intValue()!=0;
            if(terminal!=definition.get("goal_id").equals("initial_journey_ending"))return unavailable("包含额外终局条件");
        }
        var states=new HashSet<String>();String originalScene=null;
        for(String id:ids){
            var rule=byId.get(id);
            if(rule==null || !"AND".equals(rule.path("type").asText()) || !rule.path("children").isArray() || rule.path("children").size()!=2)return unavailable("目标结构不匹配");
            JsonNode at=null,facility=null;
            for(var child:rule.path("children"))switch(child.path("type").asText()){
                case "AT_SCENE" -> at=child;
                case "FACILITY_STATE" -> facility=child;
                default -> {return unavailable("包含其他目标条件");}
            }
            if(at==null || facility==null || !at.path("sceneId").asText().equals(facility.path("sceneId").asText())
                    || at.path("sceneId").asText().isBlank() || facility.path("objectName").asText().isBlank()
                    || !OBSOLETE_STATES.contains(facility.path("state").asText()))return unavailable("不属于已识别的旧版认知条件");
            String scene=at.path("sceneId").asText();if(originalScene!=null && !originalScene.equals(scene))return unavailable("目标地点不匹配");
            originalScene=scene;states.add(facility.path("state").asText());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=? AND required_objective=?",Integer.class,sid,id)>0)return unavailable("旧目标仍被道路门槛引用，不能直接重订");
        }
        if(!states.equals(OBSOLETE_STATES))return unavailable("旧版条件组合不匹配");
        String scene=profiles.get(0).get("current_scene_id").toString();
        var location=jdbc.queryForList("SELECT location_name FROM game_scene WHERE session_id=? AND scene_id=?",String.class,sid,scene);
        if(location.isEmpty())return unavailable("当前地点缺失");
        var observation=JSON.createObjectNode().put("type","OBSERVED_SCENE").put("sceneId",scene);
        var visits=JSON.createObjectNode().put("type","VISITED_SCENES").put("count",2);
        var replacements=List.of(Map.of("title","看清眼前的处境","condition","实际观察「"+location.get(0)+"」的人物与环境","satisfied",rules.matches(sid,pid,observation)),
                Map.of("title","走出初见之地","condition","亲自探访至少两个不同地点","satisfied",rules.matches(sid,pid,visits)));
        var archive=JSON.createObjectNode().put("version","legacy-cognitive-v1").put("sceneId",scene);
        archive.set("objectives",JSON.valueToTree(goals));archive.set("rules",JSON.valueToTree(definitions));
        var result=new LinkedHashMap<String,Object>();result.put("available",true);
        result.put("originalTitles",goals.stream().map(g->g.get("title")).toList());result.put("replacements",replacements);
        result.put("mayFinish",false);
        result.put("branchEpoch",runtime.get("branch_epoch"));result.put("revision",runtime.get("revision"));
        result.put("previewToken",digest(sid+":"+pid+":"+runtime.get("branch_epoch")+":"+runtime.get("revision")+":"+archive+":"+replacements));
        return result;
    }

    @Transactional
    public Map<String,Object> apply(String sid,String pid,long epoch,long revision,String token){
        checkpoints.lockForOperation(sid,pid);
        var proposal=preview(sid,pid);
        if(!Boolean.TRUE.equals(proposal.get("available")) || !Objects.equals(token,proposal.get("previewToken"))
                || epoch!=((Number)proposal.getOrDefault("branchEpoch",-1L)).longValue()
                || revision!=((Number)proposal.getOrDefault("revision",-1L)).longValue())
            throw new BusinessException(ErrorCode.DATA_CONFLICT,"存档或目标已变化，请重新查看重订方案");
        checkpoints.ensureCheckpoint(sid);
        var archive=JSON.createObjectNode().put("version","legacy-cognitive-v1");
        archive.set("objectives",JSON.valueToTree(jdbc.queryForList("SELECT objective_id,title,status,progress,current_step FROM player_objective WHERE session_id=? AND player_id=? ORDER BY objective_id",sid,pid)));
        archive.set("rules",JSON.valueToTree(jdbc.queryForList("SELECT goal_id,rule_json,terminal_flag FROM game_goal_rule WHERE session_id=? AND owner_id=? ORDER BY goal_id",sid,pid)));
        objectives.replaceLegacyWithPreset(sid,pid,archive);
        // A separate checkpoint makes the explicit replacement reversible without advancing story time.
        checkpoints.complete(sid,"重订旧版开局目标（原目标保留于终局规则记录）",false);
        return Map.of("repaired",true,"sessionId",sid);
    }
    private String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
