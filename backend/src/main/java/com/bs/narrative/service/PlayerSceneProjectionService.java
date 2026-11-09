package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Reads delivered observations instead of exposing live NPC-private body/task snapshots. */
@Service
public class PlayerSceneProjectionService {
 private static final ObjectMapper JSON=new ObjectMapper();private final JdbcTemplate jdbc;private final GodProfileRepository actors;
 @org.springframework.beans.factory.annotation.Autowired private OpenSceneService physical;
 public PlayerSceneProjectionService(JdbcTemplate jdbc,GodProfileRepository actors){this.jdbc=jdbc;this.actors=actors;}
 private Map<String,Object> decode(String raw){try{
  Map<String,Object> value=JSON.readValue(raw,new com.fasterxml.jackson.core.type.TypeReference<>(){});
  // Legacy action notices stored an entire past process in the current activity slot.
  // Keep that evidence as history, without treating its prose as a continuing posture.
  if(Set.of("亲见变化","亲见到来").contains(Objects.toString(value.get("basis"),"")) || Boolean.TRUE.equals(value.get("activityHistorical"))){
   String activity=Objects.toString(value.get("activity"),"");if(!activity.isBlank())value.putIfAbsent("lastObservedProcess",activity);
   value.put("activity","");value.put("activityHistorical",true);
  }return value;
 }catch(Exception e){throw new IllegalStateException(e);}}
 private boolean currentlySeen(Map<String,Object> value){return !Set.of("DEPARTED","NOT_SEEN").contains(Objects.toString(value.get("presence"),""));}
 private Set<String> presentIds(String sid,GameScene scene){
  var ids=new LinkedHashSet<String>(scene.getPresentGods());
  // Scene rosters contain NPCs. The human-controlled actor is stored separately.
  ids.addAll(jdbc.queryForList("SELECT god_id FROM player_profile WHERE active_session_id=? AND current_scene_id=?",String.class,sid,scene.getSceneId()));return ids;
 }
 public List<Map<String,Object>> bodies(String sid,GameScene scene,String observer){
  var captured=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND scene_id=? AND source_type='OBSERVED_STATE' ORDER BY knowledge_id",String.class,sid,observer,scene.getSceneId());
  if(hasEntry(sid,scene,observer))return captured.stream().map(this::decode).filter(this::currentlySeen).toList();
  if(!Boolean.TRUE.equals(physical.view(sid,actors.findById(observer).orElseThrow(),scene).get("awake")))return captured.stream().map(this::decode).filter(this::currentlySeen).toList();
  var result=new ArrayList<Map<String,Object>>();String opening=Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0];
  for(String id:presentIds(sid,scene)){
   var observed=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='OBSERVED_STATE'",String.class,sid,observer,"observed:"+scene.getSceneId()+":"+id);
   if(!observed.isEmpty()){var value=decode(observed.get(0));if(currentlySeen(value))result.add(value);continue;}
   var actor=actors.findById(id).filter(a->sid.equals(a.getSessionId())).orElseThrow();
   if(id.equals(observer) || Boolean.TRUE.equals(physical.view(sid,actor,scene).get("hidden")))continue;
   result.add(Map.of("id",id,"name",actor.getGodName(),"awake",!OpenSceneService.initialStatus(opening,actor.getGodName(),"昏迷|昏睡|昏厥|沉睡",false),"bound",OpenSceneService.initialStatus(opening,actor.getGodName(),"缚|捆|绑",true),"position","入场时所见位置","activity","","basis","入场观察，尚无新的送达记录"));
  }return result;
 }
 public boolean hasEntry(String sid,GameScene scene,String observer){return jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='SCENE_SEEN'",Integer.class,sid,observer,"presence:"+scene.getSceneId())>0;}
 /** An actual entry grants a new visual observation; subsequent reads never rescan the live roster. */
 public void enter(String sid,GameScene scene,String observer,long seconds){
  if(!presentIds(sid,scene).contains(observer))return;
  var self=actors.findById(observer).orElseThrow();if(!Boolean.TRUE.equals(physical.view(sid,self,scene).get("awake")))return;
  var previous=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND scene_id=? AND source_type='OBSERVED_STATE'",String.class,sid,observer,scene.getSceneId()).stream().map(this::decode).toList();
  jdbc.update("DELETE FROM role_knowledge WHERE session_id=? AND god_id=? AND scene_id=? AND source_type='OBSERVED_STATE'",sid,observer,scene.getSceneId());
  var visible=new HashSet<String>();
  for(String id:presentIds(sid,scene))if(!id.equals(observer)){
   var person=actors.findById(id).filter(a->sid.equals(a.getSessionId())).orElseThrow();var body=physical.view(sid,person,scene);
   if(Boolean.TRUE.equals(body.get("hidden")))continue;
   visible.add(id);
   var observed=new LinkedHashMap<String,Object>();observed.put("id",id);observed.put("name",person.getGodName());
   for(String field:List.of("awake","bound","position"))observed.put(field,body.get(field));
   observed.put("activity","");observed.put("presence","LAST_SEEN");observed.put("observedAtSeconds",seconds);observed.put("basis","入场所见");
   save(sid,scene,observer,"observed:"+scene.getSceneId()+":"+id,"OBSERVED_STATE",observed);
  }
  // A fresh scan supplies negative evidence only for people this observer already knew.
  // Hidden and absent people are indistinguishable here; do not infer a destination or departure.
  for(var old:previous){String id=Objects.toString(old.get("id"),"");if(id.isBlank() || id.equals(observer) || visible.contains(id))continue;
   save(sid,scene,observer,"observed:"+scene.getSceneId()+":"+id,"OBSERVED_STATE",Map.of("id",id,"name",Objects.toString(old.get("name"),""),"presence","NOT_SEEN","observedAtSeconds",seconds,"basis","本次查看未见此人，去向未知"));
  }
  save(sid,scene,observer,"presence:"+scene.getSceneId(),"SCENE_SEEN",Map.of("atSeconds",seconds));
 }
 private void save(String sid,GameScene scene,String observer,String ref,String type,Object value){try{
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'实际所见',?,?,'',?,?,0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,observer,ref,JSON.writeValueAsString(value),type,scene.getSceneId(),scene.getLocationName());
 }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}}
 public List<Map<String,Object>> facilities(String sid,String scene,String observer){
  return jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND scene_id=? AND god_id=? AND source_type='SEEN_FACILITY'",String.class,sid,scene,observer).stream().map(this::decode).toList();
 }
}

