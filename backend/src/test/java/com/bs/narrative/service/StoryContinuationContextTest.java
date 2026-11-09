package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryContinuationContextTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode fixture(){
  var f=json.createObjectNode().put("storyFirst",true).put("storyArchive",true).put("playerDirection","接下来两天修好屋顶，正常过日子");
  f.putArray("previousChapters").addObject().put("logId",1).put("story","旧篇第十日，送布已交清，工钱已收。");
  f.withArray("previousChapters").addObject().put("logId",2).put("round",2).put("recordedEndSeconds",200).put("story","饭已吃过，客人分别告辞；翌日晚收船，韩七夸了鱼。");
  f.putArray("alreadyHappenedThisChapter");
  f.putObject("currentWorld").put("openingEnvironment","旧开场：顾管事仍在等主角接药匣。");
  f.putArray("routeDestinations").addObject().put("openingEnvironment","初次建档时还是破屋顶");
  f.putObject("characterDossiers");
  f.putObject("continuityState").putObject("narrativeState").put("currentSituation","旧摘要还说正在送布").putObject("source").put("logId",1);
  return f;
 }
 @Test void historicalOpeningIsLosslessButCannotMasqueradeAsCurrentScene(){
  var f=fixture();var before=f.deepCopy();var input=StoryAuthorInput.prepare(f);
  assertFalse(input.at("/currentWorld").has("openingEnvironment"));
  assertEquals(before.at("/currentWorld/openingEnvironment"),input.at("/currentWorld/historicalOpening/text"));
  assertEquals(before.at("/routeDestinations/0/openingEnvironment"),input.at("/routeDestinations/0/historicalOpening/text"));
  assertEquals(input,StoryAuthorInput.prepare(input));assertEquals(before,f);
 }
 @Test void finalActualModelInputIncludesExactLatestProseAfterBothNormalAndRejectedAttempts()throws Exception{
  var f=fixture();var before=f.deepCopy();var model=mock(LLMProvider.class);
  when(model.chatForJson(anyList())).thenAnswer(call->{
   List<Map<String,String>> messages=call.getArgument(0);String last=messages.get(messages.size()-1).get("content");
   var guide=json.readTree(last.substring(last.indexOf('{')));var handoff=guide.path("continuation");
   assertEquals(f.at("/previousChapters/1/story"),handoff.path("latestPublishedPassage"));
   assertEquals(200,handoff.at("/publishedStatus/recordedEndSeconds").asInt());assertFalse(handoff.path("memoryMatchesLatestChapter").asBoolean());
   assertEquals(f.path("playerDirection"),handoff.path("playerDirection"));assertFalse(handoff.toString().contains("候选已修好屋顶"));
   var input=json.readTree(messages.get(1).get("content"));assertFalse(input.at("/currentWorld").has("openingEnvironment"));
   assertEquals(LLMRequestPolicy.STORY_AUTHOR,LLMRequestPolicy.current());
   return new LLMResponse("{}","{}",0,0,0,0);
  });
  var service=new SceneStoryService(model);service.write(f,null,"",System.nanoTime()+60_000_000_000L);
  service.write(f,json.createObjectNode().put("story","候选已修好屋顶"),"未保存候选",System.nanoTime()+60_000_000_000L);
  assertEquals(before,f);verify(model,times(2)).chatForJson(anyList());
 }
 @Test void committedPassageSupersedesPriorChapterAndItsRememberedSituation(){
  var f=fixture();f.withObject("continuityState").withObject("narrativeState").withObject("source").put("logId",2);
  assertTrue(StoryContinuationContext.handoff(f).path("memoryMatchesLatestChapter").asBoolean());
  f.withArray("alreadyHappenedThisChapter").add("曹大已经帮忙换好第一根椽，天刚过午。");
  var h=StoryContinuationContext.handoff(f);assertEquals(f.at("/alreadyHappenedThisChapter/0"),h.path("latestPublishedPassage"));
  assertFalse(h.path("memoryMatchesLatestChapter").asBoolean());assertFalse(h.path("publishedStatus").has("recordedEndSeconds"));
 }
 @Test void incompleteLegacyInputDoesNotInventAStartDateOrAPublishedPassage(){
  var h=StoryContinuationContext.handoff(json.createObjectNode());assertEquals("",h.path("latestPublishedPassage").asText());
  assertFalse(h.path("publishedStatus").has("recordedEndSeconds"));assertFalse(h.has("rememberedResults"));
 }
 @Test void assemblyTimeDoesNotRelabelOldPersonalExperienceAsCurrentAndHistoriesAreNotShared()throws Exception{
  var own=json.createObjectNode().put("id","gu").put("historyOrder","NEWEST_TO_OLDEST").put("storySituation","未听说别处的结果");
  var r=own.putArray("ownRecords");r.addObject().put("source_type","TURN_EVENT").putObject("content").put("endSeconds",90).put("text","亲耳听到主角拒绝");
  r.addObject().put("source_type","TURN_EVENT").putObject("content").put("endSeconds",20).put("text","更早提出委托");
  var service=new CharacterDossierService(mock(JdbcTemplate.class));var before=own.deepCopy();
  var d=service.assemble(own,json.createObjectNode(),false,"",9000);assertEquals(9000,d.path("snapshotAtSeconds").asInt());assertEquals(90,d.at("/currentState/lastPersonalExperienceEndSeconds").asInt());
  var chronological=json.readTree(StoryAuthorContext.roleMessages(own).get(1).get("content"));
  assertEquals(90,service.assemble((ObjectNode)chronological,json.createObjectNode(),false,"",9000).at("/currentState/lastPersonalExperienceEndSeconds").asInt());assertEquals(before,own);
 }
}
