package com.bs.narrative.service;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.llm.ModelOutput;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Server-owned game time, observation order and evidence kinds. No authored plot or role psychology. */
@Service
public class TemporalContextService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public TemporalContextService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public long tick(String sid){var rows=jdbc.queryForList("SELECT tick FROM world_clock WHERE session_id=?",Long.class,sid);return rows.isEmpty()?0:rows.get(0);}
    public static String sceneHeader(GameScene scene,long now,Object physicalStates){
        String original=Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0];
        return "【时序框架】世界当前第"+now+"刻。\n【入场时背景，历史资料，原文未记录精确世界时刻】"+original
                +"\n【当前仍有效的现场状态】\n"+(physicalStates instanceof Collection<?> states?states.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n")):physicalStates)
                +"\n入场背景用于理解身份与环境，不能覆盖当前身体、位置、物品及已执行结果。状态记录的更新时间早于现在不表示状态失效；只有后续实际事件才能改变它。历史台词、心理和行动提案不能当作本刻新发言或已经执行的动作。\n";
    }
    public List<Map<String,Object>> sceneState(String sid,String scene){
        var rows=jdbc.queryForList("SELECT content,learned_tick FROM role_knowledge WHERE session_id=? AND scene_id=? AND source_type='SCENE_STATE' ORDER BY learned_tick DESC,knowledge_id DESC",sid,scene);
        var states=new LinkedHashMap<String,Map<String,Object>>();var latest=new HashMap<String,Long>();
        for(var row:rows){
            Map<String,Object> value;
            try{value=JSON.readValue(row.get("content").toString(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new IllegalStateException("场景状态无法读取",e);}
            Object name=value.get("objectName"),state=value.get("state");
            if(!(name instanceof String object) || object.isBlank() || !(state instanceof String))throw new IllegalStateException("场景设施状态缺少对象或状态");
            long at=((Number)row.get("learned_tick")).longValue();
            if(!states.containsKey(object)){states.put(object,value);latest.put(object,at);}
            else if(latest.get(object)==at && !Objects.equals(states.get(object).get("state"),state))throw new IllegalStateException("场景设施存在同刻冲突状态："+object);
        }
        return new ArrayList<>(states.values());
    }
    /** Compact cross-location facts for the main author only, never a character's private input.
     * Updating a prose summary cannot erase an already authored discovery at another location. */
    public List<Map<String,Object>> establishedStoryFacts(String sid,String player){
        var rows=new ArrayList<>(jdbc.queryForList("SELECT knowledge_id,content,scene_id,location_name,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SCENE_STATE'",sid,player));
        rows.sort(Comparator.comparingLong((Map<String,Object> row)->((Number)row.get("learned_tick")).longValue()).thenComparingLong(row->((Number)row.get("knowledge_id")).longValue()));
        var facts=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:rows){
            JsonNode content;try{content=JSON.readTree(Objects.toString(row.get("content"),""));}catch(java.io.IOException ignored){continue;}
            if(content==null || !content.isObject())continue;
            String name=content.path("objectName").asText(),state=content.path("state").asText(),scene=Objects.toString(row.get("scene_id"),"");
            if(name.isBlank() || state.isBlank() || scene.isBlank())continue;
            facts.put(scene.length()+":"+scene+name,Map.of("sceneId",scene,"location",Objects.toString(row.get("location_name"),""),"subject",name,"fact",state,"recordedAtTick",row.get("learned_tick")));
        }
        return new ArrayList<>(facts.values());
    }
    /** One objective object across callers and old reference formats; history remains in TURN_EVENT. */
    @org.springframework.transaction.annotation.Transactional
    public void writeFacility(String sid,String owner,GameScene scene,String object,String value,String basis){
        if(!sid.equals(scene.getSessionId()) || object==null || object.isBlank() || value==null || value.isBlank())throw new IllegalArgumentException("invalid facility identity/state");
        if(jdbc.queryForList("SELECT scene_id FROM game_scene WHERE session_id=? AND scene_id=? FOR UPDATE",String.class,sid,scene.getSceneId()).isEmpty())throw new IllegalArgumentException("facility scene missing");
        long now=tick(sid);String content;
        try{content=JSON.writeValueAsString(Map.of("objectName",object,"state",value,"updatedAtTick",now,"basis",Objects.toString(basis,""),"sceneId",scene.getSceneId()));}catch(Exception e){throw new IllegalStateException(e);}
        // Match the complete JSON object name, never a legacy short hash or case-insensitive title.
        var rows=jdbc.queryForList("SELECT knowledge_id,content FROM role_knowledge WHERE session_id=? AND scene_id=? AND source_type='SCENE_STATE' AND BINARY JSON_UNQUOTE(JSON_EXTRACT(content,'$.objectName'))=BINARY ?",sid,scene.getSceneId(),object);
        if(!rows.isEmpty()){
            boolean unchanged=true;
            for(var row:rows)try{if(!value.equals(JSON.readTree(row.get("content").toString()).path("state").asText()))unchanged=false;}catch(Exception e){throw new IllegalStateException("场景状态无法读取",e);}
            if(unchanged)return;
            for(var row:rows)jdbc.update("UPDATE role_knowledge SET content=?,learned_tick=? WHERE knowledge_id=?",content,now,row.get("knowledge_id"));
            return;
        }
        String ref;
        try{ref="scene_state:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((scene.getSceneId().length()+":"+scene.getSceneId()+object).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'当前场景状态',?,'SCENE_STATE','',?,?,?)",sid,owner,ref,content,scene.getSceneId(),scene.getLocationName(),now);
    }
    public void validateSceneChanges(String sid,GameScene scene,JsonNode result,Collection<String> roleNames,Collection<String> portableNames){
        if(!result.has("sceneChanges"))return;if(!result.path("sceneChanges").isArray() || result.path("sceneChanges").size()>6)throw new IllegalArgumentException("invalid sceneChanges");
        String origin=Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0];var known=sceneState(sid,scene.getSceneId());var seen=new HashSet<String>();
        for(var change:result.path("sceneChanges")){
            String object=change.path("objectName").asText(),state=change.path("state").asText(),basis=change.path("basis").asText();
            int stateLimit=ActionExecutionContract.versioned(result) && state.equals(basis)?1000:240;
            if(object.isBlank() || object.length()>40 || state.isBlank() || state.length()>stateLimit || basis.isBlank() || !seen.add(object)
                    || !origin.contains(object) && known.stream().noneMatch(e->object.equals(e.get("objectName")))
                    || roleNames.contains(object) || portableNames.contains(object))throw new IllegalArgumentException("sceneChanges needs an existing scene object, not a role or a portable item");
            boolean grounded=false;for(var e:result.path("events"))if(e.path("text").asText().contains(basis) && (basis.contains(object) || ActionExecutionContract.versioned(result) && ActionExecutionContract.mentionsFacility(e,object)) && basis.contains(state))grounded=true;
            if(!grounded || state.matches("(?s).*(打算|准备|将要|希望|如果|可能会|也许会|死亡|复活|到账|获得道具|神格|属性增加).*"))throw new IllegalArgumentException("sceneChanges object="+object+", state="+state+", grounded="+grounded+" must quote an actual current physical result, not a plan or numerical effect");
        }
    }
    @org.springframework.transaction.annotation.Transactional
    public void applySceneChanges(String sid,String owner,GameScene scene,JsonNode result){
        for(var change:result.path("sceneChanges"))writeFacility(sid,owner,scene,change.path("objectName").asText(),change.path("state").asText(),change.path("basis").asText());
    }
    private void store(String sid,String role,GameScene scene,Map<String,Object> entry,String source){
        try{
            jdbc.update("INSERT IGNORE INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'回合时序记录',?,?,?,?,?,?)",
                    sid,role,entry.get("eventId"),JSON.writeValueAsString(entry),source,entry.getOrDefault("actorId",""),scene.getSceneId(),scene.getLocationName(),entry.get("generatedTick"));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    public boolean hasPrivateDecisions(String sid,String role){return jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ROLE_DECISION'",Integer.class,sid,role)>0;}
    public String roleContext(String sid,String role){return roleContext(sid,role,false);}
    public String roleContext(String sid,String role,boolean directPlayerWordsAlreadyIncluded){
        long now=tick(sid);var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('TURN_EVENT','ROLE_DECISION') AND learned_tick<=? ORDER BY learned_tick DESC,knowledge_id DESC LIMIT 12",String.class,sid,role,now);
        var candidates=new ArrayList<JsonNode>();
        var players=jdbc.queryForList("SELECT player_god_id FROM game_session WHERE session_id=?",String.class,sid);
        for(String raw:rows)try{var n=JSON.readTree(raw);if(directPlayerWordsAlreadyIncluded && n.path("generatedTick").asLong()==now && "UTTERANCE".equals(n.path("recordType").asText()) && players.contains(n.path("actorId").asText()))continue;candidates.add(n);}catch(Exception e){throw new IllegalStateException("回合记录无法读取",e);}
        // A directed question must survive crowd chatter and private-thought history truncation.
        candidates.sort(Comparator.comparingInt(n->"UTTERANCE".equals(n.path("recordType").asText()) && role.equals(n.path("targetId").asText()) && !role.equals(n.path("actorId").asText())?0:1));
        var entries=new ArrayList<JsonNode>();int budget=2500;
        for(var candidate:candidates){var n=candidate.deepCopy();if(n instanceof com.fasterxml.jackson.databind.node.ObjectNode object && n.path("text").asText().length()>420)object.put("text",ModelOutput.cap(n.path("text").asText(),420));String raw=n.toString();if(raw.length()<=budget){entries.add(n);budget-=raw.length();}}
        entries.sort(Comparator.comparingLong((JsonNode n)->n.path("generatedTick").asLong()).thenComparingInt(n->n.path("sequence").asInt()));
        var out=new StringBuilder("【自己的时序与听闻记录】世界当前第"+now+"刻。过去记录只证明当时实际发生或自己当时想过，不能重新表演；相同刻次还须按sequence和observedThroughSequence区分先后。UTTERANCE只证明说过，ROLE_DECISION是自己的心理/提案，WORLD_EVENT才是已执行结果。\n");
        for(var n:entries){out.append(n.path("generatedTick").asLong()<now?"历史":"本刻已发生").append("：");
            if("UTTERANCE".equals(n.path("recordType").asText()))out.append(n.path("actorName").asText("在场人物")).append(role.equals(n.path("targetId").asText())?"对你说：":"曾公开说：").append(n.path("text").asText()).append("；时序依据=");
            if("UTTERANCE".equals(n.path("recordType").asText()) && n instanceof com.fasterxml.jackson.databind.node.ObjectNode object){var metadata=object.deepCopy();metadata.remove("text");out.append(metadata);}else out.append(n);out.append('\n');}
        return out.toString();
    }
    public List<Map<String,Object>> executedHistory(String sid,String scene){
        var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND scene_id=? AND source_type='TURN_EVENT' ORDER BY learned_tick DESC,knowledge_id DESC LIMIT 80",String.class,sid,scene);
        var unique=new LinkedHashMap<String,Map<String,Object>>();
        for(String raw:rows)try{var n=JSON.readTree(raw);if("WORLD_EVENT".equals(n.path("recordType").asText()) && Set.of("SCENE_ACTION","SCENE_OBSERVATION","RESCUE","ATTACK","LEAVE","ARRIVE","GIFT","OFFER","FOLLOW_UP").contains(n.path("kind").asText())){
            var entry=JSON.convertValue(n,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});unique.putIfAbsent(n.path("eventId").asText(),entry);if(unique.size()>=8)break;
        }}catch(Exception e){throw new IllegalStateException(e);}
        var result=new ArrayList<>(unique.values());result.sort(Comparator.comparingLong((Map<String,Object> e)->((Number)e.get("generatedTick")).longValue()).thenComparingInt(e->((Number)e.get("sequence")).intValue()));return result;
    }
    public Frame begin(String sid,GameScene scene,long tick,int round,int phase,String player,List<String> witnesses,Set<String> spent){return new Frame(sid,scene,tick,round,phase,player,witnesses,spent);}
    public final class Frame {
        private final String sid,player;private final GameScene scene;private final long tick;private final int round,phase;
        private final List<String> witnesses;private final Set<String> spent;private int sequence=0;
        private final List<Map<String,Object>> events=new ArrayList<>();
        private final Map<String,Integer> observed=new HashMap<>();
        private Frame(String sid,GameScene scene,long tick,int round,int phase,String player,List<String> witnesses,Set<String> spent){this.round=round;this.sid=sid;this.scene=scene;this.tick=tick;this.phase=phase;this.player=player;this.witnesses=List.copyOf(witnesses);this.spent=Set.copyOf(spent);}
        public long tick(){return tick;}public int sequence(){return sequence;}public boolean alreadyActed(String role){return spent.contains(role);}
        public String beforeGeneration(String role){
            observed.put(role,sequence);
            return "\n【本次生成边界】generatedTick="+tick+"；observedThroughSequence="+sequence+"；本次只能依据已经送达自己的记录判断。未生成的其他角色发言和后续动作结果均不可知。"+(spent.contains(role)?"本刻你已执行过持续计划中的一步，本次可交谈与考虑后续安排，不再执行第二次身体行动。":"")+"\n";
        }
        private Map<String,Object> entry(String type,String actor,String target,String text){
            int order=++sequence;var e=new LinkedHashMap<String,Object>();e.put("eventId","turn:"+scene.getSceneId()+":"+tick+":"+order);e.put("generatedTick",tick);if(!"ROLE_DECISION".equals(type))e.put("occurredTick",tick);e.put("sequence",order);e.put("phase",phase);e.put("round",round);e.put("recordType",type);e.put("actorId",Objects.toString(actor,""));e.put("targetId",Objects.toString(target,""));e.put("text",text);for(String id:List.of(Objects.toString(actor,""),Objects.toString(target,"")))if(!id.isBlank()){
                var names=jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,id);if(!names.isEmpty())e.put(id.equals(actor)?"actorName":"targetName",names.get(0));
            }return e;
        }
        private void publish(Map<String,Object> e,Collection<String> recipients){
            events.add(e);
            for(String role:new LinkedHashSet<>(recipients))if(witnesses.contains(role))store(sid,role,scene,e,"TURN_EVENT");
        }
        public void recordWorld(List<Map<String,Object>> timeline,boolean hiddenPlayer){
            for(var event:timeline)if(((Number)event.get("phase")).intValue()==phase && !event.containsKey("sequence")){
                boolean notice=Set.of("DIALOGUE","DECISION","GIFT_PENDING","OFFER_FAILED","WAITING","PAUSED","SITUATION","OPPORTUNITY","INTERRUPTED","DISCOVERY").contains(event.get("kind"));
                var e=entry(notice?"NOTICE":"WORLD_EVENT",Objects.toString(event.get("actorId"),""),"",Objects.toString(event.get("text"),""));e.put("kind",event.get("kind"));stamp(event,e);
                var recipients=notice || hiddenPlayer && e.get("text").toString().startsWith("你")?List.of(player):witnesses;
                publish(e,recipients);
            }
        }
        public void playerSpeech(String target,String text,List<Map<String,Object>> timeline){
            var e=entry("UTTERANCE",player,target,text);e.put("kind","PLAYER_SPEECH");e.put("speechScope","DIRECTED");
            var line=new LinkedHashMap<String,Object>();line.put("phase",phase);e.put("round",round);line.put("round",round);line.put("kind","PLAYER_SPEECH");line.put("text","你说：“"+text+"”");line.put("npcStage","BEFORE_NPC");stamp(line,e);timeline.add(line);publish(e,List.of(player,target));
        }
        public void decision(NpcAgentService.NpcDecision d,int index,List<Map<String,Object>> timeline){
            if(d.usedFallback())return;
            var privateEntry=entry("ROLE_DECISION",d.godId(),d.targetGodId(),ModelOutput.cap(d.innerThought(),180));
            privateEntry.put("behavior",d.behavior());privateEntry.put("attempt",ModelOutput.cap(d.actionText(),220));privateEntry.put("observedThroughSequence",observed.getOrDefault(d.godId(),0));privateEntry.put("executionStatus","PROPOSED");
            store(sid,d.godId(),scene,privateEntry,"ROLE_DECISION");
            if(!d.usedFallback() && d.speech()!=null && !d.speech().isBlank() && witnesses.contains(d.godId())){
                var e=entry("UTTERANCE",d.godId(),d.targetGodId(),d.speech());e.put("kind","SPEECH");e.put("decisionIndex",index);e.put("observedThroughSequence",observed.getOrDefault(d.godId(),0));e.put("speechScope","PUBLIC");
                var line=new LinkedHashMap<String,Object>();line.put("phase",phase);e.put("round",round);line.put("round",round);line.put("kind","SPEECH");var names=jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,d.godId());
                line.put("text",(names.isEmpty()?"在场人物":names.get(0))+"说：“"+d.speech()+"”");line.put("actorId",d.godId());line.put("targetId",d.targetGodId());line.put("npcStage","NPC_SPEECH");line.put("decisionIndex",index);stamp(line,e);timeline.add(line);publish(e,witnesses);
            }
        }
        private void stamp(Map<String,Object> target,Map<String,Object> e){for(String key:List.of("eventId","generatedTick","sequence","recordType"))target.put(key,e.get(key));}
        public List<Map<String,Object>> events(){return List.copyOf(events);}
    }
}
