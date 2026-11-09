package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StoryContextSourcesTest {
 final ObjectMapper json=new ObjectMapper();
 JsonNode expand(JsonNode value,JsonNode root){
  if(value.isObject() && value.size()==1 && value.has("$ref")){
   var original=root.at(value.path("$ref").asText());assertFalse(original.isMissingNode(),value.toString());return expand(original,root);
  }
  if(value instanceof ObjectNode object){var copy=object.deepCopy();for(var field:object.properties())copy.set(field.getKey(),expand(field.getValue(),root));return copy;}
  if(value.isArray()){var out=json.createArrayNode();value.forEach(v->out.add(expand(v,root)));return out;}
  return value.deepCopy();
 }
 ObjectNode fixture(){
  var f=json.createObjectNode();var roles=f.putObject("characterDossiers");
  for(String id:List.of("a","b","c")){
   var role=roles.putObject(id);role.putObject("psychology").put("private",id.repeat(300));role.putObject("intentions").put("direction","本次最新意愿"+id);
   var known=role.putObject("personallyKnown");known.putArray("currentKnownState").addObject().put("observed","同一已获知的亲历事实".repeat(80)).put("sourceRole","a");
   known.putArray("lastObservedPeople").addObject().put("id",id).put("observed","每个人自己的不同所见".repeat(40));
  }return f;
 }
 @Test void referencesRoundTripExactlyAndNeverMergeDifferentPersonalKnowledge(){
  var f=fixture();var original=f.deepCopy();StoryContextSources.compact(f);
  assertTrue(f.toString().length()<original.toString().length());
  assertEquals(original.path("characterDossiers"),expand(f.path("characterDossiers"),f));
  assertEquals(original.at("/characterDossiers/a/psychology"),f.at("/characterDossiers/a/psychology"));
  assertNotEquals(f.at("/characterDossiers/a/personallyKnown/lastObservedPeople/0/$ref"),f.at("/characterDossiers/b/personallyKnown/lastObservedPeople/0/$ref"));
 }
 @Test void projectionIsIdempotentAndDoesNotMakeDanglingReferences(){
  var f=fixture();StoryContextSources.compact(f);var once=f.deepCopy();StoryContextSources.compact(f);assertEquals(once,f);expand(f,f);
 }
 @Test void repeatedChapterProseKeepsTheOriginalPublishedSource(){
  var f=fixture();String prose="船已到岸，药匣已经交付，报酬已经收到。".repeat(40);f.putArray("previousChapters").addObject().put("story",prose);
  var r=((ObjectNode)f.at("/characterDossiers/a")).putObject("pastInformation").putArray("ownRecords").addObject().putObject("content");r.put("text",prose);
  var original=f.path("characterDossiers").deepCopy();StoryContextSources.compact(f);
  assertEquals("/previousChapters/0/story",f.at("/characterDossiers/a/pastInformation/ownRecords/0/content/text/$ref").asText());assertEquals(original,expand(f.path("characterDossiers"),f));
 }
 @Test void realSavedInputHasLosslessDetailedDossiersAndReportsMeasuredSize()throws Exception{
  String sample=System.getProperty("story.context.sample");Assumptions.assumeTrue(sample!=null);
  var f=(ObjectNode)json.readTree(Files.readString(Path.of(sample))).path("sceneFrameFacts");
  var before=StoryAuthorInput.prepare(f);before.remove(List.of("sharedContextSources","sharedContextMeaning"));
  // Recover the original expanded dossiers from the prepared, shared projection.
  var projected=StoryAuthorInput.prepare(f);var expanded=expand(projected.path("characterDossiers"),projected);
  var noSharing=projected.deepCopy();noSharing.set("characterDossiers",expanded);noSharing.remove(List.of("sharedContextSources","sharedContextMeaning"));
  assertEquals(f.path("characterDossiers").size(),projected.path("characterDossiers").size());
  for(var entry:f.path("characterDossiers").properties()){
   assertEquals(entry.getValue().path("psychology"),expanded.path(entry.getKey()).path("psychology"));
   assertEquals(entry.getValue().path("currentState").path("body"),expanded.path(entry.getKey()).path("currentState").path("body"));
  }
  assertTrue(projected.toString().length()<noSharing.toString().length());
  System.out.println("CONTEXT_SIZE expandedChars="+noSharing.toString().length()+" sharedChars="+projected.toString().length()+" dossiers="+expanded.size());
 }
}
