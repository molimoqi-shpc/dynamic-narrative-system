package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.regex.*;

/** Bilateral, explicitly delivered consent. A pact records obligations, never a role's psychology. */
@Service
public class TreatyService {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final String TERMS="双方互不主动发起交手；保留各自行动与判断；任何一方可明确解除盟约。结盟不等于效忠、信任增加、自动同行或保证援助。";
 private static final String DURATION="([1-9][0-9]{0,4}|[一二两三四五六七八九十]{1,3})(秒|分钟|小时)";
 // Scan a whole numeric expression before validating it. A valid suffix (180)
 // must never stand in for an invalid/changed amount (1000180 or 1.180).
 private static final String RAW_DURATION="(?<![0-9一二两三四五六七八九十百千万亿零〇点.．+＋\\-－])([+＋\\-－]?[0-9一二两三四五六七八九十百千万亿零〇点.．]+)(秒|分钟|小时)";
 private final JdbcTemplate jdbc;
 public TreatyService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private record Entry(String ref,ObjectNode value){}
 private record Command(String type,String quote,long duration){}
 private ObjectNode decode(String text){try{return (ObjectNode)JSON.readTree(text);}catch(Exception e){throw new IllegalStateException("盟约记录损坏",e);}}
 private List<Entry> owned(String sid,String owner){return jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TREATY' ORDER BY knowledge_id",sid,owner).stream().map(r->new Entry(r.get("source_ref").toString(),decode(r.get("content").toString()))).toList();}
 private void lock(String sid){if(jdbc.queryForList("SELECT session_id FROM game_session WHERE session_id=? FOR UPDATE",String.class,sid).isEmpty())throw new IllegalArgumentException("treaty session missing");}
 private String name(String sid,String role){return jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,role).stream().findFirst().orElse("");}
 private boolean delivered(String sid,String owner,String event,String speaker,String target,String type,String speech,long at){
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='TURN_EVENT'",String.class,sid,owner,event);
  if(rows.isEmpty())return false;var proof=decode(rows.get(0));
  return type.equals(proof.path("recordType").asText(proof.path("kind").asText())) && speaker.equals(proof.path("actorId").asText()) && target.equals(proof.path("targetId").asText()) && proof.path("endSeconds").asLong(-1)==at
   && (speech==null || speech.equals(proof.path("text").asText()));
 }
 /** Scheduler must already have persisted full speech delivery for both named parties. */
 @Transactional
 public void captureDelivered(String sid,String speaker,String audience,String speech,String scene,long at,String event){
  captureDelivered(sid,speaker,audience,speech,scene,at,event,null);
 }
 @Transactional
 public void captureDelivered(String sid,String speaker,String audience,String speech,String scene,long at,String event,String replyToEvent){
  if(speaker.equals(audience) || at<0 || speech==null || event==null)return;var command=parse(speech);
  if(command==null && replyToEvent!=null)command=contextualConsent(sid,speaker,audience,speech,at,replyToEvent);
  if(command==null)return;
  if(name(sid,speaker).isBlank() || name(sid,audience).isBlank() || !delivered(sid,speaker,event,speaker,audience,"UTTERANCE",speech,at) || !delivered(sid,audience,event,speaker,audience,"UTTERANCE",speech,at))return;
  lock(sid);refreshAt(sid,at);
  var records=owned(sid,speaker);
  for(var entry:records)for(var change:entry.value().path("events"))if(event.equals(change.path("eventRef").asText()))return;
  var pending=records.stream().filter(e->pair(e.value(),speaker,audience) && "PROPOSED".equals(e.value().path("status").asText())).findFirst();
  var active=records.stream().filter(e->pair(e.value(),speaker,audience) && "ACTIVE".equals(e.value().path("status").asText())).findFirst();
  if(command.type().equals("PROPOSE")){
   if(pending.isPresent() || active.isPresent())return;
   var pact=JSON.createObjectNode().put("kind","ALLIANCE").put("proposer",speaker).put("recipient",audience).put("proposerName",name(sid,speaker)).put("recipientName",name(sid,audience))
    .put("status","PROPOSED").put("terms",TERMS).put("proposedSeconds",at).put("durationSeconds",command.duration()).put("changedSeconds",at);
   change(pact,"PROPOSED",speaker,command.quote(),event,at,"提议已完整交付，等待对方明确回应，尚未结盟。");
   String ref="treaty:"+UUID.nameUUIDFromBytes((sid+"\n"+event).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   for(String owner:List.of(speaker,audience))jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) SELECT ?,?,?,?,?,'TREATY',?,?,location_name,COALESCE((SELECT tick FROM world_clock WHERE session_id=?),0) FROM game_scene WHERE session_id=? AND scene_id=?",sid,owner,ref,"与"+name(sid,owner.equals(speaker)?audience:speaker)+"的盟约",pact.toString(),owner.equals(speaker)?audience:speaker,scene,sid,sid,scene);
   return;
  }
  Entry selected=command.type().equals("END")?active.orElse(null):pending.orElse(null);if(selected==null || at<selected.value().path("changedSeconds").asLong())return;var pact=selected.value();
  boolean proposer=speaker.equals(pact.path("proposer").asText());String status,reason;
  switch(command.type()){
   case "ACCEPT" -> {if(proposer || command.duration()>=0 && command.duration()!=pact.path("durationSeconds").asLong())return;status="ACTIVE";reason="双方已经分别明确同意结盟；各自仍自主选择行动。";pact.put("acceptedSeconds",at);if(pact.path("durationSeconds").asLong()>0)pact.put("dueSeconds",Math.addExact(at,pact.path("durationSeconds").asLong()));}
   case "DECLINE" -> {if(proposer)return;status="DECLINED";reason="受邀者明确拒绝，盟约没有成立；不预设敌对或欺骗。";}
   case "WITHDRAW" -> {if(!proposer)return;status="WITHDRAWN";reason="提议者明确撤回，盟约没有成立。";}
   case "END" -> {status="ENDED";reason="一方已明确告知解除盟约；之后不再承担本盟约义务，不预设仇恨。";}
   default -> {return;}
  }
  change(pact,status,speaker,command.quote(),event,at,reason);
  if(replyToEvent!=null)((ObjectNode)pact.path("events").get(pact.path("events").size()-1)).put("replyToEventRef",replyToEvent);
  save(sid,selected.ref(),pact);
 }
 private boolean pair(JsonNode pact,String a,String b){return a.equals(pact.path("proposer").asText()) && b.equals(pact.path("recipient").asText()) || b.equals(pact.path("proposer").asText()) && a.equals(pact.path("recipient").asText());}
 private void change(ObjectNode pact,String status,String actor,String quote,String event,long at,String reason){pact.put("status",status).put("changedSeconds",at).put("reason",reason);pact.withArray("events").addObject().put("status",status).put("actorId",actor).put("quote",quote).put("eventRef",event).put("atSeconds",at);}
 private void save(String sid,String ref,JsonNode pact){jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND source_ref=? AND source_type='TREATY'",pact.toString(),sid,ref);}
 /** Story mode records the author's actual agreement outcome, without parsing speech or checking co-location. */
 @Transactional
 public void applyAuthored(String sid,String owner,String scene,JsonNode outcomes,long at){
  if(outcomes.isMissingNode() || outcomes.isNull())return;
  if(!outcomes.isArray())throw new IllegalArgumentException("实际盟约结果应为数组");
  for(var outcome:outcomes){
   String peer=outcome.path("counterpartId").asText(),status=outcome.path("status").asText(),basis=outcome.path("basis").asText(),kind=outcome.path("kind").asText();
   if(peer.equals(owner) || name(sid,peer).isBlank() || basis.isBlank() || !Set.of("PROPOSED","ACTIVE","DECLINED","WITHDRAWN","ENDED","BROKEN","EXPIRED").contains(status))continue;
   var latest=owned(sid,owner).stream().filter(e->pair(e.value(),owner,peer)).max(Comparator.comparingLong(e->e.value().path("changedSeconds").asLong()));
   // A custody promise is an agreement, but not an alliance. The typed outcome,
   // rather than words such as "托管" or "结盟" in its prose, selects its ledger.
   boolean legacyUpdate=kind.isBlank() && latest.isPresent() && (!Set.of("PROPOSED","ACTIVE").contains(status) || Set.of("PROPOSED","ACTIVE").contains(latest.get().value().path("status").asText()));
   if(!"ALLIANCE".equals(kind) && !legacyUpdate){
    String note=basis+(outcome.path("terms").asText().isBlank()?"":"\n约定内容："+outcome.path("terms").asText());
    String noteRef="social:agreement:"+UUID.nameUUIDFromBytes((sid+"\n"+owner+"\n"+peer+"\n"+at+"\n"+note).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    for(String party:List.of(owner,peer)){String other=party.equals(owner)?peer:owner;jdbc.update("INSERT IGNORE INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) SELECT ?,?,?,?,?,'SOCIAL',?,?,location_name,COALESCE((SELECT tick FROM world_clock WHERE session_id=?),0) FROM game_scene WHERE session_id=? AND scene_id=?",sid,party,noteRef,"与"+name(sid,other)+"的约定",note,other,scene,sid,sid,scene);}
    continue;
   }
   if(latest.isPresent() && status.equals(latest.get().value().path("status").asText()))continue;
   String ref;ObjectNode pact;
   if(latest.isPresent() && (!Set.of("PROPOSED","ACTIVE").contains(status) || Set.of("PROPOSED","ACTIVE").contains(latest.get().value().path("status").asText()))){ref=latest.get().ref();pact=latest.get().value();}
   else{
    if(!Set.of("PROPOSED","ACTIVE").contains(status))continue;
    String proposer=outcome.path("proposerId").asText(owner);if(!Set.of(owner,peer).contains(proposer))proposer=owner;
    String recipient=proposer.equals(owner)?peer:owner;
    pact=JSON.createObjectNode().put("kind","ALLIANCE").put("proposer",proposer).put("recipient",recipient).put("proposerName",name(sid,proposer)).put("recipientName",name(sid,recipient)).put("terms",outcome.path("terms").asText("以双方在故事中实际约定的内容为准。")).put("durationSeconds",0).put("proposedSeconds",at);
    ref="treaty:story:"+UUID.nameUUIDFromBytes((sid+"\n"+owner+"\n"+peer+"\n"+at).getBytes(java.nio.charset.StandardCharsets.UTF_8));
   }
   if("ACTIVE".equals(status) && !pact.has("acceptedSeconds"))pact.put("acceptedSeconds",at);
   change(pact,status,owner,basis,ref+":"+at+":"+status,at,basis);
   for(String party:List.of(owner,peer)){
    String other=party.equals(owner)?peer:owner;
    jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) SELECT ?,?,?,?,?,'TREATY',?,?,location_name,COALESCE((SELECT tick FROM world_clock WHERE session_id=?),0) FROM game_scene WHERE session_id=? AND scene_id=? ON DUPLICATE KEY UPDATE content=VALUES(content),scene_id=VALUES(scene_id),location_name=VALUES(location_name)",sid,party,ref,"与"+name(sid,other)+"的盟约",pact.toString(),other,scene,sid,sid,scene);
   }
  }
 }
 @Transactional
 public void refresh(String sid){
  lock(sid);var rows=jdbc.queryForList("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid);
  if(!rows.isEmpty()){reconcilePendingHistory(sid,rows.get(0));refreshAt(sid,rows.get(0));}
 }
 /** Repair only a derived pending pact, from matching deliveries already present on this branch.
  * No new speech, new offer, time advance or model request is generated. Later termination and
  * aggression must be replayed too; a missed acceptance cannot silently renew an expired pact. */
 private void reconcilePendingHistory(String sid,long now){
  var seen=new HashSet<String>();
  for(var row:jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND source_type='TREATY' ORDER BY knowledge_id",sid)){
   String ref=row.get("source_ref").toString();if(!seen.add(ref))continue;var pact=decode(row.get("content").toString());
   if(!"PROPOSED".equals(pact.path("status").asText()) || pact.path("events").size()!=1)continue;
   String from=pact.path("proposer").asText(),to=pact.path("recipient").asText(),offer=pact.path("events").get(0).path("eventRef").asText();
   var copies=jdbc.queryForList("SELECT god_id,content FROM role_knowledge WHERE session_id=? AND source_ref=? AND source_type='TREATY'",sid,ref);
   var original=pact;
   if(from.equals(to) || copies.size()!=2 || !copies.stream().map(c->c.get("god_id").toString()).collect(java.util.stream.Collectors.toSet()).equals(Set.of(from,to)) || copies.stream().anyMatch(c->!original.equals(decode(c.get("content").toString()))))continue;
   var proposals=jdbc.queryForList("SELECT knowledge_id,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='TURN_EVENT'",sid,from,offer);
   if(proposals.size()!=1)continue;var proposal=decode(proposals.get(0).get("content").toString());long proposed=pact.path("proposedSeconds").asLong(-1);
   var command=parse(proposal.path("text").asText());
   if(command==null || !"PROPOSE".equals(command.type()) || command.duration()!=pact.path("durationSeconds").asLong() || proposed<0 || proposed>now || !delivered(sid,from,offer,from,to,"UTTERANCE",proposal.path("text").asText(),proposed) || !delivered(sid,to,offer,from,to,"UTTERANCE",proposal.path("text").asText(),proposed))continue;
   var events=new ArrayList<>(jdbc.queryForList("SELECT knowledge_id,god_id,source_ref,scene_id,content FROM role_knowledge WHERE session_id=? AND god_id IN (?,?) AND source_type='TURN_EVENT' AND knowledge_id>? ORDER BY knowledge_id",sid,from,to,proposals.get(0).get("knowledge_id")));
   // Numeric story time, then the original delivery order for same-second events.
   events.sort(Comparator.comparingLong((Map<String,Object> e)->decode(e.get("content").toString()).path("endSeconds").asLong(-1)).thenComparingLong(e->((Number)e.get("knowledge_id")).longValue()));
   boolean changed=false;
   for(var item:events){var e=decode(item.get("content").toString());String speaker=e.path("actorId").asText(),target=e.path("targetId").asText(),event=item.get("source_ref").toString();long at=e.path("endSeconds").asLong(-1);
    if(at<proposed || at>now || !speaker.equals(item.get("god_id")) || !(speaker.equals(from) && target.equals(to) || speaker.equals(to) && target.equals(from)))continue;
    String type=e.path("recordType").asText(e.path("kind").asText()),text=e.path("text").asText();
    if(!delivered(sid,speaker,event,speaker,target,type,text,at) || !delivered(sid,target,event,speaker,target,type,text,at))continue;
    // captureDelivered validates explicit consent against the still-pending original terms.
    // Offers are deliberately excluded: reconciliation cannot create a second pact.
    if("UTTERANCE".equals(type)){var parsed=parse(text);if(parsed==null || "PROPOSE".equals(parsed.type()))continue;captureDelivered(sid,speaker,target,text,item.get("scene_id").toString(),at,event);}
    else if("ATTEMPT_BEGIN".equals(type))recordAggression(sid,speaker,target,at,event);
    else continue;
    var current=owned(sid,from).stream().filter(p->ref.equals(p.ref())).findFirst().orElseThrow().value();
    changed=changed || !pact.equals(current);pact=current;
   }
   if(changed){pact.put("historyReconciledAtSeconds",now);save(sid,ref,pact);}
  }
 }
 private void refreshAt(String sid,long at){
  var seen=new HashSet<String>();for(var row:jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND source_type='TREATY' ORDER BY knowledge_id",sid)){
   String ref=row.get("source_ref").toString();if(!seen.add(ref))continue;var pact=decode(row.get("content").toString());
   if("ACTIVE".equals(pact.path("status").asText()) && pact.has("dueSeconds") && at>=pact.path("dueSeconds").asLong()){
    long due=pact.path("dueSeconds").asLong();change(pact,"EXPIRED","","","expiry:"+due,due,"约定期限已经结束，盟约不自动续期；不预设之后的关系。 ");save(sid,ref,pact);
   }
  }
 }
 /** Both parties actually perceived the attempted contest; hidden intent is never a breach notice. */
 @Transactional
 public void recordAggression(String sid,String attacker,String target,long at,String event){
  if(!delivered(sid,attacker,event,attacker,target,"ATTEMPT_BEGIN",null,at) || !delivered(sid,target,event,attacker,target,"ATTEMPT_BEGIN",null,at))return;lock(sid);refreshAt(sid,at);
  for(var entry:owned(sid,attacker)){var pact=entry.value();if(!pair(pact,attacker,target) || !"ACTIVE".equals(pact.path("status").asText()) || at<pact.path("acceptedSeconds").asLong())continue;
   change(pact,"BROKEN",attacker,"实际发起交手",event,at,"一方在盟约生效期间实际向盟友发起交手，盟约破裂；另一方如何回应仍由其自行决定。");save(sid,entry.ref(),pact);
  }
 }
 public boolean invitationDelivered(String sid,String speaker,String recipient,String event){return owned(sid,recipient).stream().anyMatch(e->speaker.equals(e.value().path("proposer").asText()) && recipient.equals(e.value().path("recipient").asText()) && "PROPOSED".equals(e.value().path("status").asText()) && e.value().path("events").size()>0 && event.equals(e.value().path("events").get(0).path("eventRef").asText()));}
 public String privateContext(String sid,String owner){
  var entries=new ArrayList<>(owned(sid,owner));entries.sort(Comparator.comparingInt(e->Set.of("PROPOSED","ACTIVE").contains(e.value().path("status").asText())?0:1));
  var text=new StringBuilder();for(var entry:entries.stream().limit(8).toList())text.append("【自己参与的盟约·事实与自主选择】").append(render(entry.value().toString())).append('\n');
  if(!entries.isEmpty())text.append("对方的提议不是你的同意。确实接受可明确说‘我同意与你结盟’，拒绝可说‘我拒绝与你结盟’，解除可说‘我解除与你的盟约’；可沉默、考虑或另提条件，复杂条件不会自动作为同意。\n");return text.toString();
 }
 public static String render(String raw){
  JsonNode p;try{p=JSON.readTree(raw);}catch(Exception e){return "盟约记录暂无法核对。";}
  String status=switch(p.path("status").asText()){case "PROPOSED"->"待回应 · 尚未结盟";case "ACTIVE"->"盟约生效";case "DECLINED"->"已拒绝 · 未结盟";case "WITHDRAWN"->"已撤回 · 未结盟";case "ENDED"->"已解除";case "BROKEN"->"交手破盟";case "EXPIRED"->"期限已满";default->"状态待核对";};
  return p.path("proposerName").asText()+"向"+p.path("recipientName").asText()+"提出结盟\n进度："+status+"\n约定："+p.path("terms").asText()+"\n期限："+(p.has("dueSeconds")?"故事第 "+p.path("dueSeconds").asLong()+" 秒":p.path("durationSeconds").asLong()>0?"同意后持续 "+p.path("durationSeconds").asLong()+" 秒":"持续至明确解除或破盟")+"\n"+p.path("reason").asText();
 }
 private static Command parse(String speech){
  if(speech.length()>4000 || speech.matches("(?s).*(如果|只要|除非|倘若|假如|条件|前提|必须|否则|只是说说|不算数|不作数|开玩笑|不能当真).*"))return null;var commands=new ArrayList<Command>();
  for(String part:speech.split("[。！!；;\\n]")){String quote=part.trim(),text=quote.replaceFirst("^好[，,]\\s*","");
   var offer=Pattern.compile("(?:我)?(?:提议|希望|想|愿意|愿)(?:与|和)你结盟(?:[，,](?:期限(?:为)?|持续)([1-9][0-9]{0,4}|[一二两三四五六七八九十]{1,3})(秒|分钟|小时))?").matcher(text);
   if(offer.matches()){long duration=0;if(offer.group(1)!=null){duration=number(offer.group(1))*switch(offer.group(2)){case "分钟"->60;case "小时"->3600;default->1;};if(duration<1 || duration>86400)return null;}commands.add(new Command("PROPOSE",quote,duration));}
   else {
    var acceptance=Pattern.compile("(?:我(?:同意|接受|答应)(?:(?:按原约定)?(?:与|和)你结盟|你(?:提出)?的结盟提议|这份结盟提议|结盟)|你的结盟提议[，,]我(?:同意|接受))(?:[，,](?:期限(?:为)?|持续)"+DURATION+")?").matcher(text);
    if(acceptance.matches()){long duration=acceptance.group(1)==null?-1:duration(acceptance.group(1),acceptance.group(2));if(acceptance.group(1)!=null && duration<1)return null;commands.add(new Command("ACCEPT",quote,duration));}
    else if(text.matches("(?:我说清楚[—－-]{1,3})?(?:"+DURATION+"内[，,]?\\s*)?我(?:与|和)你结盟(?:[，,](?:各走各路|各自行事)[、，,]互不交手)?(?:[—－-]{1,3}我不(?:主动)?动你[，,]你也别来扰我[^。！？?；;]{0,40})?")){
     var declaredTime=Pattern.compile(DURATION+"内[，,]?").matcher(text);long declared=-1;if(declaredTime.find()){declared=duration(declaredTime.group(1),declaredTime.group(2));if(declared<1)return null;}commands.add(new Command("ACCEPT",quote,declared));
    }
    else if(text.matches("我(?:拒绝|不同意|不接受|不愿|不想)(?:(?:与|和)你结盟|你的结盟提议|结盟)|结盟(?:就)?不必了"))commands.add(new Command("DECLINE",quote,0));
    else if(text.matches("我撤回(?:向你的|刚才的|自己的|这次的)?结盟提议"))commands.add(new Command("WITHDRAW",quote,0));
    else if(text.matches("我(?:解除|终止)(?:(?:与|和)你的盟约|我们的盟约|盟约)|我退出(?:(?:与|和)你的联盟|我们的联盟)"))commands.add(new Command("END",quote,0));
   }
  }
  if(commands.size()!=1)return null;var command=commands.get(0);
  if("ACCEPT".equals(command.type())){
   if(retractsConsent(speech))return null;
   long agreedDuration=command.duration();var durations=Pattern.compile("(?:(?:期限|时限)(?:改为|改成|为|是)?|持续|仅限|只限|只结盟|只答应)"+RAW_DURATION).matcher(speech);
   while(durations.find()){long mentioned=duration(durations.group(1),durations.group(2));if(mentioned<1 || agreedDuration>=0 && agreedDuration!=mentioned)return null;agreedDuration=mentioned;}
   var windows=Pattern.compile(RAW_DURATION+"内").matcher(speech);
   while(windows.find()){long mentioned=duration(windows.group(1),windows.group(2));if(mentioned<1 || agreedDuration>=0 && agreedDuration!=mentioned)return null;agreedDuration=mentioned;}
   return new Command(command.type(),command.quote(),agreedDuration);
  }
  return command;
 }
 private static boolean retractsConsent(String speech){return speech.matches("(?s).*(不结盟|并未结盟|没有结盟|并非结盟|(?:不|没|没有|并未)(?:真正)?(?:接受|同意|答应)|不是(?:同意|接受|答应)|我(?:现在|还是)?反悔|(?:刚才|前面|之前)(?:的)?(?:话|同意|答应)(?:作废|收回|不算)).*");}
 private static long duration(String amount,String unit){
  if(!amount.matches("[1-9][0-9]{0,4}|[一二两三四五六七八九十]{1,3}"))return -1;
  long seconds=number(amount)*switch(unit){case "分钟"->60;case "小时"->3600;default->1;};return seconds>=1 && seconds<=86400?seconds:-1;
 }
 private Command contextualConsent(String sid,String speaker,String audience,String speech,long at,String replyTo){
  // A bare yes is useful only as a reply to one frozen, actually received clarification.
  var assent=Pattern.compile("^(?:是[，,]\\s*)?我(?:应你|答应你|同意|接受)[。！!；;](.*)$",Pattern.DOTALL).matcher(speech.trim());
  if(!assent.matches() || assent.group(1).matches("(?s).*(结盟|盟约).*"))return null;
  if(retractsConsent(speech))return null;
  if(speech.matches("(?s).*(如果|只要|除非|倘若|假如|条件|前提|必须|否则|只是说说|不算数|不作数|开玩笑|不能当真).*"))return null;
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='TURN_EVENT'",String.class,sid,speaker,replyTo);
  if(rows.isEmpty())return null;var question=decode(rows.get(0));String text=question.path("text").asText();long end=question.path("endSeconds").asLong(-1);
  if(end<0 || end>=at || !delivered(sid,audience,replyTo,audience,speaker,"UTTERANCE",text,end) || !delivered(sid,speaker,replyTo,audience,speaker,"UTTERANCE",text,end))return null;
  if(!text.matches("(?s)^(?:刚才[^。！？?]{1,80}。)?(?:我想确认[：:]\\s*)?你是否(?:愿意|同意)按原(?:提议|约定)[^。！？?]{0,100}结盟[^。！？?]{0,40}[？?](?:接受、拒绝或另有打算都请直接告诉我[。！!]?)?$"))return null;
  if(text.matches("(?s).*(如果|只要|除非|倘若|假如|条件|前提|必须|否则).*"))return null;
  if(text.matches("(?s).*(还是|或者|或是|[“”‘’\"]).*"))return null;
  var pending=owned(sid,speaker).stream().filter(e->"PROPOSED".equals(e.value().path("status").asText()) && audience.equals(e.value().path("proposer").asText()) && speaker.equals(e.value().path("recipient").asText())).findFirst();
  if(pending.isEmpty() || end<pending.get().value().path("proposedSeconds").asLong())return null;
  long agreed=-1;var times=Pattern.compile(RAW_DURATION).matcher(text+"\n"+speech);
  while(times.find()){long value=duration(times.group(1),times.group(2));if(value<1 || agreed>=0 && agreed!=value)return null;agreed=value;}
  return new Command("ACCEPT",speech.trim(),agreed);
 }
 private static long number(String text){if(text.matches("[0-9]+"))return Long.parseLong(text);if(text.equals("两"))return 2;String digits="一二三四五六七八九";if(text.length()==1 && digits.contains(text))return digits.indexOf(text)+1;if(!text.matches("[一二三四五六七八九]?十[一二三四五六七八九]?"))return 0;int i=text.indexOf('十');return (i==0?1:digits.indexOf(text.charAt(0))+1)*10L+(i==text.length()-1?0:digits.indexOf(text.charAt(i+1))+1);}
}
