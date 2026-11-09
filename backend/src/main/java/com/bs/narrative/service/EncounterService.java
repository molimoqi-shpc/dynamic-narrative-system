package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Bounded firsthand conversation memory. Witnessing a claim does not make it true. */
@Service
public class EncounterService {
 @org.springframework.beans.factory.annotation.Autowired private OpenSceneService physical;
    private static final String MARK="【现场交涉】";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public EncounterService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private List<String> encounters(String sid,String role){
        return jdbc.queryForList("SELECT memory_content FROM npc_memory WHERE session_id=? AND god_id=? AND memory_type='PERSONAL' AND memory_content LIKE ? ORDER BY memory_id DESC LIMIT 3",String.class,sid,role,MARK+"%");
    }
    public String context(String sid,String role){
        var recent=new ArrayList<JsonNode>();
        for(String text:encounters(sid,role))try{recent.add(JSON.readTree(text.substring(MARK.length())));}catch(Exception ignored){}
        var incoming=new StringBuilder("【最近对你说出的交涉，先核对是否已回应】\n");
        int selected=0;
        // Put the latest directed speech first; a long crowd exchange must not hide the recipient's turn.
        for(var n:recent)for(var speech:n.path("speech"))if(selected<3 && role.equals(speech.path("to").asText()) && !role.equals(speech.path("id").asText())){
            incoming.append("第").append(n.path("tick").asLong()).append("刻，").append(speech.path("name").asText()).append("对你说：").append(ModelOutput.cap(speech.path("text").asText(),180)).append("\n");selected++;
        }
        if(selected==0)incoming.append("没有记录到明确指向你的近期发言；不把对别人的问题揽到自己身上。\n");
        var ownText=new StringBuilder("【自己的此前选择，提案结果以服务器反馈为准】\n");
        var own=jdbc.queryForList("SELECT behavior,speech,inner_thought FROM npc_decision_log WHERE session_id=? AND god_id=? AND used_fallback=false ORDER BY decision_id DESC LIMIT 2",sid,role);
        for(var row:own)ownText.append("自己此前选择：").append(row.get("behavior")).append("；自己说过：").append(ModelOutput.cap(Objects.toString(row.get("speech"),""),140)).append("；当时自己的判断：").append(ModelOutput.cap(Objects.toString(row.get("inner_thought"),""),70)).append("。\n");
        var publicText=new StringBuilder("【近期交涉，发言与提案不等于已完成事实】\n");
        // Allocate space to the newest encounter before older ones; show retained encounters chronologically.
        var segments=new ArrayList<String>();int available=800;
        for(var n:recent){
            var part=new StringBuilder("第"+n.path("tick").asLong()+"刻，"+n.path("location").asText()+"：\n");
            for(var speech:n.path("speech"))part.append(speech.path("name").asText()).append("曾说（摘录，").append(role.equals(speech.path("to").asText())?"对你":speech.path("toName").asText("对象未记载")).append("）：").append(ModelOutput.cap(speech.path("text").asText(),100)).append("（仅确认说过，内容未自动核实）\n");
            for(var event:n.path("events"))part.append("已执行：").append(ModelOutput.cap(event.asText(),140)).append("\n");
            if(available<=0)break;String kept=ModelOutput.cap(part.toString(),available);segments.add(kept);available-=kept.length();
        }
        Collections.reverse(segments);segments.forEach(publicText::append);
        return ModelOutput.cap(incoming.toString(),700)+ModelOutput.cap(ownText.toString(),500)+publicText;
    }
    static String normalized(String text){return Objects.toString(text,"").replaceAll("[\\p{Z}\\s]","").replaceAll("[“”‘’「」『』]","").replaceAll("[。！？!?；;]+$","");}
    public boolean fresh(String sid,String scene,String speaker,String text){
        String value=normalized(text);if(value.isBlank())return false;
        var old=jdbc.queryForList("SELECT speech FROM npc_decision_log WHERE session_id=? AND scene_id=? AND god_id=? AND used_fallback=false AND speech<>'' ORDER BY decision_id DESC LIMIT 6",String.class,sid,scene,speaker);
        if(old.stream().anyMatch(s->normalized(s).equals(value)))return false;
        for(String entry:encounters(sid,speaker))try{
            var n=JSON.readTree(entry.substring(MARK.length()));if(!scene.equals(n.path("sceneId").asText()))continue;
            for(var s:n.path("speech"))if(speaker.equals(s.path("id").asText()) && value.equals(normalized(s.path("text").asText())))return false;
        }catch(Exception ignored){}
        return true;
    }
    public void record(String sid,GameScene scene,String playerRole,long tick,List<NpcAgentService.NpcDecision> decisions,List<String> witnesses,List<String> executed){
        var speech=new ArrayList<Map<String,String>>();
        for(var d:decisions)if(!d.usedFallback() && d.speech()!=null && !d.speech().isBlank() && witnesses.contains(d.godId())){
            var names=jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,d.godId());
            if(!names.isEmpty()){
                String target=(scene.getPresentGods().contains(d.targetGodId()) || playerRole.equals(d.targetGodId()))?d.targetGodId():"";
                var targetNames=target.isBlank()?List.<String>of():jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,target);
                speech.add(Map.of("id",d.godId(),"name",names.get(0),"to",target,"toName",targetNames.isEmpty()?"未明确对象":targetNames.get(0),"text",ModelOutput.cap(d.speech(),400)));
            }
        }
        if(speech.isEmpty() && executed.isEmpty())return;
        try{
            String content=MARK+JSON.writeValueAsString(Map.of("sceneId",scene.getSceneId(),"location",scene.getLocationName(),"tick",tick,"speech",speech,"events",executed.stream().limit(6).map(e->ModelOutput.cap(e,240)).toList()));
            // Only actual NPC witnesses who remained at the scene receive these public statements.
            for(String id:new LinkedHashSet<>(witnesses))if(!id.equals(playerRole) && scene.getPresentGods().contains(id)
                    && jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id=?",Integer.class,sid,id)>0 && (physical==null || physical.awake(sid,id,scene)))
                jdbc.update("INSERT INTO npc_memory(session_id,god_id,memory_type,memory_content,accuracy) VALUES(?,?,'PERSONAL',?,100)",sid,id,content);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    // Conservative punctuation check: a question to another NPC is not an answer or a new fact.
    // Mixed statements/questions and real questions directed to the player remain eligible.
    static boolean questionOnly(String speech){
        return Objects.toString(speech,"").strip().matches("(?:[^。！？!?]+[？?]\\s*)+");
    }
    public static boolean dialogueNode(NpcAgentService.NpcDecision d,boolean fresh,String playerRole){
        if(!fresh || d.usedFallback() || d.speech()==null || d.speech().isBlank())return false;
        if("LEGACY".equals(d.decisionPoint()))return !questionOnly(d.speech());
        if(d.decisionBasis().isBlank() || !d.speech().contains(d.decisionBasis()))return false;
        return "NEW_INFORMATION".equals(d.decisionPoint()) && !questionOnly(d.speech()) || "PLAYER_CHOICE".equals(d.decisionPoint()) && playerRole.equals(d.targetGodId());
    }
}
