package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Main-author memory of significant published accounts and elapsed-time explanations, not an economy simulator. */
@Service
public class StoryContinuityService {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final JdbcTemplate jdbc;
 public StoryContinuityService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public ObjectNode read(String sid,String player){
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:continuity-state'",String.class,sid,player);
  if(rows.isEmpty())return JSON.createObjectNode();
  try{var value=JSON.readTree(rows.get(0));return value instanceof ObjectNode object?object:JSON.createObjectNode();}
  catch(com.fasterxml.jackson.core.JsonProcessingException ignored){return JSON.createObjectNode();}
 }
 public void apply(String sid,String player,String scene,int round,long log,int frame,JsonNode patch,JsonNode time){
  if(!patch.isObject() && !time.isObject())return;
  var state=read(sid,player);
  for(String collection:List.of("accounts","consequences")){
   var updates=patch.path(collection);if(!updates.isArray() || updates.isEmpty())continue;
   var records=state.withArray(collection);
   for(var update:updates){
    if(!update.isObject() || update.path("key").asText().isBlank())continue;
    String key=update.path("key").asText();ObjectNode merged=JSON.createObjectNode();int index=-1;
    for(int i=0;i<records.size();i++)if(key.equals(records.get(i).path("key").asText()) && records.get(i) instanceof ObjectNode object){merged=object.deepCopy();index=i;break;}
    for(var field:update.properties())if(!Set.of("source","key").contains(field.getKey()) && !field.getValue().isNull())merged.set(field.getKey(),field.getValue().deepCopy());
    merged.put("key",key);merged.putObject("source").put("logId",log).put("round",round).put("frame",frame);
    if(index<0)records.add(merged);else records.set(index,merged);
   }
  }
  if(time.isObject())state.set("timeProgress",time.deepCopy());
  if(patch.path("narrativeState").isObject() && !patch.path("narrativeState").isEmpty()){
   var handoff=(ObjectNode)patch.path("narrativeState").deepCopy();
   handoff.putObject("source").put("logId",log).put("round",round).put("frame",frame);
   state.set("narrativeState",handoff);
  }
  if(state.isEmpty())return;
  state.putObject("reviewedAt").put("logId",log).put("round",round).put("frame",frame);
  store(sid,player,scene,state);
 }
 /** Record exact human words and published source refs, without inferring acceptance. */
 public void recordResponse(String sid,String player,String scene,int round,long log,int frame,JsonNode context){
  if(!context.isObject() || !context.path("sourceLogId").isIntegralNumber() || !context.path("playerResponse").isTextual())return;
  var state=read(sid,player);var responses=state.withArray("choiceResponses");String key="choice:"+context.path("sourceLogId").asLong();ObjectNode record=null;
  for(var value:responses)if(value instanceof ObjectNode object && key.equals(value.path("key").asText()) && value.path("responseLogId").asLong()==log){record=object;break;}
  if(record==null){record=responses.addObject().put("key",key).put("responseLogId",log).put("responseRound",round);record.set("context",context.deepCopy());}
  var sources=record.withArray("publishedResults");boolean exists=false;for(var source:sources)if(source.path("logId").asLong()==log && source.path("frame").asInt()==frame)exists=true;
  if(!exists)sources.addObject().put("logId",log).put("round",round).put("frame",frame);
  store(sid,player,scene,state);
 }
 private void store(String sid,String player,String scene,ObjectNode state){
  state.put("meaning","主API从已发表正文整理的重要收付、时间及持续后果；choiceResponses保留玩家原话和正文出处，不把意愿自动当作实现。这是写作记忆，不是程序裁定的世界真相；只供主创，不广播给角色。");
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'story:continuity-state','重要剧情承接',?,'STORY_CONTINUITY','',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content),scene_id=VALUES(scene_id)",sid,player,state.toString(),scene);
 }
}
