package com.bs.narrative.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PublicAppearanceSchemaTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void keepsDetailedVisibleFactsWithoutPrivateNestedFields(){
  var raw=json.valueToTree(Map.of("gender","female","archetype","MECHANIC","hairColor","栗棕","visibleEquipment",List.of("扳手","机械核心"),"visibleAnatomy",Map.of("leftForearm","机械义肢","rightHand","自然血肉","secretHistory","隐藏旧事"),"visibleMount",Map.of("present",false,"species","unicorn","privateIntention","未来结盟"),"psychology",Map.of("thought","不可泄露")));
  var out=PublicAppearanceSchema.sanitize(raw);assertEquals("栗棕",out.path("hairColor").asText());assertEquals(2,out.path("visibleEquipment").size());assertEquals("机械义肢",out.path("visibleAnatomy").path("leftForearm").asText());
  assertFalse(out.toString().contains("隐藏旧事"));assertFalse(out.toString().contains("未来结盟"));assertFalse(out.toString().contains("不可泄露"));assertFalse(out.path("visibleMount").path("present").asBoolean());
 }
 @Test void malformedOptionalDetailsAreIgnoredAndExplicitAbsenceSurvives()throws Exception{
  assertTrue(PublicAppearanceSchema.sanitize(json.readTree("[]")).isEmpty());
  var out=PublicAppearanceSchema.sanitize(json.readTree("{\"gender\":{},\"apparentAge\":-1,\"clothing\":[{},42,\"白衣\"],\"visibleMount\":false,\"visibleAnatomy\":{\"leftForearm\":false},\"apparentAgeGroup\":\"YOUNG\"}"));
  assertFalse(out.has("gender"));assertFalse(out.has("apparentAge"));assertEquals(1,out.path("clothing").size());assertTrue(out.path("visibleMount").isBoolean());assertFalse(out.path("visibleMount").asBoolean());assertFalse(out.path("visibleAnatomy").path("leftForearm").asBoolean());assertEquals("YOUNG",out.path("apparentAgeGroup").asText());
 }
 @Test void limitsDisplayPayloadSizeWithoutChangingSourceData(){
  var raw=json.valueToTree(Map.of("basis","长".repeat(800),"portraitKeywords",Collections.nCopies(100,"词".repeat(200)),"apparentAge",22));var out=PublicAppearanceSchema.sanitize(raw);
  assertEquals(600,out.path("basis").asText().length());assertEquals(32,out.path("portraitKeywords").size());assertEquals(160,out.path("portraitKeywords").get(0).asText().length());assertEquals(22,out.path("apparentAge").asInt());assertEquals(100,raw.path("portraitKeywords").size());
 }
}
