package com.bs.narrative;
import com.bs.narrative.service.*;import com.bs.narrative.domain.GameScene;import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class ActionExecutionContractTest {
 final ObjectMapper json=new ObjectMapper();
 JsonNode result(String text,String status,String reason,List<Map<String,String>> resources,List<Map<String,String>> states){
  return json.valueToTree(Map.of("executionVersion",2,"sceneChanges",states,"events",List.of(Map.of("actorId","worker","text",text,"uses",List.of(),"nonItemUses",resources,"execution",Map.of("task","检查木闸","status",status,"basis",text,"stopReason",reason)))));
 }
 Map<String,String> resource(String kind,String name,String basis){return Map.of("kind",kind,"name",name,"basis",basis);}
 GameScene scene(){var s=new GameScene();s.setSceneSummary("木闸在岸边，检查前仍关闭。");return s;}
 void validate(JsonNode n){ActionExecutionContract.validate(n,scene(),List.of(),List.of("木匠"),List.of("维修杆"));}
 @Test void anatomyIdentificationDoesNotDependOnTheVerbThatFollowsIt(){
  for(String text:List.of("用掌缘比了比豁口到今痕上沿的距离，检查已结束。","用指节丈量边缝，检查已结束。","用肩膀稳住自身，检查已结束。")){
   String name=text.contains("掌缘")?"掌缘":text.contains("指节")?"指节":"肩膀";
   assertDoesNotThrow(()->validate(result(text,"COMPLETED","",List.of(resource("BODY",name,text)),List.of())));
  }
 }
 @Test void similarlyNamedToolsCannotBecomeBodyParts(){
  for(String text:List.of("用指甲刀切开障碍。","用牙签挑开障碍。","用肩甲抵住门扇。","用指节铁箍固定闸轴。\n工作已完成。")){
   String name=text.contains("指甲")?"指甲":text.contains("牙")?"牙":text.contains("肩")?"肩":"指节";
   assertThrows(IllegalArgumentException.class,()->validate(result(text,"COMPLETED","",List.of(resource("BODY",name,text)),List.of())));
  }
 }
 @Test void facilityReferencesMustHaveAnActualFinalStateEvenWhenUnchanged(){
  String text="木匠完成检查，木闸仍关闭。";var n=result(text,"COMPLETED","",List.of(resource("SCENE","木闸",text)),List.of());
  assertThrows(IllegalArgumentException.class,()->validate(n));
  var state=Map.of("objectName","木闸","state","仍关闭","basis",text);
  assertDoesNotThrow(()->validate(result(text,"COMPLETED","",List.of(resource("SCENE","木闸",text)),List.of(state))));
 }
 @Test void ongoingMovementCannotPretendToBeATerminalCheckpoint(){
  String text="木匠移出半寸，工作还没结束。";assertThrows(IllegalArgumentException.class,()->validate(result(text,"ONGOING","移出半寸",List.of(),List.of())));
  String actual="木匠守在原地，正在等待同伴返回检查结果。";var n=result(actual,"ONGOING","正在等待同伴返回检查结果",List.of(),List.of());
  assertDoesNotThrow(()->validate(n));assertFalse(ActionExecutionContract.reachedCheckpoint(n));
  String blocked="木匠检查后确认闸轴锈死，现有维修杆无法转动闸轴。";var blockedResult=result(blocked,"BLOCKED","现有维修杆无法转动闸轴",List.of(),List.of());
  assertTrue(ActionExecutionContract.reachedCheckpoint(blockedResult));
 }
 @Test void fabricatedResourcesAndFutureStatesCannotBypassClassification(){
  String text="木匠用密室机关探查，检查已结束。";
  assertThrows(IllegalArgumentException.class,()->validate(result(text,"COMPLETED","",List.of(resource("SCENE","密室机关",text)),List.of())));
  String tool="木匠用维修杆完成检查。";assertThrows(IllegalArgumentException.class,()->validate(result(tool,"COMPLETED","",List.of(resource("BODY","维修杆",tool)),List.of())));
 }
 @Test void providerInProgressSynonymRemainsUnfinishedAndUnknownStatusStillFails(){
  String text="木匠守在原地，正在等待同伴返回检查结果。";
  var n=result(text," in_progress ","正在等待同伴返回检查结果",List.of(),List.of());
  ActionExecutionContract.bindEvidence(n);assertDoesNotThrow(()->validate(n));
  assertEquals("ONGOING",n.path("events").get(0).path("execution").path("status").asText());
  assertFalse(ActionExecutionContract.reachedCheckpoint(n));
  var unknown=result(text,"ASSUMED_SUCCESS","正在等待同伴返回检查结果",List.of(),List.of());
  ActionExecutionContract.bindEvidence(unknown);assertThrows(IllegalArgumentException.class,()->validate(unknown));
 }
 @Test void legacyResultsCannotSmuggleUnvalidatedResourceDeclarations(){
  var n=json.valueToTree(Map.of("events",List.of(Map.of("text","用铁箍探查。","nonItemUses",List.of(resource("BODY","铁箍","用铁箍探查。"))))));
  assertThrows(IllegalArgumentException.class,()->validate(n));
 }

 @Test void serverBindsEvidenceWithoutAcceptingResourcesAbsentFromTheActualEvent(){
  String text="木匠用掌缘比量木闸，木闸仍关闭，检查已结束。";var n=result(text,"COMPLETED","",List.of(Map.of("kind","BODY","name","掌缘"),Map.of("kind","SCENE","name","木闸")),List.of(Map.of("objectName","木闸","state","仍关闭")));
  ActionExecutionContract.bindEvidence(n);assertDoesNotThrow(()->validate(n));assertEquals(text,n.path("sceneChanges").get(0).path("basis").asText());
  var invented=result(text,"COMPLETED","",List.of(Map.of("kind","BODY","name","牙签")),List.of());ActionExecutionContract.bindEvidence(invented);assertThrows(IllegalArgumentException.class,()->validate(invented));
 }

 @Test void anatomyGrammarCoversOrdinaryFormsWithoutAcceptingHandheldCompounds(){
  for(String name:List.of("双眼","眼睛","手掌","耳朵","右手","前臂","脚跟","左膝","右掌掌缘","腿脚","左臂内侧"))assertTrue(ActionExecutionContract.anatomy(name),name);
  for(String name:List.of("指甲刀","牙签","肩甲","手锯","脚铐","铁箍","手中小刀"))assertFalse(ActionExecutionContract.anatomy(name),name);
  assertTrue(ActionExecutionContract.bodyPhrase("掌缘比量旧痕的距离"));assertTrue(ActionExecutionContract.bodyPhrase("手掌丈量凹槽宽度"));
  assertFalse(ActionExecutionContract.bodyPhrase("手中小刀切开闸缝"));assertFalse(ActionExecutionContract.bodyPhrase("手锯锯开木料"));
  assertFalse(ActionExecutionContract.bodyPhrase("右掌掌缘铁箍固定"));assertFalse(ActionExecutionContract.bodyPhrase("左臂护套压住闸门"));
 }
 @Test void historicalSnapshotCopiesDoNotBecomeNewChangesAndDoNotEraseExistingState(){
  var n=result("工匠用掌缘检查闸板，检查结束。","COMPLETED","",List.of(Map.of("kind","SCENE","name","木闸")),List.of(Map.of("objectName","落叶","state","过去清薄的浮叶")));
  var current=List.<Map<String,Object>>of(Map.of("objectName","落叶","state","上次实际清薄","updatedAtTick",5));
  ActionExecutionContract.retainCurrentSnapshots(n,current);ActionExecutionContract.bindEvidence(n);
  assertEquals(1,n.path("sceneChanges").size());assertEquals("木闸",n.path("sceneChanges").get(0).path("objectName").asText());assertEquals(5,current.get(0).get("updatedAtTick"));
  var invented=result("工匠检查结束。","COMPLETED","",List.of(),List.of(Map.of("objectName","密室机关","state","已开启")));
  ActionExecutionContract.retainCurrentSnapshots(invented,current);assertEquals(1,invented.path("sceneChanges").size());
 }
 @Test void missingOrParaphrasedSnapshotUsesTheLastActualEventWithoutInventingState(){
  String text="木匠用掌缘检查闸板的边缘，闸板仍关闭，检查已经结束。";
  for(var states:List.of(List.<Map<String,String>>of(),List.of(Map.of("objectName","木闸","state","关闭状态未改变")))){
   var n=result(text,"COMPLETED","",List.of(Map.of("kind","SCENE","name","木闸"),Map.of("kind","BODY","name","掌缘")),states);
   ActionExecutionContract.bindEvidence(n);assertDoesNotThrow(()->validate(n));
   assertEquals(text,n.path("sceneChanges").get(0).path("state").asText());
   assertEquals(text,n.path("sceneChanges").get(0).path("basis").asText());
  }
  var invalid=result(text,"COMPLETED","",List.of(Map.of("kind","SCENE","name","密室机关")),List.of());
  ActionExecutionContract.bindEvidence(invalid);assertThrows(IllegalArgumentException.class,()->validate(invalid));
 }
 @Test void speechSanitizingKeepsSeparatePhysicalClausesAndNeverLeaksQuotedCommas(){
  var n=result("弟子向前半步，朝同门低问：‘为何如此，是否改期？’，随后退回队列。","COMPLETED","",List.of(),List.of());
  ActionExecutionContract.discardInventedSpeech(n);
  String text=n.path("events").get(0).path("text").asText();
  assertTrue(text.contains("向前半步"));assertTrue(text.contains("退回队列"));
  assertFalse(text.contains("为何"));assertFalse(text.contains("改期"));
 }
 @Test void hearsayAndMovementPrefixesSurviveButInventedQuotedAndIndirectAnswersDoNot(){
  var n=result("厉沧海听岳青霜开口探问，顺势上前半步，停在黑幡旁。周元退到队尾，低声向弟子打听旧事；对方推说不知。他起身并说：‘你快去，给我拿刀。’，随后退回岸上。","COMPLETED","",List.of(),List.of());
  ActionExecutionContract.discardInventedSpeech(n);String text=n.path("events").get(0).path("text").asText();
  assertTrue(text.contains("顺势上前半步"));assertTrue(text.contains("退到队尾"));assertTrue(text.contains("他起身"));assertTrue(text.contains("退回岸上"));
  assertFalse(text.contains("打听旧事"));assertFalse(text.contains("推说不知"));assertFalse(text.contains("拿刀"));
 }
}
