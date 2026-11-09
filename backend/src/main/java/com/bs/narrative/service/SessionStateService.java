package com.bs.narrative.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bs.narrative.exception.*;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Transactional state checkpoints. No reconstructed historical state is invented. */
@Service
public class SessionStateService {
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final WorldTurnService worldTurns;
    @org.springframework.beans.factory.annotation.Autowired
    private NpcGoalService npcGoals;
    @org.springframework.beans.factory.annotation.Autowired
    private PromiseService promises;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> TABLES = List.of("game_scene", "scene_connection", "god_relationship",
            "world_book", "interaction_log", "story_summary", "player_objective", "player_memory",
            "npc_goal", "npc_memory", "npc_decision_log", "session_npc_alias", "world_clock", "npc_plan", "role_knowledge", "game_goal_rule", "game_runtime",
            "story_plot_event", "story_foreshadow");
    private static final List<String> DELETE_ORDER = List.of("story_foreshadow", "story_plot_event", "npc_decision_log", "npc_memory", "npc_goal",
            "player_memory", "player_objective", "story_summary", "interaction_log", "world_book",
            "god_relationship", "scene_connection", "session_npc_alias", "npc_plan", "world_clock", "role_knowledge", "game_goal_rule", "game_runtime");

    public SessionStateService(JdbcTemplate jdbc, EntityManager em, WorldTurnService worldTurns) { this.jdbc = jdbc; this.em = em; this.worldTurns=worldTurns; }

