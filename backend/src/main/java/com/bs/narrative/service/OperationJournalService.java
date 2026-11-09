package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Projects committed events into the existing world book and layered player memory.
 * Called inside the operation transaction, before capturing its checkpoint. */
@Service
public class OperationJournalService {
    private final JdbcTemplate jdbc;
    private final PlayerMemoryService memories;
    private static final ObjectMapper JSON=new ObjectMapper();
    public OperationJournalService(JdbcTemplate jdbc,PlayerMemoryService memories){this.jdbc=jdbc;this.memories=memories;}
    private static String cap(String text,int limit){return text.length()>limit?text.substring(0,limit):text;}
    public void sync(String sid,String pid,JsonNode operation,String status){
        long log=operation.path("logId").asLong();
        if(log==0 || operation.path("events").isEmpty())return;
        var own=new ArrayList<String>();var publicLines=new ArrayList<String>();var actors=new LinkedHashSet<String>();
        boolean moved=false;
        for(var event:operation.path("events")){
            String text=event.path("text").asText();if(text.isBlank())continue;
            own.add(text);
            if(!"PUBLIC".equals(event.path("visibility").asText()))continue;
            publicLines.add(text);if(!event.path("actorId").asText().isBlank())actors.add(event.path("actorId").asText());
            for(var effect:event.path("effects"))if("MOVE".equals(effect.path("kind").asText()))moved=true;
        }
        if(own.isEmpty())return;
        String ref=Long.toString(log),content=cap(String.join("\n",own),8000);
        int updated=jdbc.update("UPDATE player_memory SET content=?,importance=? WHERE session_id=? AND player_id=? AND scope='ROUND' AND ref_id=?",content,moved?7:5,sid,pid,ref);
        if(updated==0 && jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND player_id=? AND scope='ROUND' AND ref_id=?",Integer.class,sid,pid,ref)==0)
            jdbc.update("INSERT INTO player_memory(session_id,player_id,scope,ref_id,content,importance,created_at) VALUES(?,?,'ROUND',?,?,?,CURRENT_TIMESTAMP)",sid,pid,ref,content,moved?7:5);
        var scene=jdbc.queryForMap("SELECT scene_id,location_name,coord_x,coord_y FROM game_scene WHERE session_id=? AND scene_id=(SELECT current_scene_id FROM game_session WHERE session_id=?)",sid,sid);
        if(!publicLines.isEmpty()){
            String marker="operation-log:"+log,description=cap(String.join("\n",publicLines),12000);
            int changed=jdbc.update("UPDATE world_book SET event_description=?,event_type=?,story_time=? WHERE session_id=? AND state_changes=?",description,moved?"MOVE":"ACTION","经过 "+operation.path("endSeconds").asLong()+" 秒",sid,marker);
            if(changed==0 && jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND state_changes=?",Integer.class,sid,marker)==0){
                String ids;try{ids=JSON.writeValueAsString(actors);}catch(Exception e){throw new IllegalStateException(e);}
                jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,coord_x,coord_y,location_name,event_type,actor_ids,event_description,state_changes,importance,is_secret,info_source,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,false,'PERSONAL',CURRENT_TIMESTAMP)",
                        sid,operation.path("round").asInt(),"经过 "+operation.path("endSeconds").asLong()+" 秒",scene.get("coord_x"),scene.get("coord_y"),scene.get("location_name"),moved?"MOVE":"ACTION",ids,description,marker,moved?7:5);
            }
        }
        if("COMPLETED".equals(status)){
            memories.consolidate(pid,sid,scene.get("scene_id").toString(),operation.path("round").asInt(),"NO_OP");
            String ending=jdbc.queryForObject("SELECT status FROM game_session WHERE session_id=?",String.class,sid);
            if(Set.of("DEAD","VICTORY").contains(ending) && jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND player_id=? AND scope='GLOBAL' AND ref_id=?",Integer.class,sid,pid,ref)==0)
                memories.recordGlobalMemory(pid,sid,ref,content,10);
        }
    }
}
