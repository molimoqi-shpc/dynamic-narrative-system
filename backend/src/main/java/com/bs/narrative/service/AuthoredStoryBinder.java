package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Wire-format adaptation only. No chronology, route, bodily eligibility or prose/keyword judgment. */
final class AuthoredStoryBinder {
    private AuthoredStoryBinder() {}
    static ObjectNode bind(ObjectNode original,ObjectNode facts){
        var draft=original.deepCopy();
        com.bs.narrative.llm.ModelProtocol.shape(com.bs.narrative.llm.ModelProtocol.Stage.AUTHOR,draft);
        if(draft.path("mechanics").isArray() && !draft.path("mechanics").isEmpty())return draft;
        if(!draft.path("story").isTextual() || draft.path("story").asText().isBlank())throw new IllegalArgumentException("主创回复只有构思或缺少正文；请返回包含完整story、stop和timeProgress原单位耗时的成稿JSON，authorPlan不能代替story");
        draft.put("story",draft.path("story").asText().replace("\\n","\n"));
        if(facts.path("directStoryResults").asBoolean()){
            StoryCastService.bindNamedReferences(draft,facts);
            draft.set("sceneOutcomes",StoryArchiveLocations.project(draft,facts));
        }
        draft.put("elapsedSeconds",StoryPassageTime.seconds(draft).orElse(0));
        if(!draft.has("stop"))draft.put("stop","RESOLVED");
        if(!Set.of("RESOLVED","DECISION","CONTINUE").contains(draft.path("stop").asText()))throw new IllegalArgumentException("故事结束标记格式无效");
        if(draft.has("continuity") && !draft.path("continuity").isTextual())throw new IllegalArgumentException("故事承接摘要格式无法读取");
        StoryCastService.bindProposedRecords(draft,facts);StoryCastService.validate(draft.path("newCharacters"));
        if(!draft.has("events"))draft.set("events",draft.path("changes").isArray()?draft.path("changes").deepCopy():draft.arrayNode());
        if(!draft.path("events").isArray())throw new IllegalArgumentException("故事结果应为数组");
        bindContestResolutions(draft,facts);
        StorySceneOutcomes.bind(draft,facts);
        var events=draft.arrayNode();int originalIndex=0,newDecision=-1,source=draft.path("decision").path("eventIndex").asInt(-1);
        for(var raw:draft.path("events")){
            if(!(raw instanceof ObjectNode))throw new IllegalArgumentException("故事结果应为对象");
            if(representedItemEffect(raw,draft.path("itemOutcomes"))){originalIndex++;continue;}
            var e=(ObjectNode)raw.deepCopy();String kind=e.path("kind").asText();
            if("CONTEST".equals(kind) && e.path("actorId").asText().isBlank()){
                for(var settled:facts.path("serverContests"))if(!e.path("fingerprint").asText().isBlank() && e.path("fingerprint").equals(settled.path("facts").path("fingerprint"))){e.put("actorId",settled.path("actorId").asText());break;}
            }
            if(!Set.of("ACTION","SPEECH").contains(kind)){
                var action=draft.objectNode().put("kind","ACTION").put("actorId",e.path("actorId").asText(e.path("fromId").asText(facts.path("playerId").asText())));
                for(String field:List.of("sceneId","process","visibility"))if(e.has(field))action.set(field,e.path(field));
                if("EXPLORE".equals(kind) && !e.hasNonNull("actorId"))e.put("creationOnly",true);
                action.putArray("effects").add(e);e=action;
            }
            if(e.path("actorId").asText().isBlank())e.put("actorId",facts.path("playerId").asText());
            e.put("atSeconds",0).put("durationSeconds",0);
            if("ACTION".equals(e.path("kind").asText())){
                if(!e.path("effects").isArray())throw new IllegalArgumentException("故事结果需要效果数组");
                if(e.path("process").asText().isBlank()){
                    var only=e.path("effects").path(0);
                    e.put("process",e.path("effects").size()==1 && Set.of("FACT","FACILITY").contains(only.path("kind").asText())?only.path("objectName").asText()+"："+only.path("after").asText():"本段重要结果，经过见故事正文。");
                }
                e.put("status","COMPLETED");if(!e.has("visibility"))e.put("visibility","PUBLIC");
                var durable=draft.arrayNode();
                for(var rawEffect:e.path("effects")){
                    if(!(rawEffect instanceof ObjectNode))throw new IllegalArgumentException("故事效果应为对象");
                    if(representedItemEffect(rawEffect,draft.path("itemOutcomes")))continue;
                    var effect=(ObjectNode)rawEffect.deepCopy();String effectKind=effect.path("kind").asText();
                    if(Set.of("ITEM_POSITION","OBSERVE").contains(effectKind))continue;
                    if("BODY".equals(effectKind) && effect.path("after") instanceof ObjectNode body){body.retain(List.of("awake","bound"));if(body.isEmpty())continue;}
                    if("FACILITY".equals(effectKind))effect.put("kind","FACT");
                    if(Set.of("CRAFT","GATHER").contains(effectKind))effect.put("kind","GAIN");
                    if("CONSUME".equals(effectKind))effect.put("kind","USE_UP");
                    if("GAIN".equals(effect.path("kind").asText()) && effect.path("source").asText().isBlank())effect.put("source",e.path("process").asText());
                    if("EXPLORE".equals(effect.path("kind").asText()) && effect.path("scene") instanceof ObjectNode place && place.path("characters").isArray()){
                        var fresh=draft.arrayNode();for(var person:place.path("characters")){boolean known=false;for(var resource:facts.path("currentWorld").path("resources"))if(!person.path("name").asText().isBlank() && person.path("name").equals(resource.path("name"))){known=true;break;}if(!known)fresh.add(person);}place.set("characters",fresh);
                    }
                    durable.add(effect);
                }
                e.set("effects",durable);
            }else if(!e.path("speech").isTextual() || e.path("speech").asText().isBlank())throw new IllegalArgumentException("对白结果需要原话");
            if(originalIndex==source)newDecision=events.size();events.add(e);originalIndex++;
        }
        draft.set("events",events);
        if("DECISION".equals(draft.path("stop").asText())){
            var decision=draft.path("decision") instanceof ObjectNode value?value:draft.putObject("decision");
            decision.put("eventIndex",newDecision);
            if(decision.path("type").asText().isBlank())decision.put("type","OTHER");
            if(decision.path("question").asText().isBlank())decision.put("question","此刻，你如何回应？");
        }
        validateExperiences(draft,facts);return draft;
    }
    private static boolean representedItemEffect(JsonNode effect,JsonNode outcomes){
        if(!Set.of("GAIN","TRANSFER","DROP","PICKUP","USE_UP","CRAFT","GATHER","CONSUME").contains(effect.path("kind").asText()))return false;
        for(var outcome:outcomes){String ref=outcome.path("itemRef").asText(),name=outcome.path("name").asText();
            if(!ref.isBlank() && ref.equals(effect.path("itemRef").asText()) || !name.isBlank() && name.equals(effect.path("name").asText()))return true;
        }return false;
    }
    /** The author states occurrence; the server attaches the exact conditional formula result. */
    private static void bindContestResolutions(ObjectNode draft,ObjectNode facts){
        var available=new LinkedHashMap<String,JsonNode>();
        for(var entry:facts.path("serverContests"))if("RESOLVED".equals(entry.path("facts").path("status").asText()))available.put(entry.path("facts").path("fingerprint").asText(),entry);
        if(available.isEmpty())return;
        var recorded=new HashSet<String>();
        for(var event:draft.path("events")){
            if("CONTEST".equals(event.path("kind").asText()))recorded.add(event.path("fingerprint").asText());
            for(var effect:event.path("effects"))if("CONTEST".equals(effect.path("kind").asText()))recorded.add(effect.path("fingerprint").asText());
        }
        var answered=new HashSet<String>();
        for(var resolution:draft.path("contestResolutions")){
            String fingerprint=resolution.path("fingerprint").asText();var source=available.get(fingerprint);
            if(source==null || !resolution.path("occurred").isBoolean() || !answered.add(fingerprint))throw new IllegalArgumentException("交手发生标记须引用本次结算指纹并填写occurred布尔值");
            if(!resolution.path("occurred").asBoolean()){
                if(recorded.contains(fingerprint))throw new IllegalArgumentException("同一交手不能同时登记发生与未发生");
            }else if(recorded.add(fingerprint)){
                var effect=draft.objectNode().put("kind","CONTEST").put("actorId",source.path("actorId").asText()).put("targetId",source.path("targetId").asText());
                effect.put("fingerprint",fingerprint).put("outcome",source.path("facts").path("outcome").asText());((ArrayNode)draft.path("events")).add(effect);
            }
        }
        for(String fingerprint:available.keySet())if(!recorded.contains(fingerprint) && !answered.contains(fingerprint))throw new IllegalArgumentException("请在contestResolutions为本次结算填写{fingerprint:\""+fingerprint+"\",occurred:true或false}。主创决定是否实际交手；发生则服务器保存同一公式后果，未发生则不扣血，无需另抄编号与胜负。");
    }
    private static void validateExperiences(ObjectNode draft,ObjectNode facts){
        // A departure roster is not a list of people who must acquire new memories.
        // Omitted people retain their previous private records; never broadcast the chapter to fill gaps.
        if(!draft.has("roleExperiences"))draft.putArray("roleExperiences");
        if(!draft.path("roleExperiences").isArray())throw new IllegalArgumentException("缺少独立角色亲历记录");
        var seen=new HashSet<String>();
        for(var e:draft.path("roleExperiences")){
            String role=e.path("roleId").asText();if(role.isBlank() || !seen.add(role))throw new IllegalArgumentException("角色亲历编号为空或重复");
            for(String field:List.of("experience","situation"))if(!e.path(field).isTextual() || e.path(field).asText().isBlank() || e.path(field).asText().length()>8000)throw new IllegalArgumentException("角色亲历或累计处境记录格式不完整："+role);
        }
    }
}
