package com.bs.narrative.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NarrativeSequenceTest {
 NpcAgentService.NpcDecision say(String id,String text){return new NpcAgentService.NpcDecision(id,"RESPOND",text,"私有心理不得公开",false,false);}
 Map<String,Object> event(int phase,String stage,String text){return Map.of("phase",phase,"npcStage",stage,"kind",stage.equals("BEFORE_NPC")?"ARRIVE":"SCENE_ACTION","text",text);}
 String compose(String prose,List<NpcAgentService.NpcDecision> decisions,List<Integer> phases,List<Map<String,Object>> events){return NarrativeSequence.compose(prose,decisions,List.of("a","b"),Map.of("a","木匠","b","看守"),phases,events);}
 @Test void plansMustPrecedeTheirActualResults(){
  var decisions=List.of(say("a","我先探一探裂缝。"));var events=List.of(event(1,"AFTER_NPC","木匠探过裂缝，确认深约两寸。"));
  String good=compose("[[QUOTE:0]]随后走到闸边。[[EVENT:0]]",decisions,List.of(1),events);
  assertTrue(good.indexOf("先探一探")<good.indexOf("确认深约两寸"));assertTrue(good.contains("随后走到闸边"));
  String bad=compose("他已经检查完。[[EVENT:0]][[QUOTE:0]]",decisions,List.of(1),events);
  assertTrue(bad.indexOf("先探一探")<bad.indexOf("确认深约两寸"));assertFalse(bad.contains("他已经检查完"));assertFalse(bad.contains("私有心理"));
 }
 @Test void arrivalPrecedesTheNewcomersSpeech(){
  String body=compose("[[EVENT:0]][[QUOTE:0]]",List.of(say("b","你们在检查什么？")),List.of(1),List.of(event(1,"BEFORE_NPC","看守从山路来到闸边。")));
  assertTrue(body.indexOf("来到闸边")<body.indexOf("检查什么"));
 }
 @Test void simultaneousActorsCanReorderButNotCrossTheExecutionBoundary(){
  String body=compose("[[QUOTE:1]][[QUOTE:0]][[EVENT:0]]",List.of(say("a","我去检查木槽。"),say("b","我守在岸边。")),List.of(1,1),List.of(event(1,"AFTER_NPC","木匠完成木槽检查。")));
  assertTrue(body.indexOf("我守在岸边")<body.indexOf("我去检查木槽"));assertTrue(body.indexOf("我去检查木槽")<body.indexOf("完成木槽检查"));
 }
 @Test void laterSpeechCanDiscussTheEarlierExecutedResult(){
  String body=compose("[[QUOTE:0]][[EVENT:0]][[QUOTE:1]]",List.of(say("a","我先检查。"),say("a","门扇已经确认卡死，需要另找办法。")),List.of(1,2),List.of(event(1,"AFTER_NPC","木匠完成检查，确认门扇卡死。")));
  assertTrue(body.indexOf("我先检查")<body.indexOf("完成检查"));assertTrue(body.indexOf("完成检查")<body.indexOf("需要另找办法"));
 }
 @Test void missingQuestionOrPhysicalProgressIsRestoredInOrder(){
  String body=compose("[[QUOTE:1]]",List.of(say("a","你为何在这里？"),say("b","我在等家人。")),List.of(1,2),List.of(event(2,"AFTER_NPC","看守走到石阶旁。")));
  assertTrue(body.indexOf("你为何")<body.indexOf("我在等"));assertTrue(body.contains("走到石阶旁"));
 }
 @Test void malformedForeignAndHugeMarkersCannotInjectOrCrash(){
  for(String input:List.of("[[EVENT:999999999999999999999]]","[[EVENT:foreign]]","[[QUOTE:1]]")){
   String body=assertDoesNotThrow(()->compose(input,List.of(),List.of(),List.of(event(1,"AFTER_NPC","检查已经结束。"))));assertTrue(body.contains("检查已经结束"));assertFalse(body.contains("[["));
  }
 }
 @Test void duplicatedMarkersAndRedundantExplanationsDoNotRepeatTheirContent(){
  var decisions=List.of(say("a","能否给我木料？"));var events=List.of(event(1,"AFTER_NPC","木匠仍未接到木料。"));
  String body=compose("[[QUOTE:0]][[QUOTE:0]][[EVENT:0]][[EVENT:0]]",decisions,List.of(1),events);
  assertEquals(1,body.split("能否给我木料",-1).length-1);assertEquals(1,body.split("木匠仍未接到",-1).length-1);
  body=compose("[[QUOTE:0]]这话点明他需要木料。[[EVENT:0]]",decisions,List.of(1),events);
  assertFalse(body.contains("这话点明"));assertTrue(body.contains("能否给我木料"));
 }
 @Test void introductionsAndQuotePunctuationRemainNatural(){
  assertEquals("木匠说道：“我先检查。”",compose("木匠说道：[[QUOTE:0]]。",List.of(say("a","我先检查。")),List.of(1),List.of()));
 }
 @Test void temporalPromptDoesNotExposePrivateThoughts(){
  String prompt=NarrativeSequence.prompt(List.of(say("a","我先检查。")),List.of("a"),Map.of("a","木匠"),List.of(1),List.of(event(1,"AFTER_NPC","木匠检查结束。")));
  assertTrue(prompt.indexOf("[[QUOTE:0]]")<prompt.indexOf("[[EVENT:0]]"));assertTrue(prompt.contains("早于本刻后续执行结果"));assertFalse(prompt.contains("私有心理"));
 }
 @Test void factualFallbackKeepsLaterPlayerActionsAtTheirActualTime(){
  String body=compose("[[EVENT:999]]",List.of(say("a","先探一探。")),List.of(1),List.of(event(1,"AFTER_NPC","木匠发现裂缝。"),Map.of("phase",2,"npcStage","BEFORE_NPC","kind","ACTION","text","你随后递过木料。")));
  assertTrue(body.indexOf("先探一探")<body.indexOf("发现裂缝"));assertTrue(body.indexOf("发现裂缝")<body.indexOf("随后递过木料"));
 }
 @Test void completeLiteralFactsAreBoundWithoutErasingNaturalTransitions(){
  String body=compose("木匠说：“我先检查。”\n\n过了一会儿，木匠完成检查，确认门扇卡死。",List.of(say("a","我先检查。")),List.of(1),List.of(event(1,"AFTER_NPC","木匠完成检查，确认门扇卡死。")));
  assertTrue(body.contains("过了一会儿"));assertTrue(body.indexOf("我先检查")<body.indexOf("完成检查"));assertFalse(body.contains("[["));
 }
}
