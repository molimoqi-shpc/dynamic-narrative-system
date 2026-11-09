package com.bs.narrative.service;

import com.bs.narrative.domain.GodProfile;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Physical placement is separate from custody and legal ownership; never creates inventory. */
@Service
public class ItemPlacementService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final ItemStateService items;
    public ItemPlacementService(JdbcTemplate jdbc,ItemStateService items){this.jdbc=jdbc;this.items=items;}
    private JsonNode decode(String raw){try{return JSON.readTree(raw);}catch(Exception e){throw new IllegalStateException("item placement malformed",e);}}
    public String position(String sid,String holder,String ref){
        var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_PLACEMENT' AND source_ref=?",String.class,sid,holder,"placement:"+ref);
        return rows.isEmpty()?"":decode(rows.get(0)).path("position").asText();
    }
    public List<Map<String,Object>> own(String sid,GodProfile actor){
        return items.owned(sid,actor).stream().map(item->{var value=new LinkedHashMap<String,Object>();
            value.put("ref",item.ref());value.put("name",item.name());value.put("holderId",actor.getGodId());value.put("legalOwnerId",ItemStateService.legalOwner(item));
            String position=position(sid,actor.getGodId(),item.ref());value.put("position",position);value.put("positionRecorded",!position.isBlank());return (Map<String,Object>)value;
        }).toList();
    }
    public void place(String sid,GodProfile holder,String scene,String ref,String before,String after){
        if(items.owned(sid,holder).stream().noneMatch(i->ref.equals(i.ref())))throw new IllegalArgumentException("cannot reposition an item not in actor custody");
        if(after.isBlank() || after.length()>300)throw new IllegalArgumentException("item placement destination invalid");
        String actual=position(sid,holder.getGodId(),ref);if(!actual.equals(before))throw new StateConditionException("item placement condition changed",ref,"position",before,actual);
        store(sid,holder.getGodId(),scene,ref,after);
    }
    public void resetAfterTransfer(String sid,String holder,String scene,String ref){store(sid,holder,scene,ref,"");}
    /** Custody survives travel; free-text placement may refer to furniture in the old scene. */
    public void resetAfterTravel(String sid,GodProfile holder,String scene){
        for(var item:items.owned(sid,holder))store(sid,holder.getGodId(),scene,item.ref(),"");
    }
    private void store(String sid,String holder,String scene,String ref,String position){try{
        String content=JSON.writeValueAsString(Map.of("itemRef",ref,"holderId",holder,"sceneId",scene,"position",position));
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'物品放置',?,'ITEM_PLACEMENT','',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content),scene_id=VALUES(scene_id)",sid,holder,"placement:"+ref,content,scene);
    }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
}
