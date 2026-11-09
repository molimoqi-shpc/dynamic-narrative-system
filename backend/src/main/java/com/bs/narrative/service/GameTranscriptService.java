package com.bs.narrative.service;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Owner-checked public narration. Never returns raw model responses or private inner thoughts. */
@Service
public class GameTranscriptService {
 @org.springframework.beans.factory.annotation.Autowired private OperationUsageService usage;
 private final JdbcTemplate jdbc;private final AuthService auth;
 public GameTranscriptService(JdbcTemplate jdbc,AuthService auth){this.jdbc=jdbc;this.auth=auth;}
 @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
 public Map<String,Object> read(String sid,String authenticatedPlayerId,Long before){
  if(authenticatedPlayerId==null || authenticatedPlayerId.isBlank())throw new com.bs.narrative.exception.BusinessException(com.bs.narrative.exception.ErrorCode.AUTH_REQUIRED,"请先登录");
  auth.requireOwnedSession(sid,authenticatedPlayerId);
  var versions=jdbc.queryForList("SELECT branch_epoch,revision FROM game_runtime WHERE session_id=?",sid);
  var version=versions.isEmpty()?Map.<String,Object>of("branch_epoch",0L,"revision",0L):versions.get(0);
  List<Map<String,Object>> rows=before==null?
   jdbc.queryForList("SELECT log_id,round_no,player_input,processed_dialog,rule_snapshot AS chapter_snapshot,JSON_EXTRACT(rule_snapshot,'$.decisionPoints') AS decision_points,JSON_EXTRACT(rule_snapshot,'$.activeDecisionPoints') AS active_points,JSON_EXTRACT(rule_snapshot,'$.waiting.phases') AS waiting_phases,JSON_EXTRACT(rule_snapshot,'$.technicalIssues') AS technical_issues FROM interaction_log WHERE session_id=? AND player_id=? ORDER BY log_id DESC LIMIT 40",sid,authenticatedPlayerId):
   jdbc.queryForList("SELECT log_id,round_no,player_input,processed_dialog,rule_snapshot AS chapter_snapshot,JSON_EXTRACT(rule_snapshot,'$.decisionPoints') AS decision_points,JSON_EXTRACT(rule_snapshot,'$.activeDecisionPoints') AS active_points,JSON_EXTRACT(rule_snapshot,'$.waiting.phases') AS waiting_phases,JSON_EXTRACT(rule_snapshot,'$.technicalIssues') AS technical_issues FROM interaction_log WHERE session_id=? AND player_id=? AND log_id<? ORDER BY log_id DESC LIMIT 40",sid,authenticatedPlayerId,before);
  Map<String,String> names=speakerNames(sid);
  var turns=new ArrayList<Map<String,Object>>();
  for(var row:rows){
   var actors=publicActors(Objects.toString(row.get("processed_dialog"),""),names);
   var turn=new LinkedHashMap<String,Object>(Map.of("id",row.get("log_id"),"round",Objects.requireNonNullElse(row.get("round_no"),0),"playerInput",Objects.toString(row.get("player_input"),""),"actors",actors,"decisionPoints",publicPoints(row.get("decision_points")),"activeDecisionPoints",activePoints(row),"technicalIssues",publicTechnicalIssues(row.get("technical_issues"))));turn.putAll(publicChapterSnapshot(row.get("chapter_snapshot")));turn.put("usage",usage.forLog(sid,((Number)row.get("log_id")).longValue()));turns.add(turn);
  }
  Object cursor=rows.isEmpty()?0:rows.get(rows.size()-1).get("log_id");Collections.reverse(turns);
  return Map.of("opening",opening(sid,authenticatedPlayerId),"turns",turns,"hasMore",rows.size()==40,"nextBeforeId",cursor,"sessionId",sid,"branchEpoch",version.get("branch_epoch"),"revision",version.get("revision"));
 }
 Map<String,String> speakerNames(String sid){
  Map<String,String> names=new HashMap<>();names.put("narrator","旁白");names.put("system_inquiry","传闻");names.put("system_move","行路");
  jdbc.query("SELECT god_id,god_name FROM god_profile WHERE session_id=?",rs->{names.put(rs.getString(1),rs.getString(2));},sid);return names;
 }
 static List<Map<String,Object>> publicActors(String prose,Map<String,String> names){
  var actors=new ArrayList<Map<String,Object>>();String id="narrator";StringBuilder speech=new StringBuilder();
  for(String line:prose.split("\\n")){
   int colon=line.indexOf('：');String prefix=colon<0?"":line.substring(0,colon);
   if(names.containsKey(prefix)){
    if(!speech.isEmpty())actors.add(Map.of("godId",id,"godName",names.getOrDefault(id,"此间人物"),"displayedDialogue",speech.toString()));
    id=prefix;speech=new StringBuilder(line.substring(colon+1));
   }else{if(!speech.isEmpty())speech.append("\n");speech.append(line);}
  }
  if(!speech.isEmpty())actors.add(Map.of("godId",id,"godName",names.getOrDefault(id,"此间人物"),"displayedDialogue",speech.toString()));return actors;
 }
 Map<String,Object> opening(String sid,String player){
  // The saved opening is public prose, independent of paginated turn logs.
  // Never reconstruct it from the current scene or return creation answers.
  var stories=jdbc.queryForList("SELECT birth_story FROM character_creation WHERE session_id=? AND player_id=? AND status='COMPLETED'",String.class,sid,player);
  if(stories.isEmpty() || stories.get(0)==null || stories.get(0).isBlank())return Map.of();
  return Map.of("title","序章 · 来路","text",stories.get(0));
 }
 static Map<String,Object> publicChapterSnapshot(Object raw){
  var out=new LinkedHashMap<String,Object>();if(raw==null)return out;
  try{var json=new com.fasterxml.jackson.databind.ObjectMapper();var node=json.readTree(raw.toString());
   for(String field:List.of("storyStartSeconds","storySeconds","elapsedSeconds"))if(node.path(field).isIntegralNumber() && node.path(field).asLong()>=0)out.put(field,node.path(field).asLong());
   if(node.path("inputNotice").isTextual() && node.path("inputNotice").asText().length()<=200)out.put("inputNotice",node.path("inputNotice").asText());
   if(node.path("storyProgress").isObject()){
    var source=node.path("storyProgress");var progress=new LinkedHashMap<String,Object>();
    progress.put("beats",Math.max(0,source.path("beats").asInt()));progress.put("requiresInput",source.path("requiresInput").asBoolean());
    for(String field:List.of("reason","stakes","decisionType","counterpartId"))if(source.path(field).isTextual())progress.put(field,source.path(field).asText());out.put("storyProgress",progress);
   }
   String status=node.path("operationStatus").asText();if(Set.of("RUNNING","PARTIAL","COMPLETED","CANCELLED").contains(status))out.put("operationStatus",status);
   for(String array:List.of("eventTimeline","waitResults","statChanges")){
    var result=new ArrayList<Map<String,Object>>();var allowed=switch(array){case "eventTimeline"->List.of("kind","text","startSeconds","endSeconds","status","time");case "waitResults"->List.of("status","atSeconds","conditioned","conditionText");default->List.of("key","before","after","delta");};
    if(node.path(array).isArray())for(var item:node.path(array)){var record=new LinkedHashMap<String,Object>();for(String field:allowed)if(item.has(field) && item.path(field).isValueNode())record.put(field,json.convertValue(item.path(field),Object.class));result.add(record);}
    out.put(array.equals("eventTimeline")?"events":array,result);
   }
  }catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}return out;
 }
 static List<Map<String,Object>> publicTechnicalIssues(Object raw) {
  if(raw==null)return List.of();
  try {var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw.toString());
   if(node.isArray())for(var issue:node)if("ROLE_DECISION_UNAVAILABLE".equals(issue.path("kind").asText()))return List.of(Map.of("kind","ROLE_DECISION_UNAVAILABLE"));
  }catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
  return List.of();
 }
 private static List<Map<String,Object>> activePoints(Map<String,Object> row){
  Object explicit=row.get("active_points");if(explicit!=null && !"null".equals(explicit.toString()))return publicPoints(explicit);
  var points=publicPoints(row.get("decision_points"));Object phase=row.get("waiting_phases");
  if(phase!=null && !"null".equals(phase.toString()))try{return CurrentDecisionPoints.atPhase(points,Integer.parseInt(phase.toString()));}catch(NumberFormatException ignored){}
  return points;
 }
 private static List<Map<String,Object>> publicPoints(Object raw){
  var result=new ArrayList<Map<String,Object>>();if(raw==null)return result;
  try{var n=new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw.toString());if(n.isArray())for(var p:n){
    String text=p.path("text").asText("");if(!text.isBlank() && result.size()<8)result.add(Map.of("text",com.bs.narrative.llm.ModelOutput.cap(text,500),"phase",p.path("phase").asInt(1)));
  }}catch(Exception ignored){}return result;
 }
}

