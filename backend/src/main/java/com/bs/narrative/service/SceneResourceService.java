package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative finite sources from explicit loose natural material in the accepted opening. */
@Service
public class SceneResourceService {
 private final JdbcTemplate jdbc; private final ItemStateService items; private final TemporalContextService temporal;
 private static final Pattern LOOSE=Pattern.compile("(?:地上|地面上?|路边|脚边|石旁|树下|草丛旁)(?:散落着?|落着|有|可见)(?:一(?:根|块|枚|团|束|片))?(枯枝|干树枝|干草|藤条|碎石|石子|木片|落叶)(?=[，。；！？\\n]|$)");
 public SceneResourceService(JdbcTemplate jdbc,ItemStateService items,TemporalContextService temporal){this.jdbc=jdbc;this.items=items;this.temporal=temporal;}
 public List<Map<String,String>> available(String sid,GameScene scene){
  if(!sid.equals(scene.getSessionId()))throw new IllegalArgumentException("foreign resource scene");
  String opening=Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0];var sources=new LinkedHashMap<String,Map<String,String>>();
  var changed=new HashSet<String>();for(var state:temporal.sceneState(sid,scene.getSceneId()))changed.add(Objects.toString(state.get("objectName"),""));
  for(String sentence:opening.split("[。；！？\\n]")){
   // Do not turn conditional, absent, claimed, hidden or already removed material into loot.
   if(!OpenSceneService.positiveEvidence(List.of(sentence),sentence) || sentence.matches("(?s).*(没有|并无|无有|未见|不见|不再|已无|拿走|取走|收走|拾走|属于|所有|手中|怀里|藏|绑|缠|系|插|挂|压住|盖住|看不见|不可见).*"))continue;
   var match=LOOSE.matcher(sentence);while(match.find()){
    String name=match.group(1),ref=reference(scene.getSceneId(),name);if(changed.contains(name))continue;
    if(jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_ref=? AND source_type='RESOURCE_TAKEN'",Integer.class,sid,ref)>0)continue;
    sources.putIfAbsent(ref,Map.of("ref",ref,"name",name,"source",match.group(),"availableUnits","1"));
   }
  }return List.copyOf(sources.values());
 }
 @Transactional
 public ItemStateService.Item take(String sid,GameScene scene,GodProfile actor,String player,String ref,String process,String basis){
  // Serializes all actors taking the same shared source, including separate simultaneous cohorts.
  if(jdbc.queryForList("SELECT scene_id FROM game_scene WHERE session_id=? AND scene_id=? FOR UPDATE",String.class,sid,scene.getSceneId()).isEmpty())throw new IllegalArgumentException("resource scene missing");
  var source=available(sid,scene).stream().filter(s->ref.equals(s.get("ref"))).findFirst().orElseThrow(()->new IllegalArgumentException("gather source absent, changed or already taken"));
  if(basis.isBlank() || !basis.contains(source.get("name")) || !process.contains(basis) || !OpenSceneService.positiveEvidence(List.of(process),basis)
    || !basis.matches("(?s).*(拾起|捡起|拾取|捡取|采得|采集到|取到|取得|拿起).*"))throw new IllegalArgumentException("gather needs an exact completed acquisition basis");
  var item=items.acquireGathered(sid,actor,source.get("name"),scene.getLocationName(),ref,source.get("source"));
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'已采集资源',?,'RESOURCE_TAKEN','',?,?,?)",sid,player,ref,"{\"taken\":true}",scene.getSceneId(),scene.getLocationName(),temporal.tick(sid));
  temporal.writeFacility(sid,player,scene,source.get("name"),takenState(source.get("name")),basis);
  return item;
 }
 public static String takenState(String name){return "一份"+name+"已被拾取，原地所余未确认";}
 private static String reference(String scene,String name){return "gather:"+UUID.nameUUIDFromBytes((scene+"\n"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
}
