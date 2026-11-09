package com.bs.narrative.service;

import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Facts of explicitly spoken promises. Interpretation and psychological responses belong to the actor model. */
@Service
public class PromiseService {
 @org.springframework.beans.factory.annotation.Autowired private ItemStateService items;
 @org.springframework.beans.factory.annotation.Autowired private OpenSceneService physical;
 @org.springframework.beans.factory.annotation.Autowired private com.bs.narrative.repository.GameSceneRepository scenes;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;private final GodProfileRepository gods;
    @PersistenceContext private EntityManager em;
    public PromiseService(JdbcTemplate jdbc,GodProfileRepository gods){this.jdbc=jdbc;this.gods=gods;}
    static ObjectNode read(String text){try{var n=JSON.readTree(text);return n.isObject()?(ObjectNode)n:null;}catch(Exception e){return null;}}
    private long tick(String sid){return GoalChecks.tick(jdbc,sid);}
    private boolean at(String sid,String id,String scene){
        var rows=jdbc.queryForList("SELECT present_gods FROM game_scene WHERE session_id=? AND scene_id=?",String.class,sid,scene);
        if(rows.size()!=1)return false;
        try{for(var n:JSON.readTree(rows.get(0)))if(id.equals(n.asText()))return true;}catch(Exception ignored){}
        return jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE active_session_id=? AND god_id=? AND current_scene_id=?",Integer.class,sid,id,scene)>0;
    }
    private boolean meeting(String sid,ObjectNode p){
        var matches=jdbc.queryForList("SELECT scene_id FROM game_scene WHERE session_id=? AND location_name=?",String.class,sid,p.path("value").asText());
        if(matches.size()!=1)return false;
        String sceneId=matches.get(0),speaker=p.path("speaker").asText(),recipient=p.path("recipient").asText();
        var location=scenes.findBySessionIdAndSceneId(sid,sceneId).orElse(null);
        return location!=null && at(sid,speaker,sceneId) && at(sid,recipient,sceneId)
                && physical.awake(sid,speaker,location) && physical.awake(sid,recipient,location);
    }
    private String partyName(String id){return gods.findById(id).map(g->"你".equals(g.getGodName())?"玩家":g.getGodName()).orElse("未知人物");}
    private long seconds(String sid){var rows=jdbc.queryForList("SELECT story_seconds FROM game_runtime WHERE session_id=?",Long.class,sid);return rows.isEmpty()?0:rows.get(0);}
    private static boolean timed(JsonNode p){return "STORY_SECONDS".equals(p.path("timeBasis").asText());}
    private static long due(JsonNode p){return p.path(timed(p)?"dueSeconds":"dueTick").asLong();}
    private long now(String sid,JsonNode p,long atSeconds){return timed(p)?atSeconds:tick(sid);}
    /** Only called after the operation scheduler proves full delivery to the named addressee. No new model request. */
    @Transactional
    public void captureDelivered(String sid,String speaker,String audience,String speech,String scene,long spokenEnd) {
        if(speech==null || audience==null || audience.isBlank() || spokenEnd<0)return;
        // Conservative explicit declarations only. Quoted/retrospective speech and questions are not new promises.
        var sentences=java.util.regex.Pattern.compile("(?:^|(?<=[。；;！!\\n]))\\s*(我(?:承诺|保证|答应)([^。；;！？!?\\n]{1,280})([。；;！!？?\\n]|$))").matcher(speech);
        int count=0;while(sentences.find() && count++<3){
            String quote=sentences.group(1),body=sentences.group(2).trim();
            if(quote.endsWith("？") || quote.endsWith("?") || body.matches("(?s)^(过|了|不了|吗|是否|能否).*"))continue;
            var spec=JSON.createObjectNode().put("toGodId",audience).put("quote",quote).put("kind","UNVERIFIED").put("value","");
            var duration=java.util.regex.Pattern.compile("^[：:，,\\s]*(?:在)?([1-9][0-9]{0,3}|[一二两三四五六七八九十]{1,3})(秒|分钟)(?:之内|内)(.*?)[。；;！!]*$").matcher(body);
            if(duration.matches()){
                int amount=durationAmount(duration.group(1));long delay=(long)amount*("分钟".equals(duration.group(2))?60:1);String task=duration.group(3);
                if(delay>0 && delay<=86400){
                    var gift=java.util.regex.Pattern.compile("^把([^，,。；;]{1,64})(?:送给|赠予|赠送)你$").matcher(task);
                    var meet=java.util.regex.Pattern.compile("^在([^，,。；;]{1,64}?)(?:与|和)?你(?:会面|见面|相见)$").matcher(task);
                    if(gift.matches())spec.put("kind","GIVE_ITEM").put("value",gift.group(1));
                    else if(task.matches("不(?:会)?攻击你"))spec.put("kind","NON_AGGRESSION");
                    else if(meet.matches() && jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND location_name=?",Integer.class,sid,meet.group(1))==1)spec.put("kind","MEET").put("value",meet.group(1));
                    spec.put("afterSeconds",delay);
                }
            }
            captureFact(sid,speaker,audience,speech,spec,scene,spokenEnd);
        }
    }
    private static int durationAmount(String text){
        if(text.matches("[0-9]+"))return Integer.parseInt(text);
        if(text.equals("两"))return 2;
        if(text.matches("[一二三四五六七八九]"))return "一二三四五六七八九".indexOf(text)+1;
        if(!text.matches("[一二三四五六七八九]?十[一二三四五六七八九]?"))return 0;
        int at=text.indexOf('十'),tens=at==0?1:"一二三四五六七八九".indexOf(text.charAt(0))+1;
        return tens*10+(at==text.length()-1?0:"一二三四五六七八九".indexOf(text.charAt(at+1))+1);
    }
    @Transactional
    public boolean capture(String sid,String speaker,String audience,String speech,JsonNode spec,String scene) {
        return captureFact(sid,speaker,audience,speech,spec,scene,null);
    }
    private boolean captureFact(String sid,String speaker,String audience,String speech,JsonNode spec,String scene,Long spokenEnd) {
        if(spec==null || !spec.isObject() || speech==null || speech.isBlank())return false;
        String to=spec.path("toGodId").asText(),quote=spec.path("quote").asText(),kind=spec.path("kind").asText(),value=spec.path("value").asText();
        if(!to.equals(audience) || speaker.equals(to) || quote.length()<6 || quote.length()>300 || !speech.contains(quote)
                || !quote.matches("(?s)^我(?:向[^，。；]{1,20})?(承诺|保证|答应).*"))return false;
        var a=gods.findById(speaker).orElse(null);var c=gods.findById(to).orElse(null);
        if(a==null || c==null || !sid.equals(a.getSessionId()) || !sid.equals(c.getSessionId())
                || spokenEnd==null && (!at(sid,speaker,scene) || !at(sid,to,scene)))return false;
        var location=scenes.findBySessionIdAndSceneId(sid,scene).orElse(null);
        if(location==null || spokenEnd==null && (!physical.awake(sid,speaker,location) || !physical.awake(sid,to,location)))return false;
        if(!Set.of("GIVE_ITEM","MEET","NON_AGGRESSION","UNVERIFIED").contains(kind) || value.length()>64)return false;
        if(kind.equals("UNVERIFIED"))value="";
        long now=tick(sid),due=0;
        if(!kind.equals("UNVERIFIED")) {
          if(spokenEnd!=null){
            var delay=spec.path("afterSeconds");if(!delay.isIntegralNumber() || delay.asLong()<1 || delay.asLong()>86400)return false;
            due=Math.addExact(spokenEnd,delay.asLong());
          }else{
            var delay=spec.path("afterTicks");if(!delay.isIntegralNumber() || delay.asInt()<1 || delay.asInt()>6)return false;
            int d=delay.asInt();if(!quote.contains(d+"刻") && !quote.contains("一二三四五六".substring(d-1,d)+"刻"))return false;
            due=now+d;
          }
            if(quote.matches("(?s).*(如果|只要|除非|倘若|假如|若是).*"))return false;
            if(kind.equals("GIVE_ITEM") && (value.isBlank() || value.length()>64 || !quote.contains(value)
                    || !quote.matches("(?s).*(送给|赠予|赠送).*")))return false;
            if(kind.equals("MEET") && (value.isBlank() || value.length()>64 || !quote.contains(value)
                    || !quote.matches("(?s).*(会面|见面|相见).*")))return false;
            if(kind.equals("NON_AGGRESSION") && (!value.isBlank() || !quote.matches("(?s).*不(?:会)?攻击.*")))return false;
            // A promise may precede acquiring the item. Only an actual validated delivery fulfills it.
            if(kind.equals("MEET") && jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=? AND location_name=?",Integer.class,sid,value)!=1)return false;
        }
        for(var old:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE'",String.class,sid,speaker)) {
            var p=read(old);if(p!=null && p.path("speaker").asText().equals(speaker) && p.path("recipient").asText().equals(to)
                    && p.path("kind").asText().equals(kind) && p.path("value").asText().equals(value)
                    && Set.of("OPEN","OVERDUE","UNVERIFIED").contains(p.path("status").asText())
                    && (!kind.equals("UNVERIFIED") || quote.equals(p.path("quote").asText())))return false;
        }
        var p=JSON.createObjectNode().put("speaker",speaker).put("recipient",to).put("speakerName",partyName(speaker))
                .put("recipientName",partyName(to)).put("quote",quote).put("kind",kind).put("value",value).put("createdTick",now)
                .put("dueTick",due).put("status",kind.equals("UNVERIFIED")?"UNVERIFIED":"OPEN")
                .put("reason","这是确实说出的承诺；尚不等于履约，也不预设对方信任或效忠。");
        if(spokenEnd!=null)p.put("timeBasis","STORY_SECONDS").put("createdSeconds",spokenEnd).put("dueSeconds",due).put("dueTick",0);
        if(kind.equals("MEET") && meeting(sid,p))return false;
        String ref="promise:"+speaker+":"+(spokenEnd==null?now:"s"+spokenEnd)+":"+fingerprint(quote+to+kind+value);
        String title=p.path("speakerName").asText()+"向"+p.path("recipientName").asText()+"作出的承诺";if(title.length()>120)title=title.substring(0,120);
        for(String owner:List.of(speaker,to))jdbc.update("INSERT IGNORE INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) SELECT ?,?,?,?,?, 'PROMISE',?,?,location_name,? FROM game_scene WHERE session_id=? AND scene_id=?",
                sid,owner,ref,title,p.toString(),owner.equals(speaker)?to:speaker,scene,now,sid,scene);
        return true;
    }
    @Transactional
    public void captureNpc(String sid,String id,String audience,String speech,JsonNode spec) {
        if(spec==null || !spec.isObject())return;
        var scenes=jdbc.queryForList("SELECT scene_id FROM game_scene WHERE session_id=? AND JSON_CONTAINS(present_gods,JSON_QUOTE(?))",String.class,sid,id);
        if(scenes.size()==1)capture(sid,id,audience,speech,spec,scenes.get(0));
    }
    private void save(String sid,String ref,ObjectNode p){jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND source_ref=? AND source_type='PROMISE'",p.toString(),sid,ref);}
    @Transactional
    public void refresh(String sid,String owner,boolean settled) {
        em.flush();
        var rows=owner==null?jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND source_type='PROMISE'",sid):
                jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE'",sid,owner);
        var seen=new HashSet<String>();long atSeconds=seconds(sid);
        for(var r:rows) {
            String ref=(String)r.get("source_ref");if(!seen.add(ref))continue;var p=read((String)r.get("content"));
            if(p==null || !Set.of("OPEN","OVERDUE").contains(p.path("status").asText()))continue;
            long now=now(sid,p,atSeconds),due=due(p);String kind=p.path("kind").asText();boolean done=false;
            if(kind.equals("MEET"))done=meeting(sid,p);
            if(kind.equals("NON_AGGRESSION"))done=now>due || !timed(p) && settled && now>=due;
            if(done){p.put("status",now>due && kind.equals("MEET")?"FULFILLED_LATE":"FULFILLED");p.put("reason",kind.equals("MEET")?"双方确实在约定地点会面。":"承诺期限已结束，期间未记录对受约者的实际攻击；不保证期限后的行为。");}
            else if(now>due){p.put("status","OVERDUE");p.put("reason","期限已过，尚未记录约定结果；无法据此判定故意背信，原因需要当事人核实。");}
            save(sid,ref,p);
        }
    }
    @Transactional
    public void recordDelivery(String sid,OfferService.Offer offer,String status) {recordDelivery(sid,offer,status,null);}
    @Transactional
    public void recordDelivery(String sid,OfferService.Offer offer,String status,String itemRef) {
        recordDelivery(sid,offer,status,itemRef,seconds(sid));
    }
    @Transactional
    public void recordDelivery(String sid,OfferService.Offer offer,String status,String itemRef,long atSeconds) {
        if(!"COMPLETED".equals(status) || !"GIVE".equals(offer.kind()))return;
        var sender=gods.findById(offer.sender()).orElse(null);var recipient=gods.findById(offer.recipient()).orElse(null);
        if(sender==null || recipient==null || !sid.equals(sender.getSessionId()) || !sid.equals(recipient.getSessionId())
                || recipient.getInventory()==null || !recipient.getInventory().contains(offer.offered())
                || itemRef==null && sender.getInventory()!=null && sender.getInventory().contains(offer.offered()))return;
        if(itemRef!=null){
            var delivered=items.owned(sid,recipient).stream().filter(i->itemRef.equals(i.ref()) && offer.offered().equals(i.name())).findFirst();
            if(delivered.isEmpty() || delivered.get().history().isEmpty())return;
            var last=delivered.get().history().get(delivered.get().history().size()-1);
            if(!Set.of("GIFT","TRANSFER").contains(last.get("kind")) || !offer.sender().equals(last.get("from")) || !offer.recipient().equals(last.get("to")))return;
        }
        for(var r:jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE'",sid,offer.sender())) {
            var p=read((String)r.get("content"));if(p==null || !Set.of("OPEN","OVERDUE").contains(p.path("status").asText())
                    || !p.path("speaker").asText().equals(offer.sender()) || !p.path("recipient").asText().equals(offer.recipient())
                    || timed(p) && atSeconds<p.path("createdSeconds").asLong()
                    || !p.path("kind").asText().equals("GIVE_ITEM") || !p.path("value").asText().equals(offer.offered()))continue;
            p.put("status",now(sid,p,atSeconds)>due(p)?"FULFILLED_LATE":"FULFILLED");
            p.put("reason","约定的赠予已经实际完成；迟到履约不抹去之前的延误。");save(sid,(String)r.get("source_ref"),p);
        }
    }
    @Transactional
    public void recordAggression(String sid,String attacker,String intendedTarget) {
        recordAggression(sid,attacker,intendedTarget,seconds(sid));
    }
    @Transactional
    public void recordAggression(String sid,String attacker,String intendedTarget,long atSeconds) {
        for(var r:jdbc.queryForList("SELECT source_ref,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='PROMISE'",sid,attacker)) {
            var p=read((String)r.get("content"));if(p==null || !p.path("status").asText().equals("OPEN") || !p.path("kind").asText().equals("NON_AGGRESSION")
                    || !p.path("speaker").asText().equals(attacker) || !p.path("recipient").asText().equals(intendedTarget)
                    || timed(p) && atSeconds<p.path("createdSeconds").asLong()
                    || now(sid,p,atSeconds)>due(p))continue;
            p.put("status","VIOLATED").put("reason","在承诺不攻击的期限内，对受约者实际发起了攻击；是否视为背叛、如何回应，由人物结合过去和处境判断。");
            save(sid,(String)r.get("source_ref"),p);
        }
    }
    private static String fingerprint(String text) {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,32);}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static String render(String text) {
        var p=read(text);if(p==null)return "承诺记录暂无法核对。";
        String label=switch(p.path("status").asText()){case "OPEN"->"待履行";case "OVERDUE"->"逾期未兑现·原因待核实";case "FULFILLED"->"已兑现";case "FULFILLED_LATE"->"延迟兑现";case "VIOLATED"->"言行违约·不预设敌对";default->"复杂承诺·尚不能自动验收";};
        return p.path("speakerName").asText()+"向"+p.path("recipientName").asText()+"说："+p.path("quote").asText()
                +"\n进度："+label+(due(p)>0?"；期限："+(timed(p)?"故事第"+due(p)+"秒":"世界第"+due(p)+"刻"):"")+"\n"+p.path("reason").asText();
    }
}
