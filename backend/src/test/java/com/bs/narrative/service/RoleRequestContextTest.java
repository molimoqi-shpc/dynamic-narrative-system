package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoleRequestContextTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode operation(){return json.createObjectNode().put("playerRole","player").put("playerInput","我问甲路怎么走，同时私下计划PRIVATE_PLAN，背包里藏着PRIVATE_ITEM").put("inputMode","FREE");}
 ObjectNode person(String id){var own=json.createObjectNode().put("id",id).put("name",id).put("storySituation","等候熟客");own.putArray("ownRecords").addObject().put("source_type","TURN_EVENT").putObject("content").put("text","本人已经收到的OWN_KNOWN消息");return own;}
 String messages(ObjectNode own){return StoryAuthorContext.roleMessages(own).toString();}
 @Test void mixedActionAndPrivatePlansNeverEnterAnyRoleRequestButRemainAvailableToTheAuthor(){
  var operation=operation();var original=operation.deepCopy();
  for(String id:List.of("nearby","remote","newly_introduced")){
   var own=person(id);StoryAuthorContext.bindRoleRequest(own,operation);String request=messages(own);
   assertFalse(request.contains("PRIVATE_PLAN"));assertFalse(request.contains("PRIVATE_ITEM"));assertTrue(request.contains("OWN_KNOWN"));
  }
  assertEquals(original,operation);assertTrue(operation.path("playerInput").asText().contains("PRIVATE_PLAN"));
 }
 @Test void modelBoundaryAlsoRemovesUnboundLegacyRequestsAndUnexpectedGlobalPayloads(){
  var own=person("npc");own.put("narrativeRequest","PRIVATE_PLAN").put("playerInput","PRIVATE_PLAYER_INPUT");own.putObject("privateRoleContexts").put("other","OTHER_PRIVATE_DOSSIER");
  own.putObject("sceneFrameFacts").put("secret","OTHER_WORLD_SECRET");var before=own.deepCopy();String request=messages(own);
  for(String hidden:List.of("PRIVATE_PLAN","PRIVATE_PLAYER_INPUT","OTHER_PRIVATE_DOSSIER","OTHER_WORLD_SECRET"))assertFalse(request.contains(hidden));
  assertTrue(request.contains("OWN_KNOWN"));assertEquals(before,own);
 }
 @Test void explicitTalkGoesOnlyToTheNamedRecipientAsAProposalAndPreservesTheExactWords()throws Exception{
  var operation=operation().put("inputMode","TALK").put("target","recipient");String words="我想问你：\"渡船何时出发？\"";
  operation.putObject("decisions").putObject("player|input").put("target_god_id","recipient").put("speech",words);
  var recipient=person("recipient");StoryAuthorContext.bindRoleRequest(recipient,operation);
  var focus=json.readTree(StoryAuthorContext.roleMessages(recipient).get(2).get("content"));
  assertEquals(words,focus.at("/incomingInteraction/text").asText());assertEquals("PROPOSED_DIRECT_SPEECH",focus.at("/incomingInteraction/delivery").asText());
  assertFalse(messages(recipient).contains("PRIVATE_PLAN"));
  var bystander=person("bystander");StoryAuthorContext.bindRoleRequest(bystander,operation);assertFalse(messages(bystander).contains(words));assertFalse(bystander.has("incomingInteraction"));
 }
 @Test void laterPassagesDoNotReplayAnOldDirectedSpeechOrBroadcastTheirPublishedProse(){
  var operation=operation().put("inputMode","TALK").put("target","recipient").put("sceneFrame",1);
  operation.putObject("decisions").putObject("player|input").put("target_god_id","recipient").put("speech","OLD_DIRECT_SPEECH");
  operation.putArray("scenePassages").add("ANOTHER_ROLE_PRIVATE_PUBLISHED_SCENE");
  var own=person("recipient");StoryAuthorContext.bindRoleRequest(own,operation);String request=messages(own);
  assertFalse(request.contains("OLD_DIRECT_SPEECH"));assertFalse(request.contains("ANOTHER_ROLE_PRIVATE_PUBLISHED_SCENE"));assertTrue(request.contains("CONTINUE_AFTER_COMMITTED"));
 }
 @Test void oldPendingRequestsAreRebuiltBeforeTransportWithoutRewritingTheirSavedOriginal(){
  var own=person("npc").put("narrativeRequest","OLD_PRIVATE_PLAN");
  var saved=json.valueToTree(List.of(Map.of("role","system","content","OLD_UNSAFE_PROMPT"),Map.of("role","user","content",own.toString()),Map.of("role","user","content","OLD_PRIVATE_PLAN")));
  var before=saved.deepCopy();var safe=RoleRequestContext.refresh(saved,operation(),"npc");
  assertFalse(safe.toString().contains("OLD_PRIVATE_PLAN"));assertFalse(safe.toString().contains("OLD_UNSAFE_PROMPT"));assertFalse(safe.toString().contains("PRIVATE_PLAN"));assertTrue(safe.toString().contains("OWN_KNOWN"));assertEquals(before,saved);
  assertEquals(safe,RoleRequestContext.refresh(safe,operation(),"npc"));
 }
 @Test void foreignRoleContextsAndFullPlayerContextAreNotUsedAsRecoveryFallback(){
  var operation=operation();operation.putObject("sceneFrameFacts").putObject("privateRoleContexts").set("npc",person("npc"));
  operation.withObject("sceneFrameFacts").withObject("privateRoleContexts").set("other",person("other").put("storySituation","OTHER_PRIVATE_STATE"));
  operation.putObject("playerContext").put("text","PLAYER_PRIVATE_STATE");
  String request=RoleRequestContext.refresh(json.createArrayNode(),operation,"npc").toString();
  assertTrue(request.contains("OWN_KNOWN"));assertFalse(request.contains("OTHER_PRIVATE_STATE"));assertFalse(request.contains("PLAYER_PRIVATE_STATE"));
 }
 @Test void staleInteractionIsRemovedWhenTheInputModeOrRecipientChanges(){
  var own=person("npc");own.put("requestPrivacyVersion",2).putObject("incomingInteraction").put("targetId","npc").put("text","STALE_DIRECTED_SECRET");
  StoryAuthorContext.bindRoleRequest(own,operation());assertFalse(messages(own).contains("STALE_DIRECTED_SECRET"));
 }
 @Test void aCorruptedFallbackCannotRelabelAnotherRolesPrivateRecords(){
  var operation=operation();operation.putObject("sceneFrameFacts").putObject("privateRoleContexts").set("npc",person("other").put("storySituation","OTHER_PRIVATE_HISTORY"));
  String request=RoleRequestContext.refresh(json.createArrayNode(),operation,"npc").toString();
  assertFalse(request.contains("OTHER_PRIVATE_HISTORY"));assertFalse(request.contains("OWN_KNOWN"));assertFalse(request.contains("PRIVATE_PLAN"));
 }
}
