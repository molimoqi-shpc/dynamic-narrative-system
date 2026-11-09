package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Model-boundary projection of a role's own records, never the player's free-form plan. */
final class RoleRequestContext {
 static final int VERSION=2;
 static final String OWN_DIRECTION="仅依据本人已经保存的身份、经历、所知、当前处境和目的，提供接下来一段事情中的意愿、行动方向、交流范围与条件。未收到的玩家打算和别处的新消息仍未知。";
 private static final Set<String> OWN_FIELDS=Set.of(
  "id","name","emotion","sixStats","sixStatsEstablished","body","items","ownRecords","ownKnowledge",
  "pendingStimuli","lastObservedPeople","knownPeople","currentKnownState","establishedSelf","observedFacilities",
  "goals","initialSceneHistory","sceneStateMeaning","talents","talentMeaning","openingSharedHistory",
  "recordedSceneId","recordedLocation","locationMeaning","storySituation","previousPsychology","previousPortrayal",
  "historyOrder","narrativeActorId","narrativeScope","requestAtStorySeconds","contextPurpose","unfinishedIntentions",
  "agenda","activeWork","ownPendingSpeech","pausedWork","plannedWork","ownItemPlacements","worldSetting",
  "requestPrivacyVersion","incomingInteraction"
 );
 private static final ObjectMapper JSON=new ObjectMapper();
 private RoleRequestContext(){}

 static void bind(ObjectNode own,ObjectNode operation){
  own.put("requestPrivacyVersion",VERSION);own.put("narrativeRequest",OWN_DIRECTION);own.remove("incomingInteraction");
  String role=own.path("id").asText(),player=operation.path("playerRole").asText();
  // Explicit talk is intentionally spoken text, not the free-form action box.
  // A later passage receives its own published experience, never the old proposal again.
  if(!role.isBlank() && "TALK".equals(operation.path("inputMode").asText())
    && role.equals(operation.path("target").asText()) && operation.path("sceneFrame").asInt()==0
    && operation.path("scenePassages").isEmpty()){
   var speech=operation.path("decisions").path(player+"|input");
   if(role.equals(speech.path("target_god_id").asText()) && speech.path("speech").isTextual() && !speech.path("speech").asText().isBlank())
    own.putObject("incomingInteraction").put("speakerId",player).put("targetId",role).put("text",speech.path("speech").asText())
      .put("delivery","PROPOSED_DIRECT_SPEECH").put("meaning","玩家明确选择向你亲自说的原话；是否实际送达及发生何种回应由主创承接现场。这不是已发生记忆，不授予其他私有信息。");
  }
 }

 static ObjectNode project(ObjectNode original){
  var own=original.deepCopy();own.retain(OWN_FIELDS);own.put("narrativeRequest",OWN_DIRECTION);
  if(own.path("requestPrivacyVersion").asInt()!=VERSION
    || !own.path("id").asText().equals(own.at("/incomingInteraction/targetId").asText()))own.remove("incomingInteraction");
  return own;
 }

 /** Upgrade only an unsent/missing role request. Successful peers keep their paid results. */
 static ArrayNode refresh(JsonNode saved,ObjectNode operation,String role){
  ObjectNode own=null;
  if(saved.isArray() && saved.size()>1)try{
   var parsed=JSON.readTree(saved.get(1).path("content").asText());
   if(parsed instanceof ObjectNode object && role.equals(object.path("id").asText()))own=object;
  }catch(com.fasterxml.jackson.core.JsonProcessingException ignored){}
  if(own==null){
   var retained=operation.at("/sceneFrameFacts/privateRoleContexts").path(role);
   own=retained instanceof ObjectNode object && role.equals(object.path("id").asText())?object.deepCopy():JSON.createObjectNode();own.put("id",role);
  }
  StoryAuthorContext.bindRoleRequest(own,operation);
  return JSON.valueToTree(StoryAuthorContext.roleMessages(own));
 }
}
