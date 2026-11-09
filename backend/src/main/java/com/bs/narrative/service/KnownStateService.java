package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Reduces only this observer's delivered structured facts. Never reads another role's live state. */
@Service
public class KnownStateService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public KnownStateService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private JsonNode decode(String raw){try{return JSON.readTree(raw);}catch(Exception e){throw new IllegalStateException("delivered state record is malformed",e);}}
    private void put(Map<String,Map<String,Object>> state,String kind,String subject,String field,JsonNode value,String source,Long at,long learned,String scene){
        if(subject.isBlank() || value==null || value.isMissingNode() || value.isNull())return;
        String key=kind+":"+subject+":"+field;
        var old=state.get(key);
        if(old!=null){Long previous=(Long)old.get("occurredAtSeconds");
            // An older event learned later cannot overwrite a newer firsthand observation.
            if(previous!=null && (at==null || at<previous))return;
            if(Objects.equals(previous,at) && learned<((Number)old.get("learnedOrder")).longValue())return;
        }
        var entry=new LinkedHashMap<String,Object>();entry.put("subjectType",kind);entry.put("subject",subject);entry.put("field",field);
        entry.put("value",JSON.convertValue(value,Object.class));entry.put("sourceEvent",source);entry.put("occurredAtSeconds",at);
        entry.put("learnedOrder",learned);entry.put("epistemicKind","LAST_OBSERVED");entry.put("sceneId",scene);
        entry.put("supersedes",old==null?null:old.get("sourceEvent"));state.put(key,entry);
    }
    public List<Map<String,Object>> current(String sid,String observer,String scene){
        var state=new LinkedHashMap<String,Map<String,Object>>();
        // No LIMIT shared with thoughts or prose; stable primary-key traversal avoids sorting large JSON bodies.
        jdbc.query("SELECT knowledge_id,source_ref,source_type,content FROM role_knowledge WHERE session_id=? AND god_id=? AND scene_id=? AND source_type IN ('OBSERVED_STATE','SEEN_FACILITY','TURN_EVENT') ORDER BY knowledge_id",rs->{
            long learned=rs.getLong("knowledge_id");String source=rs.getString("source_ref"),type=rs.getString("source_type");
            var n=decode(rs.getString("content"));Long at=null;
            if(n.path("observedAtSeconds").isIntegralNumber())at=n.path("observedAtSeconds").asLong();
            else if(n.path("endSeconds").isIntegralNumber())at=n.path("endSeconds").asLong();
            if("OBSERVED_STATE".equals(type)){
                for(String field:List.of("presence","awake","bound","position","activity")){
                    var value=n.get(field);
                    if("activity".equals(field) && (n.path("activityHistorical").asBoolean() || Set.of("亲见变化","亲见到来").contains(n.path("basis").asText())))value=JSON.getNodeFactory().textNode("");
                    put(state,"PERSON",n.path("id").asText(),field,value,source,at,learned,scene);
                }
                return;
            }
            if("SEEN_FACILITY".equals(type)){put(state,"FACILITY",n.path("objectName").asText(),"state",n.get("state"),source,at,learned,scene);return;}
            // Utterances, thoughts, plans and text without structured effects remain historical claims.
            if("UTTERANCE".equals(n.path("recordType").asText()))return;
            for(var effect:n.path("effects"))switch(effect.path("kind").asText()){
                case "BODY" -> {for(String field:List.of("awake","bound","position","activity"))put(state,"PERSON",effect.path("actorId").asText(),field,effect.path("after").get(field),source,at,learned,scene);}
                case "ITEM_POSITION" -> put(state,"ITEM",effect.path("itemRef").asText(),"position",effect.get("after"),source,at,learned,scene);
                case "TRANSFER" -> {put(state,"ITEM",effect.path("itemRef").asText(),"holderId",effect.get("toId"),source,at,learned,scene);
                    // Delivery changes custody; the previously seen placement is no longer current.
                    put(state,"ITEM",effect.path("itemRef").asText(),"position",JSON.getNodeFactory().textNode("交付后具体放置位置未观察"),source,at,learned,scene);}
                case "FACILITY","GATHER" -> put(state,"FACILITY",effect.path("objectName").asText(),"state",effect.get("after"),source,at,learned,scene);
                case "CONSUME" -> {
                    put(state,"ITEM",effect.path("itemRef").asText(),"state",JSON.getNodeFactory().textNode("CONSUMED"),source,at,learned,scene);
                    put(state,"ITEM",effect.path("itemRef").asText(),"position",JSON.getNodeFactory().textNode("已用尽，不再持有"),source,at,learned,scene);
                }
                case "MOVE" -> put(state,"PERSON",effect.path("actorId").asText(),"presence",JSON.getNodeFactory().textNode(effect.has("toSceneId")?"LAST_SEEN":"DEPARTED"),source,at,learned,scene);
                default -> { }
            }
        },sid,observer,scene);
        // A last-seen body must not remain a local current fact after a newer absence observation.
        // Historical events stay intact, including delayed deliveries of older body changes.
        state.values().removeIf(fact->{
            if(!"PERSON".equals(fact.get("subjectType")) || "presence".equals(fact.get("field")))return false;
            var presence=state.get("PERSON:"+fact.get("subject")+":presence");
            if(presence==null || !Set.of("DEPARTED","NOT_SEEN").contains(Objects.toString(presence.get("value"),"")))return false;
            Long bodyAt=(Long)fact.get("occurredAtSeconds"),absentAt=(Long)presence.get("occurredAtSeconds");
            if(!Objects.equals(bodyAt,absentAt))return absentAt!=null && (bodyAt==null || bodyAt<absentAt);
            return ((Number)fact.get("learnedOrder")).longValue()<=((Number)presence.get("learnedOrder")).longValue();
        });
        return List.copyOf(state.values());
    }
}
