package com.bs.narrative;
import com.bs.narrative.service.ConditionWaitService;
import com.bs.narrative.simulation.PerceivedWait;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConditionWaitTest {
 final ObjectMapper json=new ObjectMapper();final ConditionWaitService waits=new ConditionWaitService();
 JsonNode node(Object value){return json.valueToTree(value);}
 JsonNode condition(){return node(Map.of("id","wait","type","ACTION_COMPLETED","actorId","a","attemptContains","检查绳结","baseline",0));}
 JsonNode event(String kind,String attempt,String status){return node(Map.of("id","event","actorId","a","kind",kind,"attempt",attempt,"status",status,"endSeconds",3));}
 @Test void capturedLeadingSubjectContextKeepsTheWaitAndConditionalRestrictions()throws Exception{
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/leading-subject-authorization.json"));var normalized=waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied,Map.of("npc_167bd2732d7a436f9b2f4e369bd2","陆青梧"));assertEquals(3,normalized.path("steps").get(0).path("seconds").asInt());assertEquals("u3",normalized.path("steps").get(3).path("conditionUnitId").asText());assertTrue(normalized.path("steps").get(3).path("text").asText().contains("不碰或交出任何物品"));
 }
 @Test void nonSpeechContextCannotConcealNegationMethodsOrAdditionalActions(){
  for(String original:List.of("我不执行，查看工具","我先原地不动，只查看工具","我先查看工具，然后退一步")){var packet=node(Map.of("steps",List.of(Map.of("type","ACTION","text","查看工具","contextText",original))));assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(original,packet));}
 }
 @Test void capturedConditionalPrefixMayBeSharedWithItsImmediatelyLinkedAction()throws Exception{
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/conditional-prefix-authorization.json"));var normalized=waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied,Map.of("npc_167bd2732d7a436f9b2f4e369bd2","陆青梧"));var action=normalized.path("steps").get(3);assertTrue(action.path("text").asText().startsWith("听完后，"));assertTrue(action.path("text").asText().contains("不碰或交出任何物品"));assertEquals("u3",action.path("conditionUnitId").asText());assertTrue(action.path("whenWaitSatisfied").asBoolean());
 }
 @Test void sharedConditionalPrefixCannotReplayEarlierWorkOrDropItsDependency()throws Exception{
  for(String mutation:List.of("immediate","wrong-parent","earlier-work")){var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/conditional-prefix-authorization.json"));var action=(com.fasterxml.jackson.databind.node.ObjectNode)supplied.path("units").get(3);if(mutation.equals("immediate"))action.put("authorization","EXECUTE");else if(mutation.equals("wrong-parent"))action.put("conditionUnitId","u1");else action.put("text",supplied.path("rawText").asText());assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied,Map.of("npc_167bd2732d7a436f9b2f4e369bd2","陆青梧")));}
 }
 @Test void sequenceBeforeSubjectAndWhitespaceMayIntroduceTheSameKnownSpeech(){
  for(String prefix:List.of("然后我对陆青梧说：","然后 我 对 陆青梧 说：","我随后向陆青梧问：")){String original=prefix+"“你好。”";var packet=node(Map.of("steps",List.of(Map.of("type","SPEECH","text","你好。","targetId","a","contextText",original))));var normalized=waits.normalizeAuthorization(original,packet,Map.of("a","陆青梧"));assertEquals("你好。",normalized.path("steps").get(0).path("text").asText());}
 }
 @Test void aSelfContainedWaitMayUseAnEmptyOptionalParentReference()throws Exception{
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/await-heard-authorization.json"));((com.fasterxml.jackson.databind.node.ObjectNode)supplied.path("units").get(2)).put("conditionUnitId","");var normalized=waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied);assertEquals("EXECUTE",normalized.path("steps").get(2).path("authorization").asText());assertFalse(normalized.path("steps").get(2).has("conditionUnitId"));assertTrue(normalized.path("steps").get(3).path("whenWaitSatisfied").asBoolean());
 }
 @Test void emptyOrUnknownParentsCannotAuthorizeADependentAction()throws Exception{
  for(String parent:List.of(""," ","missing")){var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/await-heard-authorization.json"));((com.fasterxml.jackson.databind.node.ObjectNode)supplied.path("units").get(3)).put("conditionUnitId",parent);assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied));}
 }
 @Test void capturedQuotedPromiseRetainsSpeechAndEveryActionRestriction()throws Exception{
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/quoted-promise-authorization.json"));var normalized=waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied,Map.of("npc_167bd2732d7a436f9b2f4e369bd2","陆青梧"));
  assertEquals(4,normalized.path("steps").size());assertEquals("我承诺30秒内不会攻击你。你刚才说的踩实土路，具体看哪一处最容易辨认？",normalized.path("steps").get(1).path("text").asText());assertEquals(3,normalized.path("steps").get(0).path("seconds").asInt());assertEquals("HEARD_SPEECH",normalized.path("steps").get(2).path("condition").path("type").asText());assertTrue(normalized.path("steps").get(3).path("whenWaitSatisfied").asBoolean());assertTrue(normalized.path("steps").get(3).path("text").asText().contains("不碰或交出任何物品"));
 }
 @Test void speechContextCannotConcealNegationConditionsOrFurtherActions(){
  for(String context:List.of("我不对陆青梧说：“你好。”","如果她同意，我对陆青梧说：“你好。”","我对陆青梧说：“你好。”但不移动。","我对陆青梧说：“你好。”然后退一步。")){
   var packet=node(Map.of("steps",List.of(Map.of("type","SPEECH","text","你好。","targetId","a","contextText",context))));assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(context,packet,Map.of("a","陆青梧")));
  }
 }
 @Test void speechContextMustMatchTheKnownNameOfItsSelectedAddressee(){
  String context="再对陆青梧说：“你好。”";var packet=node(Map.of("steps",List.of(Map.of("type","SPEECH","text","你好。","targetId","a","contextText",context))));assertDoesNotThrow(()->waits.normalizeAuthorization(context,packet,Map.of("a","陆青梧")));assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(context,packet,Map.of("a","沈砚舟")));assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(context,packet));
 }
 @Test void capturedPublicSpeechDoesNotRequireInventingASingleAddressee()throws Exception{
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/public-group-authorization.json"));var normalized=waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied,Map.of("a","陆青梧"));assertEquals(1,normalized.path("steps").size());assertEquals("SPEECH",normalized.path("steps").get(0).path("type").asText());assertEquals("",normalized.path("steps").get(0).path("targetId").asText());assertEquals(supplied.path("units").get(0).path("text"),normalized.path("steps").get(0).path("text"));
 }
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"我说：","我向大家说：","我对在场众人说：","随后我向在场的所有人问道：","我对周围的人说："})
 void publicSpeechIntroducersPreserveTheOriginalUtterance(String prefix){String context=prefix+"“你好。”";var packet=node(Map.of("steps",List.of(Map.of("type","SPEECH","text","你好。","targetId","","contextText",context))));assertDoesNotThrow(()->waits.normalizeAuthorization(context,packet));}
 @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"我不向大家说：“你好。”","如果有人同意，我向大家说：“你好。”","我对大家说：“你好。”然后退一步。","我向陆青梧说：“你好。”","我只在心里向众人说：“你好。”"})
 void publicSpeechStillCannotSwallowConditionsActionsOrAPrivateAddressee(String context){var packet=node(Map.of("steps",List.of(Map.of("type","SPEECH","text","你好。","targetId","","contextText",context))));assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(context,packet,Map.of("a","陆青梧")));}
 @Test void realAwaitHeardPacketRetainsWaitAndNullOptionalFieldsDoNotBind()throws Exception{
  var supplied=json.readTree(getClass().getResourceAsStream("/fixtures/await-heard-authorization.json"));var normalized=waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied);
  assertEquals(4,normalized.path("steps").size());var waiting=normalized.path("steps").get(2);assertEquals("WAIT",waiting.path("type").asText());assertFalse(waiting.path("whenWaitSatisfied").asBoolean());assertFalse(waiting.has("seconds"));assertFalse(waiting.path("condition").has("equals"));
  var later=normalized.path("steps").get(3);assertEquals("u3",later.path("conditionUnitId").asText());assertTrue(later.path("whenWaitSatisfied").asBoolean());
 }
 @Test void aWaitWithItsOwnConditionCannotHideABrokenParentReference()throws Exception{
  var supplied=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(getClass().getResourceAsStream("/fixtures/await-heard-authorization.json"));((com.fasterxml.jackson.databind.node.ObjectNode)supplied.path("units").get(2)).put("conditionUnitId","missing");
  assertThrows(IllegalArgumentException.class,()->waits.normalizeAuthorization(supplied.path("rawText").asText(),supplied));
 }
 @Test void absentOptionalActionMarkersMayBeJsonNullWithoutLosingConstraints()throws Exception{
  String input="我原地查看石柱，不移动。";var supplied=json.readTree("{\"steps\":[{\"type\":\"ACTION\",\"text\":\""+input+"\",\"localMovement\":null,\"routineConversation\":null,\"resumeWorkRef\":null,\"contest\":null,\"exploration\":null}]}");
  var normalized=waits.normalizeAuthorization(input,supplied);assertEquals(input,normalized.path("steps").get(0).path("text").asText());for(String key:List.of("localMovement","routineConversation","resumeWorkRef","contest","exploration"))assertFalse(normalized.path("steps").get(0).has(key));
 }
 @Test void resumeReferenceCannotHideSpeechOrAddCompetingActions(){
  var step=new LinkedHashMap<String,Object>(Map.of("type","ACTION","text","继续查看","resumeWorkRef","saved-ref"));assertDoesNotThrow(()->waits.validateAuthorization("继续查看",node(Map.of("steps",List.of(step)))));
  for(var extra:List.of(Map.of("type","SPEECH"),Map.of("resumeWorkRef",true),Map.of("resumeWorkRef",""),Map.of("routineConversation",true),Map.of("contest",Map.of("kind","ATTACK")),Map.of("exploration",Map.of("direction","NORTH")))){
   var invalid=new LinkedHashMap<String,Object>(step);invalid.putAll(extra);assertThrows(IllegalArgumentException.class,()->waits.validateAuthorization("继续查看",node(Map.of("steps",List.of(invalid)))));
  }
 }
 @Test void routineConversationMarkerCannotConsumeMovementOrCombatAuthority(){
  var step=new LinkedHashMap<String,Object>(Map.of("type","ACTION","text","了解路况","routineConversation",true));
  assertDoesNotThrow(()->waits.validateAuthorization("了解路况",node(Map.of("steps",List.of(step)))));
  for(var extra:List.of(Map.of("localMovement",true),Map.of("contest",Map.of("kind","ATTACK")),Map.of("exploration",Map.of("direction","NORTH")),Map.of("type","SPEECH"),Map.of("routineConversation","true"))){
   var invalid=new LinkedHashMap<String,Object>(step);invalid.putAll(extra);assertThrows(IllegalArgumentException.class,()->waits.validateAuthorization("了解路况",node(Map.of("steps",List.of(invalid)))));
  }
 }
 @Test void declarationPreparationAndDifferentWorkDoNotCompleteTheSelectedWait(){
  for(var e:List.of(event("UTTERANCE","检查绳结","COMPLETED"),event("ACTION_RESULT","检查绳结","ONGOING"),event("ACTION_RESULT","整理衣角","COMPLETED")))assertEquals(PerceivedWait.Status.PENDING,waits.evaluate("player",condition(),3,10,node(List.of(e))).status());
  assertEquals(PerceivedWait.Status.SATISFIED,waits.evaluate("player",condition(),3,10,node(List.of(event("ACTION_RESULT","检查绳结","COMPLETED")))).status());
 }
 @Test void wrongRoleOldEventAndFutureDeliveryDoNotTrigger(){
  var e=(com.fasterxml.jackson.databind.node.ObjectNode)event("ACTION_RESULT","检查绳结","COMPLETED");e.put("actorId","b");assertEquals(PerceivedWait.Status.PENDING,waits.evaluate("player",condition(),3,10,node(List.of(e))).status());
  e.put("actorId","a");assertEquals(PerceivedWait.Status.PENDING,waits.evaluate("player",condition(),2,10,node(List.of(e))).status());
  var c=(com.fasterxml.jackson.databind.node.ObjectNode)condition();c.put("baseline",1);assertEquals(PerceivedWait.Status.PENDING,waits.evaluate("player",c,3,10,node(List.of(e))).status());
 }
 @Test void semanticEvidenceMustReferenceTheCurrentAcceptedResult(){
  var c=node(Map.of("id","wait","type","SEMANTIC_EVENT","actorId","a"));
  assertThrows(IllegalArgumentException.class,()->waits.executionEvidence(c,"a",node(Map.of("status","COMPLETED","waitMatches",List.of(Map.of("conditionId","wrong","outcome","SATISFIED","basis","ACTUAL_RESULT"))))));
  assertThrows(IllegalArgumentException.class,()->waits.executionEvidence(c,"a",node(Map.of("status","COMPLETED","waitMatches",List.of(Map.of("conditionId","wait","outcome","SATISFIED","basis","FUTURE_PLAN"))))));
 }
 @Test void conditionalFollowUpCannotLoseItsOriginalCondition(){
  var a=node(Map.of("steps",List.of(Map.of("type","WAIT","text","等甲检查完成","condition",Map.of("type","SEMANTIC_EVENT","text","甲检查完成")),Map.of("type","ACTION","text","解绳","whenWaitSatisfied",true,"conditionText","别人赠送短刀"))));
  assertThrows(IllegalArgumentException.class,()->waits.validateAuthorization("等甲检查完成，再解绳",a));
 }
}
