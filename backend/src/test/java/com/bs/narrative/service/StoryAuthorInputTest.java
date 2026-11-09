package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryAuthorInputTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode fixture(){
  var f=json.createObjectNode().put("storyFirst",true).put("storyArchive",true).put("playerDirection","沿线索查明原因并处理后果");
  f.putArray("previousChapters").addObject().put("story","失船已经归还，船主与玩家在船边。");f.putArray("storyMemory").add("船已归还，登记疑点尚未解决。");
  var d=f.putObject("characterDossiers").putObject("owner").put("name","船主");d.putObject("identity").putArray("establishedSelf").add("本地船主");d.withObject("identity").putArray("goals").add("查明冒借者");
  d.putObject("currentState").put("storySituation","船已经归还，人在船边").put("plannedWork","准备再访码头").putObject("body").put("health",61);
  d.putObject("psychology").putObject("beliefs").put("suspicion","怀疑有人故意毁船，未核实");d.putObject("intentions").put("intention","愿意现场查看归还的船").putObject("combatDisposition").put("rescueIntent","若落水愿意施救");
  d.withObject("intentions").putArray("itemIntentions").addObject().put("intention","愿意把记录交给玩家查看").put("itemRef","ledger");
  var h=d.putObject("pastInformation").put("historyOrder","OLDEST_TO_NEWEST");var records=h.putArray("ownRecords");records.addObject().put("source_type","ROLE_PROFILE").put("content","旧想法：等待失船归还");records.addObject().put("source_type","TURN_EVENT").put("content","失船归还，本人已经检查船底");h.put("ownKnowledge","邻人曾说船未归，是旧说法");
  f.putObject("roleDirections").set("owner",d.path("intentions").deepCopy());f.putObject("privateRoleContexts").putObject("owner").put("private","complete role context");f.putObject("playerContext").put("private","complete player context");return f;
 }
 @Test void archivedOldPsychologyIsLookedUpWhileCurrentDetailedTemplateAndActualHistoryRemain(){
  var f=fixture();f.putArray("worldBookIndex").addObject().put("roleId","owner").put("entries",12);var before=f.deepCopy();var input=StoryAuthorInput.prepare(f);
  var history=input.at("/characterDossiers/owner/pastInformation");assertEquals(1,history.path("ownRecords").size());assertTrue(history.path("ownRecords").toString().contains("已经检查船底"));assertFalse(input.toString().contains("旧想法：等待失船归还"));
  assertEquals("owner",history.at("/historyLookup/roleId").asText());assertEquals(1,history.path("historicalSubjectiveRecordCount").asInt());assertEquals(f.at("/characterDossiers/owner/psychology"),input.at("/characterDossiers/owner/psychology"));assertEquals(before,f);
 }
 @Test void unarchivedHistoricalPlansAreRetainedSeparatelyRatherThanSilentlyLost(){
  var input=StoryAuthorInput.prepare(fixture());var h=input.at("/characterDossiers/owner/pastInformation");assertTrue(h.path("historicalSubjectiveRecords").toString().contains("等待失船归还"));assertFalse(h.path("ownRecords").toString().contains("等待失船归还"));assertFalse(h.has("historyLookup"));
 }
 @Test void anArchiveIndexDoesNotAuthorizeDroppingUnimportedAttemptAndTaskRecords(){
  var f=fixture();f.putArray("worldBookIndex").addObject().put("roleId","owner").put("entries",2);
  for(String source:List.of("SCENE_ATTEMPT","ROLE_TASK","ROLE_DECISION"))f.withObject("characterDossiers").withObject("owner").withObject("pastInformation").withArray("ownRecords").addObject().put("source_type",source).put("content",source+" proposed only");
  var h=StoryAuthorInput.prepare(f).at("/characterDossiers/owner/pastInformation");assertEquals(3,h.path("historicalSubjectiveRecords").size());assertEquals(1,h.path("ownRecords").size());
 }
 @Test void beliefsAimsAndPlansDoNotOverwriteActualStateOrBecomeCompletedActions(){
  var input=StoryAuthorInput.prepare(fixture());var d=input.at("/characterDossiers/owner");assertEquals(61,d.at("/currentState/body/health").asInt());assertEquals("船已经归还，人在船边",d.at("/currentState/storySituation").asText());assertFalse(d.path("currentState").has("plannedWork"));assertTrue(d.path("previouslyPlannedActivity").asText().contains("准备"));assertEquals("查明冒借者",d.at("/personalAims/0").asText());assertFalse(d.path("identity").has("goals"));assertTrue(d.at("/psychology/beliefs/suspicion").asText().contains("未核实"));assertTrue(d.at("/pastInformation/ownKnowledge").asText().contains("邻人曾说"));
 }
 @Test void latestItemAndCombatIntentionsHaveOneResolvableSource(){
  var f=fixture();var input=StoryAuthorInput.prepare(f);var ref=input.at("/roleDirections/owner/$ref").asText();assertEquals(f.at("/roleDirections/owner"),input.at(ref));assertEquals(1,input.at("/roleDirections/owner").size());assertTrue(input.at(ref+"/combatDisposition/rescueIntent").asText().contains("施救"));assertEquals("ledger",input.at(ref+"/itemIntentions/0/itemRef").asText());
 }
 @Test void explicitNewestFirstPlayerHistoryIsRenderedChronologicallyWithoutGuessingFromWords(){
  var f=fixture();var h=f.withObject("characterDossiers").withObject("owner").withObject("pastInformation").put("historyOrder","NEWEST_TO_OLDEST");var rows=h.putArray("ownRecords");rows.addObject().put("source_type","TURN_EVENT").put("content","第二段");rows.addObject().put("source_type","TURN_EVENT").put("content","第一段");
  var history=StoryAuthorInput.prepare(f).at("/characterDossiers/owner/pastInformation");assertEquals("第一段",history.at("/ownRecords/0/content").asText());assertEquals("第二段",history.at("/ownRecords/1/content").asText());assertEquals("OLDEST_TO_NEWEST",history.path("historyOrder").asText());assertEquals("第二段",rows.get(0).path("content").asText());
 }
 @Test void anOlderFrozenDossierCannotHideANewerOriginalDirection(){
  var f=fixture();f.withObject("roleDirections").withObject("owner").put("intention","这次改变想法，愿意同行查清原因");var input=StoryAuthorInput.prepare(f);
  assertFalse(input.at("/roleDirections/owner").has("$ref"));assertTrue(input.at("/roleDirections/owner/intention").asText().contains("改变想法"));assertTrue(input.at("/characterDossiers/owner/intentions/intention").asText().contains("现场查看"));
 }
 @Test void finalGuideKeepsSourceReferenceAndDirectlyDisplaysExactLatestCommittedProse(){
  var f=fixture();f.putArray("alreadyHappenedThisChapter").add("登记已经核实，这段已发布，不能再问一遍。");var input=StoryAuthorInput.prepare(f);var guide=StoryAuthorContext.brief(input);assertEquals("登记已经核实，这段已发布，不能再问一遍。",input.at(guide.at("/factSources/latestPublishedPassageRef").asText()).asText());assertEquals(f.path("storyMemory"),input.at(guide.at("/factSources/accumulatedContinuityRef").asText()));assertEquals(f.at("/alreadyHappenedThisChapter/0"),guide.at("/continuation/latestPublishedPassage"));assertFalse(guide.has("playerJournalRecording"));
 }
 @Test void normalAuthorRequestUsesProseContractAndDoesNotChangeAnyStoredRoleInput()throws Exception{
  var f=fixture();var before=f.deepCopy();var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);assertEquals(LLMRequestPolicy.STORY_AUTHOR,LLMRequestPolicy.current());assertTrue(m.get(0).get("content").startsWith(StoryFirstContract.AUTHORING_PROMPT));assertTrue(m.get(0).get("content").contains("MODEL_PROTOCOL_V1/AUTHOR"));var input=json.readTree(m.get(1).get("content"));assertFalse(input.has("privateRoleContexts"));assertFalse(input.has("playerContext"));assertFalse(input.at("/roleDirections/owner").has("intention"));assertTrue(input.at("/characterDossiers/owner/intentions/intention").asText().contains("查看归还的船"));assertEquals(4,m.size());assertEquals(NarrativeMode.guidance(NarrativeMode.LONG),m.get(2).get("content"));return new LLMResponse("{}","{}",0,0,0,0);});new SceneStoryService(model).write(f,null,"",System.nanoTime()+60_000_000_000L);verify(model,times(1)).chatForJson(anyList());assertEquals(before,f);
 }
 @Test void finalIdentityAnchorsRemainExactOwnSourcesAndDoNotCopyOtherPeoplesPsychology(){
  var facts=fixture();facts.withObject("characterDossiers").withObject("owner").put("name","船主").withObject("identity").putArray("establishedSelf").add("船主是小周的父亲，玩家只是问路的过客。");var before=facts.deepCopy();var guide=StoryAuthorContext.brief(StoryAuthorInput.prepare(facts));assertEquals(facts.at("/characterDossiers/owner/identity/establishedSelf"),guide.at("/characterUpdates/0/establishedIdentity"));assertEquals("船主",guide.at("/characterUpdates/0/name").asText());assertFalse(guide.at("/characterUpdates/0").has("psychology"));assertEquals(before,facts);
 }
 @Test void malformedPaidReplyIsRetainedOutsideTheNextAuthorConversation()throws Exception{
  var facts=fixture();var before=facts.deepCopy();var broken=json.createObjectNode().put("unparsedResponse","   ").put("parseIssue","not an object");var model=mock(LLMProvider.class);
  when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);assertTrue(messages.stream().noneMatch(m->"assistant".equals(m.get("role"))));assertTrue(messages.stream().noneMatch(m->m.get("content").contains("unparsedResponse")));String finalMessage=messages.get(messages.size()-1).get("content");var guide=json.readTree(finalMessage.substring(finalMessage.indexOf('\n')+1));assertTrue(guide.path("responseInstruction").asText().contains("JSON"));assertEquals(facts.path("playerDirection"),guide.at("/episodeFocus/playerDirection"));return new LLMResponse("{}","{}",0,0,0,0);});
  new SceneStoryService(model).write(facts,broken,"主创回复格式未完整解析",System.nanoTime()+60_000_000_000L);assertEquals("   ",broken.path("unparsedResponse").asText());assertEquals(before,facts);verify(model,times(1)).chatForJson(anyList());
 }
 @Test void exactPlayerIntentRemainsTheFinalFocusAfterNewCastAndCommittedTravel()throws Exception{
  var f=fixture();String goal="与许晚南下寻兄，途中遇事按实际情况应对";f.put("playerDirection",goal);
  f.putObject("currentWorld").putObject("resources").putObject("new").put("name","船家").put("provisional",true);f.withObject("privateRoleContexts").putObject("new").putArray("establishedSelf").add("本地船家，有自己的打算");
  var model=mock(LLMProvider.class);var modes=new ArrayList<String>();
  when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var guide=json.readTree(messages.get(messages.size()-1).get("content"));var fields=new ArrayList<String>();guide.fieldNames().forEachRemaining(fields::add);assertEquals("episodeFocus",fields.get(fields.size()-1));assertEquals(goal,guide.at("/episodeFocus/playerDirection").asText());assertEquals(guide.at("/episodeFocus/committedPassageCount").asInt()>0?"CONTINUING_AFTER_COMMITTED":"REQUESTED_NOT_OCCURRED",guide.at("/episodeFocus/intentStatus").asText());var input=json.readTree(messages.get(1).get("content"));modes.add(input.path("generationScope").path("mode").asText());assertEquals(goal,input.path("playerDirection").asText());return new LLMResponse("{}","{}",0,0,0,0);});
  var service=new SceneStoryService(model);service.write(f,null,"",System.nanoTime()+60_000_000_000L);f.putArray("alreadyHappenedThisChapter").add("途中遇风，船暂泊岸边，尚未到达目的地。");service.write(f,null,"",System.nanoTime()+60_000_000_000L);assertEquals(List.of("NEW_PASSAGE","CONTINUE_AFTER_COMMITTED"),modes);verify(model,times(2)).chatForJson(anyList());
 }
 @Test void finalFocusBindsTheNamedProtagonistToTheHumanAndDoesNotGiveNpcAuthorityToTheirChoice(){
  var facts=fixture().put("playerId","hero");facts.withObject("characterDossiers").putObject("hero").put("name","沈砚").put("isPlayer",true);var guide=StoryAuthorContext.brief(StoryAuthorInput.prepare(facts));
  assertEquals("hero",guide.at("/episodeFocus/playerControl/roleId").asText());assertEquals("沈砚",guide.at("/episodeFocus/playerControl/name").asText());assertEquals("HUMAN_PLAYER",guide.at("/episodeFocus/playerControl/authority").asText());
  for(var update:guide.path("characterUpdates"))assertEquals(update.path("roleId").asText().equals("hero")?"HUMAN_PLAYER":"CHARACTER_INTENTIONS",update.path("controlAuthority").asText());
 }

 @Test void newestFirstSubjectiveHistoryKeepsTheSameChronologyAsActualHistory(){
  var f=fixture();var h=f.withObject("characterDossiers").withObject("owner").withObject("pastInformation").put("historyOrder","NEWEST_TO_OLDEST");var r=h.putArray("ownRecords");
  for(String text:List.of("新意愿","旧意愿"))r.addObject().put("source_type","ROLE_DECISION").put("content",text);
  var result=StoryAuthorInput.prepare(f).at("/characterDossiers/owner/pastInformation");assertEquals("旧意愿",result.at("/historicalSubjectiveRecords/0/content").asText());assertEquals("新意愿",result.at("/historicalSubjectiveRecords/1/content").asText());assertEquals("新意愿",r.get(0).path("content").asText());
 }

 @Test void authorKnowledgeAndCharacterKnowledgeHaveAnExplicitSeparateViewpoint(){
  var f=fixture().put("playerId","hero");f.withObject("characterDossiers").putObject("hero").putObject("pastInformation").put("ownKnowledge","只看见对方抱着未打开的包裹");var guide=StoryAuthorContext.brief(StoryAuthorInput.prepare(f));
  assertEquals("hero",guide.at("/episodeFocus/storyViewpoint/roleId").asText());assertEquals("PLAYER_EXPERIENCE",guide.at("/episodeFocus/storyViewpoint/mode").asText());assertEquals("只看见对方抱着未打开的包裹",f.at(guide.at("/episodeFocus/storyViewpoint/knownSourcesRef").asText()).path("ownKnowledge").asText());
 }


}
