package com.bs.narrative.service;
import com.bs.narrative.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class StoryPacingServiceTest {
 @Test void naturalStopCannotPublishInventedActionsOrDecisions(){
  for(String raw:List.of("{\"kind\":\"DONE\",\"reason\":\"你点头接受了盟约，并把短刀交给对方。\",\"decisionType\":\"ALLIANCE\",\"requiresInput\":true}","{\"kind\":\"DONE\"}","{\"kind\":\"DONE\",\"reason\":null}","{\"kind\":\"DONE\",\"reason\":{\"text\":\"错误结构\"}}")){
   answer(raw);var next=new StoryPacingService(model).next(input());assertEquals("DONE",next.path("kind").asText());assertEquals(StoryPacingService.NATURAL_STOP,next.path("reason").asText());assertEquals(2,next.size());
  }
 }
 Map<String,Object> withSpeech(String kind,String actor,String target,String speech){var context=new HashMap<String,Object>(input());context.put("playerKnownContext",Map.of("id","player"));context.put("visibleEvents",List.of(Map.of("kind",kind,"actorId",actor,"targetId",target,"speech",speech,"text","你说：“"+speech+"”")));return context;}
 @Test void plannerCannotRepeatAnExplicitPlayerQuestionToTheSamePerson(){
  routine("伤口还疼吗?");var next=new StoryPacingService(model).next(withSpeech("UTTERANCE","player","friend","伤口还疼吗？"));assertEquals("DONE",next.path("kind").asText());verify(model,times(1)).chatForJson(anyList());
 }
 @Test void speechByOthersOrToAnotherPersonDoesNotSuppressANewQuestion(){
  for(var context:List.of(withSpeech("UTTERANCE","friend","player","伤口还疼吗？"),withSpeech("UTTERANCE","player","other","伤口还疼吗？"),withSpeech("UTTERANCE","player","","伤口还疼吗？"),withSpeech("ACTION_RESULT","player","friend","伤口还疼吗？"))){routine("伤口还疼吗？");assertEquals("SPEECH",new StoryPacingService(model).next(context).path("kind").asText());}
 }
 @Test void aDifferentFollowUpQuestionStillContinuesAfterExplicitSpeech(){
  routine("是谁伤了你？");assertEquals("SPEECH",new StoryPacingService(model).next(withSpeech("UTTERANCE","player","friend","伤口还疼吗？")).path("kind").asText());
 }
 LLMProvider model=mock(LLMProvider.class);
 Map<String,Object> input(){return Map.of("originalIntent",List.of("了解朋友伤情"),"knownPeople",List.of(Map.of("id","friend","name","朋友")));}
 void answer(String raw){when(model.chatForJson(anyList())).thenReturn(new LLMResponse(raw,raw,0,0,0,0));}
 void routine(String text){var response=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("kind","SPEECH").put("routineKind","CARE").put("targetId","friend").put("text",text);answer(response.toString());}
 @Test void aRoutineLabelCannotAuthorizePromisesRelationshipChoicesOrGifts(){
  for(String text:List.of("我承诺30秒内把水囊送给你。","我同意与你结盟。","我们结盟吧。","我拒绝和你结盟。","我接受你的盟约。","我也喜欢你。","我愿意和你在一起。","我向你宣战。","我把短刀送给你，愿意结盟吗？")){
   routine(text);var next=new StoryPacingService(model).next(input());assertEquals("DONE",next.path("kind").asText(),text);assertFalse(next.has("text"));assertFalse(next.has("decisionType"));
  }
 }
 @Test void questionsAboutTheOtherPersonsTermsRemainOrdinaryInformation(){
  for(String text:List.of("你为何受伤，是谁伤了你？","你提出的结盟需要什么条件？","你说你承诺不攻击我，是这个意思吗？","我的水囊需要先交给你吗？","我想知道你为何不愿结盟。")){routine(text);assertEquals("SPEECH",new StoryPacingService(model).next(input()).path("kind").asText(),text);}
 }
 @Test void onlyChangingTerminalPunctuationDoesNotRepeatTheSameQuestion(){
  var context=new HashMap<String,Object>(input());context.put("routineHistory",List.of(Map.of("kind","SPEECH","targetId","friend","text","伤口还疼吗？")));for(String text:List.of("伤口还疼吗?","伤口还疼吗。","伤口还疼吗？！ ")){routine(text);assertEquals("DONE",new StoryPacingService(model).next(context).path("kind").asText());}
 }
 @Test void naturalInjuryQuestionUsesMainModelWithoutInventingAFriendReply(){answer("{\"kind\":\"SPEECH\",\"routineKind\":\"CARE\",\"targetId\":\"friend\",\"text\":\"你为何受伤，是谁伤了你？\"}");var result=new StoryPacingService(model).next(input());assertEquals("SPEECH",result.path("kind").asText());assertFalse(result.has("npcReply"));verify(model,times(1)).chatForJson(anyList());}
 @Test void unknownTargetsAndNonRoutineSpeechCategoriesAreRejected(){for(String raw:List.of("{\"kind\":\"SPEECH\",\"routineKind\":\"CARE\",\"targetId\":\"hidden\",\"text\":\"怎么受伤的？\"}","{\"kind\":\"SPEECH\",\"routineKind\":\"ALLIANCE\",\"targetId\":\"friend\",\"text\":\"我答应结盟。\"}")){answer(raw);assertThrows(IllegalArgumentException.class,()->new StoryPacingService(model).next(input()));}}
 @Test void relationshipChoicesAreReturnedForTheHumanInsteadOfAnAutomaticReply(){var facts=new HashMap<String,Object>(input());facts.put("playerKnownContext",Map.of("id","player"));facts.put("visibleEvents",List.of(Map.of("id","proposal","kind","UTTERANCE","actorId","friend","text","朋友向你表达了自己的立场。")));for(String type:List.of("ALLIANCE","HOSTILITY","ROMANCE")){answer("{\"kind\":\"DECISION\",\"decisionType\":\""+type+"\",\"question\":\"你准备如何回应？\",\"evidenceEventId\":\"proposal\"}");assertEquals("DECISION",new StoryPacingService(model).next(facts).path("kind").asText());}}
 @Test void ordinaryInformationIsNotAValidImportantDecisionCategory(){answer("{\"kind\":\"DECISION\",\"decisionType\":\"INFORMATION\",\"question\":\"朋友为何受伤？\"}");assertThrows(IllegalArgumentException.class,()->new StoryPacingService(model).next(input()));}
 @Test void aDecisionCannotBeInventedWithoutAnActualVisibleEvent(){
  answer("{\"kind\":\"DECISION\",\"decisionType\":\"ALLIANCE\",\"question\":\"你同意结盟吗？\",\"evidenceEventId\":\"missing\"}");assertThrows(IllegalArgumentException.class,()->new StoryPacingService(model).next(input()));
 }
 @Test void selfSpeechOtherPeoplesInvitationsAndRoutineWaitsCannotBeUsedAsPlayerProposals(){
  for(var source:List.of(Map.of("kind","UTTERANCE","actorId","player"),Map.of("kind","UTTERANCE","actorId","friend","addressedElsewhere",true),Map.of("kind","WAIT_END","actorId","friend"),Map.of("kind","ACTION_RESULT","actorId","friend"))){
   var event=new HashMap<String,Object>(source);event.put("id","event");event.put("text","这不是向玩家提出的邀请。");var facts=new HashMap<String,Object>(input());facts.put("playerKnownContext",Map.of("id","player"));facts.put("visibleEvents",List.of(event));answer("{\"kind\":\"DECISION\",\"decisionType\":\"ALLIANCE\",\"question\":\"同意结盟吗？\",\"evidenceEventId\":\"event\"}");assertThrows(IllegalArgumentException.class,()->new StoryPacingService(model).next(facts));
  }
 }
 @Test void aVisibleHazardCanBeAnImportantRiskEvenWhenThereIsNoNpcProposer(){
  var facts=new HashMap<String,Object>(input());facts.put("playerKnownContext",Map.of("id","player"));facts.put("visibleEvents",List.of(Map.of("id","hazard","kind","ACTION_RESULT","actorId","player","text","你看到前方桥面已经断裂。")));answer("{\"kind\":\"DECISION\",\"decisionType\":\"RISK\",\"question\":\"是否冒险前进？\",\"evidenceEventId\":\"hazard\",\"counterpartId\":\"invented\"}");var next=new StoryPacingService(model).next(facts);assertEquals("",next.path("counterpartId").asText());assertEquals("你看到前方桥面已经断裂。",next.path("basisText").asText());
 }
 @Test void repeatedQuestionStopsEvenIfPlannerChangesWhitespace(){
  var context=new HashMap<String,Object>(input());context.put("routineHistory",List.of(Map.of("kind","SPEECH","targetId","friend","text","伤口还疼吗？")));
  answer("{\"kind\":\"SPEECH\",\"routineKind\":\"CARE\",\"targetId\":\"friend\",\"text\":\" 伤口还 疼吗？ \"}");
  assertEquals("DONE",new StoryPacingService(model).next(context).path("kind").asText());
 }
 @Test void sameQuestionToAnotherPersonRemainsAllowed(){
  var context=new HashMap<String,Object>(input());context.put("routineHistory",List.of(Map.of("kind","SPEECH","targetId","other","text","伤口还疼吗？")));
  answer("{\"kind\":\"SPEECH\",\"routineKind\":\"CARE\",\"targetId\":\"friend\",\"text\":\"伤口还疼吗？\"}");
  assertEquals("SPEECH",new StoryPacingService(model).next(context).path("kind").asText());
 }
}
