package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryManuscriptReviewTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode facts(){
  var f=json.createObjectNode().put("allowPublicationEdits",true).put("finalizeManuscript",true).put("manuscriptReview",true).put("playerDirection","先接父母，拒绝个人担保");
  f.put("originSource","character_creation.birth_story").put("originStory","沈立本是沈照仍健在的父亲；柳氏是母亲，祖父已经去世。");
  f.putObject("openingPublishedChapter").put("story","这是最早正式行动章，不是原始身世。");
  f.putArray("previousChapters").addObject().put("story","当日正午，何九与沈照已在闸口，听完所有条件。");
  f.putArray("characterIndex").addObject().put("roleId","father").put("name","沈立本");
  var father=f.putObject("characterDossiers").putObject("father").put("name","沈立本");father.putObject("identity").putArray("establishedSelf").add("沈照父亲，腿有旧伤，仍然健在");father.putObject("intentions").put("intention","愿意撤离");
  return f;
 }
 ObjectNode reply(){
  var p=json.createObjectNode();p.putObject("publication").put("story","父亲拿着祖父的牌位，与家人安全抵达。");p.putObject("timeProgress").putArray("milestones").addObject().putArray("elapsedPartsFromStart").addObject().put("amount",16).put("unit","HOUR");
  p.putArray("cast").addObject().put("roleId","father").put("name","沈立本").putObject("relationToPlayer").put("kind","FAMILY").put("label","父亲").put("basis","原始身世明确沈立本是父亲");return p;
 }
 @Test void evenAShortManuscriptGetsExactOriginsIntentionsAndItsDecisionBoundaryAndUsesACache()throws Exception{
  var f=facts();var before=f.deepCopy();var draft=json.createObjectNode().put("story","这句称谓有误。").put("elapsedSeconds",60).put("stop","DECISION");draft.putObject("decision").put("question","是否承担新责任？");var model=mock(LLMProvider.class);var response=reply();
  when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);var input=json.readTree(messages.get(1).get("content"));assertEquals(f.path("originStory"),input.path("originStory"));assertEquals(f.at("/previousChapters/0/story"),input.path("actualStart"));assertEquals("拒绝个人担保",input.path("playerDirection").asText().substring(5));assertEquals("愿意撤离",input.at("/people/0/intentions/intention").asText());assertEquals("DECISION",input.at("/decisionBoundary/stop").asText());assertEquals(LLMRequestPolicy.STORY_REVIEW,LLMRequestPolicy.current());return new LLMResponse(response.toString(),response.toString(),0,0,0,0);});
  var clock=new StorySourceClock(model);var result=clock.read(f,draft,json.createObjectNode(),raw->{});assertEquals(result,clock.read(f,draft,result,raw->{}));verify(model,times(1)).chatForJson(anyList());assertEquals(before,f);
  var manuscript=StoryManuscriptReview.apply(StoryPublicationRevision.apply(draft,result,f),result,f);assertEquals("DECISION",manuscript.path("stop").asText());assertEquals(60,manuscript.path("elapsedSeconds").asLong(),"A family-title edit does not create a second sixteen-hour clock");assertEquals("FAMILY",manuscript.at("/manuscriptRelationships/0/kind").asText());assertEquals(manuscript,StoryManuscriptReview.apply(manuscript,result,f));
 }
 @Test void aChangedIntentionInvalidatesReviewEvenWhenTheAuthorReturnsIdenticalText(){
  var f=facts();var draft=json.createObjectNode().put("story","同一份未发表稿");var first=StoryManuscriptReview.source(f,draft);f.withObject("characterDossiers").withObject("father").withObject("intentions").put("intention","改为拒绝冒险");assertNotEquals(first,StoryManuscriptReview.source(f,draft));
 }
 @Test void aLegacySaveWithoutBirthProseDoesNotRelabelAnActionChapterAsTheOrigin(){
  var f=facts();f.remove("originStory");f.put("originSource","LEGACY_NOT_RECORDED");var input=json.createObjectNode();StoryManuscriptReview.enrich(input,f,json.createObjectNode());
  assertFalse(input.has("originStory"));assertEquals("LEGACY_NOT_RECORDED",input.path("originSource").asText());
 }
 @Test void existingRelationshipsComeFromTheActualStoredJournalRecordShape(){
  var f=facts();var relation=json.createObjectNode().put("roleId","father").put("kind","FAMILY").put("label","父亲");var records=f.putObject("playerJournal").putArray("records");
  records.addObject().put("source_type","PLAYER_RELATION").put("content",relation.toString());records.addObject().put("source_type","APPEARANCE").put("content","外貌资料不混入关系");
  var input=json.createObjectNode();StoryManuscriptReview.enrich(input,f,json.createObjectNode());assertEquals(1,input.path("existingRelationships").size());assertEquals("FAMILY",input.at("/existingRelationships/0/kind").asText());
 }
 @Test void addressedParagraphChangesRetainAllOtherTextAndItsOriginalSpacing(){
  String original="第一段原文。\r\n\r\n第二段“旧称谓”。\n\n\n末段原文。";var f=facts();var input=json.createObjectNode();StoryManuscriptReview.enrich(input,f,json.createObjectNode().put("story",original));
  assertEquals("P2",input.at("/paragraphs/1/paragraphId").asText());assertEquals("第二段“旧称谓”。",input.at("/paragraphs/1/text").asText());assertFalse(input.has("story"));
  var review=json.createObjectNode();review.putArray("paragraphRevisions").addObject().put("paragraphId","P2").put("text","第二段“正确称谓”。");
  assertEquals("第一段原文。\r\n\r\n第二段“正确称谓”。\n\n\n末段原文。",StoryManuscriptReview.reviewedReply(review,original).at("/publication/story").asText());
  review.withArray("paragraphRevisions").addObject().put("paragraphId","P2").put("text","不能偷偷覆盖第一份修订");assertThrows(IllegalArgumentException.class,()->StoryManuscriptReview.reviewedReply(review,original));
 }
 @Test void missingMotherUsesTheExistingCastProposalPathAndFamilyMetadataIsNotLostByArchiving(){
  var f=facts();var review=json.createObjectNode();review.putObject("manuscriptReview").putArray("cast").addObject().put("name","柳氏").put("profile","沈照母亲，原始身世已有记载").put("narrativeTier","EXTRA").putObject("relationToPlayer").put("kind","FAMILY").put("label","母亲").put("basis","originStory已明确亲缘");
  var d=StoryManuscriptReview.apply(json.createObjectNode().put("story","柳氏说了自己的意见。"),review,f);assertEquals("柳氏",d.at("/newCharacters/0/name").asText());assertEquals("EXTRA",d.at("/newCharacters/0/narrativeTier").asText());
  f.withArray("characterIndex").addObject().put("roleId","mother").put("name","柳氏");var rows=d.putObject("playerJournal").putArray("relationships");rows.addObject().put("roleId","mother").put("kind","ACQUAINTANCE");rows.addObject().put("roleId","mother").put("kind","ACQUAINTANCE").put("label","重复旧卡");StoryManuscriptReview.mergeRelationships(d,f);
  assertEquals(1,d.at("/playerJournal/relationships").size());assertEquals("mother",d.at("/playerJournal/relationships/0/roleId").asText());assertEquals("母亲",d.at("/playerJournal/relationships/0/label").asText());assertEquals("FAMILY",d.at("/playerJournal/relationships/0/kind").asText());
 }
 @Test void incompleteReviewIsRetainedAndRecoveryRetriesOnlyFinalization()throws Exception{
  var model=mock(LLMProvider.class);var invalid=reply();invalid.remove("cast");var valid=reply();when(model.chatForJson(anyList())).thenReturn(new LLMResponse(invalid.toString(),invalid.toString(),0,0,0,0),new LLMResponse(valid.toString(),valid.toString(),0,0,0,0));
  var saved=new ArrayList<ObjectNode>();var clock=new StorySourceClock(model);var draft=json.createObjectNode().put("story","保留的初稿").put("elapsedSeconds",60);var f=facts();
  assertThrows(IllegalArgumentException.class,()->clock.read(f,draft,json.createObjectNode(),raw->{try{saved.add((ObjectNode)json.readTree(raw));}catch(Exception e){throw new RuntimeException(e);}}));assertEquals("RECEIVED",saved.get(0).at("/timeReading/status").asText());
  assertEquals("DONE",clock.read(f,draft,saved.get(0),raw->{}).at("/timeReading/status").asText());verify(model,times(2)).chatForJson(anyList());assertEquals("保留的初稿",draft.path("story").asText());
 }
}
