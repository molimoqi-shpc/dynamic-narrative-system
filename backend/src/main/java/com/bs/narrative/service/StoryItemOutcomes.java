package com.bs.narrative.service;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Stores explicitly authored final custody. Never extracts possessions from prose or intentions. */
@Service
public class StoryItemOutcomes {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final JdbcTemplate jdbc;private final ItemStateService items;private final GodProfileRepository actors;
 public StoryItemOutcomes(JdbcTemplate jdbc,ItemStateService items,GodProfileRepository actors){this.jdbc=jdbc;this.items=items;this.actors=actors;}
 private GodProfile actor(String sid,String role){return actors.findById(role).filter(a->sid.equals(a.getSessionId())).orElseThrow(()->new IllegalArgumentException("物品结果引用了其他存档的人物"));}
 private JsonNode parse(String raw){try{return JSON.readTree(raw);}catch(Exception e){throw new IllegalStateException(e);}}
 private String sceneReference(String sid,JsonNode outcome){
  String name=outcome.path("scene").path("locationName").asText(outcome.path("locationName").asText());
  if(!name.isBlank()){
   var matches=jdbc.queryForList("SELECT scene_id FROM game_scene WHERE session_id=? AND location_name=?",String.class,sid,name);
   if(matches.size()==1)return matches.get(0);
   if(matches.isEmpty() && outcome.path("scene").isObject()){
    // The main author explicitly places an already tracked object with a shop
    // or institution. No person has to move there merely to create its record.
    String id="scene_"+UUID.randomUUID().toString().replace("-","").substring(0,26);
    String description=outcome.path("scene").path("opening").asText(outcome.path("source").asText(name));
    jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,?,'[]',?)",id,sid,name,description);return id;
   }

  }
  return outcome.path("sceneId").asText();
 }
 public void apply(String sid,JsonNode outcomes){apply(sid,outcomes,null);}
 public void apply(String sid,JsonNode outcomes,JsonNode journal){
  if(outcomes.isMissingNode() || outcomes.isNull())return;
  if(!outcomes.isArray())throw new IllegalArgumentException("物品实际结果应为数组");
  var aliases=new HashMap<String,String>();
  for(var outcome:outcomes){
   String ref=outcome.path("itemRef").asText(),name=outcome.path("name").asText(),holder=outcome.path("holderId").asText();
   String state=outcome.path("state").asText(holder.isBlank()?"IN_SCENE":"HELD");
   if(!Set.of("HELD","IN_SCENE","CONSUMED").contains(state))throw new IllegalArgumentException("物品实际结果状态需要HELD、IN_SCENE或CONSUMED");
   var live=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.state')) IN ('HELD','IN_SCENE')",String.class,sid).stream().map(this::parse).toList();
   var matches=live.stream().filter(i->!ref.isBlank() && ref.equals(i.path("ref").asText())).toList();
   if(matches.isEmpty() && !name.isBlank())matches=live.stream().filter(i->name.equals(i.path("name").asText())).toList();
   if(matches.size()>1)throw new IllegalArgumentException("同名多件物品请引用实际物品编号");
   if(matches.isEmpty()){
    if(!"HELD".equals(state))continue; // Uncollected scenery and same-passage used-up objects need no inventory unit.
    String source=outcome.path("source").asText();
    if(name.isBlank() || name.length()>64 || source.isBlank() || source.length()>2000)throw new IllegalArgumentException("新物品结果需要名称与实际取得来历");
    var acquired=items.acquireNarrative(sid,actor(sid,holder),name,source);if(!ref.isBlank())aliases.put(ref,acquired.ref());continue;
   }
   var item=matches.get(0);String actualRef=item.path("ref").asText(),previous=item.path("holderId").asText();if(!ref.isBlank())aliases.put(ref,actualRef);
   if("HELD".equals(state)){
    var recipient=actor(sid,holder);
    if("IN_SCENE".equals(item.path("state").asText()))items.takeFromScene(sid,recipient,item.path("sceneId").asText(),actualRef);
    else if(!previous.equals(holder)){
     String mode=outcome.path("mode").asText("HOLD");if(!Set.of("GIFT","LEND","RETURN","SEIZE").contains(mode))mode="HOLD";
     items.transferNarrative(sid,actor(sid,previous),recipient,actualRef,mode);
    }
   }else if("IN_SCENE".equals(state)){
    if(!previous.isBlank())items.leaveInScene(sid,actor(sid,previous),sceneReference(sid,outcome),actualRef);
   }else if("CONSUMED".equals(state)){
    if(!previous.isBlank())items.consumeNarrative(sid,actor(sid,previous),actualRef);
   }else throw new IllegalArgumentException("物品实际结果状态需要HELD、IN_SCENE或CONSUMED");
  }
  actors.flush();
  bindItemReferences(journal,aliases);
 }
 /** Temporary author labels never become server inventory IDs; bind only structured references. */
 private void bindItemReferences(JsonNode node,Map<String,String> aliases){
  if(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object){
   if(node.path("itemRef").isTextual() && aliases.containsKey(node.path("itemRef").asText()))object.put("itemRef",aliases.get(node.path("itemRef").asText()));
   for(var child:node)if(child.isContainerNode())bindItemReferences(child,aliases);
  }else if(node!=null && node.isArray())for(var child:node)bindItemReferences(child,aliases);
 }
}
