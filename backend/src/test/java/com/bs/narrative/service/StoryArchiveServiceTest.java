package com.bs.narrative.service;
import com.bs.narrative.llm.LLMProvider;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class StoryArchiveServiceTest {
 final ObjectMapper json=new ObjectMapper();final StoryArchiveService archive=new StoryArchiveService(mock(LLMProvider.class));
 @Test void finalizedRecordingDoesNotReceiveCopyeditingOrAnotherClockInstruction(){
  var facts=json.createObjectNode().put("finalManuscriptReady",true);var focus=archive.reviewFocus(facts,json.createObjectNode().put("story","已经完成的唯一正文"));
  assertEquals("FINAL_MANUSCRIPT_RECORD_ONLY",focus.path("publicationMode").asText());assertFalse(focus.has("publicationFocus"));assertFalse(focus.at("/timing/needsElapsedSeconds").asBoolean());
  assertFalse(StoryArchiveService.RECORD_CONTRACT.contains("publicationReview:{"));assertTrue(StoryArchiveService.CONTRACT.contains("publicationReview:{"));
  assertTrue(StoryArchiveService.RECORD_CONTRACT.contains("endingPlaces"));assertTrue(StoryArchiveService.RECORD_CONTRACT.contains("roleExperiences"));
 }
 @Test void personallyAttributedChangesAreNotLostWhenTheProviderUsesTheHandoffInsteadOfTheExperienceSection(){
  var raw=json.createObjectNode();var changes=raw.putObject("continuityState").putObject("narrativeState").putArray("characterChanges");
  changes.addObject().put("roleId","mother").put("cause","本人听儿子回报清土开沟完成").put("change","已知修妥，准备捎话");changes.addObject().put("roleId","outsider").put("change","没有本人新见闻的旁观摘要");
  var out=archive.normalize(raw.toString());assertEquals(1,out.path("roleExperiences").size());assertEquals("mother",out.at("/roleExperiences/0/roleId").asText());assertTrue(out.at("/roleExperiences/0/experience").asText().contains("本人听儿子回报"));assertEquals(out,archive.normalize(out.toString()));
  raw.putArray("roleExperiences").addObject().put("roleId","mother").put("experience","更完整的原始本人经历").put("situation","仍有原来的完整现状");out=archive.normalize(raw.toString());assertEquals(1,out.path("roleExperiences").size());assertEquals("更完整的原始本人经历",out.at("/roleExperiences/0/experience").asText());
 }
 @Test void unpublishedCopyeditingReceivesTheClockBoundToItsCurrentManuscript(){
  var draft=json.createObjectNode().put("story","当夜落雨，次日清早查看，直到那天夜里才歇下。").put("elapsedSeconds",999);
  draft.putObject("timeProgress").put("to","旧预估末尾").putArray("elapsedParts").addObject().put("amount",999).put("unit","DAY");var before=draft.deepCopy();
  var facts=json.createObjectNode().put("allowPublicationEdits",true);var input=archive.input(facts,draft);var focus=archive.reviewFocus(facts,draft);
  assertEquals(draft.path("elapsedSeconds"),input.at("/chapterOutcome/elapsedSeconds"));assertEquals(draft.path("timeProgress"),input.at("/chapterOutcome/timeProgress"));assertEquals(draft.path("timeProgress"),focus.at("/publicationFocus/authoredTimeFrame"));assertFalse(focus.at("/timing/needsElapsedSeconds").asBoolean());
  assertEquals(draft.path("story"),input.path("finalStory"));assertEquals(before,draft,"The saved provisional clock is retained, just not shown as the final quantity");
  facts.put("allowPublicationEdits",false);assertEquals(draft.path("timeProgress"),archive.input(facts,draft).at("/chapterOutcome/timeProgress"));
 }
 @Test void completeArchiveWithOnlyItsOuterObjectCloserMissingKeepsAllNestedResults()throws Exception{
  var raw=json.createObjectNode();raw.putObject("publication").put("unchanged",true).putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");
  var state=raw.putObject("continuityState");state.putObject("narrativeState").put("currentSituation","第七日夜里已回家");
  state.putArray("roleExperiences").addObject().put("roleId","mother").put("experience","听清已经回家");state.putArray("endingCast").addObject().put("roleId","mother").put("sceneId","home");
  state.putArray("itemOutcomes");state.putArray("objectiveUpdates").addObject().put("objectiveId","trip").put("status","COMPLETED");state.putArray("knownPlotReviews");
  String complete=raw.toString(),missing=complete.substring(0,complete.length()-1);var out=archive.normalize(missing);
  assertEquals("CLOSED_OUTER_OBJECT_AT_EOF",out.at("/wireFormatRepair/kind").asText());
  for(String field:java.util.List.of("roleExperiences","endingCast","itemOutcomes","objectiveUpdates","knownPlotReviews")){assertEquals(state.path(field),out.path(field));assertFalse(out.path("continuityState").has(field));}
  assertEquals(state.path("narrativeState"),out.at("/continuityState/narrativeState"));assertEquals(out,archive.normalize(out.toString()));
  assertEquals(archive.normalize(complete).path("roleExperiences"),out.path("roleExperiences"));
 }
 @Test void archiveFormatCompatibilityNeverInventsMissingValuesOrCompletesPartialStories(){
  for(String raw:java.util.List.of("{\"publication\":{\"story\":\"unfinished", "{\"endingCast\":[{\"roleId\":", "{\"endingCast\":[],", "{\"endingCast\":[],\"continuityState\":{\"narrativeState\":{}"))assertThrows(IllegalArgumentException.class,()->archive.normalize(raw));
 }
 @Test void minimalFinalPublicationAndProviderNestedPublicationKeepTextAndUnitsTogether(){
  var raw=json.createObjectNode();var publication=raw.putObject("publication").put("story","已到渡口，次日告辞。");publication.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",1).put("unit","DAY");
  var normalized=archive.normalize(raw.toString());assertEquals(publication,normalized.path("publication"));
  var nested=json.createObjectNode();nested.putObject("continuityState").set("publication",publication.deepCopy());
  var lifted=archive.normalize(nested.toString());assertEquals(publication,lifted.path("publication"));assertFalse(lifted.path("continuityState").has("publication"));assertEquals(lifted,archive.normalize(lifted.toString()));
  var facts=json.createObjectNode().put("allowPublicationEdits",true);var draft=json.createObjectNode().put("story","旧稿").put("elapsedSeconds",60);
  for(var projection:java.util.List.of(normalized,lifted)){archive.requireTiming(projection,draft,facts);var merged=archive.merge(draft,projection,facts);assertEquals(publication.path("story"),merged.path("story"));assertEquals(86400,merged.path("elapsedSeconds").asLong());}
 }
 @Test void authorPassageRelativeTimeFrameReachesArchiveWithoutMixingPreviousChapterOrdinals(){
  var facts=json.createObjectNode();facts.putArray("previousChapters").addObject().put("story","上一段第七日傍晚，韩七提点以后做活。完成的是此前那一周。");
  var draft=json.createObjectNode().put("story","接下来十天，三人议定分客办法并照做。第十日午后靠岸。").put("elapsedSeconds",864000).put("stop","RESOLVED");
  var time=draft.putObject("timeProgress").put("scope","THIS_PASSAGE").put("dayNumbering","从上一段结束后重新起算本篇十天").put("from","本篇开始").put("to","本篇第十日午后");time.putArray("elapsedParts").addObject().put("amount",10).put("unit","DAY");var original=draft.deepCopy();
  var input=archive.input(facts,draft);assertEquals(time,input.at("/chapterOutcome/timeProgress"));assertEquals(864000,input.at("/chapterOutcome/elapsedSeconds").asLong());assertEquals(original,draft);assertTrue(input.at("/before/latestPublishedPassage").asText().contains("第七日"));
 }
 @Test void providerNestedPublicReviewsAndContinuityAreLiftedVerbatimWithoutAnotherCall(){
  var raw=json.createObjectNode();var state=raw.putObject("continuityState");state.putArray("accounts").addObject().put("key","case");state.put("continuity","旧账已获解释，但收入与已支说法仍有方向冲突。");state.putArray("knownPlotReviews").addObject().put("threadId","fo_1").put("changed",true).putObject("playerView").put("summary","六两收入与九两已支总括冲突").put("status","已获说明仍有矛盾");
  var before=raw.deepCopy();var normalized=archive.normalize(raw.toString());assertEquals(before.at("/continuityState/continuity"),normalized.path("continuity"));assertEquals(before.at("/continuityState/knownPlotReviews"),normalized.path("knownPlotReviews"));assertEquals("fo_1",normalized.at("/plotUpdates/foreshadows/0/threadId").asText());assertFalse(normalized.path("continuityState").has("knownPlotReviews"));assertEquals(normalized,archive.normalize(normalized.toString()));
 }
 @Test void sourceReviewedAccountsAreCachedAndOnlyNewEvidenceRequestsAnotherPass(){
  var facts=json.createObjectNode();facts.putObject("continuityState").putArray("accounts").addObject().put("key","same-case").put("sourceReview","MAIN_API");
  var projection=json.createObjectNode();var row=projection.putObject("continuityState").putArray("accounts").addObject().put("key","same-case");assertFalse(archive.needsAccountSources(facts,projection));row.put("newEvidence",true);assertTrue(archive.needsAccountSources(facts,projection));
  projection.putObject("accountReview").put("status","DONE");assertFalse(archive.needsAccountSources(facts,projection),"Durably cached completion must not pay again on recovery");
 }
 @Test void focusedSourcePassReplacesOnlyItsExplicitPublicCardsAndNeverTheFixedStory()throws Exception{
  var model=mock(LLMProvider.class);var service=new StoryArchiveService(model);var raw=json.createObjectNode().put("continuity","既有收付说法仍有方向矛盾。");raw.putObject("continuityState").putArray("accounts").addObject().put("key","same-case").putArray("unresolved").add("六两收入被旧说法合称支出");raw.putArray("knownPlotReviews").addObject().put("eventId","evt_old").put("changed",true).putObject("playerView").put("summary","已获解释，六两方向仍与九两支出说法冲突").put("status","已获说明仍有矛盾");
  when(model.chatForJson(anyList())).thenReturn(new com.bs.narrative.llm.LLMResponse(raw.toString(),raw.toString(),0,0,0,0));var old=service.normalize("{\"continuityState\":{\"accounts\":[{\"key\":\"same-case\"}]},\"knownPlotReviews\":[{\"eventId\":\"evt_old\",\"changed\":true,\"playerView\":{\"summary\":\"旧错误的全部核清\"}}],\"timeProgress\":{\"elapsedSeconds\":1382400}}");
  var draft=json.createObjectNode().put("story","不可改写的已发表正文").put("elapsedSeconds",2678400);var out=service.reconcileAccounts(json.createObjectNode(),draft,old);
  assertEquals("MAIN_API",out.at("/continuityState/accounts/0/sourceReview").asText());assertFalse(out.path("plotUpdates").toString().contains("旧错误的全部核清"));assertEquals("已获说明仍有矛盾",out.at("/plotUpdates/events/0/playerView/status").asText());assertEquals(draft.path("story"),service.merge(draft,out).path("story"));assertEquals(2678400,service.merge(draft,out).path("elapsedSeconds").asLong());
 }
 @Test void unavailableSourcePassDoesNotBlockSavingTheStory(){
  var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenThrow(new com.bs.narrative.llm.LLMApiException("MODEL_CALL_LIMIT: test",false));var service=new StoryArchiveService(model);var projection=service.normalize("{\"continuityState\":{\"accounts\":[{\"key\":\"new-case\"}]}}");var result=service.completeAccounts(json.createObjectNode(),json.createObjectNode().put("story","仍可保存的正文"),projection,raw->{});
  assertEquals("UNREVIEWED",result.at("/accountReview/status").asText());assertEquals("UNREVIEWED",result.at("/continuityState/accounts/0/sourceReview").asText());
 }
 @Test void missingThreadReviewOrCumulativeSummaryCannotBeCachedAsComplete()throws Exception{
  var model=mock(LLMProvider.class);var service=new StoryArchiveService(model);var raw=json.createObjectNode();raw.putObject("continuityState").putArray("accounts").addObject().put("key","case");raw.putArray("knownPlotReviews").addObject().put("eventId","evt_1").put("changed",false);
  when(model.chatForJson(anyList())).thenReturn(new com.bs.narrative.llm.LLMResponse(raw.toString(),raw.toString(),0,0,0,0));var facts=json.createObjectNode();var card=facts.putObject("plotMemory").putArray("playerKnownState").addObject().put("eventId","evt_1");card.putObject("eventView").put("summary","旧案");card.putArray("foreshadows").addObject().put("threadId","fo_1").put("summary","未同步伏笔");
  var out=service.reconcileAccounts(facts,json.createObjectNode().put("story","原故事"),service.normalize("{\"continuityState\":{\"accounts\":[{\"key\":\"case\"}]}}"));assertEquals("PARTIAL",out.at("/accountReview/status").asText());assertEquals("UNREVIEWED",out.at("/continuityState/accounts/0/sourceReview").asText());
 }
 @Test void authorUnitsCorrectItsOldSecondsAndArchiveCannotReplaceThePassageClock(){
  var draft=json.createObjectNode().put("story","此前已做满十五日；今日做满三十日领薪，翌日探友至晚。").put("elapsedSeconds",2678400);
  draft.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",16).put("unit","DAY");
  var projection=archive.normalize("{\"endingCast\":[],\"timeProgress\":{\"from\":\"第十五日傍晚\",\"to\":\"第三十一日傍晚\",\"elapsedSeconds\":1382400,\"basis\":\"只计后十五日及次日\"}}");archive.requireTiming(projection,draft);
  var merged=archive.merge(draft,projection);assertEquals(1382400,merged.path("elapsedSeconds").asLong());assertEquals(draft.path("story"),merged.path("story"));assertEquals(2678400,draft.path("elapsedSeconds").asLong());
  assertEquals("STORY_AUTHOR",merged.at("/timeProgress/source").asText());
 }
 @Test void modelChosenDaysAndRemainderAreConvertedWithoutJudgingTheNarrative(){
  var projection=archive.normalize("{\"timeProgress\":{\"elapsedDays\":16,\"elapsedRemainderSeconds\":3600,\"elapsedSeconds\":1296000,\"basis\":\"由主API决定的十六日又一小时\"}}");
  assertEquals(1386000,projection.at("/timeProgress/elapsedSeconds").asLong());assertEquals(1296000,projection.at("/timeProgress/reportedElapsedSeconds").asLong());assertEquals(projection,archive.normalize(projection.toString()));
 }
 @Test void explicitPublicReviewsUseTheExistingProjectionAndNormalizeIdempotently(){
  var raw=json.createObjectNode();var reviews=raw.putArray("knownPlotReviews");reviews.addObject().put("eventId","evt_old").put("changed",true).put("reason","前章已听完，原卡漏更").putObject("playerView").put("title","旧账").put("summary","已获说法，收付方向仍矛盾").put("status","已获说明但尚有矛盾");
  reviews.addObject().put("threadId","fo_old").put("changed",false).put("reason","未知者仍未知").putObject("playerView").put("summary","不得写入的秘密");
  var normalized=archive.normalize(raw.toString());assertEquals("evt_old",normalized.at("/plotUpdates/events/0/eventId").asText());assertFalse(normalized.at("/plotUpdates/events/0").has("status"));assertFalse(normalized.path("plotUpdates").toString().contains("不得写入"));
  assertEquals(normalized,archive.normalize(normalized.toString()));
 }
 @Test void significantAccountSnapshotsReachBothAuthoringAndArchiveWithoutArithmeticGuessing(){
  var facts=json.createObjectNode();var account=facts.putObject("continuityState").putArray("accounts").addObject().put("key","old-account").put("subject","同一主体的收付");account.putArray("entries").addObject().put("amount","六两").put("from","德昌").put("to","同福").put("status","收讫");account.withArray("entries").addObject().put("amount","三两").put("from","同福").put("to","木匠").put("status","付讫");account.putArray("unresolved").add("有人合称已支九两，与方向不符");
  var input=archive.input(facts,json.createObjectNode().put("story","只整理已知收付。"));assertEquals(facts.path("continuityState"),input.at("/before/continuityState"));assertEquals(facts.path("continuityState"),StoryAuthorInput.prepare(facts).path("continuityState"));
  assertEquals("/continuityState",StoryAuthorContext.brief(facts).at("/factSources/significantAccountsAndTimeRef").asText());
 }
 @Test void explicitlyQueriedRoleHistoryReachesTheArchiveWithoutBecomingNewExperience(){
  var facts=json.createObjectNode();var page=facts.putArray("worldBookResults").addObject().put("roleId","role_old");var entries=page.putArray("entries");
  entries.addObject().put("entryId",901).put("recordType","CHARACTER_HISTORY").putObject("content").put("recordType","TURN_EVENT").putObject("content").put("text","此人早先已将旧印交还原主。");
  entries.addObject().put("entryId",902).put("recordType","CHARACTER_DOSSIER").putObject("content").putObject("intentions").put("intention","过去希望取回旧印，尚未执行");
  var draft=json.createObjectNode().put("story","主角拜访原主，核对旧印。");var before=facts.deepCopy();var input=archive.input(facts,draft);
  assertEquals(facts.path("worldBookResults"),input.at("/before/worldBookResults"));assertEquals(before,facts);assertEquals(draft.path("story"),input.path("finalStory"));
  assertEquals("TURN_EVENT",input.at("/before/worldBookResults/0/entries/0/content/recordType").asText());assertFalse(input.has("roleExperiences"));
 }
 @Test void archiveReceivesAllRecentlyPublishedEvidenceWithoutDuplicatingTheLatestPassage(){
  var facts=json.createObjectNode();var chapters=facts.putArray("previousChapters");
  chapters.addObject().put("logId",1).put("story","已取得凭据").put("recordedStartSeconds",0).put("recordedEndSeconds",600);
  chapters.addObject().put("logId",2).put("story","已当面说明并交账").put("recordedStartSeconds",600).put("recordedEndSeconds",1200);
  var input=archive.input(facts,json.createObjectNode().put("story","领酬后离开。"));
  assertEquals("已当面说明并交账",input.at("/before/latestPublishedPassage").asText());assertEquals(1,input.at("/before/previousChapters").size());assertEquals("已取得凭据",input.at("/before/previousChapters/0/story").asText());assertEquals(600,input.at("/before/previousChapters/0/recordedEndSeconds").asLong());
  facts.putArray("alreadyHappenedThisChapter").add("本回合已完成告别").add("已到码头");input=archive.input(facts,json.createObjectNode().put("story","登船南下。"));
  assertEquals(2,input.at("/before/previousChapters").size());assertEquals("已到码头",input.at("/before/latestPublishedPassage").asText());assertEquals("本回合已完成告别",input.at("/before/earlierPublishedPassagesThisChapter/0").asText());
 }
 @Test void visibleCardUpdatesMergeOnceWithoutImportingPrivateFactsOrChangingAuthorStatus(){
  var raw=json.createObjectNode();raw.putArray("endingCast");
  raw.putObject("plotUpdates").putArray("events").addObject().put("eventId","evt_1").put("status","OPEN").put("development","幕后仍在追查");
  var visible=raw.putObject("playerViewUpdates");var card=visible.putArray("events").addObject().put("eventId","evt_1").put("status","RESOLVED").put("authorNotes","不应被复制的秘密");
  card.putObject("playerView").put("summary","已听完对方解释，仍有疑点").put("status","已有说明");
  visible.putArray("foreshadows").addObject().put("threadId","fo_1").putObject("playerView").put("summary","已经亲见残页并核对").put("status","已回收");
  var out=archive.normalize(raw.toString());assertEquals(1,out.at("/plotUpdates/events").size());assertEquals("OPEN",out.at("/plotUpdates/events/0/status").asText());assertEquals("已有说明",out.at("/plotUpdates/events/0/playerView/status").asText());
  assertFalse(out.path("plotUpdates").toString().contains("不应被复制"));assertFalse(out.at("/plotUpdates/foreshadows/0").has("status"));assertEquals("fo_1",out.at("/plotUpdates/foreshadows/0/threadId").asText());assertEquals(out.path("plotUpdates"),archive.normalize(out.toString()).path("plotUpdates"));
 }
 @Test void finalReadingFocusDistinguishesOriginalGoalsPublicCardsAndIncrementalTime(){
  var facts=json.createObjectNode().put("storySeconds",10000);facts.putArray("playerObjectives").addObject().put("objectiveId","goal_1").put("title","抵达并找到工作").put("status","ACTIVE");facts.withArray("playerObjectives").addObject().put("objectiveId","goal_2").put("status","COMPLETED");
  facts.putObject("plotMemory").putArray("playerKnownState").addObject().put("eventId","evt_1").putObject("eventView").put("summary","上次保存的本人所知");
  var draft=json.createObjectNode().put("story","已从工作第十日做到第二十日。");var focus=archive.reviewFocus(facts,draft);assertTrue(focus.at("/timing/needsElapsedSeconds").asBoolean());assertEquals(10000,focus.at("/timing/chapterStartedAtSeconds").asLong());assertEquals(1,focus.path("originalObjectivesToReview").size());assertEquals("/playerKnownPlotStates",focus.path("savedPlayerPlotCardsRef").asText());assertEquals("evt_1",focus.at("/publicCardReviewTasks/0/eventId").asText());assertEquals(10000,archive.input(facts,draft).at("/before/storySeconds").asLong());
  draft.put("elapsedSeconds",864000);assertFalse(archive.reviewFocus(facts,draft).at("/timing/needsElapsedSeconds").asBoolean());
 }
 @Test void archiverCanSupplyMissingDurationButCannotOverrideTheWritersGivenDuration(){
  var draft=json.createObjectNode().put("story","半个月过去了。");var projected=archive.normalize("{\"endingCast\":[],\"elapsedSeconds\":1296000}");archive.requireTiming(projected,draft);
  assertEquals(1296000,archive.merge(draft,projected).path("elapsedSeconds").asLong());
  draft.put("elapsedSeconds",300);assertEquals(300,archive.merge(draft,projected).path("elapsedSeconds").asLong());
  draft.remove("elapsedSeconds");assertThrows(IllegalArgumentException.class,()->archive.requireTiming(archive.normalize("{\"endingCast\":[]}"),draft));
 }
 @Test void publicPlotCardsArePresentedSeparatelyWithoutReplacingThemWithPrivateAuthorFacts(){
  var facts=json.createObjectNode();var plot=facts.putObject("plotMemory");
  var known=plot.putArray("playerKnownState");known.addObject().put("eventId","evt_1").putObject("eventView").put("status","待查");
  plot.putArray("recentEvents").addObject().put("privateTruth","尚未公开的幕后结果").put("status","RESOLVED");
  var original=facts.deepCopy();var input=archive.input(facts,json.createObjectNode().put("story","玩家已转入新的生活。"));
  assertEquals(known,input.path("playerKnownPlotStates"));assertFalse(input.path("playerKnownPlotStates").toString().contains("尚未公开"));
  assertEquals("待查",input.at("/playerKnownPlotStates/0/eventView/status").asText());assertEquals(original,facts);
 }
 @Test void archiveReceivesTheWritersUnchosenDecisionAndCurrentObjectivesWithoutMutatingThem(){
  var facts=json.createObjectNode();facts.putArray("playerObjectives").addObject().put("objectiveId","OBJ-01").put("currentStep","尚待说明查账条件");
  var draft=json.createObjectNode().put("story","东家说完新增条件，等沈砚表态。").put("stop","DECISION");draft.putObject("decision").put("type","COMMITMENT").put("question","是否接受新增条件？");
  var input=archive.input(facts,draft);assertEquals("DECISION",input.at("/chapterOutcome/stop").asText());assertEquals(draft.path("decision"),input.at("/chapterOutcome/decision"));assertEquals(facts.path("playerObjectives"),input.at("/before/playerObjectives"));
  var projection=archive.normalize("{\"endingCast\":[],\"objectiveUpdates\":[{\"objectiveId\":\"OBJ-01\",\"status\":\"ACTIVE\",\"currentStep\":\"条件已说明，待玩家选择\"}],\"plotUpdates\":{\"events\":[{\"eventId\":\"evt_old\",\"development\":\"条件已说明\"}]}}");
  var merged=archive.merge(draft,projection,facts);assertEquals(projection.path("objectiveUpdates"),merged.path("objectiveUpdates"));assertEquals(projection.path("plotUpdates"),merged.path("plotUpdates"));assertEquals(draft.path("decision"),merged.path("decision"));assertEquals("尚待说明查账条件",facts.at("/playerObjectives/0/currentStep").asText());
 }
 @Test void plotEventsInThePhysicalEventsArrayAreMovedWithoutLosingTheirOriginalRecord()throws Exception{
  var raw=json.createObjectNode();var plot=json.createObjectNode().put("eventId","evt_1").put("title","高处安顿").put("category","JOURNEY").put("status","RESOLVED").put("development","已实际入住");
  raw.putArray("events").add(plot).addObject().put("kind","HEALTH").put("actorId","p").put("health",80);raw.putObject("plotUpdates").putArray("events").add(plot.deepCopy());
  assertTrue(StoryArchiveService.hasMisplacedPlotEvents(raw.toString()));var out=archive.normalize(raw.toString());assertEquals(1,out.path("events").size());assertEquals("HEALTH",out.at("/events/0/kind").asText());assertEquals(1,out.at("/plotUpdates/events").size());assertEquals(plot,out.at("/plotUpdates/events/0"));
 }
 @Test void aMisfiledPlotWithoutItsOwnSectionIsPreservedAndUnidentifiedEffectsAreNotSilentlyDropped(){
  var raw=json.createObjectNode();raw.putArray("events").addObject().put("eventId","evt_2").put("title","旧事已明").put("category","INVESTIGATION");raw.withArray("events").addObject().put("unexpected","not an identified plot record");
  var out=archive.normalize(raw.toString());assertEquals(1,out.at("/plotUpdates/events").size());assertEquals("not an identified plot record",out.at("/events/0/unexpected").asText());assertFalse(StoryArchiveService.hasMisplacedPlotEvents("not JSON"));
 }
 @Test void archiveIdentitySurvivesDatabaseObjectKeyReorderingButNotStoryOrArrayChanges()throws Exception{
  var a=json.readTree("{\"story\":\"原样正文\",\"stop\":\"RESOLVED\",\"authorPlan\":{\"b\":2,\"a\":1},\"roles\":[\"p\",\"n\"]}");
  var b=json.readTree("{\"roles\":[\"p\",\"n\"],\"authorPlan\":{\"a\":1,\"b\":2},\"stop\":\"RESOLVED\",\"story\":\"原样正文\"}");
  assertEquals(StoryArchiveService.source(a),StoryArchiveService.source(b));
  ((ObjectNode)b).put("story","改变的正文");assertNotEquals(StoryArchiveService.source(a),StoryArchiveService.source(b));
  ((ObjectNode)b).put("story","原样正文");((ObjectNode)b).putArray("roles").add("n").add("p");assertNotEquals(StoryArchiveService.source(a),StoryArchiveService.source(b));
 }
 @Test void optionalUnchangedCollectionsAndJournalCanBeOmittedOrNull(){
  var out=archive.normalize("{\"endingCast\":[],\"continuity\":\"仍沿原有经历继续。\",\"contestResolutions\":null}");
  for(String field:java.util.List.of("events","sceneOutcomes","itemOutcomes","roleExperiences","contestResolutions","newCharacterExperiences"))assertTrue(out.path(field).isArray());
  assertTrue(out.path("playerJournal").isObject());assertEquals("仍沿原有经历继续。",out.path("continuity").asText());
 }
 @Test void absentContinuityDoesNotInventANewSummaryAndUnrelatedRepliesAreNotArchives(){
  assertEquals("",archive.normalize("{\"endingCast\":[]}").path("continuity").asText());
  assertThrows(IllegalArgumentException.class,()->archive.normalize("{\"story\":\"这不是归档结果\"}"));
 }
 @Test void archiveGetsAccumulatedFactsAndTheLatestCommittedSourceButNotPlannedResults(){
  var facts=json.createObjectNode();facts.putArray("storyMemory").add("早先已解决的失踪案与尚待回应的邀请");facts.putArray("establishedStoryFacts").addObject().put("fact","原始登记已经核实");facts.putArray("previousChapters").addObject().put("story","两人已经抵达渡口");facts.putArray("alreadyHappenedThisChapter").add("知秋已收好映潮印，二人尚未结盟");facts.putObject("roleDirections").put("privatePlan","UNEXECUTED_PLAN");var draft=json.createObjectNode().put("story","二人继续核对登记，完成当下调查。");draft.putArray("roleExperiences").addObject().put("experience","CONTRADICTORY_DRAFT_TABLE");
  var before=facts.deepCopy();var input=archive.input(facts,draft);assertEquals(facts.path("storyMemory"),input.at("/before/storyMemory"));assertEquals(facts.path("establishedStoryFacts"),input.at("/before/establishedStoryFacts"));assertEquals("知秋已收好映潮印，二人尚未结盟",input.at("/before/latestPublishedPassage").asText());assertFalse(input.toString().contains("UNEXECUTED_PLAN"));assertFalse(input.toString().contains("CONTRADICTORY_DRAFT_TABLE"));assertEquals(before,facts);
 }
 @Test void aNewChapterFallsBackToTheLastPublishedChapterWithoutInventingHistory(){
  var facts=json.createObjectNode();facts.putArray("previousChapters").addObject().put("story","已完成旧委托");facts.putArray("alreadyHappenedThisChapter");var input=archive.input(facts,json.createObjectNode().put("story","新的经过"));assertEquals("已完成旧委托",input.at("/before/latestPublishedPassage").asText());assertEquals("",archive.input(json.createObjectNode(),json.createObjectNode()).at("/before/latestPublishedPassage").asText());
 }
 @Test void authorPlansArePrivateAndOnlyTheFinalProjectionCanSupplyPlotFacts(){
  var facts=json.createObjectNode();facts.putObject("plotMemory").putArray("eventIndex").addObject().put("eventId","evt_old");
  var draft=json.createObjectNode().put("story","主角亲见旧册缺页。");draft.putObject("authorPlan").put("possibleExplanation","尚未发生的解释");draft.putObject("plotUpdates").putArray("events").addObject().put("outcome","未提交候选结果");
  var input=archive.input(facts,draft);assertEquals("UNCOMMITTED_AUTHOR_PLAN",input.at("/authorPlanning/status").asText());assertTrue(input.path("authorPlanning").toString().contains("尚未发生的解释"));assertFalse(input.path("before").toString().contains("尚未发生的解释"));assertFalse(input.toString().contains("未提交候选结果"));
  var projection=json.createObjectNode();assertFalse(archive.merge(draft,projection).has("plotUpdates"));
  projection.putObject("plotUpdates").putArray("events").addObject().put("eventId","evt_old").put("development","亲见缺页");assertEquals(projection.path("plotUpdates"),archive.merge(draft,projection).path("plotUpdates"));
 }
 @Test void archivalNotesCannotInventFutureMysteriesAndWriterPlansStayVerbatim(){
  var draft=json.createObjectNode().put("story","已经说明旧册的来历，主角尚未决定善后。");var plan=draft.putObject("authorPlan").put("centralQuestion","旧册来历与人物回应");plan.putArray("eventIds").add("evt_old");plan.putArray("possibleDevelopments").add("主角听完自行决定");
  var projection=json.createObjectNode();var updates=projection.putObject("plotUpdates");var event=updates.putArray("events").addObject().put("eventId","evt_old");event.putObject("authorNotes").put("newMystery","归档私自新增幕后隐情");var thread=updates.putArray("foreshadows").addObject().put("threadId","fo_old").put("status","PAID_OFF").put("payoff","来历已解释");thread.putObject("authorNotes").put("newMystery","归档私自新增另一个反转");
  var merged=archive.merge(draft,projection);assertEquals(plan,merged.at("/plotUpdates/events/0/authorNotes/content"));assertEquals("UNCOMMITTED_AUTHOR_PLAN",merged.at("/plotUpdates/events/0/authorNotes/status").asText());assertFalse(merged.toString().contains("归档私自新增"));assertEquals("PAID_OFF",merged.at("/plotUpdates/foreshadows/0/status").asText());
  var fresh=json.createObjectNode();fresh.putObject("plotUpdates").putArray("events").addObject().put("key","newCase").put("useAuthorPlan",true);assertEquals(plan,archive.merge(draft,fresh).at("/plotUpdates/events/0/authorNotes/content"));
 }

 @Test void focusedAccountReviewCannotEraseUnrelatedLastingConsequences(){
  var model=mock(LLMProvider.class);var service=new StoryArchiveService(model);var reply=json.createObjectNode().put("continuity","收付依据已按原文整理");reply.putObject("continuityState").putArray("accounts").addObject().put("key","account");
  when(model.chatForJson(anyList())).thenReturn(new com.bs.narrative.llm.LLMResponse(reply.toString(),reply.toString(),0,0,0,0));
  var old=json.createObjectNode();var state=old.putObject("continuityState");state.putArray("accounts").addObject().put("key","account");var consequences=state.putArray("consequences");consequences.addObject().put("key","promise").put("actualOutcome","只负责这次护送，不承担余债");
  var out=service.reconcileAccounts(json.createObjectNode(),json.createObjectNode().put("story","护送范围已经谈定"),service.normalize(old.toString()));assertEquals(consequences,out.at("/continuityState/consequences"));assertTrue(out.at("/continuityState/accounts/0/sourceReview").isTextual());
 }

 @Test void offSceneTrackedCustodyIsAvailableToArchiveWithoutBecomingProseOrNewEvents(){
  var facts=json.createObjectNode();facts.putArray("importantItemStates").addObject().put("ref","old-parcel").put("name","重要货匣").put("holderId","remote-employer");var input=archive.input(facts,json.createObjectNode().put("story","店铺掌柜已验收货匣，主角离开。"));assertEquals(facts.path("importantItemStates"),input.at("/before/importantItemStates"));assertFalse(input.has("itemOutcomes"));
 }

 @Test void archivePolicyStaysScopedAndProjectionDoesNotRegenerateTheStory(){
  var model=mock(LLMProvider.class);var policies=new java.util.ArrayList<com.bs.narrative.llm.LLMRequestPolicy>();var raw=json.createObjectNode();raw.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",15).put("unit","DAY");
  when(model.chatForJson(anyList())).thenAnswer(call->{policies.add(com.bs.narrative.llm.LLMRequestPolicy.current());return new com.bs.narrative.llm.LLMResponse(raw.toString(),raw.toString(),0,0,0,0);});var service=new StoryArchiveService(model);var draft=json.createObjectNode().put("story","The published story is unchanged.").put("elapsedSeconds",3600);var before=draft.deepCopy();var result=service.project(json.createObjectNode(),draft,"");
  assertEquals(before,draft);assertEquals(3600,result.at("/timeProgress/elapsedSeconds").asLong());assertEquals(15*86400,result.at("/archiveTimeInterpretation/elapsedSeconds").asLong());assertEquals(java.util.List.of(com.bs.narrative.llm.LLMRequestPolicy.STORY_ARCHIVE),policies);assertEquals(com.bs.narrative.llm.LLMRequestPolicy.DEFAULT,com.bs.narrative.llm.LLMRequestPolicy.current());verify(model,times(1)).chatForJson(anyList());
 }
}
