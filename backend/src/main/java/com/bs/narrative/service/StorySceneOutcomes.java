package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Projects the author's final whereabouts into storage operations; never infers travel from prose or intent. */
final class StorySceneOutcomes {
    private StorySceneOutcomes() {}
    static void bind(ObjectNode draft,ObjectNode facts){
        if(!draft.has("sceneOutcomes"))return; // Previously saved drafts keep their original wire contract.
        var outcomes=draft.path("sceneOutcomes");
        if(!outcomes.isArray())throw new IllegalArgumentException("本段实际场景结果需要人物分组数组");
        var factory=draft.objectNode();var prefix=factory.arrayNode();var seen=new HashSet<String>();
        for(var outcome:outcomes){
            if(!outcome.path("roleIds").isArray())throw new IllegalArgumentException("场景结果缺少实际在场人物编号");
            for(var who:outcome.path("roleIds"))if(!who.isTextual() || who.asText().isBlank() || !seen.add(who.asText()))throw new IllegalArgumentException("场景结果人物编号为空或重复");
            String destination=outcome.path("sceneId").asText();
            // Some model responses nest an explicit existing ID with its descriptive scene.
            // Reuse that authored reference instead of creating another copy of the location.
            if(destination.isBlank())destination=outcome.path("scene").path("sceneId").asText();
            boolean fresh=destination.isBlank();
            if(fresh){
                var place=outcome.path("scene");
                if(!place.isObject() || place.path("locationName").asText().isBlank() || place.path("opening").asText().isBlank())throw new IllegalArgumentException("新场景结果需要主API给出的名称和环境");
                var create=factory.objectNode().put("kind","EXPLORE").put("creationOnly",true).put("direction",outcome.path("direction").asText("OTHER")).put("exitName",outcome.path("exitName").asText(place.path("locationName").asText())).put("returnRoute",outcome.path("returnRoute").asBoolean(false));
                var packet=place.deepCopy();((ObjectNode)packet).putArray("characters");create.set("scene",packet);
                prefix.add(action(factory,facts.path("playerId").asText(),create));destination="NEW_DESTINATION";
            }
            // An off-scene person can return to the player's current scene. Always store explicit arrivals;
            // the storage operation is idempotent when that person was already there.
            for(var who:outcome.path("roleIds"))prefix.add(action(factory,who.asText(),factory.objectNode().put("kind","MOVE").put("actorId",who.asText()).put("toSceneId",destination)));
        }
        var combined=factory.arrayNode();combined.addAll(prefix);
        int decisionSource=draft.path("decision").path("eventIndex").asInt(-1),newDecision=-1;
        int oldIndex=0;
        for(var raw:draft.path("events")){
            var event=raw.deepCopy();
            // Whereabouts have a single authoritative representation in this response.
            if(Set.of("MOVE","EXPLORE").contains(event.path("kind").asText())){oldIndex++;continue;}
            if(event instanceof ObjectNode object && event.path("effects").isArray()){
                var retained=factory.arrayNode();for(var effect:event.path("effects"))if(!Set.of("MOVE","EXPLORE").contains(effect.path("kind").asText()))retained.add(effect);
                object.set("effects",retained);
            }
            if(oldIndex==decisionSource)newDecision=combined.size();combined.add(event);oldIndex++;
        }
        if(draft.path("decision") instanceof ObjectNode decision)decision.put("eventIndex",newDecision);
        // Only elapsedSeconds advances the story clock; array order is the result storage order.
        for(var event:combined)if(event instanceof ObjectNode object){object.put("atSeconds",0).put("durationSeconds",0);}
        draft.set("events",combined);
    }
    private static ObjectNode action(ObjectNode factory,String role,JsonNode effect){
        var event=factory.objectNode().put("kind","ACTION").put("actorId",role).put("process","主API本段实际场景结果，经过见故事正文。").put("visibility","PUBLIC").put("status","COMPLETED");
        event.putArray("effects").add(effect);return event;
    }
}
