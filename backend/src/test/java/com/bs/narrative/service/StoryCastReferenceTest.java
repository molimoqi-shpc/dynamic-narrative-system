package com.bs.narrative.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoryCastReferenceTest {
 private final ObjectMapper json=new ObjectMapper();
 @org.junit.jupiter.api.RepeatedTest(3) void realPaidManuscriptBindsItsExplicitNamedReferenceWithoutRewriting()throws Exception{
  var saved=json.readTree(getClass().getResourceAsStream("/fixtures/authored-reference-alias-20261029.json"));
  var draft=(com.fasterxml.jackson.databind.node.ObjectNode)saved.path("draft").deepCopy();String text=draft.path("story").asText();
  StoryCastService.bindNamedReferences(draft,(com.fasterxml.jackson.databind.node.ObjectNode)saved.path("facts"));
  var known=new java.util.HashSet<String>();saved.at("/facts/currentWorld/resources").fieldNames().forEachRemaining(known::add);saved.at("/facts/characterIndex").forEach(x->known.add(x.path("roleId").asText()));
  assertFalse(StoryCastService.referencedRoles(draft).contains("npc_ext_0001"));assertTrue(known.containsAll(StoryCastService.referencedRoles(draft)));
  assertEquals(text,draft.path("story").asText());assertTrue(draft.path("lifeEnding").path("fulfilled").asBoolean());
 }
 @Test void anAlternateReferenceNeverReassignsAnExistingIdOrAnAmbiguousPerson()throws Exception{
  var facts=json.createObjectNode();var index=facts.putArray("characterIndex");index.addObject().put("roleId","first").put("name","甲");index.addObject().put("roleId","second").put("name","乙");
  var draft=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"playerJournal\":{\"relationships\":[{\"roleId\":\"first\",\"name\":\"乙\"},{\"roleId\":\"unknown\",\"name\":\"甲\"},{\"roleId\":\"unknown\",\"name\":\"乙\"}]}}");
  StoryCastService.bindNamedReferences(draft,facts);assertEquals("first",draft.at("/playerJournal/relationships/0/roleId").asText());assertEquals("unknown",draft.at("/playerJournal/relationships/1/roleId").asText());
 }
 @Test void archivedOldParticipantsAreNotCurrentActorsOrUnresolvedCastReferences()throws Exception{
  var draft=json.readTree("{\"roleIds\":[\"p\",\"current\"],\"roleRequests\":[{\"roleId\":\"requested\"}],\"authorPlan\":{\"roleIds\":[\"past-plan\"]},\"plotUpdates\":{\"events\":[{\"roleIds\":[\"past-witness\"]}]},\"playerJournal\":{\"relationships\":[{\"roleId\":\"known-old-friend\"}]}}");
  assertEquals(java.util.Set.of("p","current","requested"),StoryCastService.participantRoles(draft));
  assertFalse(StoryCastService.referencedRoles(draft).contains("past-witness"));assertFalse(StoryCastService.referencedRoles(draft).contains("past-plan"));
  assertTrue(StoryCastService.referencedRoles(draft).contains("known-old-friend"));
 }
 @Test void onlyUniqueExactNamesAreBoundAndProseIsUntouched()throws Exception{
  var facts=json.createObjectNode();facts.putObject("currentWorld").putObject("resources").putObject("id-wu").put("name","吴伯");var index=facts.putArray("characterIndex");index.addObject().put("roleId","id-wu").put("name","吴伯");index.addObject().put("roleId","other-1").put("name","船家");index.addObject().put("roleId","other-2").put("name","船家");
  var draft=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("{\"story\":\"吴伯与船家说话\",\"roleIds\":[\"吴伯\",\"船家\",\"吴老伯\"],\"roleExperiences\":[{\"roleId\":\"吴伯\",\"experience\":\"吴伯原话\"}],\"itemOutcomes\":[{\"holderId\":\"吴伯\"}]}");
  StoryCastService.bindNamedReferences(draft,facts);assertEquals("id-wu",draft.at("/roleIds/0").asText());assertEquals("船家",draft.at("/roleIds/1").asText());assertEquals("吴老伯",draft.at("/roleIds/2").asText());assertEquals("id-wu",draft.at("/roleExperiences/0/roleId").asText());assertEquals("id-wu",draft.at("/itemOutcomes/0/holderId").asText());assertEquals("吴伯与船家说话",draft.path("story").asText());assertEquals("吴伯原话",draft.at("/roleExperiences/0/experience").asText());
 }
}
