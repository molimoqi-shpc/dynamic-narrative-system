package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Objective facts, distinct from personal ambitions. No model-generated percentages or success flag. */
@Service
public class ObjectiveRuleService {
 private static final ObjectMapper JSON=new ObjectMapper();private final JdbcTemplate jdbc;private final TemporalContextService temporal;
 public ObjectiveRuleService(JdbcTemplate jdbc,TemporalContextService temporal){this.jdbc=jdbc;this.temporal=temporal;}
 private JsonNode parse(Object raw){try{return JSON.readTree(raw.toString());}catch(Exception e){throw new IllegalArgumentException("invalid goal rule",e);}}
 public void define(String sid,String owner,String id,JsonNode rule,boolean terminal){
  validate(sid,rule,0);jdbc.update("INSERT INTO game_goal_rule(session_id,owner_id,goal_id,rule_json,terminal_flag) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE rule_json=VALUES(rule_json),terminal_flag=VALUES(terminal_flag)",sid,owner,id,rule.toString(),terminal);
 }
 private void validate(String sid,JsonNode n,int depth){
  if(depth>6 || !n.isObject())throw new IllegalArgumentException("goal rule too deep");String type=n.path("type").asText();
  if(Set.of("AND","OR").contains(type)){if(!n.path("children").isArray() || n.path("children").isEmpty() || n.path("children").size()>8)throw new IllegalArgumentException("empty goal group");for(var c:n.path("children"))validate(sid,c,depth+1);return;}
  if("NOT".equals(type)){validate(sid,n.path("child"),depth+1);return;}
  switch(type){
   case "ITEM_HELD" -> {String name=n.path("itemName").asText();if(name.isBlank() || jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND JSON_CONTAINS(inventory,JSON_QUOTE(?))",Integer.class,sid,name)==0)throw new IllegalArgumentException("goal item not in established world");}
   case "AT_SCENE","FACILITY_STATE" -> {if(jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND scene_id=?",Integer.class,sid,n.path("sceneId").asText())!=1)throw new IllegalArgumentException("goal scene missing");
    if("FACILITY_STATE".equals(type)){String opening=jdbc.queryForObject("SELECT scene_summary FROM game_scene WHERE session_id=? AND scene_id=?",String.class,sid,n.path("sceneId").asText());if(n.path("objectName").asText().isBlank() || !Objects.toString(opening,"").contains(n.path("objectName").asText()) || n.path("state").asText().isBlank())throw new IllegalArgumentException("goal facility missing");}}
   case "TIME_AT_LEAST" -> {if(!n.path("seconds").isIntegralNumber() || n.path("seconds").asLong()<0)throw new IllegalArgumentException("invalid goal time");}
   case "KNOWS_RECORD" -> {if(n.path("sourceRef").asText().isBlank())throw new IllegalArgumentException("missing knowledge reference");}
   case "OBSERVED_SCENE" -> {if(jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND scene_id=?",Integer.class,sid,n.path("sceneId").asText())!=1)throw new IllegalArgumentException("goal scene missing");}
   case "HEARD_FROM" -> {if(jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id=?",Integer.class,sid,n.path("godId").asText())!=1)throw new IllegalArgumentException("goal speaker missing");}
   case "VISITED_SCENES" -> {if(!n.path("count").isIntegralNumber() || n.path("count").asInt()<2 || n.path("count").asInt()>20)throw new IllegalArgumentException("invalid scene count");}
   case "OBJECTIVE_COMPLETED" -> {if(jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND objective_id=?",Integer.class,sid,n.path("objectiveId").asText())!=1)throw new IllegalArgumentException("unknown objective");}
   default -> throw new IllegalArgumentException("unsupported goal condition");
  }
 }
 private String role(String sid,String owner){var ids=jdbc.queryForList("SELECT player_god_id FROM game_session WHERE session_id=? AND player_id=?",String.class,sid,owner);return ids.isEmpty()?owner:ids.get(0);}
 public boolean matches(String sid,String owner,JsonNode n){
  String actor=role(sid,owner);switch(n.path("type").asText()){
   case "AND":for(var c:n.path("children"))if(!matches(sid,owner,c))return false;return true;
   case "OR":for(var c:n.path("children"))if(matches(sid,owner,c))return true;return false;
   case "NOT":return !matches(sid,owner,n.path("child"));
   case "ITEM_HELD":return jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id=? AND JSON_CONTAINS(inventory,JSON_QUOTE(?))",Integer.class,sid,actor,n.path("itemName").asText())>0;
   case "AT_SCENE":return jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND scene_id=? AND JSON_CONTAINS(present_gods,JSON_QUOTE(?))",Integer.class,sid,n.path("sceneId").asText(),actor)>0 || jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE active_session_id=? AND god_id=? AND current_scene_id=?",Integer.class,sid,actor,n.path("sceneId").asText())>0;
   case "TIME_AT_LEAST":var seconds=jdbc.queryForList("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid);return !seconds.isEmpty() && seconds.get(0)>=n.path("seconds").asLong();
   case "KNOWS_RECORD":return jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",Integer.class,sid,actor,n.path("sourceRef").asText())>0;
   case "OBSERVED_SCENE":return jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SCENE_SEEN' AND scene_id=?",Integer.class,sid,actor,n.path("sceneId").asText())>0;
   case "HEARD_FROM":return jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD' AND source_god_id=?",Integer.class,sid,actor,n.path("godId").asText())>0;
   case "VISITED_SCENES":return jdbc.queryForObject("SELECT COUNT(DISTINCT scene_id) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('SCENE_SEEN','VISITED_SCENE')",Integer.class,sid,actor)>=n.path("count").asInt();
   case "OBJECTIVE_COMPLETED":return jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND player_id=? AND objective_id=? AND status='COMPLETED'",Integer.class,sid,owner,n.path("objectiveId").asText())>0;
   case "FACILITY_STATE":return temporal.sceneState(sid,n.path("sceneId").asText()).stream().anyMatch(state->n.path("objectName").asText().equals(state.get("objectName")) && n.path("state").asText().equals(state.get("state")));
   default:throw new IllegalArgumentException("unknown goal condition");
  }
 }
 public void refresh(String sid,String owner){
  var active=new HashSet<>(jdbc.queryForList("SELECT objective_id FROM player_objective WHERE session_id=? AND player_id=? AND status='ACTIVE'",String.class,sid,owner));
  var pending=new LinkedHashMap<String,JsonNode>();
  for(var row:jdbc.queryForList("SELECT goal_id,rule_json FROM game_goal_rule WHERE session_id=? AND owner_id=? AND terminal_flag=false ORDER BY goal_id",sid,owner)){
   String id=row.get("goal_id").toString();if(active.contains(id))pending.put(id,parse(row.get("rule_json")));
  }
  // Evaluate prerequisites first, including under NOT: a later-completed prerequisite must
  // not let its negation become an irreversible achievement earlier in the same refresh.
  var ordered=new ArrayList<String>();var visited=new HashMap<String,Boolean>();
  for(String id:pending.keySet())order(id,pending,new HashSet<>(),visited,ordered);
  for(String id:ordered){var rule=pending.get(id);
   if(matches(sid,owner,rule))jdbc.update("UPDATE player_objective SET progress=100,status='COMPLETED',current_step='客观完成条件已满足',updated_at=CURRENT_TIMESTAMP WHERE session_id=? AND player_id=? AND objective_id=? AND status='ACTIVE'",sid,owner,id);
   else jdbc.update("UPDATE player_objective SET progress=? WHERE session_id=? AND player_id=? AND objective_id=? AND status='ACTIVE'",progress(sid,owner,rule),sid,owner,id);
  }
 }
 private boolean order(String id,Map<String,JsonNode> rules,Set<String> visiting,Map<String,Boolean> visited,List<String> result){
  if(visited.containsKey(id))return visited.get(id);
  if(!visiting.add(id))return false;
  var dependencies=new HashSet<String>();references(rules.get(id),dependencies);boolean valid=true;
  for(String dependency:dependencies)if(rules.containsKey(dependency) && !order(dependency,rules,visiting,visited,result))valid=false;
  visiting.remove(id);visited.put(id,valid);if(valid)result.add(id);return valid;
 }
 private void references(JsonNode rule,Set<String> result){
  switch(rule.path("type").asText()){
   case "OBJECTIVE_COMPLETED" -> result.add(rule.path("objectiveId").asText());
   case "AND","OR" -> {for(var child:rule.path("children"))references(child,result);}
   case "NOT" -> references(rule.path("child"),result);
  }
 }
 private int progress(String sid,String owner,JsonNode n){
  if(matches(sid,owner,n))return 100;
  if("AND".equals(n.path("type").asText())){int sum=0;for(var child:n.path("children"))sum+=progress(sid,owner,child);return sum/n.path("children").size();}
  if("OR".equals(n.path("type").asText())){int best=0;for(var child:n.path("children"))best=Math.max(best,progress(sid,owner,child));return best;}
  if("VISITED_SCENES".equals(n.path("type").asText()))return Math.min(99,100*jdbc.queryForObject("SELECT COUNT(DISTINCT scene_id) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('SCENE_SEEN','VISITED_SCENE')",Integer.class,sid,role(sid,owner))/n.path("count").asInt());
  return 0;
 }
 public boolean terminalReached(String sid,String owner){
  var rows=jdbc.queryForList("SELECT rule_json FROM game_goal_rule WHERE session_id=? AND owner_id=? AND terminal_flag=true",sid,owner);
  return !rows.isEmpty() && rows.stream().allMatch(r->matches(sid,owner,parse(r.get("rule_json"))));
 }
}
