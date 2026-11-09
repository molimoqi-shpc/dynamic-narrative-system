package com.bs.narrative.service;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import static org.junit.jupiter.api.Assertions.*;

class StoryArchiveLocationsTest {
 @Test void explicitlyUnknownWhereaboutsDoNotFallBackToAnOldPlayerScene() {
  var archive=new ObjectMapper().createObjectNode();
  archive.putArray("endingCast").addObject().put("roleId","reported-person").put("placeKey","").put("sceneId","player-home").put("basis","仅被转述，没有在主角处出现");
  archive.putArray("sceneOutcomes").addObject().put("sceneId","player-home").putArray("roleIds").add("reported-person").add("actual-companion");
  var result=StoryArchiveLocations.project(archive,new ObjectMapper().createObjectNode());
  assertEquals(1,result.size());assertEquals("actual-companion",result.at("/0/roleIds/0").asText());assertEquals(1,result.at("/0/roleIds").size());
 }
 final ObjectMapper json=new ObjectMapper();
 @Test void explicitFinalLocationIsStoredEvenWhenTheDuplicateSceneTableIsEmpty(){
  var projection=json.createObjectNode();projection.putArray("sceneOutcomes");
  projection.putArray("endingCast").addObject().put("roleId","player").put("location","西闸回程路途中").put("basis","他已经离开西闸，走在回程路上。");
  var original=projection.deepCopy();var result=StoryArchiveLocations.project(projection,json.createObjectNode());
  assertEquals("西闸回程路途中",result.at("/0/scene/locationName").asText());assertEquals("player",result.at("/0/roleIds/0").asText());
  assertEquals("他已经离开西闸，走在回程路上。",result.at("/0/scene/opening").asText());assertEquals(original,projection);
 }
 @Test void exactDictionaryReferencesGroupTogetherWithoutTakingAnAbsentCompanionAlong(){
  var facts=json.createObjectNode();facts.putArray("locationIndex").addObject().put("sceneId","room").put("name","旧账房");
  var projection=json.createObjectNode();var cast=projection.putArray("endingCast");
  cast.addObject().put("roleId","a").put("location","旧账房");cast.addObject().put("roleId","b").put("location","旧账房");cast.addObject().put("roleId","p").put("location","河堤回程路").put("basis","独自回程。");
  var result=StoryArchiveLocations.project(projection,facts);assertEquals(2,result.size());assertEquals("room",result.at("/0/sceneId").asText());assertEquals(2,result.at("/0/roleIds").size());assertEquals("p",result.at("/1/roleIds/0").asText());
 }
 @Test void typedLegacyResultsStayAuthoritativeAndLocationsAreNeverGuessedFromProse(){
  var projection=json.createObjectNode();projection.put("story","玩家希望与甲一同到东岸，但事情如何发生不由程序判断。");
  var old=projection.putArray("sceneOutcomes").addObject().put("sceneId","already-authored");old.putArray("roleIds").add("p");
  projection.putArray("endingCast").addObject().put("roleId","p").put("location","显示名称");projection.withArray("endingCast").addObject().put("roleId","a");
  var result=StoryArchiveLocations.project(projection,json.createObjectNode());assertEquals(1,result.size());assertEquals(old,result.get(0));
 }
 @Test void explicitStructuredDestinationsAreCopiedWithoutRewritingTheirMeaning(){
  var projection=json.createObjectNode();var cast=projection.putArray("endingCast");cast.addObject().put("roleId","p").put("sceneId","old-id");
  var row=cast.addObject().put("roleId","n");row.putObject("scene").put("locationName","沿山离去的路段").put("opening","只知已离去，后续去向未知。");
  var result=StoryArchiveLocations.project(projection,json.createObjectNode());assertEquals("old-id",result.at("/0/sceneId").asText());assertEquals(row.path("scene"),result.at("/1/scene"));
 }
 @RepeatedTest(3) void capturedIdCollisionUsesTwoNamedPlacesWithoutGuessingTravelFromTheProse()throws Exception{
  var fixture=json.readTree(getClass().getResourceAsStream("/fixtures/final-place-id-collision.json"));
  var captured=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.path("legacyArchive");
  assertEquals(1,StoryArchiveLocations.project(captured,(com.fasterxml.jackson.databind.node.ObjectNode)fixture.path("facts")).size(),"The old paid reply used the same inn ID for every row; preserve the real failure evidence");
  // An explicitly supplied named-place reply exercises the new contract. This
  // is a local model substitute, not a claim that the live model was retested.
  var named=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.path("namedEquivalent").deepCopy();var before=named.deepCopy();
  var facts=(com.fasterxml.jackson.databind.node.ObjectNode)fixture.path("facts");var result=StoryArchiveLocations.project(named,facts);
  assertEquals(2,result.size());assertEquals(3,result.at("/0/roleIds").size());assertEquals(1,result.at("/1/roleIds").size());
  assertEquals("旧驿道回程路",result.at("/0/scene/locationName").asText());assertEquals("城东盐栈",result.at("/1/scene/locationName").asText());
  for(var group:result)assertFalse(group.has("sceneId"),"The old inn reference cannot win over an explicit named-place key");
  assertEquals(before,named);assertEquals(result,StoryArchiveLocations.project(named,facts));
 }
 @Test void namedPlacesReuseExactEstablishedNamesAndReplaceOnlyTheSamePersonsLegacyRow(){
  var facts=json.createObjectNode();facts.putArray("locationIndex").addObject().put("sceneId","shop").put("name","市集药铺");
  var archive=json.createObjectNode();archive.putObject("endingPlaces").putObject("seller").put("locationName","市集药铺").put("opening","掌柜留在店里。").put("sceneId","ignored-stale-id");
  archive.putArray("endingCast").addObject().put("roleId","seller").put("placeKey","seller").put("sceneId","wrong-home");
  var old=archive.putArray("sceneOutcomes").addObject().put("sceneId","home");old.putArray("roleIds").add("seller").add("unrelated");
  var result=StoryArchiveLocations.project(archive,facts);assertEquals(2,result.size());assertEquals("shop",result.at("/0/sceneId").asText());
  assertEquals("seller",result.at("/0/roleIds/0").asText());assertEquals("home",result.at("/1/sceneId").asText());assertEquals(1,result.at("/1/roleIds").size());assertEquals("unrelated",result.at("/1/roleIds/0").asText());
 }
 @Test void optionalMissingPlaceKeyDoesNotInventAMoveAndNestedTablesRemainAvailableToArchiving(){
  var archive=json.createObjectNode();archive.putArray("endingCast").addObject().put("roleId","unknown").put("placeKey","missing");
  assertTrue(StoryArchiveLocations.project(archive,json.createObjectNode()).isEmpty());
  var wrapped=json.createObjectNode();var state=wrapped.putObject("continuityState");state.putObject("endingPlaces").putObject("road").put("locationName","回程路");state.putArray("endingCast").addObject().put("roleId","player").put("placeKey","road");
  var normalized=new StoryArchiveService(null).normalize(wrapped.toString());assertTrue(normalized.path("endingPlaces").isObject());assertFalse(normalized.path("continuityState").has("endingPlaces"));
  assertEquals("回程路",StoryArchiveLocations.project(normalized,json.createObjectNode()).at("/0/scene/locationName").asText());
 }
 @Test void legacyTypedRowsAreNeverSilentlyDeduplicatedIntoAnArbitraryDestination(){
  var archive=json.createObjectNode();var rows=archive.putArray("sceneOutcomes");
  rows.addObject().put("sceneId","a").putArray("roleIds").add("same-role");rows.addObject().put("sceneId","b").putArray("roleIds").add("same-role");
  assertEquals(rows,StoryArchiveLocations.project(archive,json.createObjectNode()));
 }
}
