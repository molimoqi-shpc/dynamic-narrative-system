package com.bs.narrative.service;

import com.bs.narrative.engine.*;
import com.bs.narrative.exception.*;
import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** A read-only observer view. Looking behind the scenes never teaches a character new facts. */
@Service
public class PerspectiveService {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final JdbcTemplate jdbc;private final AuthService auth;private final GodProfileRepository gods;
 private final CharacterDossierService dossiers;private final ItemStateService items;
 @org.springframework.beans.factory.annotation.Autowired private CharacterStatService stats;
 @org.springframework.beans.factory.annotation.Autowired private PlayerMemoryService memory;
 @org.springframework.beans.factory.annotation.Autowired private VitalStateService vitality;
 @org.springframework.beans.factory.annotation.Autowired private OpenSceneService physical;
 @org.springframework.beans.factory.annotation.Autowired private com.bs.narrative.repository.GameSceneRepository scenes;
 public PerspectiveService(JdbcTemplate jdbc,AuthService auth,GodProfileRepository gods,CharacterDossierService dossiers,ItemStateService items){this.jdbc=jdbc;this.auth=auth;this.gods=gods;this.dossiers=dossiers;this.items=items;}
 private JsonNode decode(Object raw){try{return JSON.readTree(Objects.toString(raw,"null"));}catch(Exception ignored){return JSON.getNodeFactory().textNode(Objects.toString(raw,""));}}
 public boolean omniscient(String sid){return "OMNISCIENT".equals(jdbc.queryForObject("SELECT view_mode FROM game_session WHERE session_id=?",String.class,sid));}
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Map<String,Object> read(String sid,String pid,String roleId,Long before){
  auth.requireOwnedSession(sid,pid);
  var result=new LinkedHashMap<String,Object>();boolean omni=omniscient(sid);
  result.put("sessionId",sid);result.put("viewMode",omni?"OMNISCIENT":"DREAMER");
  var versions=jdbc.queryForList("SELECT branch_epoch,revision,story_seconds FROM game_runtime WHERE session_id=?",sid);
  var version=versions.isEmpty()?Map.<String,Object>of():versions.get(0);
  for(String field:List.of("branch_epoch","revision","story_seconds"))result.put(switch(field){case "branch_epoch"->"branchEpoch";case "story_seconds"->"storySeconds";default->field;},version.getOrDefault(field,0L));
  if(!omni){result.put("available",false);return result;}
  if(before!=null && before<1)throw new BusinessException(ErrorCode.PARAM_INVALID,"记忆游标无效");
  result.put("available",true);result.put("roles",jdbc.queryForList("SELECT god_id id,god_name name FROM god_profile WHERE session_id=? ORDER BY god_name,god_id",sid));
  var decisions=new ArrayList<>(publishedDirections(sid));
  for(var row:jdbc.queryForList("SELECT d.decision_id,d.log_id,d.god_id,d.behavior,d.speech,d.inner_thought,d.is_lying,g.god_name FROM npc_decision_log d JOIN interaction_log l ON l.log_id=d.log_id AND l.session_id=d.session_id JOIN god_profile g ON g.god_id=d.god_id AND g.session_id=d.session_id WHERE d.session_id=? ORDER BY d.decision_id DESC LIMIT 100",sid)){
   var d=new LinkedHashMap<String,Object>();d.put("godId",row.get("god_id"));d.put("godName",row.get("god_name"));d.put("logId",row.get("log_id"));d.put("kind","LEGACY_DECISION");d.put("innerThought",row.get("inner_thought"));d.put("speech",row.get("speech"));d.put("behavior",row.get("behavior"));d.put("isLying",Boolean.TRUE.equals(row.get("is_lying")) || row.get("is_lying") instanceof Number n && n.intValue()!=0);decisions.add(d);
  }
  result.put("decisions",decisions);
  result.put("ruleSnapshots",jdbc.queryForList("SELECT log_id,round_no,rule_snapshot FROM interaction_log WHERE session_id=? AND player_id=? ORDER BY log_id DESC LIMIT 12",sid,pid).stream().map(row->{var r=new LinkedHashMap<String,Object>();r.put("logId",row.get("log_id"));r.put("round",row.get("round_no"));r.put("snapshot",decode(row.get("rule_snapshot")));return r;}).toList());
  result.put("playerMemoryContext",memory.buildContextForPrompt(pid,sid));
  String selected=Objects.requireNonNullElse(roleId,"");
  if(selected.isBlank())selected=Objects.toString(jdbc.queryForObject("SELECT player_god_id FROM game_session WHERE session_id=?",String.class,sid),"");
  var actor=gods.findById(selected).filter(g->sid.equals(g.getSessionId())).orElseThrow(()->new BusinessException(ErrorCode.AUTH_FORBIDDEN,"该角色不属于此存档"));
  var character=new LinkedHashMap<String,Object>();character.put("id",actor.getGodId());character.put("name",actor.getGodName());
  character.put("baseStats",StatsCalculator.calcActualStats(actor).basesAsMap());character.put("actualStats",StatsCalculator.calcActualStats(actor).actualsAsMap());character.put("formula",StatFormulaBook.describe(actor));
  character.put("sixStatsEstablished",stats.established(sid,selected));
  character.put("inventory",items.describeOwned(sid,actor));
  var dossier=dossiers.previousDossier(sid,selected);if(dossier.has("snapshotAtSeconds"))character.put("dossierSnapshotSeconds",dossier.path("snapshotAtSeconds").asLong());character.put("tier",dossier.path("narrativeTier").asText("EXTRA"));
  if("EXTRA".equals(character.get("tier"))){var brief=JSON.createObjectNode();for(String key:List.of("roleInScene","immediateConcern","attitude","willingHelp","knowledgeLimits","boundaries"))if(dossier.path("psychology").has(key))brief.set(key,dossier.path("psychology").path(key));character.put("dossier",brief);}
  else character.put("dossier",dossier);
  character.put("location",jdbc.queryForList("SELECT scene_id,location_name FROM game_scene WHERE session_id=? AND (JSON_CONTAINS(present_gods,JSON_QUOTE(?)) OR scene_id=(SELECT COALESCE(p.current_scene_id,s.current_scene_id) FROM game_session s LEFT JOIN player_profile p ON p.player_id=s.player_id AND p.active_session_id=s.session_id WHERE s.session_id=? AND s.player_god_id=?))",sid,selected,sid,selected));
  character.put("health",vitality.health(sid,selected));
  var locations=(List<Map<String,Object>>)character.get("location");
  if(locations.size()==1)scenes.findBySessionIdAndSceneId(sid,locations.get(0).get("scene_id").toString()).ifPresent(place->{var actualBody=physical.view(sid,actor,place);var body=new LinkedHashMap<String,Object>();for(String key:List.of("awake","bound","position","activity","activityHistorical","hidden"))if(actualBody.containsKey(key))body.put(key,actualBody.get(key));character.put("body",body);});
  result.put("character",character);
  var memories=before==null?jdbc.queryForList("SELECT knowledge_id id,source_type type,title,content,learned_tick tick FROM role_knowledge WHERE session_id=? AND god_id=? ORDER BY knowledge_id DESC LIMIT 21",sid,selected):jdbc.queryForList("SELECT knowledge_id id,source_type type,title,content,learned_tick tick FROM role_knowledge WHERE session_id=? AND god_id=? AND knowledge_id<? ORDER BY knowledge_id DESC LIMIT 21",sid,selected,before);
  result.put("hasMoreMemories",memories.size()>20);var page=memories.stream().limit(20).map(row->{var m=new LinkedHashMap<>(row);m.put("content",decode(row.get("content")));return m;}).toList();result.put("memories",page);result.put("nextBefore",page.isEmpty()?null:page.get(page.size()-1).get("id"));
  result.put("perspectiveNote","天眼是玩家查阅已保存资料的观察视角，不改变角色自己的限知、记忆或行为。心理档案有记录时刻；行动意愿与条件性公式结果不等于已经发生。未知的属性和说谎状态不作推定。");
  return result;
 }
 /** Only intentions from passages actually committed to a retained chapter; exclude in-flight/rejected drafts. */
 public List<Map<String,Object>> publishedDirections(String sid){
  if(!omniscient(sid))return List.of();
  var names=new HashMap<String,String>();jdbc.query("SELECT god_id,god_name FROM god_profile WHERE session_id=?",rs->{names.put(rs.getString(1),rs.getString(2));},sid);
  var result=new ArrayList<Map<String,Object>>();
  for(Long log:jdbc.queryForList("SELECT log_id FROM interaction_log WHERE session_id=? ORDER BY log_id DESC LIMIT 12",Long.class,sid)){
   // Frozen operation JSON can exceed MySQL's sort buffer; order identifiers, not payloads.
   var keys=jdbc.queryForList("SELECT matched.branch_epoch,matched.operation_id FROM (SELECT branch_epoch,operation_id,updated_at FROM game_operation WHERE session_id=? AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.logId'))=? LIMIT 18446744073709551615) matched ORDER BY matched.updated_at DESC,matched.branch_epoch DESC,matched.operation_id DESC LIMIT 1",sid,log.toString());
   if(keys.isEmpty())continue;var key=keys.get(0);
   var row=jdbc.queryForMap("SELECT payload,status,operation_id FROM game_operation WHERE session_id=? AND branch_epoch=? AND operation_id=?",sid,key.get("branch_epoch"),key.get("operation_id"));var payload=decode(row.get("payload"));int committed=payload.path("scenePassages").size();
   for(var frame:payload.path("sceneDirections").properties()){
    int index;try{index=Integer.parseInt(frame.getKey());}catch(NumberFormatException ignored){continue;}if(index>=committed)continue;
    for(var role:frame.getValue().properties()){
     if(!names.containsKey(role.getKey()) || role.getKey().equals(payload.path("playerRole").asText()))continue;
     var entry=new LinkedHashMap<String,Object>();entry.put("godId",role.getKey());entry.put("godName",names.get(role.getKey()));entry.put("logId",log);entry.put("frame",index+1);entry.put("operationId",row.get("operation_id"));entry.put("phaseStatus",row.get("status"));entry.put("kind","STORY_DIRECTION");entry.put("isLying",null);
     for(String field:List.of("thought","intention","actionDirection","communicationIntent","boundaries","itemIntentions","combatDisposition"))if(role.getValue().has(field))entry.put(field,JSON.convertValue(role.getValue().path(field),Object.class));
     entry.put("innerThought",role.getValue().path("thought").asText());
     entry.put("deceptionIntent",role.getValue().path("characterTemplate").path("expression").path("deceptionIntent").asText());
     if(index==committed-1 && payload.path("sceneFrame").asInt()==index){entry.put("conditionalContests",payload.path("sceneFrameFacts").path("serverContests"));entry.put("contestResolutions",payload.path("sceneArchiveResult").path("contestResolutions"));}
     result.add(entry);
    }
   }
  }return result;
 }
}