    private List<Map<String,String>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String,String> row = new LinkedHashMap<>();
            for (int i=1; i<=rs.getMetaData().getColumnCount(); i++)
                row.put(rs.getMetaData().getColumnLabel(i), rs.getString(i));
            return row;
        }, args);
    }

    private Map<String,String> lock(String sid, String pid) {
        em.flush();
        if (rows("SELECT player_id FROM player_profile WHERE player_id=? FOR UPDATE", pid).isEmpty())
            throw new BusinessException(ErrorCode.PLAYER_NOT_FOUND, "玩家不存在");
        var sessions = rows("SELECT * FROM game_session WHERE session_id=? FOR UPDATE", sid);
        if (sessions.isEmpty() || !pid.equals(sessions.get(0).get("player_id")))
            throw new BusinessException(ErrorCode.PARAM_INVALID, "会话不存在或不属于当前玩家");
        return sessions.get(0);
    }

    @Transactional
    public int begin(String sid, String pid) {
        com.bs.narrative.llm.LLMUsage.begin();
        var session = lock(sid,pid);
        var pending=jdbc.queryForList("SELECT active_operation FROM game_runtime WHERE session_id=?",sid);
        if(!pending.isEmpty() && pending.get(0).get("active_operation")!=null) throw new BusinessException(ErrorCode.DATA_CONFLICT,"上一操作尚未完成，请续接或取消未完成部分");
        if (!"PLAYING".equals(session.get("status")))
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "当前会话不可行动");
        var player = rows("SELECT * FROM player_profile WHERE player_id=?",pid).get(0);
        if (!sid.equals(player.get("active_session_id")))
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "请先恢复该会话，不能同时操作不同周目");
        ensureCheckpoint(sid);
        return Integer.parseInt(session.get("current_round"))+1;
    }

    @Transactional
    public void lockCreation(String sid,String pid) {
        com.bs.narrative.llm.LLMUsage.begin();
        var session=lock(sid,pid);
        if(!"CREATING".equals(session.get("status"))) throw new BusinessException(ErrorCode.CREATION_STATE_CONFLICT,"角色创建已结束");
    }

    public int nextRound(String sid) {
        return jdbc.queryForObject("SELECT current_round+1 FROM game_session WHERE session_id=?",Integer.class,sid);
    }

    @Transactional
    public void initialize(String sid, String pid) {
        em.flush();
        var player = rows("SELECT * FROM player_profile WHERE player_id=?",pid).get(0);
        jdbc.update("UPDATE game_session SET player_god_id=?,current_scene_id=?,current_round=0 WHERE session_id=?",
                player.get("god_id"),player.get("current_scene_id"),sid);
        jdbc.update("UPDATE player_profile SET active_session_id=? WHERE player_id=?",sid,pid);
        jdbc.update("INSERT IGNORE INTO game_runtime(session_id) VALUES(?)",sid);
        em.clear();
        var session=rows("SELECT inherit_world,prev_session_id FROM game_session WHERE session_id=?",sid).get(0);
        if("1".equals(session.get("inherit_world")) && session.get("prev_session_id")!=null) {
            jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,coord_x,coord_y,location_name,event_type,actor_ids,event_description,state_changes,importance,is_secret,info_source) "
                    +"SELECT ?,0,story_time,coord_x,coord_y,location_name,'INHERITED',actor_ids,event_description,state_changes,importance,is_secret,'TOLD' FROM world_book WHERE session_id=? AND event_type NOT IN ('PLOT_EVENT','PLOT_THREAD')",sid,session.get("prev_session_id"));
        }
        capture(sid,"初始状态");
    }

    @Transactional
    public void complete(String sid, String label) { complete(sid,label,true); }

    @Transactional
    public void complete(String sid, String label, boolean advanceWorld) {
        var owner=rows("SELECT player_id FROM game_session WHERE session_id=?",sid).get(0).get("player_id");lock(sid,owner);
        if(!sid.equals(rows("SELECT active_session_id FROM player_profile WHERE player_id=?",owner).get(0).get("active_session_id")))throw new BusinessException(ErrorCode.DATA_CONFLICT,"非当前会话不能推进");
        em.flush();
        // Action phases advance explicitly; other turn-producing operations advance once here.
        Long previous=jdbc.queryForObject("SELECT COALESCE(MAX(CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.world_clock[0].tick')) AS UNSIGNED)),0) FROM session_checkpoint WHERE session_id=? AND round_no=(SELECT current_round FROM game_session WHERE session_id=?)",Long.class,sid,sid);
        if(advanceWorld && worldTurns.tick(sid)<=previous) {
            var p=jdbc.queryForMap("SELECT p.current_scene_id,p.god_id,gs.current_round FROM player_profile p JOIN game_session gs ON gs.player_id=p.player_id WHERE gs.session_id=?",sid);
            worldTurns.advance(sid,((Number)p.get("current_round")).intValue()+1,(String)p.get("current_scene_id"),(String)p.get("god_id"));
        }
        jdbc.update("UPDATE game_session gs JOIN player_profile p ON p.player_id=gs.player_id "
                + "SET gs.current_scene_id=p.current_scene_id,gs.player_god_id=p.god_id,gs.current_round=gs.current_round+1 WHERE gs.session_id=?",sid);
        // Compatibility actions change the version too. A tick is deliberately not converted into seconds.
        jdbc.update("UPDATE game_runtime SET revision=revision+1 WHERE session_id=? AND active_operation IS NULL",sid);
        em.flush();
        boolean framed=jdbc.queryForObject("SELECT COUNT(*) FROM game_operation o JOIN game_runtime r ON r.session_id=o.session_id AND r.branch_epoch=o.branch_epoch AND r.active_operation=o.operation_id WHERE o.session_id=? AND JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.storyResultMode'))='AUTHOR_RESULT_V2'",Integer.class,sid)>0;
        if(!framed){promises.refresh(sid,null,true);npcGoals.refreshSession(sid);}
        em.flush();
        em.clear();
        capture(sid,label);
    }

    @Transactional
    public void ensureCheckpoint(String sid) {
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM session_checkpoint WHERE session_id=?",Integer.class,sid);
        if(n==0) {
            var session=rows("SELECT player_id FROM game_session WHERE session_id=?",sid);
            if(session.isEmpty()) throw new BusinessException(ErrorCode.PARAM_INVALID,"会话不存在");
            lock(sid,session.get(0).get("player_id"));
            var active=rows("SELECT active_session_id FROM player_profile WHERE player_id=?",session.get(0).get("player_id")).get(0);
            if(!sid.equals(active.get("active_session_id"))) throw new BusinessException(ErrorCode.DATA_CONFLICT,"请先恢复会话再建立状态检查点");
            capture(sid,"旧存档当前状态（更早历史不可恢复）");
        }
    }

    @Transactional
    public void refreshCurrent(String sid,String pid) { lock(sid,pid); capture(sid,"当前阶段"); }

    @Transactional
    public void lockForOperation(String sid,String pid) { lock(sid,pid); }

    private void capture(String sid,String label) {
        em.flush();
        var session=rows("SELECT * FROM game_session WHERE session_id=?",sid).get(0);
        Map<String,List<Map<String,String>>> data=new LinkedHashMap<>();
        data.put("game_session",List.of(session));
        var profile=new LinkedHashMap<>(rows("SELECT player_id,god_id,current_scene_id,inventory,view_mode,active_session_id FROM player_profile WHERE player_id=?",session.get("player_id")).get(0));
        if(!sid.equals(profile.get("active_session_id")))throw new BusinessException(ErrorCode.DATA_CONFLICT,"非当前会话不能保存玩家状态");
        if(!Objects.equals(profile.get("god_id"),session.get("player_god_id")))throw new BusinessException(ErrorCode.DATA_CONFLICT,"会话角色状态不一致");
        data.put("player_profile",List.of(profile));
        data.put("_execution_v2",rows("SELECT o.* FROM game_operation o JOIN game_runtime r ON r.session_id=o.session_id AND r.branch_epoch=o.branch_epoch AND r.active_operation=o.operation_id WHERE o.session_id=?",sid));
        data.put("god_profile",rows("SELECT * FROM god_profile WHERE session_id=?",sid));
        for(String table:TABLES) data.put(table,rows("SELECT * FROM `"+table+"` WHERE session_id=?",sid));
        try {
            jdbc.update("INSERT INTO session_checkpoint(session_id,round_no,label,payload) VALUES(?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE label=VALUES(label),payload=VALUES(payload)",sid,
                    Integer.parseInt(session.get("current_round")),label.length()>255?label.substring(0,255):label,
                    mapper.writeValueAsString(data));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("保存状态失败",e); }
    }

    public List<Map<String,Object>> list(String sid) {
        return jdbc.queryForList("SELECT round_no AS roundNo,label AS playerInput,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.game_session[0].current_scene_id')) AS sceneId,created_at AS createdAt FROM session_checkpoint WHERE session_id=? ORDER BY round_no",sid);
    }

    private void upsert(String table, Map<String,String> row) {
        String columns=String.join(",",row.keySet().stream().map(k->"`"+k+"`").toList());
        String values=String.join(",",Collections.nCopies(row.size(),"?"));
        String updates=String.join(",",row.keySet().stream().map(k->"`"+k+"`=VALUES(`"+k+"`)").toList());
        jdbc.update("INSERT INTO `"+table+"`("+columns+") VALUES("+values+") ON DUPLICATE KEY UPDATE "+updates,row.values().toArray());
    }

    @Transactional
    public Map<String,Object> restore(String sid,int round) {
        var session=rows("SELECT * FROM game_session WHERE session_id=?",sid);
        if(session.isEmpty()) throw new BusinessException(ErrorCode.PARAM_INVALID,"会话不存在");
        lock(sid,session.get(0).get("player_id"));
        pausePrevious(sid,session.get(0).get("player_id"));
        var oldRuntime=jdbc.queryForList("SELECT branch_epoch,fence FROM game_runtime WHERE session_id=? FOR UPDATE",sid);
        long nextEpoch=oldRuntime.isEmpty()?1:((Number)oldRuntime.get(0).get("branch_epoch")).longValue()+1;
        long nextFence=oldRuntime.isEmpty()?1:((Number)oldRuntime.get(0).get("fence")).longValue()+1;
        var payloads=rows("SELECT payload FROM session_checkpoint WHERE session_id=? AND round_no=?",sid,round);
        if(payloads.isEmpty()) throw new BusinessException(ErrorCode.PARAM_INVALID,"该回合没有完整状态检查点，无法可靠回溯");
        Map<String,List<Map<String,String>>> data;
        try { data=mapper.readValue(payloads.get(0).get("payload"),new TypeReference<>(){}); }
        catch(Exception e) { throw new IllegalStateException("状态检查点损坏",e); }
        if(data.get("game_session").size()!=1 || !sid.equals(data.get("game_session").get(0).get("session_id")))
            throw new IllegalStateException("状态检查点会话不匹配");
        var savedRuntime=data.getOrDefault("game_runtime",List.of());
        boolean unfinished=!savedRuntime.isEmpty() && savedRuntime.get(0).get("active_operation")!=null;
        if(unfinished && (!data.containsKey("_execution_v2") || data.get("_execution_v2").size()!=1))throw new BusinessException(ErrorCode.DATA_CONFLICT,"此旧检查点缺少未完成执行状态，不能可靠回溯");
        jdbc.update("INSERT IGNORE INTO session_checkpoint_archive(session_id,branch_epoch,round_no,payload,label) SELECT session_id,?,round_no,payload,label FROM session_checkpoint WHERE session_id=?",nextEpoch-1,sid);
        for(var row:data.get("god_profile")) upsert("god_profile",row);
        for(var row:data.get("game_scene")) upsert("game_scene",row);
        var player=data.get("player_profile").get(0);
        jdbc.update("UPDATE player_profile SET god_id=?,current_scene_id=?,inventory=?,view_mode=?,active_session_id=? WHERE player_id=?",
                player.get("god_id"),player.get("current_scene_id"),player.get("inventory"),player.get("view_mode"),sid,player.get("player_id"));
        for(String table:DELETE_ORDER) jdbc.update("DELETE FROM `"+table+"` WHERE session_id=?",sid);
        for(String table:TABLES) if(!table.equals("game_scene")) for(var row:data.getOrDefault(table,List.of())) upsert(table,row);
        deleteAbsent("game_scene","scene_id",sid,data.get("game_scene"));
        deleteAbsent("god_profile","god_id",sid,data.get("god_profile"));
        upsert("game_session",data.get("game_session").get(0));
        jdbc.update("INSERT INTO game_runtime(session_id,branch_epoch,fence) VALUES(?,?,?) ON DUPLICATE KEY UPDATE branch_epoch=VALUES(branch_epoch),fence=VALUES(fence),active_operation=NULL,revision=revision+1",sid,nextEpoch,nextFence);
        if(unfinished){
            var source=data.get("_execution_v2").get(0);String fresh="restore_"+UUID.randomUUID();
            try{
                var restored=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(source.get("payload"));
                restored.put("originOperationId",source.get("operation_id"));restored.put("operationId",fresh);restored.put("pendingKind","RESTORED_EXECUTION");
                jdbc.update("INSERT INTO game_operation(session_id,branch_epoch,operation_id,request_hash,player_id,status,fence,payload) VALUES(?,?,?,?,?,'PARTIAL',?,?)",sid,nextEpoch,fresh,source.get("request_hash"),source.get("player_id"),nextFence,mapper.writeValueAsString(restored));
                jdbc.update("UPDATE game_runtime SET active_operation=? WHERE session_id=?",fresh,sid);
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("执行快照损坏",e);}
        }
        jdbc.update("DELETE FROM session_checkpoint WHERE session_id=? AND round_no>?",sid,round);
        em.clear();
        return Map.of("sessionId",sid,"targetRound",round,"restoredSceneId",player.get("current_scene_id"),
                "playerGodId",player.get("god_id"),"sessionStatus",data.get("game_session").get(0).get("status"));
    }

    private void pausePrevious(String sid,String pid){
        var active=rows("SELECT active_session_id FROM player_profile WHERE player_id=?",pid).get(0).get("active_session_id");
        if(active!=null && !active.equals(sid) && !rows("SELECT session_id FROM game_session WHERE session_id=? AND player_id=?",active,pid).isEmpty()){
            lock(active,pid);
            jdbc.update("UPDATE game_operation o JOIN game_runtime r ON r.session_id=o.session_id AND r.branch_epoch=o.branch_epoch AND r.active_operation=o.operation_id SET o.status='PARTIAL',o.payload=JSON_SET(o.payload,'$.pendingKind','SESSION_PAUSED') WHERE o.session_id=? AND o.status='RUNNING'",active);
            jdbc.update("UPDATE game_runtime SET fence=fence+1 WHERE session_id=?",active);
        }
    }

    private void deleteAbsent(String table,String key,String sid,List<Map<String,String>> saved) {
        if(saved.isEmpty()) { jdbc.update("DELETE FROM `"+table+"` WHERE session_id=?",sid); return; }
        var args=new ArrayList<Object>(); args.add(sid); saved.forEach(row->args.add(row.get(key)));
        jdbc.update("DELETE FROM `"+table+"` WHERE session_id=? AND `"+key+"` NOT IN ("
                +String.join(",",Collections.nCopies(saved.size(),"?"))+")",args.toArray());
    }

    @Transactional
    public Map<String,Object> resume(String sid,String pid) {
        var session=lock(sid,pid);
        if(session.get("player_god_id")==null || session.get("current_scene_id")==null)
            throw new BusinessException(ErrorCode.DATA_CONFLICT,"此会话尚未创建角色");
        pausePrevious(sid,pid);
        jdbc.update("UPDATE player_profile SET god_id=?,current_scene_id=?,active_session_id=?,view_mode=? WHERE player_id=?",
                session.get("player_god_id"),session.get("current_scene_id"),sid,session.get("view_mode"),pid);
        em.clear();
        return Map.of("sessionId",sid,"sceneId",session.get("current_scene_id"),"playerGodId",session.get("player_god_id"),"status",session.get("status"));
    }

    public void checkGate(String sid,String pid,com.bs.narrative.domain.SceneConnection exit) {
        if(exit.getRequiredItem()!=null && !exit.getRequiredItem().isBlank()) {
            String inventory=jdbc.queryForObject("SELECT g.inventory FROM god_profile g JOIN player_profile p ON p.god_id=g.god_id WHERE p.player_id=?",String.class,pid);
            try {
                List<String> items=inventory==null?List.of():mapper.readValue(inventory,new TypeReference<>(){});
                if(!items.contains(exit.getRequiredItem())) throw new BusinessException(ErrorCode.MOVE_GATE_ITEM_REQUIRED,"缺少通行物品："+exit.getRequiredItem());
            } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException("背包数据损坏",e);}
        }
        if(exit.getRequiredObjective()!=null && !exit.getRequiredObjective().isBlank()) {
            Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND player_id=? AND objective_id=? AND status='COMPLETED'",Integer.class,sid,pid,exit.getRequiredObjective());
            if(n==0) throw new BusinessException(ErrorCode.MOVE_GATE_OBJECTIVE_REQUIRED,"尚未完成通行目标");
        }
    }

}
