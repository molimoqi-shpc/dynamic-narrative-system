package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.GodProfileRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** The player's known identity, meaningful possessions and actual social history. No model calls on reads. */
@Service
public class PlayerJournalService {
    @org.springframework.beans.factory.annotation.Autowired private WorldSettingService worldSettings;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> PLACEHOLDERS=Set.of("你","玩家","主角","未入世者","未留名旅人","");
    private static final Set<String> ITEM_KINDS=Set.of("ARTIFACT","EQUIPMENT","KEY_ITEM","CONSUMABLE","KEEPSAKE","OTHER");
    private static final Set<String> RELATIONS=Set.of("ALLY","FRIEND","ENEMY","RIVAL","FAMILY","ACQUAINTANCE","ESTRANGED");
    private final JdbcTemplate jdbc;
    private final GodProfileRepository actors;
    @org.springframework.beans.factory.annotation.Autowired private TreatyService treaties;
    public PlayerJournalService(JdbcTemplate jdbc,GodProfileRepository actors){this.jdbc=jdbc;this.actors=actors;}
    private JsonNode parse(Object raw){try{var value=JSON.readTree(Objects.toString(raw,"{}"));return value==null?JSON.createObjectNode():value;}catch(Exception e){return JSON.createObjectNode();}}
    private ObjectNode object(Object raw){var value=parse(raw);return value!=null && value.isObject()?(ObjectNode)value:JSON.createObjectNode();}
    private String text(JsonNode n,String key,int max){String s=n.path(key).asText("").strip();return s.length()>max?s.substring(0,max):s;}
    public static String declaredName(String... answers){
        // Compatibility for explicit player-authored names in old creation answers, not an inference from novel prose.
        var pattern=Pattern.compile("(?:^|[，。；\\n])\\s*(?:我(?:的名字)?|主角|角色)?(?:名叫|名为|叫作|叫|姓名[：:为是]?|名字[：:为是]?)\\s*[“\"「]?([\\p{IsHan}·]{2,12}|[A-Za-z][A-Za-z .'-]{1,29})(?=[，。；\\n”\"」]|$)");
        for(String answer:answers){var m=pattern.matcher(Objects.toString(answer,""));if(m.find())return m.group(1).strip();}return "";
    }
    static String legacyBiographyName(String biography,String... answers){
        // Old creation packets lacked player_name. Accept an opening biography subject
        // only when the player's own first-person introduction independently names it.
        var subject=Pattern.compile("^\\s*[“\"「]?([\\p{IsHan}·]{2,12}?)(?:出生于|生于|自幼|自小|来自)").matcher(Objects.toString(biography,""));
        if(!subject.find())return "";
        String candidate=subject.group(1);
        var introduction=Pattern.compile("(?:^|[，。；\\n])\\s*(?:我|主角|角色)(?:是|为)[^，。；\\n]{0,30}"+Pattern.quote(candidate)+"(?=[，。；\\n]|$)");
        for(String answer:answers)if(introduction.matcher(Objects.toString(answer,"")).find())return candidate;
        return "";
    }
    public String name(String sid,String role,String current){
        if(!PLACEHOLDERS.contains(Objects.toString(current,"")))return current;
        for(String raw:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SELF_PROFILE' ORDER BY knowledge_id DESC",String.class,sid,role)){
            var node=parse(raw);for(String key:List.of("playerName","name")){String found=text(node,key,30);if(!PLACEHOLDERS.contains(found))return found;}
        }
        for(var row:jdbc.queryForList("SELECT answer_1,answer_2,answer_3,birth_story FROM character_creation WHERE session_id=?",sid)){
            String[] answers={Objects.toString(row.get("answer_1"),""),Objects.toString(row.get("answer_2"),""),Objects.toString(row.get("answer_3"),"")};
            String found=declaredName(answers);if(found.isBlank())found=legacyBiographyName(Objects.toString(row.get("birth_story"),""),answers);if(!found.isBlank())return found;
        }
        return "未留名旅人";
    }
    /** Runs inside a normal accepted game operation; viewing a profile never rewrites a save. */
    public void synchronizeName(String sid,String role){var actor=actors.findById(role).filter(a->sid.equals(a.getSessionId())).orElse(null);if(actor!=null && PLACEHOLDERS.contains(Objects.toString(actor.getGodName(),""))){String resolved=name(sid,role,actor.getGodName());if(!resolved.equals(actor.getGodName())){actor.setGodName(resolved);actors.saveAndFlush(actor);}}}
    private void store(String sid,String player,String ref,String type,ObjectNode value,String scene,long seconds){
        value.put("recordedAtSeconds",seconds);
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'玩家已知人物与珍藏',?,?,'',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content),scene_id=VALUES(scene_id)",sid,player,ref,value.toString(),type,scene);
    }
    /** Optional presentation data accompanies the same accepted story. Missing fields never stop a passage. */
    public void apply(String sid,String player,String scene,JsonNode journal,long seconds){
        if(!journal.isObject())return;
        // Optional public art direction, archived with the same accepted story.
        // Incomplete appearance data is harmless and never rejects a passage.
        for(var raw:journal.path("characterAppearances")){
            String target=text(raw,"roleId",64),basis=text(raw,"basis",600);
            if(target.isBlank() && !text(raw,"name",50).isBlank()){
                var ids=jdbc.queryForList("SELECT god_id FROM god_profile WHERE session_id=? AND god_name=?",String.class,sid,text(raw,"name",50));
                if(ids.size()==1)target=ids.get(0);
            }
            if(target.isBlank() || basis.isBlank() || actors.findById(target).filter(a->sid.equals(a.getSessionId())).isEmpty())continue;
            var card=PublicAppearanceSchema.sanitize(raw).put("roleId",target).put("basis",basis);
            var previous=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='APPEARANCE' AND source_ref=?",String.class,sid,player,"appearance:"+target);
            var retained=previous.isEmpty()?JSON.createObjectNode():object(previous.get(0));
            card.properties().forEach(e->retained.set(e.getKey(),e.getValue()));
            store(sid,player,"appearance:"+target,"APPEARANCE",retained,scene,seconds);
        }
        String newName=text(journal,"playerName",30);
        if(!PLACEHOLDERS.contains(newName)){
            var self=actors.findById(player).filter(a->sid.equals(a.getSessionId())).orElseThrow();
            String established=name(sid,player,self.getGodName());
            String retained=PLACEHOLDERS.contains(established)?newName:established;
            self.setGodName(retained);actors.saveAndFlush(self);
            store(sid,player,"player:identity","SELF_PROFILE",JSON.createObjectNode().put("playerName",retained),scene,seconds);
        }
        for(var raw:journal.path("items")){
            String ref=text(raw,"itemRef",96),requestedName=text(raw,"name",120);
            // Resolve against real held units; this ledger cannot grant items or change custody.
            var held=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(content,'$.state'))='HELD'",String.class,sid,player).stream().map(this::parse).toList();
            var matches=held.stream().filter(i->!ref.isBlank() && ref.equals(i.path("ref").asText())).toList();
            // A GAIN gets its server ref only during commit. The same response can refer to
            // that new held object by its unique name, as documented in the author contract.
            if(matches.isEmpty() && (ref.isBlank() || ref.equals(requestedName)) && !requestedName.isBlank())
                matches=held.stream().filter(i->requestedName.equals(i.path("name").asText())).toList();
            if(matches.size()!=1)continue;
            var actual=matches.get(0);var card=JSON.createObjectNode().put("itemRef",actual.path("ref").asText()).put("name",actual.path("name").asText());
            String kind=text(raw,"kind",30);if(ITEM_KINDS.contains(kind))card.put("kind",kind);if(raw.path("important").isBoolean())card.put("important",raw.path("important").asBoolean());
            for(String field:List.of("description","appearance","knownEffects","limitations","significance","origin")){String value=text(raw,field,1800);if(!value.isBlank())card.put(field,value);}
            // Partial updates preserve established descriptions; unknown powers are never inferred from names.
            var previous=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=? AND source_type='ITEM_PROFILE'",String.class,sid,player,"item-card:"+actual.path("ref").asText());
            var retained=previous.isEmpty()?JSON.createObjectNode():object(previous.get(0));card.properties().forEach(e->retained.set(e.getKey(),e.getValue()));
            if(!retained.has("kind"))retained.put("kind","OTHER");if(!retained.has("important"))retained.put("important",true);
            store(sid,player,"item-card:"+actual.path("ref").asText(),"ITEM_PROFILE",retained,scene,seconds);
        }
        treaties.applyAuthored(sid,player,scene,journal.path("agreements"),seconds);
        for(var raw:journal.path("relationships")){
            String target=text(raw,"roleId",64),kind=text(raw,"kind",30);
            if(target.isBlank() && !text(raw,"name",50).isBlank()){var ids=jdbc.queryForList("SELECT god_id FROM god_profile WHERE session_id=? AND god_name=?",String.class,sid,text(raw,"name",50));if(ids.size()==1)target=ids.get(0);}
            if(target.equals(player) || !RELATIONS.contains(kind) || actors.findById(target).filter(a->sid.equals(a.getSessionId())).isEmpty())continue;
            var relation=JSON.createObjectNode().put("roleId",target).put("kind",kind);
            for(String field:List.of("label","summary","basis")){String value=text(raw,field,1500);if(!value.isBlank())relation.put(field,value);}
            if(relation.path("basis").asText().isBlank() && relation.path("summary").asText().isBlank())continue;
            store(sid,player,"relationship:"+target,"PLAYER_RELATION",relation,scene,seconds);
        }
    }
    public List<Map<String,Object>> importantItems(String sid,String player,List<Map<String,Object>> inventory){
        var profiles=new HashMap<String,JsonNode>();
        for(String raw:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='ITEM_PROFILE' ORDER BY knowledge_id",String.class,sid,player)){var item=parse(raw);profiles.put(item.path("itemRef").asText(),item);}
        var result=new ArrayList<Map<String,Object>>();
        for(var owned:inventory){var profile=profiles.get(owned.get("ref"));if(profile==null || !profile.path("important").asBoolean(true))continue;
            var card=new LinkedHashMap<String,Object>(owned);
            for(String field:List.of("kind","description","appearance","knownEffects","limitations","significance","origin","recordedAtSeconds"))if(profile.has(field))card.put(field,JSON.convertValue(profile.path(field),Object.class));
            result.add(card);
        }return result;
    }
    public Map<String,Map<String,Object>> publicAppearances(String sid,String player){
        var result=new LinkedHashMap<String,Map<String,Object>>();
        for(String raw:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='APPEARANCE' ORDER BY knowledge_id",String.class,sid,player)){
            var card=parse(raw);String role=card.path("roleId").asText();if(role.isBlank())continue;
            var known=new LinkedHashMap<String,Object>(JSON.convertValue(PublicAppearanceSchema.sanitize(card),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}));
            if(card.path("recordedAtSeconds").isNumber())known.put("recordedAtSeconds",card.path("recordedAtSeconds").asLong());
            known.put("worldStyle",worldSettings.brief(sid).path("culturalStyle").asText(""));
            result.put(role,known);
        }return result;
    }
    public Map<String,Object> authorContext(String sid,String player){
        return Map.of("meaning","仅玩家已经获知的姓名、珍藏资料与实际关系；不等于当前实时位置。遗漏不删除旧记录。",
            "records",jdbc.queryForList("SELECT k.source_type,k.content FROM role_knowledge k WHERE k.session_id=? AND k.god_id=? AND (k.source_type IN ('PLAYER_RELATION','TREATY','APPEARANCE') OR k.source_ref='player:identity' OR (k.source_type='ITEM_PROFILE' AND EXISTS (SELECT 1 FROM role_knowledge i WHERE i.session_id=k.session_id AND i.god_id=k.god_id AND i.source_type='ITEM_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(i.content,'$.state'))='HELD' AND JSON_UNQUOTE(JSON_EXTRACT(i.content,'$.ref'))=JSON_UNQUOTE(JSON_EXTRACT(k.content,'$.itemRef'))))) ORDER BY k.knowledge_id",sid,player));
    }
    public List<Map<String,Object>> relationships(String sid,String self,String pid){
        var names=new LinkedHashMap<String,String>();jdbc.query("SELECT god_id,god_name FROM god_profile WHERE session_id=?",rs->{names.put(rs.getString(1),rs.getString(2));},sid);
        var known=new LinkedHashMap<String,Map<String,Object>>();
        for(var row:jdbc.queryForList("SELECT source_type,source_god_id,content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type IN ('OBSERVED_STATE','HEARD','PERSONAL','TOLD','INQUIRED','INVESTIGATED','PLAYER_RELATION') ORDER BY knowledge_id",sid,self)){
            String type=Objects.toString(row.get("source_type"),"");var content=parse(row.get("content"));
            String id="OBSERVED_STATE".equals(type)?content.path("id").asText():"PLAYER_RELATION".equals(type)?content.path("roleId").asText():Objects.toString(row.get("source_god_id"),"");
            if(!names.containsKey(id) || id.equals(self))continue;
            var person=known.computeIfAbsent(id,key->connection(key,names.get(key)));
            if("OBSERVED_STATE".equals(type)){if(!person.containsKey("recordedAtSeconds"))person.put("basis","曾经实际见过");if(content.has("observedAtSeconds"))person.put("lastSeenSeconds",content.path("observedAtSeconds").asLong());}
            if("PLAYER_RELATION".equals(type)){for(String field:List.of("kind","label","summary","basis","recordedAtSeconds"))if(content.has(field))person.put(field,JSON.convertValue(content.path(field),Object.class));}
        }
        // A public chapter or biography can establish acquaintances without an old numeric relationship row.
        for(var row:jdbc.queryForList("SELECT g.god_id,g.god_name FROM god_profile g WHERE g.session_id=? AND g.god_id<>? AND (EXISTS (SELECT 1 FROM interaction_log l WHERE l.session_id=g.session_id AND l.player_id=? AND (LOCATE(g.god_id,l.processed_dialog)>0 OR LOCATE(g.god_name,l.processed_dialog)>0)) OR EXISTS (SELECT 1 FROM character_creation c WHERE c.session_id=g.session_id AND c.player_id=? AND LOCATE(g.god_name,c.birth_story)>0))",sid,self,pid,pid))known.computeIfAbsent(row.get("god_id").toString(),key->connection(key,row.get("god_name").toString()));
        for(var row:jdbc.queryForList("SELECT target_id,trust_value,is_allied,note FROM god_relationship WHERE session_id=? AND source_id=?",sid,self)){
            String id=Objects.toString(row.get("target_id"),"");if(!names.containsKey(id) || id.equals(self))continue;
            var person=known.computeIfAbsent(id,key->connection(key,names.get(key)));int trust=row.get("trust_value") instanceof Number n?n.intValue():50;
            person.put("trust",trust);boolean allied=Boolean.TRUE.equals(row.get("is_allied")) || row.get("is_allied") instanceof Number n && n.intValue()!=0;
            if(!person.containsKey("recordedAtSeconds")){person.put("kind",allied?"ALLY":trust>=70?"FRIEND":trust<=30?"RIVAL":"ACQUAINTANCE");person.put("label",allied?"已结盟":trust>=70?"我方信任":trust<=30?"我方戒备":"相识");}
            if(!Objects.toString(row.get("note"),"").isBlank())person.putIfAbsent("summary",row.get("note"));
        }
        for(String raw:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='TREATY'",String.class,sid,self)){
            var pact=parse(raw);if(!"ACTIVE".equals(pact.path("status").asText()))continue;
            String id=self.equals(pact.path("proposer").asText())?pact.path("recipient").asText():pact.path("proposer").asText();
            if(names.containsKey(id) && !id.equals(self)){var person=known.computeIfAbsent(id,key->connection(key,names.get(key)));if(!person.containsKey("recordedAtSeconds")){person.put("kind","ALLY");person.put("label","已结盟");person.put("basis","双方已成立的盟约");}}
        }
        return new ArrayList<>(known.values());
    }
    private Map<String,Object> connection(String id,String name){var result=new LinkedHashMap<String,Object>();result.put("godId",id);result.put("name",name);result.put("kind","ACQUAINTANCE");result.put("label","已相识");result.put("basis","已有公开交往或亲历记录");return result;}
}
