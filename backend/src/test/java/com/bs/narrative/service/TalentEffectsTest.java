package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.llm.ModelProtocol;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class TalentEffectsTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode candidate(String key){
  var node=json.createObjectNode().put("stat_key",key);
  node.putObject("special_effect").put("benefit","从实际轻敲的声音察觉木器松动").put("trigger","靠近熟悉木器检查时").put("limits","不能隔墙获知内部，也不能直接知道用途").put("tradeoff","嘈杂环境会干扰判断");return node;
 }
 Talent talent(String description){var t=new Talent();t.setTalentName("听木知隙");t.setTalentDescription(description);return t;}
 @Test void pureSituationalEffectPreservesConditionsWithoutInventingAStatBonus(){
  var description=TalentEffects.description(candidate(""),3,0);var t=talent(description);var g=new GodProfile();g.setBaseHui(9);g.setBaseWen(11);TalentEffects.apply(t,g);
  assertTrue(description.contains("轻敲"));assertTrue(description.contains("不能隔墙"));assertTrue(description.contains("嘈杂"));assertFalse(description.contains("开局增益"));assertEquals(9,g.getBaseHui());assertEquals(11,g.getBaseWen());assertEquals("NARRATIVE",TalentEffects.kind(t));
 }
 @RepeatedTest(3) void aSpecialEffectCanOnlyCarryOneOrTwoOpeningStatPoints(RepetitionInfo info){
  int power=info.getCurrentRepetition();var t=talent(TalentEffects.description(candidate("hui"),power,0));var g=new GodProfile();g.setBaseHui(9);TalentEffects.apply(t,g);
  assertEquals(9+Math.min(2,power),g.getBaseHui());assertEquals("NARRATIVE",TalentEffects.kind(t));assertTrue(t.getTalentDescription().contains("不能隔墙"));
 }
 @Test void legacyStatOnlyCandidatesKeepTheirOriginalSixPointBonus(){
  var old=talent(TalentEffects.description("hui",3,0));var viaOldProtocol=talent(TalentEffects.description(json.createObjectNode().put("stat_key","hui"),3,0));
  assertEquals(old.getTalentDescription(),viaOldProtocol.getTalentDescription());var g=new GodProfile();g.setBaseHui(9);TalentEffects.apply(viaOldProtocol,g);assertEquals(15,g.getBaseHui());assertEquals("STAT",TalentEffects.kind(viaOldProtocol));
 }
 @Test void arbitraryOldNarrativePromisesAreNotRetroactivelyActivated(){
  var old=talent("旧版曾写永生和自动回血，未实现");var g=new GodProfile();g.setBaseDou(10);TalentEffects.apply(old,g);assertEquals(10,g.getBaseDou());assertEquals("LEGACY",TalentEffects.view(old).get("effectKind"));
 }
 @Test void theNewTransportAcceptsAConditionedEffectWithoutChangingOldCandidates(){
  var root=json.createObjectNode();root.putArray("talents").add(candidate("").put("talent_name","听木知隙"));
  assertEquals("从实际轻敲的声音察觉木器松动",ModelProtocol.object(ModelProtocol.Stage.TALENTS,root.toString()).at("/talents/0/special_effect/benefit").asText());
 }
}
