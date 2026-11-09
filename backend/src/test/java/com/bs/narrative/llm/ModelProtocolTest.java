package com.bs.narrative.llm;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelProtocolTest {
 @ParameterizedTest @EnumSource(value=ModelProtocol.Stage.class,names="STORY_TEXT",mode=EnumSource.Mode.EXCLUDE)
 void allStagesUseOneObjectProtocolAndDoNotMutateFrozenInput(ModelProtocol.Stage stage){
  var source=List.of(Map.of("role","system","content","原阶段职责"),Map.of("role","user","content","{\"private\":\"只属于这次请求\"}"));
  var prepared=ModelProtocol.messages(stage,source);assertEquals(2,prepared.size());assertEquals(source.get(1),prepared.get(1));
  assertTrue(prepared.get(0).get("content").startsWith("原阶段职责"));assertTrue(prepared.get(0).get("content").contains("MODEL_PROTOCOL_V1/"+stage));
  assertEquals("原阶段职责",source.get(0).get("content"));assertEquals(prepared,ModelProtocol.messages(ModelProtocol.Stage.GENERAL,prepared));
  var schema=ModelProtocol.schema(stage);schema.removeAll();assertEquals("object",ModelProtocol.schema(stage).path("type").asText());
 }
 @Test void framedManuscriptUsesPlainTextWithoutAnAccidentalJsonObjectInstruction(){
  var source=List.of(Map.of("role","system","content","按已确定框架生成完整纯正文"),Map.of("role","user","content","原文与场景框架"));
  assertEquals(source,ModelProtocol.messages(ModelProtocol.Stage.STORY_TEXT,source));
  assertEquals("string",ModelProtocol.canonicalSchema(ModelProtocol.Stage.STORY_TEXT).path("type").asText());
  assertEquals("text/plain",ModelProtocol.canonicalSchema(ModelProtocol.Stage.STORY_TEXT).path("contentMediaType").asText());
 }
 @Test void finalFrameExplicitlyRecordsContinuingLifeOrPlayerRequestedRetirement(){
  var schema=ModelProtocol.canonicalSchema(ModelProtocol.Stage.STORY_FRAME);
  assertTrue(schema.at("/anyOf/0/required").toString().contains("lifeEnding"));assertTrue(schema.at("/properties/lifeEnding/properties/kind/enum").toString().contains("ONGOING"));
  assertDoesNotThrow(()->ModelProtocol.object(ModelProtocol.Stage.STORY_FRAME,"{\"lifeEnding\":{\"kind\":\"RETIREMENT\",\"playerRequested\":true,\"fulfilled\":true}}"));
 }
 @Test void singularSceneAliasNeverOverridesCanonicalScenesOrConvertsAnObject(){
  var canonical=ModelProtocol.object(ModelProtocol.Stage.STORY_FRAME,"{\"scenes\":[{\"key\":\"kept\"}],\"scene\":[{\"key\":\"other\"}]}");
  assertEquals("kept",canonical.at("/scenes/0/key").asText());assertEquals(1,canonical.path("scenes").size());
  assertFalse(ModelProtocol.object(ModelProtocol.Stage.STORY_FRAME,"{\"scene\":{\"key\":\"not-an-array\"}}").has("scenes"));
  assertFalse(ModelProtocol.object(ModelProtocol.Stage.ROLE,"{\"scene\":[{\"key\":\"unrelated\"}]}").has("scenes"));
 }
 @Test void canonicalBirthInventoryAndCharacterSectionsMatchTheActualCreationProtocol(){
  var birth=ModelProtocol.object(ModelProtocol.Stage.BIRTH,"{\"birth_story\":\"原文\",\"initial_location\":{\"location_name\":\"灰港\",\"scene_cast\":[{\"name\":\"米拉\",\"self_history\":\"本人过去\"}]},\"initial_inventory\":[{\"owner\":\"PLAYER\",\"items\":[]}]}" );
  assertTrue(birth.path("initial_inventory").isArray());assertEquals("array",ModelProtocol.schema(ModelProtocol.Stage.BIRTH).at("/properties/initial_inventory/type").asText());
  assertDoesNotThrow(()->ModelProtocol.object(ModelProtocol.Stage.BIRTH,"{\"initial_inventory\":{\"PLAYER\":[]}}"));
  for(String section:ModelProtocol.CORE_SECTIONS)assertTrue(ModelProtocol.schema(ModelProtocol.Stage.ROLE).at("/properties/characterTemplate/properties").has(section));
 }
 @Test void presentFieldsHaveOneTypeAndUnknownCompatibleFieldsAreRetained(){
  for(var pair:List.of(Map.entry(ModelProtocol.Stage.ROLE,"{\"itemIntentions\":{}}"),Map.entry(ModelProtocol.Stage.ARCHIVE,"{\"endingCast\":\"所有人都在\"}"),Map.entry(ModelProtocol.Stage.REVIEW,"{\"paragraphRevisions\":{}}"),Map.entry(ModelProtocol.Stage.TIME,"{\"timeProgress\":{\"elapsedParts\":[{\"amount\":\"三\",\"unit\":\"DAY\"}]}}"))){
   var error=assertThrows(IllegalArgumentException.class,()->ModelProtocol.object(pair.getKey(),pair.getValue()));assertTrue(error.getMessage().contains("模型交接格式"));
  }
  assertEquals("保留扩展",ModelProtocol.object(ModelProtocol.Stage.ARCHIVE,"{\"newOptionalField\":\"保留扩展\",\"endingCast\":[]}").path("newOptionalField").asText());
 }
 @Test void oneTimeUnitTableDrivesThePromptWithoutInventingDurations(){
  var units=ModelProtocol.schema(ModelProtocol.Stage.AUTHOR).at("/properties/timeProgress/properties/elapsedParts/items/properties/unit/enum");
  var names=new HashSet<String>();units.forEach(value->names.add(value.asText()));assertEquals(ModelProtocol.SECONDS_PER_UNIT.keySet(),names);
  assertEquals(31536000L,ModelProtocol.SECONDS_PER_UNIT.get("YEAR"));
 }
 @Test void canonicalRequestsNeverAdvertiseTheLegacyAlternativesThatTheirReadersStillSupport(){
  assertFalse(ModelProtocol.canonicalSchema(ModelProtocol.Stage.REVIEW).path("properties").has("publication"));
  for(String field:List.of("publication","timeProgress","sceneOutcomes"))assertFalse(ModelProtocol.canonicalSchema(ModelProtocol.Stage.ARCHIVE).path("properties").has(field));
  assertTrue(ModelProtocol.schema(ModelProtocol.Stage.ARCHIVE).path("properties").has("sceneOutcomes"));assertTrue(ModelProtocol.canonicalSchema(ModelProtocol.Stage.ARCHIVE_EDIT).path("properties").has("publication"));
  assertFalse(ModelProtocol.canonicalSchema(ModelProtocol.Stage.BIRTH).toString().contains("x-legacy-type"));assertFalse(ModelProtocol.canonicalSchema(ModelProtocol.Stage.TIME).path("properties").has("publication"));
  assertEquals(ModelProtocol.Stage.PUBLICATION,ModelProtocol.policyStage(LLMRequestPolicy.STORY_PUBLICATION));
 }
 @Test void participantQueriesRetirementAndJournalCollectionsHaveDefinedWireTypes(){
  var author=ModelProtocol.canonicalSchema(ModelProtocol.Stage.AUTHOR).path("properties");assertTrue(author.has("lifeEnding"));assertTrue(author.has("authorPlan"));
  assertDoesNotThrow(()->ModelProtocol.object(ModelProtocol.Stage.AUTHOR,"{\"roleIds\":[\"r\"],\"roleRequests\":[\"legacy-r\",{\"roleId\":\"new-r\"}],\"lifeEnding\":{\"kind\":\"RETIREMENT\",\"playerRequested\":true,\"fulfilled\":true}}"));
  assertThrows(IllegalArgumentException.class,()->ModelProtocol.object(ModelProtocol.Stage.AUTHOR,"{\"roleIds\":[{\"roleId\":\"wrong-shape\"}]}"));
  assertThrows(IllegalArgumentException.class,()->ModelProtocol.object(ModelProtocol.Stage.ARCHIVE,"{\"playerJournal\":{\"relationships\":\"友人列表\"}}"));
  assertDoesNotThrow(()->ModelProtocol.object(ModelProtocol.Stage.ARCHIVE,"{\"playerJournal\":{\"characterAppearances\":\"无图也继续故事\"}}"));
 }
 @Test void recoversActualFrameValuesMistakenlyPlacedInsideSchemaPropertiesWithoutChangingTimeOrScenes(){
  String raw="""
   {"type":"object","properties":{
    "endMoment":{"year":0,"day":1,"period":"MORNING"},
    "scenes":[
     {"key":"practice","kind":"CURRENT","at":{"year":0,"day":1,"period":"DAWN"},"place":"刻坊后院","content":"师傅让学徒练翻石认槽，指出背面旧痕。"},
     {"key":"clerk","kind":"CURRENT","at":{"year":0,"day":1,"period":"MORNING"},"place":"刻坊后院","content":"执秤人来核对租数，留下午前拿凭据的答复。"}],
    "stop":"RESOLVED","roleIds":["player","teacher"],"endingPlaces":{"yard":{"locationName":"刻坊后院"}},
    "endingCast":[{"roleId":"player","placeKey":"yard"}],"roleExperiences":[],
    "lifeEnding":{"kind":"ONGOING","playerRequested":false,"fulfilled":false},"extension":{"literal":"完整保留"}}}
   """;
  var parsed=ModelProtocol.object(ModelProtocol.Stage.STORY_FRAME,raw);
  assertEquals(0,parsed.at("/endMoment/year").asInt());assertEquals(1,parsed.at("/endMoment/day").asInt());assertEquals("MORNING",parsed.at("/endMoment/period").asText());
  assertEquals(2,parsed.path("scenes").size());assertEquals("DAWN",parsed.at("/scenes/0/at/period").asText());assertEquals("刻坊后院",parsed.at("/scenes/1/place").asText());
  assertEquals("完整保留",parsed.at("/extension/literal").asText());assertFalse(parsed.has("properties"));assertFalse(parsed.has("type"));
 }
 @Test void sharedEnvelopeCompatibilityAlsoAppliesToRoleWorldAndNestedDataResultReplies(){
  var role=ModelProtocol.object(ModelProtocol.Stage.ROLE,"{\"type\":\"object\",\"properties\":{\"thought\":\"仍想教她\",\"actionDirection\":\"演示刻刀用法\",\"characterTemplate\":{\"personality\":{\"trait\":\"耐心\"}}}}");
  assertEquals("演示刻刀用法",role.path("actionDirection").asText());assertEquals("耐心",role.at("/characterTemplate/personality/trait").asText());
  var world=ModelProtocol.object(ModelProtocol.Stage.WORLD,"{\"success\":true,\"data\":{\"result\":{\"type\":\"object\",\"properties\":{\"title\":\"盐镜潮生\",\"summary\":\"港城旧事\",\"questions\":[\"从何而来？\"]}}}}");
  assertEquals("盐镜潮生",world.path("title").asText());assertEquals("港城旧事",world.path("summary").asText());assertTrue(world.path("questions").isArray());
  var intent=ModelProtocol.object(ModelProtocol.Stage.GENERAL,"{\"data\":{\"private\":\"不属于已知阶段\"}}");assertTrue(intent.has("data"));
 }
 @Test void doesNotMistakeRealSchemasForStoryInstancesOrChooseBetweenConflictingEnvelopes(){
  for(var stage:List.of(ModelProtocol.Stage.WORLD,ModelProtocol.Stage.ROLE,ModelProtocol.Stage.STORY_FRAME,ModelProtocol.Stage.ARCHIVE)){
   var failure=assertThrows(IllegalArgumentException.class,()->ModelProtocol.object(stage,ModelProtocol.canonicalSchema(stage).toString()));
   assertTrue(failure.getMessage().contains("字段定义而非实际数据"));
  }
  assertThrows(IllegalArgumentException.class,()->ModelProtocol.object(ModelProtocol.Stage.AUTHOR,"{\"data\":{\"story\":\"甲\"},\"result\":{\"story\":\"乙\"}}"));
  var root=ModelProtocol.object(ModelProtocol.Stage.AUTHOR,"{\"story\":\"根层已完整生成的原文\",\"result\":{\"story\":\"不覆盖根层\"}}");
  assertEquals("根层已完整生成的原文",root.path("story").asText());assertTrue(root.has("result"));
  assertThrows(IllegalArgumentException.class,()->ModelProtocol.object(ModelProtocol.Stage.STORY_FRAME,"{\"type\":\"object\",\"properties\":{\"endMoment\":{\"type\":\"object\",\"properties\":{\"year\":{\"type\":\"number\"}}},\"scenes\":{\"type\":\"array\"}}}"));
 }
}
