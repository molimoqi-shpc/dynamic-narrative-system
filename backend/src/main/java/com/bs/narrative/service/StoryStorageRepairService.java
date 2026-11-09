package com.bs.narrative.service;

import com.bs.narrative.repository.GameSceneRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Repairs the old introduction serializer from committed author results, never from prose or intent. */
@Service
public class StoryStorageRepairService {
    private static final String VERSION="storage:authored-introduction-location:v1";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final StoryCastService cast;
    private final GameSceneRepository scenes;
    private final OperationEffectService effects;
    public StoryStorageRepairService(JdbcTemplate jdbc,StoryCastService cast,GameSceneRepository scenes,OperationEffectService effects){this.jdbc=jdbc;this.cast=cast;this.scenes=scenes;this.effects=effects;}
    private record Correction(String role,String oldScene,String actualScene,String introRef,String operation){}
    /** Caller holds the session's short transaction. The repair and marker join the next ordinary checkpoint. */
    public void repair(String sid,String pid){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND state_changes=?",Integer.class,sid,VERSION)>0)return;
        // Only chapters still published on this branch count. Matching the saved text identifies the
        // committed version; it does not interpret its plot. No large JSON participates in a SQL sort.
        var history=new ArrayList<ObjectNode>();
        for(String raw:jdbc.queryForList("""
            SELECT JSON_OBJECT('operationId',o.operation_id,'logId',l.log_id,'round',l.round_no,
              'sceneId',JSON_EXTRACT(o.payload,'$.sceneId'),'frame',JSON_EXTRACT(o.payload,'$.sceneFrame'),
              'passages',JSON_LENGTH(JSON_EXTRACT(o.payload,'$.scenePassages')),
              'introductions',JSON_EXTRACT(o.payload,'$.sceneDraft.newCharacters'),
              'outcomes',JSON_EXTRACT(o.payload,'$.sceneDraft.sceneOutcomes'),
              'events',JSON_EXTRACT(o.payload,'$.events'))
            FROM game_operation o JOIN interaction_log l ON BINARY l.session_id=BINARY o.session_id
              AND l.log_id=CAST(JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.logId')) AS UNSIGNED)
              AND BINARY JSON_UNQUOTE(JSON_EXTRACT(l.rule_snapshot,'$.operationId'))=BINARY o.operation_id
            WHERE o.session_id=? AND o.status='COMPLETED'
              AND BINARY l.processed_dialog=BINARY CONCAT('narrator：',JSON_UNQUOTE(JSON_EXTRACT(o.payload,'$.story')))
            """,String.class,sid)){
            try{history.add((ObjectNode)JSON.readTree(raw));}catch(java.io.IOException e){throw new IllegalStateException("已提交故事记录无法读取",e);}
        }
        history.sort(Comparator.comparingInt((ObjectNode n)->n.path("round").asInt()).thenComparingLong(n->n.path("logId").asLong()));
        var pending=new LinkedHashMap<String,Correction>();
        for(var record:history){
            // A later authored move wins, including a return to the old location. Never rewind it.
            for(var group:record.path("outcomes"))for(var who:group.path("roleIds"))pending.remove(who.asText());
            for(var event:record.path("events"))for(var effect:event.path("effects"))if(Set.of("MOVE","EXPLORE").contains(effect.path("kind").asText()) && !effect.path("creationOnly").asBoolean())pending.remove(effect.path("actorId").asText(event.path("actorId").asText()));
            if(record.path("frame").asInt()!=0 || record.path("passages").asInt()!=1 || !record.path("outcomes").isArray())continue;
            String operation=record.path("operationId").asText(),destination=record.path("sceneId").asText();
            for(var intro:record.path("introductions")){
                String old=intro.path("sceneId").asText(),role=cast.idFor(sid,operation,0,intro.path("name").asText());
                if(old.isBlank() || destination.isBlank() || old.equals(destination))continue;
                boolean explicit=false;for(var group:record.path("outcomes"))for(var who:group.path("roleIds"))if(role.equals(who.asText()))explicit=true;
                if(!explicit)pending.put(role,new Correction(role,old,destination,"story-introduction:"+operation+":0",operation));
            }
        }
        String player=jdbc.queryForObject("SELECT god_id FROM player_profile WHERE player_id=?",String.class,pid);
        var repairs=JSON.createArrayNode();
        for(var c:pending.values()){
            // Scope the migration to people actually materialized by that introduction; repeated
            // mentions of an existing person, abandoned candidates and foreign IDs cannot qualify.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND scene_id=?",Integer.class,sid,c.role(),c.introRef(),c.oldScene())!=1)continue;
            var old=scenes.findBySessionIdAndSceneId(sid,c.oldScene());var actual=scenes.findBySessionIdAndSceneId(sid,c.actualScene());
            if(old.isEmpty() || actual.isEmpty() || !old.get().getPresentGods().contains(c.role()))continue;
            var result=JSON.createObjectNode().put("status","COMPLETED");result.putArray("effects").addObject().put("kind","MOVE").put("actorId",c.role()).put("toSceneId",c.actualScene());
            effects.apply(sid,old.get(),c.role(),player,result,new HashSet<>(),0,0,true);
            jdbc.update("UPDATE role_knowledge SET scene_id=?,location_name=? WHERE session_id=? AND god_id=? AND scene_id=? AND source_ref IN (?,?,?)",c.actualScene(),actual.get().getLocationName(),sid,c.role(),c.oldScene(),c.introRef(),"genesis:"+c.role(),"story:situation");
            repairs.addObject().put("roleId",c.role()).put("fromSceneId",c.oldScene()).put("toSceneId",c.actualScene()).put("sourceOperationId",c.operation());
        }
        var current=scenes.findBySessionIdAndSceneId(sid,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid)).orElseThrow();
        jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,coord_x,coord_y,location_name,event_type,actor_ids,event_description,state_changes,importance,is_secret,info_source) VALUES(?,(SELECT current_round FROM game_session WHERE session_id=?),'存档数据修复',?,?,?,'STORAGE_REPAIR','[]',?,?,1,true,'SYSTEM')",sid,sid,current.getCoordX(),current.getCoordY(),current.getLocationName(),repairs.toString(),VERSION);
    }
}
