package com.bs.narrative.service;
import com.bs.narrative.llm.LLMProvider;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SceneStoryServiceTest {
 final ObjectMapper json=new ObjectMapper();final SceneStoryService service=new SceneStoryService(mock(LLMProvider.class));
 ObjectNode facts()throws Exception{return (ObjectNode)json.readTree("""
 {"playerId":"p","roleDirections":{"a":{"intention":"调查"}},"currentWorld":{"resources":{"p":{"items":[]},"a":{"items":[{"ref":"knife","position":"手中"}]}},"facilities":[{"objectName":"石柱","state":"旧痕"}]},"serverEffectBaselines":{"a":{"activity":"原位","awake":true},"p":{"activity":"等待"}}}
 """);}
 ObjectNode draft()throws Exception{return (ObjectNode)json.readTree("""
 {"story":"甲完成调查。","elapsedSeconds":600,"stop":"RESOLVED","events":[{"kind":"ACTION","actorId":"a","atSeconds":0,"durationSeconds":300,"process":"甲完成调查。","effects":[{"kind":"BODY","actorId":"a","after":{"activity":"已比对"}},{"kind":"FACILITY","objectName":"石柱","after":"清理后的刻痕"}]}]}
 """);}
 @Test void directionProjectsOnlyPrivateIntentFields(){var d=service.direction("{\"intention\":\"调查\",\"speech\":\"不能直接发布\",\"story\":\"不是最终故事\"}");assertFalse(d.has("speech"));assertFalse(d.has("story"));assertThrows(IllegalArgumentException.class,()->service.direction("{\"story\":\"只有故事\"}"));}
 @Test void retainsTheWholeDossierWhenAnObjectArrayMissesItsClosingBracket(){
  var direction=service.direction("""
   {"intention":"等同伴答复","characterTemplate":{"relationships":[{"roleId":"p","attitude":"愿意配合"},"relationshipsNote":null,"actionPlan":{"primaryDirection":"明日继续查线"}},"combatDisposition":{"ifDefeated":"呼救"}}
   """);
  assertEquals("愿意配合",direction.at("/characterTemplate/relationships/0/attitude").asText());
  assertEquals("明日继续查线",direction.at("/characterTemplate/actionPlan/primaryDirection").asText());
  assertTrue(direction.at("/characterTemplate/relationshipsNote").isNull());
  assertEquals("呼救",direction.at("/combatDisposition/ifDefeated").asText());
 }
 @Test void doesNotGuessTruncatedOrAmbiguousRoleReplies(){
  assertThrows(IllegalArgumentException.class,()->service.direction("{\"intention\":\"查线\",\"characterTemplate\":{\"relationships\":[{\"name\":\"甲\"}"));
  assertThrows(IllegalArgumentException.class,()->service.direction("{\"intention\":\"查线\",\"items\":[\"a\",\"b\":1]}"));
  var ordinary=service.direction("{\"intention\":\"查线\",\"characterTemplate\":{\"relationships\":[{\"name\":\"甲\"}],\"personality\":{\"stableTraits\":[\"谨慎\",\"重情\"]}}}");
  assertEquals(1,ordinary.at("/characterTemplate/relationships").size());assertEquals(2,ordinary.at("/characterTemplate/personality/stableTraits").size());
 }
 @Test void aNullOptionalContestDoesNotInterruptPeacefulRoleGeneration(){
  var direction=service.direction("{\"intention\":\"说明印槽用途\",\"contest\":null}");assertFalse(direction.has("contest"));assertEquals("说明印槽用途",direction.path("intention").asText());
  assertThrows(IllegalArgumentException.class,()->service.direction("{\"intention\":\"说明用途\",\"contest\":{\"kind\":\"ATTACK\"}}"));
 }
 @Test void explicitGoalsSurviveFlattenedOrMissingLegacyIntentFields(){
  var core=service.direction("{\"narrativeTier\":\"CORE\",\"motivations\":{\"immediateGoal\":\"把同行范围谈清\"},\"actionPlan\":{\"primaryDirection\":\"说明自己实际去处\"},\"characterTemplate\":{\"values\":{\"coreValues\":[\"如实说明\"]}}}");
  assertEquals("把同行范围谈清",core.path("intention").asText());assertEquals("说明自己实际去处",core.path("actionDirection").asText());assertTrue(core.path("characterTemplate").has("motivations"));assertTrue(core.path("characterTemplate").has("values"));
  var extra=service.direction("{\"narrativeTier\":\"EXTRA\",\"personality\":{\"flaws\":[\"不需要的细节\"]},\"characterTemplate\":{\"immediateConcern\":\"照看山亭\",\"willingHelp\":\"指明避雨位置\",\"boundaries\":\"不替人带路\"}}");
  assertEquals("照看山亭",extra.path("intention").asText());assertEquals("指明避雨位置",extra.path("communicationIntent").asText());assertFalse(extra.path("characterTemplate").has("personality"));
  assertThrows(IllegalArgumentException.class,()->service.direction("{\"characterTemplate\":{\"personality\":{\"stableTraits\":[\"沉默\"]}}}"));
 }
 @Test void newAndLegacyIntentionsRemainCompatibleWithoutDemandingDetailsFromExtras(){
  var extra=service.direction("{\"intention\":\"回答路况\",\"narrativeTier\":\"EXTRA\",\"characterTemplate\":{\"willingHelp\":\"说明附近道路\"}}");
  assertEquals("EXTRA",extra.path("narrativeTier").asText());assertEquals(1,extra.path("characterTemplate").size());
  assertEquals("旧意愿",service.direction("{\"intention\":\"旧意愿\"}").path("intention").asText());
  assertThrows(IllegalArgumentException.class,()->service.direction("{\"intention\":\"问路\",\"narrativeTier\":\"UNKNOWN\"}"));
 }
 @Test void serialStateBindingDoesNotMutateFrozenWorld()throws Exception{var f=facts();var original=f.deepCopy();var d=draft();var second=((ObjectNode)d.path("events").get(0)).deepCopy();second.put("atSeconds",300);((ObjectNode)second.path("effects").get(0).path("after")).put("activity","已结束");((ObjectNode)second.path("effects").get(1)).put("after","最新刻痕");d.withArray("events").add(second);var bound=service.bindAndValidate(d,f);assertEquals("原位",bound.path("events").get(0).path("effects").get(0).path("before").path("activity").asText());assertEquals("已比对",bound.path("events").get(1).path("effects").get(0).path("before").path("activity").asText());assertEquals("清理后的刻痕",bound.path("events").get(1).path("effects").get(1).path("before").asText());assertEquals(original,f);}
 @Test void rejectsUnknownRolesMissingIntentAndInvalidTime()throws Exception{var f=facts();for(String actor:List.of("unknown","p")){var d=draft();((ObjectNode)d.path("events").get(0)).put("actorId",actor.equals("p")?"a":actor);if(actor.equals("p"))f.remove("roleDirections");assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,f));}var d=draft();((ObjectNode)d.path("events").get(0)).put("atSeconds",500);assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts()));}
 @Test void decisionCannotUsePrivateUnheardEvent()throws Exception{var d=draft();d.put("stop","DECISION");d.putObject("decision").put("type","ALLIANCE").put("eventIndex",0).put("question","是否结盟？");((ObjectNode)d.path("events").get(0)).put("visibility","PRIVATE");assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts()));}
 @Test void actualSpeechMustAppearInMainProse()throws Exception{var d=draft();var e=d.withArray("events").addObject().put("kind","SPEECH").put("actorId","a").put("speech","真正说出口的话").put("scope","PUBLIC").put("atSeconds",400);assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts()));d.put("story","甲完成调查，说：“真正说出口的话”");assertDoesNotThrow(()->service.bindAndValidate(d,facts()));}
 @Test void dialogueInterruptedByNarrationIsStillTheSameSpokenText()throws Exception{
  var d=draft();d.put("story","甲说：“前半句。”他放下笔，继续道：“后半句。”");d.withArray("events").addObject().put("kind","SPEECH").put("actorId","a").put("scope","PUBLIC").put("atSeconds",400).put("speech","前半句。后半句。");assertDoesNotThrow(()->service.bindAndValidate(d,facts()));assertFalse(SceneStoryService.containsSpeech(d.path("story").asText(),"原文没有说过的话"));
 }
 @Test void dialogueTypographyDoesNotRejectUnchangedWords()throws Exception{
  var d=draft();d.put("story","“不必勾。”你说，“并到一处看得明白，就够了。”你转向甲，“你也听清了。他认的是笔势一路，不是别的。”");
  d.withArray("events").addObject().put("kind","SPEECH").put("actorId","p").put("scope","PUBLIC").put("atSeconds",400).put("speech","不必勾。并到一处看得明白，就够了。你也听清了，他认的是笔势一路，不是别的。");
  assertDoesNotThrow(()->service.bindAndValidate(d,facts()));assertFalse(SceneStoryService.containsSpeech(d.path("story").asText(),"他认的是同一个人"));
 }
 @Test void eventTimesDetermineOrderAndDecisionKeepsItsSource()throws Exception{
  var d=draft();var action=((ObjectNode)d.path("events").get(0));action.put("atSeconds",20);action.put("durationSeconds",100);
  var e=d.withArray("events").addObject().put("kind","SPEECH").put("actorId","a").put("scope","PUBLIC").put("atSeconds",500).put("speech","是否结盟？");
  d.put("story","调查完毕，他问：“是否结盟？”").put("stop","DECISION");d.putObject("decision").put("type","ALLIANCE").put("question","是否接受？").put("eventIndex",1);
  d.withArray("events").removeAll().add(e).add(action);((ObjectNode)d.path("decision")).put("eventIndex",0);
  var bound=service.bindAndValidate(d,facts());assertEquals("ACTION",bound.path("events").get(0).path("kind").asText());assertEquals(1,bound.path("decision").path("eventIndex").asInt());assertEquals(0,d.path("decision").path("eventIndex").asInt());
 }
 @Test void allEnvelopeProblemsReachTheSameRepair()throws Exception{
  var d=draft();((ObjectNode)d.path("events").get(0)).putArray("effects").add("沿山径离开").addObject().put("kind","UNKNOWN");
  ((ObjectNode)d.path("events").get(0)).put("actorId","p");d.put("stop","DECISION");d.putObject("decision").put("type","COMMITMENT").put("question","是否接受？").put("eventIndex",0);
  var error=assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts()));assertTrue(error.getMessage().contains("effects[0]"));assertTrue(error.getMessage().contains("effects[1]"));assertTrue(error.getMessage().contains("decision.eventIndex"));
 }
 @Test void aDepartureCannotLeaveADialogueTargetInTwoPlaces()throws Exception{
  var d=draft();var move=(ObjectNode)d.path("events").get(0);move.put("actorId","p");move.putArray("effects").addObject().put("kind","MOVE").put("actorId","p").put("fromSceneId","here").put("toSceneId","there");
  d.put("story","你抵达另一个地方。甲问：“是否结盟？”");d.withArray("events").addObject().put("kind","SPEECH").put("actorId","a").put("targetId","p").put("speech","是否结盟？").put("atSeconds",400);
  var error=assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts()));assertTrue(error.getMessage().contains("同一地点"));
 }
 @Test void destinationActionsNeedFreshFactsAfterArrival()throws Exception{
  var d=draft();var move=(ObjectNode)d.path("events").get(0);move.put("actorId","p");move.putArray("effects").addObject().put("kind","MOVE").put("actorId","p").put("fromSceneId","here").put("toSceneId","there");
  var next=move.deepCopy();next.put("atSeconds",400).put("durationSeconds",10).putArray("effects");d.withArray("events").add(next);
  assertTrue(assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts())).getMessage().contains("CONTINUE"));
 }
 @Test void sortingCannotMoveActionsPastAPendingDecision()throws Exception{
  var d=draft();((ObjectNode)d.path("events").get(0)).put("atSeconds",200).put("durationSeconds",50);
  d.withArray("events").addObject().put("kind","SPEECH").put("actorId","a").put("scope","PUBLIC").put("atSeconds",100).put("speech","是否结盟？");
  d.put("story","他问：“是否结盟？”").put("stop","DECISION");d.putObject("decision").put("type","ALLIANCE").put("question","是否接受？").put("eventIndex",1);
  assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts()));
 }
}
