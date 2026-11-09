package com.bs.narrative.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;

/** Physical health is independent of knowledge, cultivation and loyalty. */
@Service
public class VitalStateService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public VitalStateService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public int health(String sid,String role){
        var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='vital:v1' AND source_type='VITAL_STATE'",String.class,sid,role);
        if(rows.isEmpty())return 100;
        try{return Math.max(0,Math.min(100,JSON.readTree(rows.get(0)).path("health").asInt(100)));}
        catch(Exception e){throw new IllegalStateException("生命状态损坏",e);}
    }
    public Map<String,Integer> preview(String sid,String role,String target,String outcome){
        int a=health(sid,role),b=health(sid,target);
        return Map.of("actorBefore",a,"targetBefore",b,"actorAfter",Math.max(0,a-("DEFENDER_WIN".equals(outcome)?25:"DRAW".equals(outcome)?5:0)),
                "targetAfter",Math.max(0,b-("ATTACKER_WIN".equals(outcome)?25:"DRAW".equals(outcome)?5:0)));
    }
    public void applyContest(String sid,String role,String target,String outcome){
        var health=preview(sid,role,target,outcome);
        write(sid,role,health.get("actorAfter"));write(sid,target,health.get("targetAfter"));
    }
    public void applyFirstAid(String sid,String role){
        int before=health(sid,role);
        if(before<=0 || before>=100)throw new IllegalArgumentException("first aid requires a living injured actor");
        write(sid,role,Math.min(100,before+10));
    }
    public void applyStoryHealth(String sid,String role,int health){
        if(health<0 || health>100)throw new IllegalArgumentException("生命结果超界");
        write(sid,role,health);
    }
    private void write(String sid,String role,int health){
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','身体生命',?,'VITAL_STATE','','','',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,role,"{\"health\":"+health+"}");
    }
    public boolean playerDead(String sid){
        var roles=jdbc.queryForList("SELECT player_god_id FROM game_session WHERE session_id=?",String.class,sid);
        return !roles.isEmpty() && roles.get(0)!=null && health(sid,roles.get(0))==0;
    }
    public void registerEnding(String sid,String summary){
        if(playerDead(sid))jdbc.update("UPDATE game_session SET status='DEAD',death_summary=? WHERE session_id=? AND status='PLAYING'",summary,sid);
    }
}
