package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The main author introduces people; this only retains their brief authored record for later role calls. */
@Service
public class StoryCastService {
    private final GodProfileRepository actors;private final GameSceneRepository scenes;private final JdbcTemplate jdbc;
    private static final ObjectMapper JSON=new ObjectMapper();
    public StoryCastService(GodProfileRepository actors,GameSceneRepository scenes,JdbcTemplate jdbc){this.actors=actors;this.scenes=scenes;this.jdbc=jdbc;}
    public static void validate(JsonNode entries){
        if(entries.isMissingNode())return;
        if(!entries.isArray())throw new IllegalArgumentException("新人物资料须为数组");
        for(var entry:entries)if(!entry.path("name").isTextual() || entry.path("name").asText().isBlank() || entry.path("name").asText().length()>50 || !entry.path("profile").isTextual() || entry.path("profile").asText().isBlank() || entry.path("profile").asText().length()>4000)throw new IllegalArgumentException("新人物需要姓名和主API已确立的简版资料");
    }
    public String idFor(String sid,String operation,int frame,String name){return "npc_"+UUID.nameUUIDFromBytes((sid+":"+operation+":"+frame+":"+name).getBytes(StandardCharsets.UTF_8)).toString().replace("-","").substring(0,28);}
    /** The author explicitly identifies a known person's newly revealed name.
     * No prose matching or guessed identity merges are performed. */
    public boolean bindExistingIdentities(String sid,String operation,int frame,ObjectNode draft,ObjectNode facts){
        if(!(draft.path("newCharacters") instanceof ArrayNode entries))return false;
        var known=new HashMap<String,String>();for(var person:facts.path("characterIndex"))known.put(person.path("roleId").asText(),person.path("name").asText());
        for(var entry:facts.path("currentWorld").path("resources").properties())if(!entry.getValue().path("provisional").asBoolean())known.put(entry.getKey(),entry.getValue().path("name").asText());
        var aliases=new HashMap<String,String>();var kept=JSON.createArrayNode();boolean changed=false;
        for(var entry:entries){String id=entry.path("existingRoleId").asText(),name=entry.path("name").asText();
            if(!known.containsKey(id) || name.isBlank() || name.length()>50){kept.add(entry);continue;}
            changed=true;aliases.put(name,id);aliases.put(idFor(sid,operation,frame,name),id);
            boolean saved=false;for(var update:draft.path("identityUpdates"))if(id.equals(update.path("roleId").asText()) && name.equals(update.path("name").asText()))saved=true;
            if(!saved)draft.withArray("identityUpdates").addObject().put("roleId",id).put("name",name).put("previousName",known.get(id)).put("basis",entry.path("basis").asText("正文实际披露或更改公开称呼，人物身份与过往不变"));
            boolean indexed=false;for(var person:facts.path("characterIndex"))if(id.equals(person.path("roleId").asText()) && name.equals(person.path("name").asText()))indexed=true;
            if(!indexed)facts.withArray("characterIndex").addObject().put("roleId",id).put("name",name);
        }
        if(changed){draft.set("newCharacters",kept);replaceNamedReferences(draft,aliases);}return changed;
    }
    /** Apply only explicit authored name updates inside the passage transaction. */
    public void applyIdentities(String sid,String operation,int frame,String scene,JsonNode updates){
        for(var update:updates){String id=update.path("roleId").asText(),name=update.path("name").asText();
            var actor=actors.findById(id).filter(a->sid.equals(a.getSessionId()));if(actor.isEmpty() || name.isBlank() || name.length()>50)continue;
            String old=actor.get().getGodName();actor.get().setGodName(name);actors.saveAndFlush(actor.get());
            var record=JSON.createObjectNode().put("recordType","IDENTITY_REVEAL").put("actorId",id).put("previousName",update.path("previousName").asText(old)).put("name",name).put("text",update.path("basis").asText());
            jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'公开称呼变化',?,'TURN_EVENT','',?,'',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,id,"story:identity:"+operation+":"+frame,record.toString(),scene);
        }
    }
    /** Author-selected participant IDs, not a roster inferred from prose, location or keywords. */
    static Set<String> referencedRoles(JsonNode proposal){
        var roles=participantRoles(proposal);
        for(String field:List.of("sceneOutcomes","endingCast","itemOutcomes","roleExperiences","playerJournal","newCharacters"))collectRoles(proposal.path(field),roles);
        return roles;
    }
    /** Only the author's selected cast/requested intentions require a role call, not historical citations. */
    static Set<String> participantRoles(JsonNode proposal){
        var roles=new LinkedHashSet<String>();
        for(var role:proposal.path("roleIds"))if(role.isTextual() && !role.asText().isBlank())roles.add(role.asText());
        for(var request:proposal.path("roleRequests"))if(request.isTextual() && !request.asText().isBlank())roles.add(request.asText());
        for(String field:List.of("roleRequests","events","mechanics","sceneOutcomes","roleExperiences","decision"))collectRoles(proposal.path(field),roles);
        return roles;
    }
    /** Transport adaptation: an exact, unique indexed name can stand for its ID. Never guess aliases. */
    static void bindNamedReferences(ObjectNode proposal,ObjectNode facts){
        var names=new LinkedHashMap<String,Set<String>>();var ids=new HashSet<String>();
        for(var entry:facts.path("currentWorld").path("resources").properties())indexName(names,ids,entry.getValue().path("name").asText(),entry.getKey());
        for(var entry:facts.path("characterIndex"))indexName(names,ids,entry.path("name").asText(),entry.path("roleId").asText());
        var unique=new HashMap<String,String>();names.forEach((name,roles)->{if(roles.size()==1 && !ids.contains(name))unique.put(name,roles.iterator().next());});
        var explicit=new HashMap<String,Set<String>>();collectExplicitAliases(proposal,unique,ids,explicit);
        explicit.forEach((alias,roles)->{if(roles.size()==1)unique.put(alias,roles.iterator().next());});
        replaceNamedReferences(proposal,unique);
    }
    /** An explicit name+reference row can identify a provider's alternate label.
     * Existing IDs and ambiguous names are never reassigned, and prose is never searched. */
    private static void collectExplicitAliases(JsonNode node,Map<String,String> names,Set<String> ids,Map<String,Set<String>> aliases){
        if(node.isObject()){
            String name=node.path("name").asText(),id=node.path("roleId").asText();String actual=names.get(name);
            if(actual!=null && !id.isBlank() && !ids.contains(id) && !id.equals(actual))aliases.computeIfAbsent(id,key->new HashSet<>()).add(actual);
        }
        for(var child:node)if(child.isContainerNode())collectExplicitAliases(child,names,ids,aliases);
    }
    private static void indexName(Map<String,Set<String>> names,Set<String> ids,String name,String id){if(name.isBlank() || id.isBlank())return;ids.add(id);names.computeIfAbsent(name,key->new LinkedHashSet<>()).add(id);}
    private static void replaceNamedReferences(JsonNode node,Map<String,String> names){
        if(node instanceof ObjectNode object)for(var field:object.properties()){
            var value=field.getValue();
            if(Set.of("roleId","actorId","targetId","fromId","toId","counterpartId","holderId","proposerId","ownerId","legalOwnerId","sourceGodId").contains(field.getKey()) && value.isTextual() && names.containsKey(value.asText()))object.put(field.getKey(),names.get(value.asText()));
            else if("roleIds".equals(field.getKey()) && value instanceof ArrayNode array){for(int i=0;i<array.size();i++)if(array.get(i).isTextual() && names.containsKey(array.get(i).asText()))array.set(i,TextNode.valueOf(names.get(array.get(i).asText())));}
            else if(value.isContainerNode())replaceNamedReferences(value,names);
        }else if(node.isArray())for(var child:node)replaceNamedReferences(child,names);
    }
    private static void collectRoles(JsonNode node,Set<String> roles){
        if(node.isObject())for(var field:node.properties()){
            var value=field.getValue();
            if(Set.of("roleId","actorId","targetId","fromId","toId","counterpartId").contains(field.getKey()) && value.isTextual() && !value.asText().isBlank())roles.add(value.asText());
            else if("roleIds".equals(field.getKey()) && value.isArray()){for(var role:value)if(role.isTextual() && !role.asText().isBlank())roles.add(role.asText());}
            else if(value.isContainerNode())collectRoles(value,roles);
        }else if(node.isArray())for(var child:node)collectRoles(child,roles);
    }
    /** Give the author proposed identities, never an uncommitted passage to continue as history. */
    static boolean refreshPlanning(ObjectNode facts){
        boolean legacy=facts.has("uncommittedStoryProposal");facts.remove("uncommittedStoryProposal");
        var published=facts.path("alreadyHappenedThisChapter");boolean continuing=published.isArray() && !published.isEmpty();
        var scope=facts.putObject("generationScope").put("mode",continuing?"CONTINUE_AFTER_COMMITTED":"NEW_PASSAGE").put("committedPassageCount",published.isArray()?published.size():0);
        scope.put("instruction",continuing?
            "alreadyHappenedThisChapter中的所有段落均已发布并保存。现在只从最后一段结尾之后创作新的完整事件；玩家原方向是持续目标，不能从本轮开头重新执行。补齐角色意愿、查世界书或修复候选，都只重写当前尚未提交段落，绝不重写已发布行程、介绍、问答或重复登记其结果。continuity保留累计进展，story和events只包含这次新发生的部分。":
            "当前尚无本轮已发布段落。依据真实前文与玩家方向，完整创作当前尚未提交段落的起因、行程、交谈和结果；角色提案与失败候选没有发生，不从候选结尾接写。");
        var candidates=JSON.createArrayNode();
        for(var resource:facts.path("currentWorld").path("resources").properties())if(resource.getValue().path("provisional").asBoolean()){
            var own=facts.path("privateRoleContexts").path(resource.getKey());
            var entry=candidates.addObject().put("roleId",resource.getKey()).put("name",resource.getValue().path("name").asText());
            entry.set("profile",own.path("establishedSelf").deepCopy());entry.put("narrativeTier",own.path("previousPortrayal").path("narrativeTier").asText("EXTRA"));
        }
        if(!candidates.isEmpty()){
            var planning=facts.putObject("castPlanning").put("status","UNCOMMITTED").put("instruction","这里只是当前尚未提交段落的候选人物设定与本人意愿，候选正文不是历史。按generationScope创作当前新段落：已有已发布段落时从其结束后继续，不重写本轮开头。最终实际登场的新人物仍返回newCharacters及其最终经历；未登场者可以放弃。");planning.set("candidates",candidates);
        }
        return legacy;
    }
    /** Final structured references select proposed people; no prose/keyword inference or extra API call. */
    static void bindProposedRecords(ObjectNode draft,ObjectNode facts){
        var records=draft.path("newCharacters").isArray()?(ArrayNode)draft.path("newCharacters"):JSON.createArrayNode();
        var selected=new HashSet<String>(participantRoles(draft));for(var e:draft.path("roleExperiences"))selected.add(e.path("roleId").asText());
        for(var group:draft.path("sceneOutcomes"))for(var role:group.path("roleIds"))selected.add(role.asText());
        for(var event:draft.path("events")){
            for(String field:List.of("actorId","targetId","fromId","toId"))selected.add(event.path(field).asText());
            for(var effect:event.path("effects"))for(String field:List.of("actorId","targetId","fromId","toId"))selected.add(effect.path(field).asText());
        }
        for(String role:selected)if(facts.path("currentWorld").path("resources").path(role).path("provisional").asBoolean()){
            String name=facts.path("currentWorld").path("resources").path(role).path("name").asText();boolean present=false;for(var entry:records)if(name.equals(entry.path("name").asText()))present=true;if(present)continue;
            var own=facts.path("privateRoleContexts").path(role);var profiles=new ArrayList<String>();for(var profile:own.path("establishedSelf"))profiles.add(profile.asText());
            var entry=records.addObject().put("name",name).put("profile",String.join("\n",profiles)).put("narrativeTier",own.path("previousPortrayal").path("narrativeTier").asText("EXTRA"));
            for(var memory:draft.path("roleExperiences"))if(role.equals(memory.path("roleId").asText()))for(String field:List.of("experience","situation"))if(memory.has(field))entry.set(field,memory.path(field).deepCopy());
        }
        if(draft.has("newCharacters") || !records.isEmpty())draft.set("newCharacters",records);
        // The sceneOutcomes protocol has one whereabouts source. Introduction metadata is not a second one.
        if(draft.has("sceneOutcomes"))for(var entry:records)if(entry instanceof ObjectNode object)object.remove("sceneId");
    }
    /** These contexts remain in the operation payload until the main author commits a final story. */
    public Map<String,ObjectNode> proposedContexts(String sid,String operation,int frame,JsonNode entries,String request){
        validate(entries);var result=new LinkedHashMap<String,ObjectNode>();
        for(var entry:entries){String name=entry.path("name").asText();if(jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name=?",Integer.class,sid,name)>0)continue;
            String id=idFor(sid,operation,frame,name);var own=JSON.createObjectNode().put("id",id).put("name",name).put("narrativeRequest",RoleRequestContext.OWN_DIRECTION).put("contextPurpose","主API拟在本段引入你；只按本人既有设定给出意愿。候选交谈尚未发生，不把提案中的答应或结果当作经历。");
            own.put("sixStatsEstablished",false);own.putObject("sixStats");
            own.putArray("establishedSelf").add(entry.path("profile").asText());own.putArray("ownRecords");own.putObject("previousPsychology");own.putObject("previousPortrayal").put("narrativeTier",entry.path("narrativeTier").asText("EXTRA"));
            own.put("storySituation","本段拟初次出场，具体后续由主API依据本人意愿创作。");result.put(id,own);
        }return result;
    }
    /** Materialization happens inside the final passage transaction, never during role-model calls. */
    public void prepare(String sid,String operation,int frame,JsonNode entries){
        validate(entries);for(var entry:entries){String name=entry.path("name").asText();if(jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_name=?",Integer.class,sid,name)>0)continue;
            var person=new GodProfile();person.setGodId(idFor(sid,operation,frame,name));person.setSessionId(sid);person.setGodName(name);person.setGodStatus("PARTICIPANT");person.setGodEmotion("见本人处境");
            person.setBaseZhi(15);person.setBaseHui(15);person.setBaseDou(15);person.setBaseWen(15);person.setBaseBen(0);person.setBaseGui(50);person.setInventory(new ArrayList<>());actors.saveAndFlush(person);
        }
    }
    public void remember(String sid,String operation,int frame,GameScene current,JsonNode entries){
        remember(sid,operation,frame,current,entries,false);
    }
    /** The frozen pre-archive wire protocol defined introductions as current-scene arrivals. */
    public void remember(String sid,String operation,int frame,GameScene current,JsonNode entries,boolean legacyCurrentScene){
        validate(entries);
        for(var entry:entries){
            String name=entry.path("name").asText();
            // An already established person is not regenerated or relocated by a repeated introduction.
            String id=idFor(sid,operation,frame,name);var existing=jdbc.queryForList("SELECT god_id FROM god_profile WHERE session_id=? AND god_name=?",String.class,sid,name);if(!existing.isEmpty() && !existing.contains(id))continue;
            if(existing.isEmpty())prepare(sid,operation,frame,JSON.createArrayNode().add(entry));
            String sceneId=entry.path("sceneId").asText();var actual=scenes.findBySessionId(sid).stream().filter(place->place.getPresentGods().contains(id)).findFirst();if(actual.isPresent())sceneId=actual.get().getSceneId();
            if(sceneId.isBlank() && legacyCurrentScene)sceneId=current.getSceneId();
            // A new identity is not evidence that this person followed the player home.
            // Save the profile even if the archive omitted whereabouts; a later explicit
            // final-location record can bind the person without inventing travel now.
            var place=sceneId.isBlank()?null:scenes.findBySessionIdAndSceneId(sid,sceneId).orElseThrow(()->new IllegalArgumentException("新人物引用的场景不存在"));
            if(place!=null){var present=new ArrayList<>(place.getPresentGods());if(!present.contains(id))present.add(id);place.setPresentGods(present);scenes.saveAndFlush(place);}
            var profile=JSON.createObjectNode().put("profile",entry.path("profile").asText()).put("narrativeTier",entry.path("narrativeTier").asText("EXTRA")).put("sixStatsEstablished",false).put("statsMeaning","尚未由主API确立具体六维，库中为中性占位，不据此创作人物能力");
            store(sid,id,"genesis:"+id,"SELF_PROFILE",place,profile.toString());
            String situation=entry.path("situation").asText();if(!situation.isBlank())store(sid,id,"story:situation","ROLE_STORY_STATE",place,situation);
            String experience=entry.path("experience").asText();if(!experience.isBlank())store(sid,id,"story-introduction:"+operation+":"+frame,"TURN_EVENT",place,JSON.createObjectNode().put("recordType","CHAPTER_EXPERIENCE").put("actorId",id).put("text",experience).put("visibility","PRIVATE").toString());
        }
    }
    private void store(String sid,String role,String ref,String type,GameScene scene,String content){jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'主API人物资料',?,?, '',?,?,0) ON DUPLICATE KEY UPDATE content=VALUES(content),scene_id=VALUES(scene_id),location_name=VALUES(location_name)",sid,role,ref,content,type,scene==null?"":scene.getSceneId(),scene==null?"":scene.getLocationName());}
}
