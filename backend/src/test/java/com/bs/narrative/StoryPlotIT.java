package com.bs.narrative;

import com.bs.narrative.service.StoryPlotService;
import com.bs.narrative.service.WorldBookService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class StoryPlotIT {
 @Autowired StoryPlotService plots;@Autowired JdbcTemplate jdbc;@Autowired WorldBookService worldBook;
 final ObjectMapper json=new ObjectMapper();final String sid="plot_memory_it";
 ObjectNode initial(){
  var updates=json.createObjectNode();var event=updates.putArray("events").addObject().put("key","flood").put("title","旧堤真相").put("category","INVESTIGATION").put("status","OPEN").put("openQuestion","当年为何提前开闸？").put("development","主角发现旧堤修缮册缺了一页。").put("sourceQuote","修缮册中间留着一截撕痕。");
  event.putArray("establishedFacts").addObject().put("text","修缮册确实缺页").put("source","主角亲眼查阅");
  event.putObject("authorNotes").put("possibility","UNCOMMITTED_POSSIBILITY");event.putObject("playerView").put("title","旧堤缺页").put("summary","已查看旧册，关键一页缺失，原因未明。").put("status","OPEN").put("category","INVESTIGATION");
  var thread=updates.putArray("foreshadows").addObject().put("key","missingPage").put("eventId","flood").put("title","缺页的去向").put("status","OPEN").put("setup","修缮册有早已干涸的撕痕。").put("question","谁取走这一页？");thread.putObject("playerView").put("title","册中的撕痕").put("summary","册页被撕走，尚不知是谁所为。").put("status","OPEN");return updates;
 }
 String event(){return jdbc.queryForObject("SELECT event_id FROM story_plot_event WHERE session_id=?",String.class,sid);}
 String thread(){return jdbc.queryForObject("SELECT thread_id FROM story_foreshadow WHERE session_id=?",String.class,sid);}
 String encoded(Object value)throws Exception{return json.writeValueAsString(value);}
 ArrayNode query(String field,String value){return json.createArrayNode().add(json.createObjectNode().put(field,value));}
 JsonNode publicFirst(){return json.valueToTree(plots.browse(sid,false,"","","",0,0)).path("events").get(0);}
 @Test void publicReviewDoesNotDisappearWhenItsEventLeavesTheFourRecentDetails(){
  for(int i=0;i<7;i++)plots.apply(sid,501+i,0,i+1,(i+1)*120,initial());
  var context=plots.authorContext(sid);assertEquals(4,context.path("recentEvents").size());assertEquals(7,context.path("playerKnownState").size());
  assertFalse(context.path("playerKnownState").toString().contains("UNCOMMITTED_POSSIBILITY"));
 }
 @Test void aPayoffOutsideTheRecentWindowBringsBackItsActualPublishedChapter(){
  var keys=new HashMap<Integer,Long>();for(int round=1;round<=7;round++){
   jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,outcome_type,true_intent,processed_dialog) VALUES(?,'source_test_u','source_test_s',?,'查旧事','NO_OP','{}',?)",sid,round,round==2?"narrator：第二章当面交出残页，解释为何取走，并且主角已听完。":"narrator：第"+round+"章已发表经过。");
   keys.put(round,jdbc.queryForObject("SELECT log_id FROM interaction_log WHERE session_id=? AND round_no=?",Long.class,sid,round));
  }
  plots.apply(sid,keys.get(1),0,1,120,initial());var update=json.createObjectNode();update.putArray("events").addObject().put("eventId",event()).put("status","RESOLVED").put("outcome","残页已交出");update.putArray("foreshadows").addObject().put("threadId",thread()).put("status","PAID_OFF").put("payoff","第二章已解释");plots.apply(sid,keys.get(2),0,2,240,update);
  var display=json.createObjectNode();display.putArray("events").addObject().put("eventId",event()).putObject("playerView").put("summary","旧页去向早已听完，公开卡现已补齐").put("status","已获说明");plots.apply(sid,keys.get(7),0,7,840,display);
  var plan=json.createObjectNode();plan.putArray("events").addObject().put("eventId",event()).putObject("authorNotes").put("possibility","UNCOMMITTED_NEW_POSSIBILITY");plots.apply(sid,keys.get(7),1,7,840,plan);
  assertEquals(keys.get(7).longValue(),publicFirst().at("/source/logId").asLong());
  assertEquals(keys.get(2).longValue(),plots.lookup(sid,query("eventId",event())).at("/0/records/0/event/source/logId").asLong());
  var facts=json.createObjectNode();var recent=facts.putArray("previousChapters");for(int round=4;round<=7;round++)recent.addObject().put("logId",keys.get(round));facts.putObject("openingPublishedChapter").put("logId",keys.get(1));facts.set("plotMemory",plots.authorContext(sid));plots.attachPublishedSources(sid,facts);
  assertEquals(1,facts.path("plotSourcePages").size());assertEquals(keys.get(2).longValue(),facts.at("/plotSourcePages/0/logId").asLong());assertTrue(facts.at("/plotSourcePages/0/story").asText().contains("第二章当面交出残页"));assertEquals("LATEST_RECORDED_DEVELOPMENT",facts.at("/plotSourcePages/0/sourceRole").asText());assertFalse(facts.path("plotSourcePages").toString().contains("UNCOMMITTED_POSSIBILITY"));
 }
 @Test void latePublicProjectionOfSameChapterIsSavedAndUnchangedFutureReviewsDoNotGrowHistory(){
  plots.apply(sid,501,0,1,120,initial());String event=event(),thread=thread();
  var review=json.createObjectNode();review.putArray("events").addObject().put("eventId",event).putObject("playerView").put("summary","已经听到说明，仍保留未亲见部分").put("status","已有说明");review.putArray("foreshadows").addObject().put("threadId",thread).putObject("playerView").put("summary","残页去向已当面说明").put("status","已解释");
  plots.apply(sid,501,0,1,120,review);assertEquals("已有说明",publicFirst().path("status").asText());assertEquals("已解释",publicFirst().at("/foreshadows/0/status").asText());
  int count=jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Integer.class,sid);
  for(int round=2;round<=60;round++)plots.apply(sid,500+round,0,round,round*120,review);
  assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Integer.class,sid));assertEquals(501,publicFirst().at("/source/logId").asLong());
  assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM story_plot_event WHERE session_id=?",String.class,sid));
 }
 @Test void displayOnlyArchivePatchUpdatesBothPublicCardsWhilePreservingPrivatePlotFacts(){
  plots.apply(sid,501,0,1,120,initial());String event=event(),thread=thread();
  var privateUpdate=json.createObjectNode();privateUpdate.putArray("events").addObject().put("eventId",event).put("status","RESOLVED").put("outcome","PRIVATE_TRUTH");privateUpdate.putArray("foreshadows").addObject().put("threadId",thread).put("status","PAID_OFF").put("payoff","PRIVATE_DETAIL");plots.apply(sid,502,0,2,240,privateUpdate);
  var raw=json.createObjectNode();raw.putArray("endingCast");var views=raw.putObject("playerViewUpdates");views.putArray("events").addObject().put("eventId",event).putObject("playerView").put("summary","已当面听到解释，但没有目睹幕后经过").put("status","已获说明");views.putArray("foreshadows").addObject().put("threadId",thread).putObject("playerView").put("summary","残页去向已经当面说明").put("status","已解释");
  var normalized=new com.bs.narrative.service.StoryArchiveService(org.mockito.Mockito.mock(com.bs.narrative.llm.LLMProvider.class)).normalize(raw.toString());assertTrue(plots.apply(sid,503,0,3,360,normalized.path("plotUpdates")).isEmpty());
  assertEquals("已获说明",publicFirst().path("status").asText());assertEquals("已解释",publicFirst().at("/foreshadows/0/status").asText());assertFalse(publicFirst().toString().contains("PRIVATE_"));
  assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM story_plot_event WHERE session_id=?",String.class,sid));assertEquals("PAID_OFF",jdbc.queryForObject("SELECT status FROM story_foreshadow WHERE session_id=?",String.class,sid));assertEquals("PRIVATE_TRUTH",plots.lookup(sid,query("eventId",event)).at("/0/records/0/event/outcome").asText());
  assertEquals(502,plots.lookup(sid,query("eventId",event)).at("/0/records/0/event/source/logId").asLong());assertEquals(502,plots.lookup(sid,query("threadId",thread)).at("/0/records/0/source/logId").asLong());assertEquals(503,publicFirst().at("/source/logId").asLong());
 }
 @Test void repeatingUnchangedPublicViewDuringPrivateDevelopmentDoesNotRevealItsTimestamp(){
  var initial=initial();plots.apply(sid,501,0,1,120,initial);var before=publicFirst().deepCopy();
  var privateUpdate=json.createObjectNode();var event=privateUpdate.putArray("events").addObject().put("eventId",event()).put("status","RESOLVED").put("outcome","SECRET_LATER_DISCOVERY");event.set("playerView",initial.at("/events/0/playerView").deepCopy());
  var thread=privateUpdate.putArray("foreshadows").addObject().put("threadId",thread()).put("status","PAID_OFF").put("payoff","SECRET_PAYOFF");thread.set("playerView",initial.at("/foreshadows/0/playerView").deepCopy());
  plots.apply(sid,502,0,2,240,privateUpdate);assertEquals(before,publicFirst());
  assertEquals(502,plots.lookup(sid,query("eventId",event())).at("/0/records/0/event/source/logId").asLong());
  assertFalse(publicFirst().toString().contains("SECRET"));
 }
 @Test void newIdsBindBothTablesAndRetryDoesNotDuplicateTheSamePublishedSource(){
  assertTrue(plots.apply(sid,501,0,1,120,initial()).isEmpty());String event=event(),thread=thread();
  assertEquals(event,jdbc.queryForObject("SELECT event_id FROM story_foreshadow WHERE session_id=? AND thread_id=?",String.class,sid,thread));
  plots.apply(sid,501,0,1,120,initial());assertEquals(event,event());assertEquals(thread,thread());
  assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=?",Integer.class,sid));
  assertEquals(501,publicFirst().at("/source/logId").asLong());
  assertTrue(plots.apply(sid,502,0,2,240,json.createObjectNode()).isEmpty());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM story_plot_event WHERE session_id=?",Integer.class,sid));
 }
 @Test void payoffKeepsOriginalSetupAndAnAddressableHistoryWithoutPublishingThePlan()throws Exception{
  plots.apply(sid,501,0,1,120,initial());String event=event(),thread=thread();
  var updates=json.createObjectNode();updates.putArray("events").addObject().put("eventId",event).put("status","RESOLVED").put("openQuestion","已查明缺页去向").put("outcome","取页者承认隐瞒旧堤裂缝，同行者改变了对他的信任。");
  var paid=updates.putArray("foreshadows").addObject().put("threadId",thread).put("status","PAID_OFF").put("payoff","旧册缺页与取页者交出的残页吻合，他承认隐瞒裂缝。");paid.putArray("developments").addObject().put("text","残页与撕痕吻合").put("source","本段当面核对");
  plots.apply(sid,502,1,2,360,updates);
  var request=json.createArrayNode().addObject().put("threadId",thread).put("limit",1);var result=plots.lookup(sid,json.createArrayNode().add(request));
  assertEquals("PAID_OFF",result.at("/0/records/0/status").asText());assertEquals("修缮册有早已干涸的撕痕。",result.at("/0/records/0/setup").asText());assertEquals(1,result.at("/0/history").size());
  long before=result.at("/0/nextBeforeEntryId").asLong();var older=plots.lookup(sid,json.createArrayNode().add(json.createObjectNode().put("threadId",thread).put("beforeEntryId",before)));assertEquals("OPEN",older.at("/0/history/0/record/status").asText());
  var context=plots.authorContext(sid);assertEquals(event,context.at("/eventIndex/0/eventId").asText());assertTrue(context.toString().contains("UNCOMMITTED_POSSIBILITY"));
  assertEquals("RESOLVED",context.at("/recentEvents/0/event/status").asText());assertEquals("OPEN",context.at("/playerKnownState/0/eventView/status").asText());assertEquals("OPEN",context.at("/playerKnownState/0/foreshadows/0/status").asText());assertFalse(context.path("playerKnownState").toString().contains("UNCOMMITTED_POSSIBILITY"));
  String ui=encoded(plots.browse(sid,true,"","","",0,0));assertFalse(ui.contains("UNCOMMITTED_POSSIBILITY"));assertTrue(ui.contains("PAID_OFF"));
  assertFalse(worldBook.buildContextForPrompt(sid).contains("UNCOMMITTED_POSSIBILITY"));assertTrue(worldBook.queryByLocation(sid,0,0).isEmpty());
 }
 @Test void hiddenProgressNeverChangesLimitedTitleStateTimeSearchOrThreadCount()throws Exception{
  plots.apply(sid,501,0,1,120,initial());String event=event();
  var update=json.createObjectNode();update.putArray("events").addObject().put("eventId",event).put("title","SECRET_EVENT_TITLE").put("category","CONFLICT").put("status","RESOLVED").put("development","SECRET_ACTUAL_PAYOFF");
  update.putArray("foreshadows").addObject().put("eventId",event).put("title","SECRET_THREAD").put("status","PAID_OFF").put("payoff","SECRET_EXPLANATION");plots.apply(sid,502,0,2,900,update);
  var limited=publicFirst();assertEquals("旧堤缺页",limited.path("title").asText());assertEquals("OPEN",limited.path("status").asText());assertEquals(120,limited.at("/source/storySeconds").asLong());assertEquals(1,limited.path("foreshadows").size());
  assertFalse(encoded(limited).contains("SECRET"));assertEquals(0L,plots.browse(sid,false,"SECRET","","",0,0).get("total"));assertEquals(0L,plots.browse(sid,false,"","","CONFLICT",0,0).get("total"));
  assertTrue(encoded(plots.browse(sid,true,"SECRET_EVENT_TITLE","","",0,0)).contains("SECRET_EXPLANATION"));
  assertEquals(1L,plots.browse(sid,true,"SECRET_EXPLANATION","","",0,0).get("total"));
 }
 @Test void tenantReferencesAndMalformedSupplementaryRecordsCannotCorruptOrBlockTheStory(){
  plots.apply(sid,501,0,1,120,initial());String event=event();
  var other=json.createObjectNode();other.putArray("events").addObject().put("eventId",event).put("title","不能覆盖别的存档");other.putArray("foreshadows").addObject().put("title","错误跨存档引用").put("eventId",event);
  assertEquals(2,plots.apply("plot_other_it",600,0,1,10,other).size());assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM story_plot_event WHERE session_id='plot_other_it'",Integer.class));
  assertTrue(plots.lookup("plot_other_it",query("eventId",event)).at("/0/records").isEmpty());
  assertFalse(plots.apply(sid,601,0,1,10,json.getNodeFactory().textNode("坏格式")).isEmpty());
  assertThrows(IllegalArgumentException.class,()->plots.browse(sid,false,"","","",-1,0));
 }
 @Test void compactAuthorContextKeepsOldFactsQueryableAndThreadsHaveRealPages(){
  var update=initial();var facts=(ArrayNode)update.at("/events/0/establishedFacts");for(int i=0;i<20;i++)facts.addObject().put("text","旧事实"+i).put("source","前文");
  var list=(ArrayNode)update.path("foreshadows");for(int i=0;i<21;i++)list.addObject().put("key","f"+i).put("eventId","flood").put("title","重要支线问题"+i).put("setup","已发生的铺垫");plots.apply(sid,501,0,1,120,update);
  var context=plots.authorContext(sid);assertEquals(6,context.at("/recentEvents/0/event/establishedFacts").size());assertEquals(8,context.at("/recentEvents/0/foreshadows").size());
  var first=plots.lookup(sid,query("eventId",event()));assertEquals(21,first.at("/0/records/0/event/establishedFacts").size());assertEquals(20,first.at("/0/records/0/foreshadows").size());assertTrue(first.at("/0/records/0/threadHasMore").asBoolean());
  var next=plots.lookup(sid,json.createArrayNode().add(json.createObjectNode().put("eventId",event()).put("threadPage",1)));assertEquals(2,next.at("/0/records/0/foreshadows").size());assertFalse(next.at("/0/records/0/threadHasMore").asBoolean());
 }
 @Test void longPlotRecordsCanBeBrowsedWithASmallMysqlSortBuffer(){
  long prior=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);
  try{
   jdbc.execute("SET SESSION sort_buffer_size=32768");
   for(int i=0;i<16;i++){var update=initial();var event=(ObjectNode)update.at("/events/0");event.put("title","重要事件"+i).put("development","长篇已发生进展".repeat(2500));plots.apply(sid,700+i,0,i,100+i,update);}
   assertEquals(16L,plots.browse(sid,true,"","","",0,0).get("total"));assertEquals(16L,plots.browse(sid,false,"","","",0,0).get("total"));assertEquals(16,plots.authorContext(sid).path("eventIndex").size());
   assertEquals(16L,plots.browse(sid,true,"长篇已发生进展","","",0,0).get("total"));assertEquals(16L,plots.browse(sid,false,"缺页","","",0,0).get("total"));
   assertEquals(4,plots.lookup(sid,query("keyword","重要事件")).at("/0/records").size());
   String ref=jdbc.queryForObject("SELECT event_id FROM story_plot_event WHERE session_id=? ORDER BY event_id LIMIT 1",String.class,sid);
   for(int i=0;i<5;i++){var update=json.createObjectNode();update.putArray("events").addObject().put("eventId",ref).put("development","第"+i+"次新进展："+"很长的后续实际经历".repeat(2500));plots.apply(sid,900+i,0,20+i,200+i,update);}
   assertEquals(4,plots.lookup(sid,query("eventId",ref)).at("/0/history").size());
  }finally{jdbc.execute("SET SESSION sort_buffer_size="+prior);}
 }
 @Test void originalSourceSurvivesNewDevelopmentsAndLegacyRecordsRecoverTheirFirstPublishedReference(){
  plots.apply(sid,501,0,1,120,initial());String event=event();
  var update=json.createObjectNode();var patch=update.putArray("events").addObject().put("eventId",event).put("development","新的实际进展");patch.putObject("originalSource").put("logId",999);plots.apply(sid,502,0,2,240,update);
  var record=plots.lookup(sid,query("eventId",event)).at("/0/records/0/event");assertEquals(501,record.at("/originalSource/logId").asLong());assertEquals(502,record.at("/source/logId").asLong());
  jdbc.update("UPDATE story_plot_event SET record_json=JSON_REMOVE(record_json,'$.originalSource') WHERE session_id=?",sid);
  plots.apply(sid,503,0,3,360,update);assertEquals(501,plots.lookup(sid,query("eventId",event)).at("/0/records/0/event/originalSource/logId").asLong());
 }
}
