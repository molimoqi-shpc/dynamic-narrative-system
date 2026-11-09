package com.bs.narrative;
import com.bs.narrative.service.*;import com.fasterxml.jackson.databind.*;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class SceneObjectRegistryTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void anExistingReferenceUnifiesNaturalPartsAndMissingSnapshots(){
  String text="工匠检查闸板，闸板仍关闭。";
  var result=json.valueToTree(Map.of("executionVersion",2,"events",List.of(Map.of("text",text,"nonItemUses",List.of(Map.of("kind","SCENE","name","闸板","ref","scene_gate")),"execution",Map.of("task","检查木闸","status","COMPLETED"))),"sceneChanges",List.of(Map.of("objectName","闸板","state","仍关闭"))));
  SceneObjectRegistryService.bind(result,List.of(Map.of("ref","scene_gate","name","木闸","source","木闸仍关闭。")));ActionExecutionContract.bindEvidence(result);
  assertEquals("木闸",result.path("events").get(0).path("nonItemUses").get(0).path("name").asText());assertEquals("木闸",result.path("sceneChanges").get(0).path("objectName").asText());
  var scene=new com.bs.narrative.domain.GameScene();scene.setSceneSummary("木闸仍关闭。");assertDoesNotThrow(()->ActionExecutionContract.validate(result,scene,List.of(),List.of(),List.of()));
 }
 @Test void unknownIdentifiersNeverCreateNewObjects(){
  var result=json.valueToTree(Map.of("executionVersion",2,"events",List.of(Map.of("text","工匠检查密室。","nonItemUses",List.of(Map.of("kind","SCENE","name","密室","ref","scene_fake"))))));
  assertThrows(IllegalArgumentException.class,()->SceneObjectRegistryService.bind(result,List.of(Map.of("ref","scene_gate","name","木闸"))));
 }
 @Test void anExactResultAnchorBindsAKnownObjectEvenWhenProseUsesItsNaturalName(){
  String text="腐叶团仍卡在原地，检查已经结束。";
  var result=json.valueToTree(Map.of("executionVersion",2,"events",List.of(Map.of("text",text,"nonItemUses",List.of(),"execution",Map.of("task","检查落叶","status","BLOCKED"))),"sceneChanges",List.of(Map.of("objectName","落叶","state",text,"basis",text))));
  SceneObjectRegistryService.bind(result,List.of(Map.of("ref","scene_leaves","name","落叶")));ActionExecutionContract.bindEvidence(result);
  assertTrue(ActionExecutionContract.mentionsFacility(result.path("events").get(0),"落叶"));assertEquals(text,result.path("sceneChanges").get(0).path("basis").asText());
 }
 @Test void inventedSpeechIsRemovedWithoutCreatingAnotherUtteranceOrRemovingActualBodyResults(){
  String text="工匠用掌缘检查木闸，检查结束。随后对同行人说闸板仍关闭。他抬头向同行人开口，请借一把刀。工匠退回岸上。";
  var result=json.valueToTree(Map.of("events",List.of(Map.of("text",text,"view",Map.of("activity","工匠说完退回岸上")))));
  ActionExecutionContract.discardInventedSpeech(result);String actual=result.path("events").get(0).path("text").asText();
  assertTrue(actual.contains("用掌缘检查木闸，检查结束"));assertTrue(actual.contains("工匠退回岸上"));assertFalse(actual.contains("说"));assertFalse(actual.contains("闸板仍关闭"));
  assertFalse(actual.contains("请借"));
 }
 @Test void indexingRejectsInventedNamesAndPortableItemsAndReusesVerifiedCache()throws Exception{
  var jdbc=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);var model=org.mockito.Mockito.mock(com.bs.narrative.llm.LLMProvider.class);
  var service=new SceneObjectRegistryService(jdbc,model);org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true);
  var scene=new com.bs.narrative.domain.GameScene();scene.setSceneId("gate");scene.setSceneSummary("木闸仍关闭。木匠持有小刀。");
  String draft="{\"objects\":[{\"name\":\"木闸\",\"source\":\"木闸仍关闭。\"},{\"name\":\"小刀\",\"source\":\"木匠持有小刀。\"},{\"name\":\"密室\",\"source\":\"木闸仍关闭。\"}]}";
  org.mockito.Mockito.when(model.chatForJson(org.mockito.ArgumentMatchers.anyList())).thenReturn(new com.bs.narrative.llm.LLMResponse(draft,draft,0,0,0,0));
  var objects=service.objects("s",scene,"p",List.of("木匠"),List.of("小刀"));assertEquals(1,objects.size());assertEquals("木闸",objects.get(0).get("name"));
  org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(String.class),org.mockito.ArgumentMatchers.eq("s"),org.mockito.ArgumentMatchers.eq("scene_objects:gate"))).thenReturn(List.of(json.writeValueAsString(objects)));
  assertEquals(objects,service.objects("s",scene,"p",List.of("木匠"),List.of("小刀")));org.mockito.Mockito.verify(model,org.mockito.Mockito.times(1)).chatForJson(org.mockito.ArgumentMatchers.anyList());
 }
}
