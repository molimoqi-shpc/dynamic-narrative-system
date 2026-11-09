package com.bs.narrative.service;
import com.bs.narrative.llm.ModelOutput;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.transaction.annotation.Transactional;

/** Private self-understanding and evidence-backed exchange interpretations; never world facts. */
@Service
public class RoleContinuityService {
 private final JdbcTemplate jdbc;private static final ObjectMapper JSON=new ObjectMapper();
 private static final Set<String> STATES=Set.of("ANSWERED","REFUSED","UNANSWERED","CONTRADICTORY","CONDITIONAL");
 public RoleContinuityService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private long tick(String sid){var rows=jdbc.queryForList("SELECT tick FROM world_clock WHERE session_id=?",Long.class,sid);return rows.isEmpty()?0:rows.get(0);}
 private void store(String sid,String role,String ref,String source,Map<String,Object> data,boolean once){
  try {String sql="INSERT "+(once?"IGNORE ":"")+"INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'角色私有连续认知',?,?,'','','',?)"+(once?"":" ON DUPLICATE KEY UPDATE content=VALUES(content),learned_tick=VALUES(learned_tick)");jdbc.update(sql,sid,role,ref,JSON.writeValueAsString(data),source,tick(sid));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
 }
 /** Only the actor's own executed attempt and resulting limited perception, never another actor's thought. */
 public void recordAttempt(String sid,String role,String scene,String attempt,Map<String,Object> before,Map<String,Object> after){
  if(attempt==null || attempt.isBlank() || !Boolean.TRUE.equals(after.get("awake")))return;
  var data=new LinkedHashMap<String,Object>();
  data.put("sceneId",scene);data.put("tick",tick(sid));data.put("attempt",ModelOutput.cap(attempt,240));
  data.put("previousActivity",ModelOutput.cap(Objects.toString(before.get("activity"),""),180));
  data.put("resultActivity",ModelOutput.cap(Objects.toString(after.get("activity"),""),240));
  var observed=new ArrayList<String>();
  if(after.get("percepts") instanceof List<?> percepts)for(var p:percepts)
   if(p instanceof String text && !text.isBlank() && observed.size()<3)observed.add(ModelOutput.cap(text,180));
  data.put("ownPercepts",observed);
  String ref="attempt:"+scene+":"+tick(sid);
  store(sid,role,ref,"SCENE_ATTEMPT",data,false);
  jdbc.update("UPDATE role_knowledge SET scene_id=? WHERE session_id=? AND god_id=? AND source_ref=?",scene,sid,role,ref);
 }
 public String attemptsContext(String sid,String role,String scene){
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND scene_id=? AND source_type='SCENE_ATTEMPT' ORDER BY learned_tick DESC,knowledge_id DESC LIMIT 3",String.class,sid,role,scene);
  if(rows.isEmpty())return "";
  var out=new StringBuilder("【自己在此处已经执行的尝试与亲自感知的结果，不能重置为尚未检查】\n");
  var segments=new ArrayList<String>();int remaining=1200;
  for(String row:rows)try{
   var n=JSON.readTree(row);String part="第"+n.path("tick").asLong()+"刻，实际尝试："+n.path("attempt").asText()
    +"；随后自己的事务状态："+n.path("resultActivity").asText()+"；自己感知："+n.path("ownPercepts")+"。\n";
   if(part.length()<=remaining){segments.add(part);remaining-=part.length();}
  }catch(Exception e){throw new IllegalStateException(e);}
  Collections.reverse(segments);segments.forEach(out::append);return out.toString();
 }
 public String context(String sid,String role){
  var rows=jdbc.queryForList("SELECT source_type,source_ref,content,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('SELF_PROFILE','TALK_STATE') ORDER BY (source_type='SELF_PROFILE') DESC,learned_tick DESC,knowledge_id DESC LIMIT 9",sid,role);
  var out=new StringBuilder("【仅属于你的连续认知】职责、方法和交涉判断均为自己的理解；对方的说法未自动核实。每个议题独立，旧答复不等于已答复另一个问题。\n");
  int remaining=2600;boolean hasProfile=false;
  for(var row:rows)try{
   var data=JSON.readTree((String)row.get("content"));String line;
   if("SELF_PROFILE".equals(row.get("source_type"))){hasProfile=true;line="SELF_PROFILE（建立于第"+row.get("learned_tick")+"刻的自我理解，不是永久事实；未知项须结合后来亲见听闻重新判断）："+data+"。\n";}
   else {
    var compact=JSON.createObjectNode();compact.put("topic_id",(String)row.get("source_ref"));
    for(String field:List.of("partner","topic","status","detail","basis","repeats"))compact.set(field,data.path(field));
    compact.put("elapsedTicks",Math.max(0,tick(sid)-data.path("sinceTick").asLong(tick(sid))));
    var history=data.path("history");if(history.isArray() && !history.isEmpty())compact.set("previous",history.get(history.size()-1));
    compact.put("updatedAtTick",((Number)row.get("learned_tick")).longValue());
    line="TALK_STATE："+compact+"。\n";
   }
   // Keep complete records, newest updates first; never cut a topic halfway through JSON.
   if(line.length()<=remaining){out.append(line);remaining-=line.length();}
  }catch(Exception e){throw new IllegalStateException(e);}
  if(!hasProfile)out.append("【自身认知尚未建立】本次输出self_profile，basis从实际可见资料原样摘录；不能补造过去秘密。\n");
  var sceneIds=jdbc.queryForList("SELECT scene_id FROM game_scene WHERE session_id=? AND JSON_CONTAINS(present_gods,JSON_QUOTE(?)) LIMIT 1",String.class,sid,role);
  return out.toString()+(sceneIds.isEmpty()?"":attemptsContext(sid,role,sceneIds.get(0)));
 }
 private static String topicRef(String partner,String topic){
  String normalized=Normalizer.normalize(topic,Normalizer.Form.NFKC).replaceAll("[\\p{Z}\\s]","").replaceAll("[。！？!?；;]+$","");
  if(normalized.isBlank())throw new IllegalArgumentException("Empty topic");
  try{return "talk:"+partner+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8))).substring(0,24);}
  catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
 }
 /** A prompt substring alone is not proof that this partner spoke to this listener. */
 private boolean heardBasis(String sid,String role,String partner,String status,String basis){
  var heard=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='HEARD' AND source_god_id=? ORDER BY knowledge_id DESC LIMIT 20",String.class,sid,role,partner);
  if(heard.stream().anyMatch(s->s.contains(basis)))return true;
  var timed=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TURN_EVENT' ORDER BY learned_tick DESC,knowledge_id DESC LIMIT 20",String.class,sid,role);
  for(String raw:timed)try{
   var n=JSON.readTree(raw);if(!"UTTERANCE".equals(n.path("recordType").asText()) || !n.path("text").asText().contains(basis))continue;
   String speaker=n.path("actorId").asText(),to=n.path("targetId").asText();
   if(partner.equals(speaker) && (role.equals(to) || to.isBlank()))return true;
   if("UNANSWERED".equals(status) && role.equals(speaker) && partner.equals(to))return true;
  }catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}

  var encounters=jdbc.queryForList("SELECT memory_content FROM npc_memory WHERE session_id=? AND god_id=? AND memory_type='PERSONAL' AND memory_content LIKE '【现场交涉】%' ORDER BY memory_id DESC LIMIT 10",String.class,sid,role);
  for(String entry:encounters)try{
   var n=JSON.readTree(entry.substring("【现场交涉】".length()));
   for(var speech:n.path("speech"))if(speech.path("text").asText().contains(basis)){
    String speaker=speech.path("id").asText(),to=speech.path("to").asText();
    if(partner.equals(speaker) && (role.equals(to) || to.isBlank()))return true;
    if("UNANSWERED".equals(status) && role.equals(speaker) && partner.equals(to))return true;
   }
  }catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}

  return false;
 }
 private boolean evidenced(JsonNode n,String grounding){String basis=n.path("basis").asText("");return !basis.isBlank() && basis.length()<=240 && grounding.contains(basis);}
 @Transactional
 public boolean remember(String sid,String role,JsonNode node,String grounding){
  var profile=node.path("self_profile");if(profile.isObject() && evidenced(profile,grounding)){
   String duty=ModelOutput.text(profile,"duty","",160),method=ModelOutput.text(profile,"method","",200),unknown=ModelOutput.text(profile,"unknown","",160);
   if(!duty.isBlank() && !method.isBlank())store(sid,role,"self:continuity","SELF_PROFILE",Map.of("duty",duty,"method",method,"unknown",unknown,"basis",profile.path("basis").asText()),true);
  }
  var exchange=node.path("exchange_update");if(!exchange.isObject() || !evidenced(exchange,grounding))return false;
  String partner=exchange.path("partner_id").asText(""),status=exchange.path("status").asText(""),topic=ModelOutput.text(exchange,"topic","",100),detail=ModelOutput.text(exchange,"detail","",220);
  if(partner.equals(role) || !STATES.contains(status) || topic.isBlank() || detail.isBlank() || jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id=?",Integer.class,sid,partner)==0 || !grounding.contains(partner))return false;
  String basis=exchange.path("basis").asText();
  if(!heardBasis(sid,role,partner,status,basis))return false;
  String requested=exchange.path("topic_id").asText("");String ref;
  if(!requested.isBlank()){
   var owned=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TALK_STATE' AND source_ref=?",String.class,sid,role,requested);
   if(owned.isEmpty())return false;
   try{var n=JSON.readTree(owned.get(0));if(!partner.equals(n.path("partner").asText()))return false;topic=n.path("topic").asText();ref=requested;}catch(Exception e){throw new IllegalStateException(e);}
  }else try{ref=topicRef(partner,topic);}catch(IllegalArgumentException e){return false;}
  // Preserve the single legacy topic before updating or starting another; never fabricate lost topics.
  var legacy=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TALK_STATE' AND source_ref=?",String.class,sid,role,"talk:"+partner);
  if(!legacy.isEmpty())try{
   var prev=JSON.readTree(legacy.get(0));String legacyRef=topicRef(partner,prev.path("topic").asText());
   jdbc.update("UPDATE IGNORE role_knowledge SET source_ref=? WHERE session_id=? AND god_id=? AND source_ref=?",legacyRef,sid,role,"talk:"+partner);
   // If the destination already exists it is newer; remove only the duplicate legacy slot.
   jdbc.update("DELETE FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",sid,role,"talk:"+partner);
   if(requested.equals("talk:"+partner))ref=legacyRef;
  }catch(Exception e){throw new IllegalStateException(e);}
  var old=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TALK_STATE' AND source_ref=?",String.class,sid,role,ref);
  int repeats=0;long since=tick(sid);boolean repeated=exchange.path("repeated_question").asBoolean(false),unchanged=false;
  var history=JSON.createArrayNode();
  if(!old.isEmpty())try{
   var prev=JSON.readTree(old.get(0));topic=prev.path("topic").asText();repeats=prev.path("repeats").asInt(0);since=prev.path("sinceTick").asLong(since);
   unchanged=status.equals(prev.path("status").asText()) && basis.equals(prev.path("basis").asText()) && detail.equals(prev.path("detail").asText());
   if(prev.path("history").isArray())for(var h:prev.path("history"))history.add(h);
   if(!unchanged){var h=JSON.createObjectNode();for(String field:List.of("status","detail","basis"))h.set(field,prev.path(field));history.add(h);}
   while(history.size()>3)history.remove(0);
  }catch(Exception e){throw new IllegalStateException(e);}
  if(repeated && unchanged)repeats++;
  var data=new LinkedHashMap<String,Object>();data.put("partner",partner);data.put("topic",topic);data.put("status",status);data.put("detail",detail);data.put("basis",basis);data.put("repeats",repeats);data.put("sinceTick",since);data.put("history",history);
  store(sid,role,ref,"TALK_STATE",data,false);
  // A repeat can suppress a claimed new node only for the same unchanged, evidenced topic.
  return repeated && unchanged && Set.of("RESPOND","INQUIRE").contains(node.path("behavior").asText()) && partner.equals(node.path("target_god_id").asText());
 }
}
