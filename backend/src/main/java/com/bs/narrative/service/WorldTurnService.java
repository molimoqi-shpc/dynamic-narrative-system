package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Game time and durable, condition-checked NPC steps. No model call in the scheduler. */
@Service
public class WorldTurnService {
    public record PlannedStep(String behavior, String targetGodId, String destinationSceneId, int afterTicks, String purpose) {}
    public record VisibleEvent(String kind, String text) {}
    public record Turn(long tick, List<VisibleEvent> events, Set<String> actedIds) {}
    @org.springframework.beans.factory.annotation.Autowired private OpenSceneService openScenes;
    private final JdbcTemplate jdbc;
    private final GameSceneRepository scenes;
    private final GodProfileRepository gods;
    private final SceneConnectionRepository exits;
    private final EntityManager em;
    private final ObjectProvider<InquiryService> inquiries;
    private final ObjectMapper mapper=new ObjectMapper();
    public WorldTurnService(JdbcTemplate jdbc, GameSceneRepository scenes, GodProfileRepository gods,
            SceneConnectionRepository exits, EntityManager em, ObjectProvider<InquiryService> inquiries) {
        this.jdbc=jdbc;this.scenes=scenes;this.gods=gods;this.exits=exits;this.em=em;this.inquiries=inquiries;
    }
    public long tick(String sid) {
        var ticks=jdbc.queryForList("SELECT tick FROM world_clock WHERE session_id=?",Long.class,sid);
        return ticks.isEmpty()?0:ticks.get(0);
    }
    public String time(String sid) {return "世界时序 · 第"+tick(sid)+"刻";}
    public String privateAgenda(String sid,String id) {
        var rows=jdbc.queryForList("SELECT purpose,behavior,due_tick,created_tick,target_god_id,destination_scene_id FROM npc_plan WHERE session_id=? AND god_id=? AND status='PLANNED' ORDER BY plan_id",sid,id);
        if(rows.isEmpty()) {
            var last=jdbc.queryForList("SELECT purpose,status,result FROM npc_plan WHERE session_id=? AND god_id=? ORDER BY plan_id DESC LIMIT 1",sid,id);
            return last.isEmpty()?"尚无已安排的行动。":"上一项安排："+last.get(0).get("purpose")+"；状态="+last.get(0).get("status")+"；结果="+last.get(0).get("result")+"。请依据自己的目标与现状决定下一步，不把受阻写成成功。";
        }
        var p=rows.get(0);
        return "先前于第"+p.get("created_tick")+"刻提出的计划（不等于已经执行）："+p.get("purpose")+"；行动="+p.get("behavior")+"；剩余"+Math.max(0,((Number)p.get("due_tick")).longValue()-tick(sid))+"刻；原对象="+p.get("target_god_id")+"；原目的地="+p.get("destination_scene_id")+"。未到期不要无故刷新期限；已到期时结合本刻已知情况决定是否按原计划执行、调整或暂不执行，不把计划自动当成完成。";
    }
    public boolean hasPending(String sid,String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM npc_plan WHERE session_id=? AND god_id=? AND status='PLANNED'",Integer.class,sid,id)>0;
    }
    public boolean hasDue(String sid,String id){return jdbc.queryForObject("SELECT COUNT(*) FROM npc_plan WHERE session_id=? AND god_id=? AND status='PLANNED' AND due_tick<=?",Integer.class,sid,id,tick(sid))>0;}
    public void reviewDue(String sid,String id){jdbc.update("UPDATE npc_plan SET status='REVIEWED',result='到期提案已交由角色重新判断，是否实际执行以本刻事件为准' WHERE session_id=? AND god_id=? AND status='PLANNED' AND due_tick<=?",sid,id,tick(sid));}
        private Optional<SceneConnection> route(String sid,GameScene from,GodProfile actor,String dest) {
        return exits.findByFromSceneId(from.getSceneId()).stream()
                .filter(e->sid.equals(e.getSessionId()))
                .filter(e->dest.equals(e.getToSceneId()) && !dest.equals(from.getSceneId()))
                .filter(e->e.getRequiredObjective()==null || e.getRequiredObjective().isBlank())
                .filter(e->e.getRequiredItem()==null || e.getRequiredItem().isBlank() || actor.getInventory()!=null && actor.getInventory().contains(e.getRequiredItem()))
                .filter(e->scenes.findBySessionIdAndSceneId(sid,dest).isPresent()).findFirst();
    }
    @Transactional
    public boolean schedule(String sid,String id,String origin,PlannedStep step) {
        if(step==null || step.purpose()==null || step.purpose().isBlank() || step.purpose().length()>240
                || step.afterTicks()<1 || step.afterTicks()>6 || hasPending(sid,id))return false;
        var actor=gods.findById(id).orElse(null);var from=scenes.findBySessionIdAndSceneId(sid,origin).orElse(null);
        var owners=jdbc.queryForList("SELECT player_god_id FROM game_session WHERE session_id=?",String.class,sid);
        if(actor==null || !sid.equals(actor.getSessionId()) || owners.contains(id)
                || from==null || !from.getPresentGods().contains(id))return false;
        if(openScenes!=null){var v=openScenes.view(sid,actor,from);if(!Boolean.TRUE.equals(v.get("awake")) || "LEAVE".equals(step.behavior()) && Boolean.TRUE.equals(v.get("bound")))return false;}
        if("LEAVE".equals(step.behavior())) {
            if(step.destinationSceneId()==null || route(sid,from,actor,step.destinationSceneId()).isEmpty())return false;
        } else if("INQUIRE".equals(step.behavior())) {
            var target=gods.findById(step.targetGodId()).orElse(null);
            if(target==null || !sid.equals(target.getSessionId()) || id.equals(step.targetGodId())
                    || !from.getPresentGods().contains(step.targetGodId()) || openScenes!=null && !openScenes.awake(sid,step.targetGodId(),from))return false;
        } else return false;
        long now=tick(sid);
        jdbc.update("INSERT INTO npc_plan(session_id,god_id,origin_scene_id,behavior,target_god_id,destination_scene_id,purpose,created_tick,due_tick) VALUES(?,?,?,?,?,?,?,?,?)",
                sid,id,origin,step.behavior(),step.targetGodId()==null?"":step.targetGodId(),step.destinationSceneId()==null?"":step.destinationSceneId(),step.purpose(),now,now+step.afterTicks());
        return true;
    }
    @Transactional
    public void cancel(String sid,String id,String reason) {
        jdbc.update("UPDATE npc_plan SET status='CANCELLED',result=? WHERE session_id=? AND god_id=? AND status='PLANNED'",reason,sid,id);
    }
    private void finish(long id,String status,String result) {jdbc.update("UPDATE npc_plan SET status=?,result=? WHERE plan_id=?",status,result,id);}
    private List<String> witnesses(GameScene s,String observed,String player) {
        var ids=new ArrayList<>(s.getPresentGods());
        if(s.getSceneId().equals(observed) && player!=null && !ids.contains(player))ids.add(player);
        return ids;
    }
    private void record(String sid,int round,long tick,GameScene scene,List<String> witnesses,String text) {
        try {
            jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,coord_x,coord_y,location_name,event_type,actor_ids,event_description,state_changes,importance,is_secret,info_source) VALUES(?,?,?,?,?,?,'NPC_PLAN',?,?,?,5,false,'PERSONAL')",
                    sid,round,"世界时序 · 第"+tick+"刻",scene.getCoordX(),scene.getCoordY(),scene.getLocationName(),mapper.writeValueAsString(witnesses),text,"已执行的角色行动");
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    @Transactional
    public Turn advance(String sid,int round,String observed,String player) {return advance(sid,round,observed,player,false);}
    @Transactional
    public Turn advanceForDecision(String sid,int round,String observed,String player) {return advance(sid,round,observed,player,true);}
    private Turn advance(String sid,int round,String observed,String player,boolean reviewVisibleRoles) {
        em.flush();
        jdbc.update("INSERT INTO world_clock(session_id,tick) VALUES(?,1) ON DUPLICATE KEY UPDATE tick=tick+1",sid);
        long now=tick(sid);
        var visible=new ArrayList<VisibleEvent>();var acted=new LinkedHashSet<String>();
        var due=jdbc.queryForList("SELECT * FROM npc_plan WHERE session_id=? AND status='PLANNED' AND due_tick<=? ORDER BY due_tick,plan_id",sid,now);
        for(var p:due) {
            long planId=((Number)p.get("plan_id")).longValue();String id=(String)p.get("god_id");
            if(acted.contains(id)){finish(planId,"CANCELLED","本刻已有其他计划执行");continue;}
            var from=scenes.findBySessionIdAndSceneId(sid,(String)p.get("origin_scene_id")).orElse(null);
            var actor=gods.findById(id).orElse(null);
            var owners=jdbc.queryForList("SELECT player_god_id FROM game_session WHERE session_id=?",String.class,sid);
            if(actor==null || !sid.equals(actor.getSessionId()) || owners.contains(id) || from==null || !from.getPresentGods().contains(id)) {
                finish(planId,"BLOCKED","人物已离开原位置或无法行动");continue;
            }
            String behavior=(String)p.get("behavior");
            if(openScenes!=null){var v=openScenes.view(sid,actor,from);if(!Boolean.TRUE.equals(v.get("awake")) || "LEAVE".equals(behavior) && Boolean.TRUE.equals(v.get("bound"))){finish(planId,"BLOCKED","人物当前昏迷或受到束缚，无法行动，原计划未执行；需依据实际状态重新判断");continue;}}
            // In the active scene, an old proposal must enter current psychology before becoming an effect.
            if(reviewVisibleRoles && from.getSceneId().equals(observed))continue;
            if("LEAVE".equals(behavior)) {
                String dest=(String)p.get("destination_scene_id");var exit=route(sid,from,actor,dest);
                if(exit.isEmpty()){finish(planId,"BLOCKED","原定路线当前无法通行");continue;}
                var to=scenes.findBySessionIdAndSceneId(sid,dest).orElseThrow();
                var seen=witnesses(from,observed,player);String text=actor.getGodName()+"沿"+exit.get().getExitName()+"离开，前往"+to.getLocationName()+"。";
                var remaining=new ArrayList<>(from.getPresentGods());remaining.remove(id);from.setPresentGods(remaining);
                var arriving=new ArrayList<>(to.getPresentGods());if(!arriving.contains(id))arriving.add(id);to.setPresentGods(arriving);
                scenes.save(from);scenes.save(to);
                record(sid,round,now,from,seen,text);
                String arrival=actor.getGodName()+"从"+from.getLocationName()+"来到此地。";
                record(sid,round,now,to,witnesses(to,observed,player),arrival);
                if(from.getSceneId().equals(observed))visible.add(new VisibleEvent("LEAVE",text));
                if(to.getSceneId().equals(observed))visible.add(new VisibleEvent("ARRIVE",arrival));
                finish(planId,"COMPLETED","已抵达目的地");acted.add(id);
            } else if("INQUIRE".equals(behavior)) {
                String targetId=(String)p.get("target_god_id");var target=gods.findById(targetId).orElse(null);
                if(target==null || !sid.equals(target.getSessionId()) || !from.getPresentGods().contains(targetId) || openScenes!=null && !openScenes.awake(sid,targetId,from)) {
                    finish(planId,"BLOCKED","原定打听对象已不在此地或无法回应");continue;
                }
                inquiries.getObject().processInquiry(sid,id,from.getCoordX(),from.getCoordY());
                String text=actor.getGodName()+"向"+target.getGodName()+"打听此地消息。";
                record(sid,round,now,from,witnesses(from,observed,player),text);
                if(from.getSceneId().equals(observed))visible.add(new VisibleEvent("INQUIRE",text));
                finish(planId,"COMPLETED","已完成打听，所得消息仅进入自身记忆");acted.add(id);
            } else finish(planId,"BLOCKED","不支持的计划行为");
        }
        return new Turn(now,List.copyOf(visible),Set.copyOf(acted));
    }
}
