package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Gives each opening actor their own authored past, never the player's private biography. */
@Service
public class OpeningRoleMemoryService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public OpeningRoleMemoryService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void establish(String sid,String scene,JsonNode cast){
        for(var person:cast){
            String name=person.path("name").asText(),past=person.path("self_history").asText().strip();
            if(past.isBlank())continue;
            var ids=jdbc.queryForList("SELECT god_id FROM god_profile WHERE session_id=? AND god_name=?",String.class,sid,name);
            if(ids.size()!=1)continue;
            var self=JSON.createObjectNode().put("name",name).put("personalHistory",past.length()>4000?past.substring(0,4000):past).put("source","OPENING_AUTHOR");
            jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'genesis:self','本人开局经历',?,'SELF_PROFILE','',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,ids.get(0),self.toString(),scene);
        }
    }
    /** Legacy openings already have explicit mutual family/friend history in the author's journal.
     * Only the matching role's original record is projected; later impressions and secrets are omitted. */
    public ArrayNode sharedPast(String sid,String role,String player){
        var out=JSON.createArrayNode();if(role.equals(player))return out;
        var players=jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,player);
        if(players.size()!=1)return out;
        for(String raw:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PLAYER_RELATION'",String.class,sid,player))try{
            var relation=JSON.readTree(raw);
            if(relation==null || !role.equals(relation.path("roleId").asText()) || !relation.path("recordedAtSeconds").isIntegralNumber() || relation.path("recordedAtSeconds").asLong()!=0 || !Set.of("FRIEND","FAMILY","ALLY").contains(relation.path("kind").asText()))continue;
            var shared=out.addObject().put("withRoleId",player).put("withName",players.get(0)).put("source","AUTHOR_ESTABLISHED_OPENING_RELATION");
            for(String key:List.of("kind","label","summary","basis"))if(relation.has(key))shared.set(key,relation.path(key).deepCopy());
        }catch(java.io.IOException invalid){/* Optional old presentation record cannot block play. */}
        return out;
    }
}
