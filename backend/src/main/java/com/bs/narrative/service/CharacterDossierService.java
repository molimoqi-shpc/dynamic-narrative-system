package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Detailed character dossiers and an append-only, audience-specific world-book archive. */
@Service
public class CharacterDossierService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public CharacterDossierService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public ObjectNode assemble(ObjectNode context,JsonNode direction,boolean player,String declaredDirection,long seconds){
        String previous=context.path("previousPortrayal").path("narrativeTier").asText();
        // Existing detailed/legacy characters retain their continuity; extras can grow into major roles.
        boolean establishedCore="CORE".equals(previous) || (previous.isEmpty() && context.path("previousPsychology").isObject() && !context.path("previousPsychology").isEmpty());
        String tier=player?"PLAYER":establishedCore?"CORE":"EXTRA".equals(direction.path("narrativeTier").asText())?"EXTRA":"CORE";
        var dossier=JSON.createObjectNode().put("templateVersion","CHARACTER_V2").put("narrativeTier",tier).put("id",context.path("id").asText()).put("name",context.path("name").asText()).put("isPlayer",player).put("snapshotAtSeconds",seconds);
        if(!player)dossier.put("tierReason",establishedCore?"承接此前重要角色档案":direction.path("tierReason").asText("兼容既有角色意愿"));
        var identity=dossier.putObject("identity");copy(context,identity,"establishedSelf","openingSharedHistory","sixStats","sixStatsEstablished","goals","talents","talentMeaning");
        var current=dossier.putObject("currentState");copy(context,current,"recordedSceneId","recordedLocation","storySituation","emotion","body","items","ownItemPlacements","agenda","activeWork","pausedWork","plannedWork");
        var ownRecords=context.path("ownRecords");JsonNode latestExperience=null;
        for(var record:ownRecords)if("TURN_EVENT".equals(record.path("source_type").asText())){
            latestExperience=record.path("content");
            if(!"OLDEST_TO_NEWEST".equals(context.path("historyOrder").asText()))break;
        }
        if(latestExperience!=null && latestExperience.path("endSeconds").isIntegralNumber())current.set("lastPersonalExperienceEndSeconds",latestExperience.path("endSeconds").deepCopy());
        current.put("freshnessMeaning","snapshotAtSeconds是本次组装角色表的时刻，不是所有经历都更新到此刻。storySituation与心理可能是本人最后保存的处境；未参与后续时不自动知道后事，也不以旧处境重置较新已发表正文。最后亲历的时间仅表示该条来源，不证明此后什么也没发生。");
        var known=dossier.putObject("personallyKnown");copy(context,known,"currentKnownState","lastObservedPeople","knownPeople","observedFacilities","pendingStimuli","ownPendingSpeech","sceneStateMeaning");
        var past=dossier.putObject("pastInformation");copy(context,past,"ownRecords","ownKnowledge","initialSceneHistory","historyOrder");
        ObjectNode psychology=!player && direction.path("characterTemplate").isObject()?(ObjectNode)direction.path("characterTemplate").deepCopy():JSON.createObjectNode();
        if("EXTRA".equals(tier))psychology.retain(List.of("roleInScene","immediateConcern","attitude","willingHelp","knowledgeLimits","boundaries"));
        if(establishedCore && !player && context.path("previousPsychology").isObject()){
            var retained=(ObjectNode)context.path("previousPsychology").deepCopy();
            if("EXTRA".equals(direction.path("narrativeTier").asText()))retained.set("currentSceneUpdate",psychology);
            else mergePsychology(retained,psychology);
            psychology=retained;
        }
        dossier.set("psychology",psychology);
        ObjectNode intentions=!player && direction.isObject()?(ObjectNode)direction.deepCopy():JSON.createObjectNode();
        intentions.remove(List.of("characterTemplate","narrativeTier","tierReason"));dossier.set("intentions",intentions);
        if(player)dossier.put("playerDeclaredDirection",declaredDirection).put("psychologyMeaning","玩家仅声明本次方向；未输入的心理、承诺和立场保持未知，不由模型补定。");
        else dossier.put("psychologyMeaning","心理和意愿是本人主观内容，不是已经发生的行为，不自动公开给其他角色或玩家。未在本段更新的心理栏目保留此前本人记录，不代表本段重新确认；以新亲历和本次明确更新承接变化。");
        return dossier;
    }
    /** A brief/partial API reply updates its supplied sections; it does not erase the rest of this person. */
    private void mergePsychology(ObjectNode retained,ObjectNode update){
        for(var field:update.properties()){
            if(field.getValue() instanceof ObjectNode child && retained.path(field.getKey()) instanceof ObjectNode previous)mergePsychology(previous,child);
            else retained.set(field.getKey(),field.getValue().deepCopy());
        }
    }
    private void copy(JsonNode from,ObjectNode to,String... fields){for(String field:fields)if(from.has(field))to.set(field,from.path(field).deepCopy());}
    private JsonNode decode(String raw){try{return JSON.readTree(raw);}catch(Exception ignored){return JSON.getNodeFactory().textNode(raw);}}
    private String encode(JsonNode value){return value.toString();}

    /** The caller holds the session's short transaction; references make retries idempotent. */
    public void archive(String sid,String role,int round,long seconds,String sceneId,String reference,String type,JsonNode content){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND state_changes=?",Integer.class,sid,reference)>0)return;
        var place=jdbc.queryForList("SELECT location_name,coord_x,coord_y FROM game_scene WHERE session_id=? AND scene_id=?",sid,sceneId);
        String name=place.isEmpty()?"历史地点":Objects.toString(place.get(0).get("location_name"),"历史地点");
        int x=!place.isEmpty() && place.get(0).get("coord_x") instanceof Number n?n.intValue():0,y=!place.isEmpty() && place.get(0).get("coord_y") instanceof Number n?n.intValue():0;
        jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,coord_x,coord_y,location_name,event_type,actor_ids,event_description,state_changes,importance,is_secret,info_source,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,5,true,'PERSONAL',CURRENT_TIMESTAMP)",sid,round,seconds<0?"历史记录（见原始时间）":"故事第"+seconds+"秒",x,y,name,type,JSON.createArrayNode().add(role).toString(),encode(content),reference);
    }
    /** Import every retained personal history record; never turn another person's secret into this role's memory. */
    public void importPersonalHistory(String sid,String role,int round){
        var imported=new HashSet<>(jdbc.queryForList("SELECT state_changes FROM world_book WHERE session_id=? AND event_type='CHARACTER_HISTORY' AND JSON_CONTAINS(actor_ids,JSON_QUOTE(?))",String.class,sid,role));
        for(var row:jdbc.queryForList("SELECT knowledge_id,source_type,content,scene_id,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('TURN_EVENT','ROLE_PROFILE','SELF_PROFILE','SCENE_ATTEMPT') ORDER BY knowledge_id",sid,role)){
            String ref="role-history:"+row.get("knowledge_id");if(imported.contains(ref))continue;
            JsonNode value=decode(row.get("content").toString());
            if(!"SELF_PROFILE".equals(row.get("source_type")) && value instanceof ObjectNode object)object.retain(List.of("recordType","kind","actorId","actorName","targetId","text","situation","speech","thought","attempt","direction","process","status","startSeconds","endSeconds","visibility","effects","observations","background"));
            var entry=JSON.createObjectNode().put("recordType",row.get("source_type").toString()).put("recordedTick",((Number)row.get("learned_tick")).longValue());entry.set("content",value);
            archive(sid,role,round,-1,Objects.toString(row.get("scene_id"),""),ref,"CHARACTER_HISTORY",entry);
        }
    }
    /** New narrative workflow imports only the committed records after its checkpointed cursor. */
    public void importPersonalHistoryIncremental(String sid,String role,int round){
        var cursors=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='history:cursor'",String.class,sid,role);
        long after=cursors.isEmpty()?0:decode(cursors.get(0)).path("lastKnowledgeId").asLong();
        var rows=jdbc.queryForList("SELECT knowledge_id,source_type,content,scene_id,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND knowledge_id>? AND source_type IN ('TURN_EVENT','ROLE_PROFILE','SELF_PROFILE') ORDER BY knowledge_id",sid,role,after);
        for(var row:rows){long id=((Number)row.get("knowledge_id")).longValue();var entry=JSON.createObjectNode().put("recordType",row.get("source_type").toString()).put("recordedTick",((Number)row.get("learned_tick")).longValue());entry.set("content",decode(row.get("content").toString()));archive(sid,role,round,-1,Objects.toString(row.get("scene_id"),""),"role-history:"+id,"CHARACTER_HISTORY",entry);after=id;}
        if(!rows.isEmpty())jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'history:cursor','历史索引位置',?,'ROLE_INTEGRATED','','','',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,role,JSON.createObjectNode().put("lastKnowledgeId",after).toString());
    }
    public JsonNode previousDossier(String sid,String role){
        var rows=jdbc.queryForList("SELECT event_description FROM world_book WHERE session_id=? AND event_type='CHARACTER_DOSSIER' AND JSON_CONTAINS(actor_ids,JSON_QUOTE(?)) ORDER BY entry_id DESC LIMIT 1",String.class,sid,role);
        return rows.isEmpty()?JSON.createObjectNode():decode(rows.get(0));
    }
    public JsonNode previousPsychology(String sid,String role){
        return previousDossier(sid,role).path("psychology").deepCopy();
    }
    public ArrayNode index(String sid){
        var out=JSON.createArrayNode();
        for(var row:jdbc.queryForList("SELECT g.god_id,g.god_name,COUNT(w.entry_id) AS entries,MAX(w.entry_id) AS latest FROM god_profile g JOIN world_book w ON w.session_id=g.session_id AND JSON_CONTAINS(w.actor_ids,JSON_QUOTE(g.god_id)) WHERE g.session_id=? AND w.event_type IN ('CHARACTER_DOSSIER','CHARACTER_HISTORY') GROUP BY g.god_id,g.god_name",sid)){
            out.addObject().put("roleId",row.get("god_id").toString()).put("name",row.get("god_name").toString()).put("entries",((Number)row.get("entries")).longValue()).put("latestEntryId",((Number)row.get("latest")).longValue());
        }
        return out;
    }
    /** Main-author lookup only. Results are never copied into another role's input. */
    public ArrayNode lookup(String sid,JsonNode requests){
        if(!requests.isArray() || requests.size()<1 || requests.size()>4)throw new IllegalArgumentException("世界书每次可查询一至四个角色历史页");
        var result=JSON.createArrayNode();
        for(var request:requests){
            String role=request.path("roleId").asText();if(jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id=?",Integer.class,sid,role)!=1)throw new IllegalArgumentException("世界书角色不属于此会话");
            long before=request.path("beforeEntryId").asLong(Long.MAX_VALUE);if(before<1)before=Long.MAX_VALUE;int limit=Math.max(1,Math.min(6,request.path("limit").asInt(4)));
            var page=result.addObject().put("roleId",role);var entries=page.putArray("entries");
            var rows=jdbc.queryForList("SELECT entry_id,round_no,story_time,location_name,event_type,event_description FROM world_book WHERE session_id=? AND JSON_CONTAINS(actor_ids,JSON_QUOTE(?)) AND event_type IN ('CHARACTER_DOSSIER','CHARACTER_HISTORY') AND entry_id<? ORDER BY entry_id DESC LIMIT ?",sid,role,before,limit);
            for(var row:rows){var entry=entries.addObject().put("entryId",((Number)row.get("entry_id")).longValue()).put("round",((Number)row.get("round_no")).intValue()).put("storyTime",Objects.toString(row.get("story_time"),"")).put("location",Objects.toString(row.get("location_name"),"")).put("recordType",row.get("event_type").toString());entry.set("content",decode(row.get("event_description").toString()));}
            if(!rows.isEmpty())page.put("nextBeforeEntryId",((Number)rows.get(rows.size()-1).get("entry_id")).longValue());
        }
        return result;
    }
}
