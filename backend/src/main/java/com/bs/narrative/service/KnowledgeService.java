package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.GodProfileRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** A role-owned notebook: observations, investigated reports and explicitly shared messages. */
@Service
public class KnowledgeService {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Set<String> NOTEBOOK_SOURCES=Set.of("PERSONAL","HEARD","TOLD","INQUIRED","INVESTIGATED","SOCIAL","PROMISE","TREATY");
    private final JdbcTemplate jdbc;
    private final GodProfileRepository gods;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private OpenSceneService openScenes;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private TreatyService treaties;
    public KnowledgeService(JdbcTemplate jdbc,GodProfileRepository gods){this.jdbc=jdbc;this.gods=gods;}
    private String shortText(String s,int max){return s.length()<=max?s:s.substring(0,max);}
    private boolean remember(String sid,String god,String ref,String title,String content,String source,String sender,GameScene scene,long tick){
        return jdbc.update("INSERT IGNORE INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,?,?,?,?,?,?,?)",
                sid,god,ref,shortText(title,120),shortText(content,4000),source,sender,scene.getSceneId(),scene.getLocationName(),tick)>0;
    }
    public List<Map<String,Object>> list(String sid,String god){
        var rows=new ArrayList<>(jdbc.queryForList("SELECT knowledge_id,title,content,source_type,source_ref,source_god_id,location_name,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('PERSONAL','HEARD','TOLD','INQUIRED','INVESTIGATED','PROMISE','TREATY') ORDER BY knowledge_id DESC LIMIT 40",sid,god));
        rows.addAll(jdbc.queryForList("SELECT knowledge_id,title,content,source_type,source_ref,source_god_id,location_name,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SOCIAL' ORDER BY knowledge_id DESC LIMIT 20",sid,god));
        rows.sort((a,b)->Long.compare(((Number)b.get("knowledge_id")).longValue(),((Number)a.get("knowledge_id")).longValue()));
        var proofsByRef=proofs(sid,god,rows);
        return rows.stream().map(r->{Map<String,Object> item=new LinkedHashMap<>();
            item.put("knowledgeId",r.get("knowledge_id"));item.put("title",r.get("title"));item.put("content",r.get("content"));
            String source=(String)r.get("source_type");if("PROMISE".equals(source))item.put("content",PromiseService.render((String)r.get("content")));if("TREATY".equals(source))item.put("content",TreatyService.render((String)r.get("content")));item.put("sourceType",source);
            item.put("sourceLabel",switch(source){case "TREATY"->"盟约记录 · 双方表态";case "PROMISE"->"承诺记录 · 进度实录";case "SOCIAL"->"交往记录 · 实际发生";case "PERSONAL"->"亲见";case "HEARD"->"亲耳听见";case "INQUIRED"->"打听所得";case "INVESTIGATED"->"调查所得";default->"他人转述";});
            if("TREATY".equals(source))try{var pact=JSON.readTree((String)r.get("content"));boolean proposer=god.equals(pact.path("proposer").asText());String counterpart=pact.path(proposer?"recipient":"proposer").asText();String name=gods.findById(counterpart).filter(g->sid.equals(g.getSessionId())).map(GodProfile::getGodName).orElse("");item.put("treaty",Map.of("status",pact.path("status").asText(),"isProposer",proposer,"counterpartId",counterpart,"counterpartName",name));}catch(Exception ignored){}
            item.put("locationName",r.get("location_name"));item.put("learnedTick",r.get("learned_tick"));
            String sender=(String)r.get("source_god_id");item.put("sourceName",gods.findById(sender).filter(g->sid.equals(g.getSessionId())).map(GodProfile::getGodName).orElse(""));
            item.put("canVerify","TOLD".equals(source) && ((String)r.get("source_ref")).startsWith("event:"));
            var proofRow=proofsByRef.get("check:"+r.get("knowledge_id"));
            if(proofRow!=null)try {
                var proof=JSON.readTree((String)proofRow.get("content"));
                if(digest((String)r.get("content")).equals(proof.path("claimHash").asText())) {
                    item.put("verificationStatus",proof.path("status").asText());item.put("verificationNote",proof.path("note").asText());
                    item.put("verifiedTick",proofRow.get("learned_tick"));
                }
            }catch(Exception ignored){}
            return item;}).toList();
    }
    public String privateContext(String sid,String god){return privateContext(sid,god,false);}
    public String privateContext(String sid,String god,boolean canonicalSpeechIncluded){
        // A boolean cannot prove which quotes survived the caller's history budget.
        return privateContext(sid,god,Set.of());
    }
    public record IncludedSpeech(String speakerId,String text) {}
    public String privateContext(String sid,String god,Set<IncludedSpeech> includedSpeech){
        return privateContext(sid,god,includedSpeech,false);
    }
    public String privateContext(String sid,String god,Set<IncludedSpeech> includedSpeech,boolean storyFirst){
        // Keep evidence and social history in separate budgets so gifts cannot crowd out key information.
        var rows=jdbc.queryForList("SELECT knowledge_id,title,content,source_type,source_god_id,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type NOT IN ('CHECK','SOCIAL','PROMISE','TREATY','SCENE_VIEW','SELF_PROFILE','TALK_STATE','SEEN_ROLE','ITEM_STATE','SCENE_ATTEMPT','TURN_EVENT','ROLE_DECISION','SCENE_STATE','SCENE_OBJECTS','ROLE_PROCESS','ROLE_TASK','ROLE_PROFILE','ROLE_SCHEDULE','OBSERVED_STATE','SEEN_FACILITY','VISITED_SCENE','BODY_STATE','VITAL_STATE','SCENE_SEEN','ROLE_INBOX','ROLE_CONSUMED','ROLE_CONSUMPTION','ITEM_PLACEMENT','ROLE_PLAN','ROLE_PAUSED','ROLE_PENDING','ROLE_INTEGRATED','ROLE_SUPERSEDED','ROLE_STOPPED','SPEECH_STOPPED','RESOURCE_TAKEN','ROLE_STORY_STATE','ITEM_PROFILE','PLAYER_RELATION') ORDER BY knowledge_id DESC LIMIT 10",sid,god);
        var proofsByRef=proofs(sid,god,rows);StringBuilder context=new StringBuilder();
        for(var own:rows) {
            if("HEARD".equals(own.get("source_type")) && includedSpeech.contains(new IncludedSpeech(Objects.toString(own.get("source_god_id"),""),Objects.toString(own.get("content"),""))))continue;
            context.append("【记录序号").append(own.get("learned_tick")).append("（非故事时刻）；来源：").append(own.get("source_type")).append("】").append(own.get("title")).append("：").append(shortText((String)own.get("content"),500)).append("\n");
            String provider=(String)own.get("source_god_id");if(provider!=null && !provider.isBlank())context.append("提供者：").append(gods.findById(provider).filter(g->sid.equals(g.getSessionId())).map(GodProfile::getGodName).orElse("未知")).append("\n");
            var proof=proofsByRef.get("check:"+own.get("knowledge_id"));
            if(proof!=null)try {var n=JSON.readTree((String)proof.get("content"));if(digest((String)own.get("content")).equals(n.path("claimHash").asText()))context.append("核验：").append(n.path("note").asText()).append("\n");}catch(Exception ignored){}
        }
        var social=jdbc.queryForList("SELECT title,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SOCIAL' ORDER BY knowledge_id DESC LIMIT 6",sid,god);
        for(var record:social)context.append("【自己的实际交往·").append(record.get("title")).append("】").append(record.get("content")).append("\n");
        var pledges=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE' ORDER BY knowledge_id DESC LIMIT 8",String.class,sid,god);
        for(var pledge:pledges)context.append("【自己参与的承诺·只记录事实，不决定心理】").append(PromiseService.render(pledge)).append("\n");
        if(treaties!=null && !storyFirst)context.append(treaties.privateContext(sid,god));
        if(storyFirst)for(String old:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TREATY' ORDER BY knowledge_id DESC LIMIT 8",String.class,sid,god))context.append("【此前盟约记录，以后续亲历变化为准】").append(TreatyService.render(old)).append('\n');
        var reflections=jdbc.queryForList("SELECT inner_thought FROM npc_decision_log WHERE session_id=? AND god_id=? AND used_fallback=false AND inner_thought<>'' ORDER BY decision_id DESC LIMIT 2",String.class,sid,god);
        for(var thought:reflections)context.append("【自己过去的心理·不是客观事实或不可更改的决定】").append(shortText(thought,220)).append("\n");
        return context.toString();
    }
    @Transactional
    public void hear(String sid,String speaker,String listener,String text,GameScene scene,long tick,int round){
        if(text==null || text.isBlank())return;
        boolean speakerPresent=scene.getPresentGods().contains(speaker) || jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE god_id=? AND active_session_id=? AND current_scene_id=?",Integer.class,speaker,sid,scene.getSceneId())>0;
        boolean listenerPresent=scene.getPresentGods().contains(listener) || jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE god_id=? AND active_session_id=? AND current_scene_id=?",Integer.class,listener,sid,scene.getSceneId())>0;
        var a=gods.findById(speaker).orElse(null);var b=gods.findById(listener).orElse(null);
        if(!speakerPresent || !listenerPresent || a==null || b==null
                || a.getSessionId()!=null && !sid.equals(a.getSessionId()) || b.getSessionId()!=null && !sid.equals(b.getSessionId()))return;
        if(openScenes!=null && (!Boolean.TRUE.equals(openScenes.view(sid,a,scene).get("awake")) || !Boolean.TRUE.equals(openScenes.view(sid,b,scene).get("awake"))))return;
        remember(sid,listener,"heard:"+round+":"+tick+":"+speaker+":"+digest(text).substring(0,16),a.getGodName()+"的话："+shortText(text,30),text,"HEARD",speaker,scene,tick);
    }
    /** Caller has already proved this historical interval; endpoint movement cannot revoke it. */
    void hearDelivered(String sid,String speaker,String listener,String text,GameScene scene,long tick,String eventId){
        var a=gods.findById(speaker).filter(g->sid.equals(g.getSessionId())).orElseThrow();
        gods.findById(listener).filter(g->sid.equals(g.getSessionId())).orElseThrow();
        remember(sid,listener,"heard:"+digest(eventId+":"+listener).substring(0,32),a.getGodName()+"的话："+shortText(text,30),text,"HEARD",speaker,scene,tick);
    }
    @Transactional
    public void rememberInquiry(String sid,String god,WorldBook event){
        var actor=gods.findById(god).orElse(null);
        if(actor==null || actor.getSessionId()!=null && !sid.equals(actor.getSessionId()) || !sid.equals(event.getSessionId()) || event.getEntryId()==null)return;
        var locations=jdbc.queryForList("SELECT scene_id FROM game_scene WHERE session_id=? AND location_name=? AND coord_x=? AND coord_y=? LIMIT 1",String.class,sid,event.getLocationName(),event.getCoordX(),event.getCoordY());
        if(locations.isEmpty())return;
        var scene=new GameScene();scene.setSceneId(locations.get(0));scene.setLocationName(event.getLocationName());
        var ticks=jdbc.queryForList("SELECT tick FROM world_clock WHERE session_id=?",Long.class,sid);
        String content=event.getEventDescription();if(content==null || content.isBlank())return;
        remember(sid,god,"event:"+event.getEntryId(),event.getLocationName()+"："+shortText(content,30),content,"INQUIRED","",scene,ticks.isEmpty()?0:ticks.get(0));
    }
    public String indexForPlayer(String sid,String god){
        return list(sid,god).stream().limit(60).map(k->k.get("knowledgeId")+"="+k.get("title")).toList().toString();
    }
    @Transactional
    public List<String> investigate(String sid,String god,GameScene scene,long tick){
        var learned=new ArrayList<String>();
        String base=scene.getSceneSummary()==null?"":scene.getSceneSummary().split("【当前局势】",2)[0].strip();
        if(!base.isBlank() && remember(sid,god,"scene:"+scene.getSceneId(),scene.getLocationName()+"：初见环境",base,"PERSONAL","",scene,tick))
            learned.add(scene.getLocationName()+"：初见环境");
        // Secret records never become discoveries simply because a user asks to investigate.
        var rows=jdbc.queryForList("SELECT entry_id,event_description,actor_ids FROM world_book WHERE session_id=? AND coord_x=? AND coord_y=? AND location_name=? AND is_secret=false AND event_type IN ('NPC_PLAN','MOVE','COMBAT','STORY_MILESTONE','DIALOGUE') ORDER BY entry_id DESC LIMIT 20",sid,scene.getCoordX(),scene.getCoordY(),scene.getLocationName());
        for(var r:rows){
            if(learned.size()>=3)break;
            String content=(String)r.get("event_description");
            if(content==null || content.isBlank() || content.contains("强约束") || content.contains("six-stats") || content.matches("(?s).*(player_[a-f0-9]{20,}|\\[OBSERVE|规则引擎最终裁决).*"))continue;
            String title=scene.getLocationName()+"："+shortText(content,30);
            // Investigation establishes a report, not a proof of every inference the model might add.
            if(remember(sid,god,"event:"+r.get("entry_id"),title,content,"INVESTIGATED","",scene,tick))learned.add(title);
        }
        learned.addAll(verifyLocalReports(sid,god,scene,tick));
        return learned;
    }
    @Transactional
    public String share(String sid,String sender,String target,long id,String raw,GameScene scene,Map<String,GodProfile> present,long tick){
        var actor=present.get(sender);var recipient=present.get(target);
        if(actor==null || recipient==null || !sid.equals(actor.getSessionId()) || !sid.equals(recipient.getSessionId())
                || openScenes!=null && (!openScenes.awake(sid,sender,scene) || !openScenes.awake(sid,target,scene)) || sender.equals(target) || !scene.getPresentGods().contains(target))return "消息没有交付，对方已不在可交谈范围。";
        var rows=jdbc.queryForList("SELECT * FROM role_knowledge WHERE knowledge_id=? AND session_id=? AND god_id=?",id,sid,sender);
        if(rows.isEmpty())return "没有找到你已掌握的这条消息，未替你编造或传递内容。";
        var known=rows.get(0);if(!NOTEBOOK_SOURCES.contains(known.get("source_type")))return "内部角色状态不能作为消息交付，请用自己的话交谈。";if(Set.of("PROMISE","TREATY").contains(known.get("source_type")))return "承诺进度只记录当事人实际履约情况，请用普通问话或转述表达自己的看法。";String title=(String)known.get("title");
        if(raw==null || !raw.contains(title) || raw.matches("(?s).*(不要|不想|不能|不愿|别|勿)[^，。；]{0,12}(告诉|告知|分享|出示|转告).*"))
            return "尚未明确要交付哪条消息，请从见闻录选择后再行动。";
        if(raw.matches("(?s).*(不(告诉|告知|分享|出示|转告)|不(把|将)[^，。；]{0,24}(告诉|分享|出示|转告)).*"))return "你未授权交付这条消息，内容没有传递。";
        if(openScenes!=null && !Boolean.TRUE.equals(openScenes.view(sid,recipient,scene).get("awake")))return "消息没有交付，对方尚未恢复意识。";
        boolean added=remember(sid,target,(String)known.get("source_ref"),title,(String)known.get("content"),"TOLD",sender,scene,tick);
        return recipient.getGodName()+(added?"收到了你提供的一条消息，仍可自行核验；内容未向其他人物公开。":"已经掌握这条消息，没有重复增加信息。");
    }
    private Map<String,Map<String,Object>> proofs(String sid,String owner,List<Map<String,Object>> records) {
        var result=new HashMap<String,Map<String,Object>>();if(records.isEmpty())return result;
        var args=new ArrayList<Object>();args.add(sid);args.add(owner);
        for(var r:records)args.add("check:"+r.get("knowledge_id"));
        for(var p:jdbc.queryForList("SELECT source_ref,content,learned_tick FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='CHECK' AND source_ref IN ("+String.join(",",Collections.nCopies(records.size(),"?"))+")",args.toArray()))result.put((String)p.get("source_ref"),p);
        return result;
    }
    private static String digest(String value) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private void social(String sid,String owner,String peer,String ref,String text,GameScene scene,long tick) {
        var a=gods.findById(owner).orElse(null);var b=gods.findById(peer).orElse(null);
        if(a==null || b==null || !sid.equals(a.getSessionId()) || !sid.equals(b.getSessionId()) || owner.equals(peer))return;
        remember(sid,owner,ref,"与"+b.getGodName()+"的交往",text,"SOCIAL",peer,scene,tick);
    }
    @Transactional
    public void recordOffer(String sid,OfferService.Offer offer,String status,GameScene scene,int round,long tick) {
        if(status==null || !Set.of("COMPLETED","DECLINED").contains(status))return;
        var a=gods.findById(offer.sender()).orElse(null);var b=gods.findById(offer.recipient()).orElse(null);
        if(a==null || b==null || !sid.equals(a.getSessionId()) || !sid.equals(b.getSessionId()))return;
        if("COMPLETED".equals(status) && (b.getInventory()==null || !b.getInventory().contains(offer.offered())
                || a.getInventory()==null || a.getInventory().contains(offer.offered())
                || !offer.requested().isBlank() && (!a.getInventory().contains(offer.requested()) || b.getInventory().contains(offer.requested()))))return;
        String action="DECLINED".equals(status)?"明确拒绝了物品提议，双方没有转移物品；拒绝不等于欺骗或背叛。":
                offer.kind().equals("GIVE")?"接受赠予，实际取得「"+offer.offered()+"」；接受物品不等于效忠或承诺。":
                "完成交换：「"+offer.offered()+"」与「"+offer.requested()+"」已实际转移；这证明本次交换履行，不保证以后合作。";
        String text=a.getGodName()+"向"+b.getGodName()+"提出物品往来；"+b.getGodName()+action;
        social(sid,offer.sender(),offer.recipient(),"social:offer:"+round+":"+offer.recipient(),text,scene,tick);
        social(sid,offer.recipient(),offer.sender(),"social:offer:"+round+":"+offer.sender(),text,scene,tick);
    }
    private List<String> verifyLocalReports(String sid,String owner,GameScene scene,long tick) {
        var reports=jdbc.queryForList("SELECT knowledge_id,content,source_ref,source_god_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TOLD' AND source_ref LIKE 'event:%' ORDER BY knowledge_id DESC LIMIT 30",sid,owner);
        var result=new ArrayList<String>();
        for(var report:reports) {
            if(result.size()>=3)break;
            long eventId;try {eventId=Long.parseLong(((String)report.get("source_ref")).substring(6));}catch(Exception e){continue;}
            var events=jdbc.queryForList("SELECT event_description FROM world_book WHERE entry_id=? AND session_id=? AND coord_x=? AND coord_y=? AND location_name=? AND is_secret=false AND event_type IN ('NPC_PLAN','MOVE','COMBAT','STORY_MILESTONE','DIALOGUE')",String.class,eventId,sid,scene.getCoordX(),scene.getCoordY(),scene.getLocationName());
            if(events.isEmpty())continue;
            String claim=(String)report.get("content");String original=events.get(0);
            if(original==null || original.isBlank() || original.contains("强约束") || original.contains("six-stats"))continue;
            String status=claim.equals(original)?"SOURCE_MATCH":"SOURCE_MISMATCH";
            String note=status.equals("SOURCE_MATCH")?"转述与当地公开原始记录一致；是同一来源的核对，不是独立证据或绝对真相。":"转述与当地公开原始记录不一致，原因待核实；不能仅据此判定提供者撒谎。";
            String ref="check:"+report.get("knowledge_id");var old=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,owner,ref);
            var data=JSON.createObjectNode().put("status",status).put("claimHash",digest(claim)).put("sourceHash",digest(original)).put("note","当地公开记录："+shortText(original,600)+"\n"+note);
            if(!old.isEmpty() && old.get(0).equals(data.toString()))continue;
            jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,?,?,'CHECK','',?,?,?) ON DUPLICATE KEY UPDATE content=VALUES(content),learned_tick=VALUES(learned_tick)",
                    sid,owner,ref,"原始来源核对",data.toString(),scene.getSceneId(),scene.getLocationName(),tick);
            String provider=(String)report.get("source_god_id");
            social(sid,owner,provider,"social:check:"+report.get("knowledge_id")+":"+status,"你核对了对方提供的一条消息："+note,scene,tick);
            result.add("核验："+note);
        }
        return result;
    }

}


