package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryPublicationRevisionTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode projection(String before,String after){var p=json.createObjectNode();p.putObject("publication").putArray("edits").addObject().put("before",before).put("after",after).put("reason","删去超出本篇跨度的普通尾声");return p;}
 @Test void mainApiLiteralEditsPreserveOriginalAndClockAndAreReplayable(){
  var d=json.createObjectNode().put("story","翌日看屋，次日修完。往后几日又下雨回访。").put("stop","RESOLVED");d.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",2).put("unit","DAY");
  var before=d.deepCopy();var patch=projection("往后几日又下雨回访。","");var originalPatch=patch.deepCopy();var facts=json.createObjectNode().put("allowPublicationEdits",true);
  var result=StoryPublicationRevision.apply(d,patch,facts);assertEquals("翌日看屋，次日修完。",result.path("story").asText());assertEquals(d.path("timeProgress"),result.path("timeProgress"));
  assertEquals(d.path("story"),result.at("/publicationRevision/originalStory"));assertEquals(1,result.at("/publicationRevision/applied").size());
  assertEquals(result,StoryPublicationRevision.apply(d,patch,facts));assertEquals(before,d);assertEquals(originalPatch,patch);
 }
 @Test void ambiguousAbsentOrOverlappingAnchorsDoNotTargetSomeOtherParagraph(){
  var d=json.createObjectNode().put("story","他说好。她说好。修屋完毕。");var p=projection("好。","未答");
  var edits=(ArrayNode)p.at("/publication/edits");edits.addObject().put("before","不存在的句子").put("after","新情节");
  edits.addObject().put("before","修屋完毕。").put("after","屋已修妥。");edits.addObject().put("before","屋完毕").put("after","错误覆盖");
  var before=d.deepCopy();assertThrows(IllegalArgumentException.class,()->StoryPublicationRevision.apply(d,p,json.createObjectNode().put("allowPublicationEdits",true)));assertEquals(before,d,"Never publish partial edits with the result tables for an unapplied version");
 }
 @Test void alreadyPublishedRepairNeverRewritesVisibleProse(){
  var d=json.createObjectNode().put("story","已经向玩家发表的原文");var facts=json.createObjectNode().put("allowPublicationEdits",false);
  assertEquals(d,StoryPublicationRevision.apply(d,projection(d.path("story").asText(),"另一个故事"),facts));
 }
 @Test void finalArchiveFocusDirectlyPairsTheActualManuscriptEndingWithTheAuthoredTimeFrame(){
  var facts=json.createObjectNode();facts.putArray("previousChapters").addObject().put("story","上篇收船时已经告辞。");
  var draft=json.createObjectNode().put("story","先前主事件。\n\n末尾另加了一段余波。");draft.putObject("timeProgress").put("to","主事件当天收船时");
  var service=new StoryArchiveService(null);var focus=service.reviewFocus(facts,draft).path("publicationFocus");
  assertEquals(draft.path("timeProgress"),focus.path("authoredTimeFrame"));assertEquals(draft.path("story").asText(),focus.path("manuscriptEnding").asText());assertEquals("上篇收船时已经告辞。",focus.path("priorPublishedEnding").asText());
  assertEquals("PUBLISHED_RECORD_ONLY",service.input(facts,draft).path("publicationMode").asText(),"Unknown callers do not gain permission to rewrite a published record");
 }
 @Test void finalTimeLabelsCanCorrectAnOldSceneWithoutChangingUnitsOrCreatingASecondClock(){
  var service=new StoryArchiveService(null);var draft=json.createObjectNode().put("story","屋顶修好当晚起，接着过了七天。");draft.putObject("timeProgress").put("from","误写成更早的家宴").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");
  var projected=json.createObjectNode();var labels=projected.putObject("publication").putObject("timeLabels").put("from","屋顶修好当晚").put("to","接下来第七日收船后").put("elapsedSeconds",1);
  projected.putObject("timeProgress").put("elapsedSeconds",1);
  var facts=json.createObjectNode().put("allowPublicationEdits",true);var out=service.bindAuthoredTime(draft,projected,facts);
  assertEquals(604800,out.at("/timeProgress/elapsedSeconds").asLong());assertEquals("STORY_AUTHOR",out.at("/timeProgress/source").asText());assertEquals("屋顶修好当晚",out.at("/timeProgress/from").asText());
  assertEquals(draft.path("timeProgress").path("elapsedParts"),out.at("/timeProgress/elapsedParts"));assertEquals(out,service.bindAuthoredTime(draft,out,facts));
  assertEquals("误写成更早的家宴",service.bindAuthoredTime(draft,projected,json.createObjectNode()).at("/timeProgress/from").asText());assertTrue(labels.has("elapsedSeconds"));
 }
 @Test void manuscriptStartBindsTheLastPublishedEndingInsteadOfRecyclingAnOlderSceneLabel(){
  var facts=json.createObjectNode().put("allowPublicationEdits",true);facts.putArray("previousChapters").addObject().put("logId",50).put("story","饭后早已告辞。\n\n后来屋顶已修好，收船回家点灯。");
  var draft=json.createObjectNode().put("story","从修屋之后继续生活。");draft.putObject("timeProgress").put("from","旧家宴当晚").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");
  var projected=json.createObjectNode();projected.putObject("continuityState").putObject("narrativeState").putObject("actualTime").put("from","旧家宴当晚");
  var service=new StoryArchiveService(null);var out=service.bindAuthoredTime(draft,projected,facts);
  assertEquals(50,out.at("/timeProgress/startSource/logId").asInt());assertTrue(out.at("/timeProgress/startSource/publishedEnding").asText().endsWith("收船回家点灯。"));
  assertEquals(out.at("/timeProgress/from"),out.at("/continuityState/narrativeState/actualTime/from"));assertEquals(604800,out.at("/timeProgress/elapsedSeconds").asLong());
  assertEquals("旧家宴当晚",out.at("/publicationAuthorOriginalTime/from").asText());assertEquals(out,service.bindAuthoredTime(draft,out,facts));
 }
 @Test void archiveMergePublishesTheSameEditedTextWhileKeepingStopAndAuthoredDuration(){
  var d=json.createObjectNode().put("story","听清条件，尚未答复。几天后已经结盟。").put("stop","DECISION").put("elapsedSeconds",60);
  var service=new StoryArchiveService(null);var p=service.normalize(projection("几天后已经结盟。","").put("continuity","已听清新条件，仍待玩家答复。").toString());
  var result=service.merge(d,p,json.createObjectNode().put("allowPublicationEdits",true));assertEquals("听清条件，尚未答复。",result.path("story").asText());assertEquals("DECISION",result.path("stop").asText());assertEquals(60,result.path("elapsedSeconds").asLong());
 }
 @Test void aCompleteFinalManuscriptAndItsOwnUnitsAreOnePublicationWithoutLosingTheOriginal(){
  var draft=json.createObjectNode().put("story","草稿有不连贯的日期。").put("stop","DECISION").put("elapsedSeconds",604800);draft.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");
  var p=json.createObjectNode();var publication=p.putObject("publication").put("story","两日里查看屋顶，次日修妥。听完新的结盟提议，尚待答复。").put("stop","RESOLVED");publication.putObject("timeProgress").put("to","修妥后听清提议，尚未表态").putArray("elapsedParts").addObject().put("amount",3).put("unit","DAY");
  p.putObject("timeProgress").put("elapsedSeconds",1);p.put("continuity","屋顶已修妥，提议尚待玩家答复。");
  var facts=json.createObjectNode().put("allowPublicationEdits",true);var service=new StoryArchiveService(null);var original=draft.deepCopy();var projection=service.normalize(p.toString());
  var out=service.merge(draft,projection,facts);assertEquals(publication.path("story"),out.path("story"));assertEquals(259200,out.path("elapsedSeconds").asLong());assertEquals("FINAL_PUBLICATION",out.at("/timeProgress/version").asText());
  assertEquals("DECISION",out.path("stop").asText(),"Publication cannot overwrite the human decision boundary through unrelated fields");assertEquals(original,draft);assertEquals(original.path("story"),out.at("/publicationRevision/originalStory"));
  var bound=service.bindAuthoredTime(draft,projection,facts);assertEquals(604800,bound.at("/publicationAuthorOriginalTime/elapsedSeconds").asLong());assertEquals(bound,service.bindAuthoredTime(draft,bound,facts));
  assertEquals(original.path("story"),service.merge(draft,projection,json.createObjectNode()).path("story"));assertEquals(604800,service.merge(draft,projection,json.createObjectNode()).path("elapsedSeconds").asLong());
 }
 @Test void unchangedOvernightManuscriptKeepsItsAuthoredHoursDespiteAConflictingArchiveEstimate(){
  var draft=json.createObjectNode().put("story","午后撤人，入夜守闸，第二天清早水退。").put("stop","RESOLVED");draft.putObject("timeProgress").put("to","次日清早").putArray("elapsedParts").addObject().put("amount",16).put("unit","HOUR");
  var p=json.createObjectNode();p.putObject("publication").put("unchanged",true).putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",1.5).put("unit","DAY");
  var facts=json.createObjectNode().put("allowPublicationEdits",true);var service=new StoryArchiveService(null);var merged=service.merge(draft,service.normalize(p.toString()),facts);
  assertEquals(57600,merged.path("elapsedSeconds").asLong());assertEquals(draft.path("story"),merged.path("story"));assertEquals(16,merged.at("/timeProgress/elapsedParts/0/amount").asInt());
  assertEquals(1.5,merged.at("/publicationRevision/unusedArchiveTime/elapsedParts/0/amount").asDouble());
  assertEquals(merged,service.merge(draft,service.normalize(p.toString()),facts));
  ((ObjectNode)p.path("publication")).put("story",draft.path("story").asText()).remove("unchanged");assertEquals(57600,service.merge(draft,p,facts).path("elapsedSeconds").asLong());
 }
 @Test void aDedicatedFinalManuscriptReadingCanStillCorrectTimeWithoutRewritingTheText(){
  var draft=json.createObjectNode().put("story","经历三日，第四日清早道别。");draft.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",3).put("unit","DAY");
  var projection=json.createObjectNode();projection.putObject("publication").put("unchanged",true).putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",4).put("unit","DAY");
  var facts=json.createObjectNode().put("allowPublicationEdits",true).put("finalizeManuscript",true);
  assertEquals(345600,StoryPublicationRevision.apply(draft,projection,facts).path("elapsedSeconds").asLong());
 }
 @Test void unchangedManuscriptCanFinalizeDurationWithoutAnotherCopyOfTheStory(){
  var draft=json.createObjectNode().put("story","半日里已走到渡口，见面后道别。").put("stop","RESOLVED");
  var p=json.createObjectNode();p.putObject("publication").put("unchanged",true).putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",0.5).put("unit","DAY");p.put("continuity","已抵达，已道别。");
  var facts=json.createObjectNode().put("allowPublicationEdits",true);var service=new StoryArchiveService(null);service.requireTiming(p,draft,facts);
  assertThrows(IllegalArgumentException.class,()->service.requireTiming(p,draft));var result=service.merge(draft,service.normalize(p.toString()),facts);
  assertEquals(draft.path("story"),result.path("story"));assertEquals(43200,result.path("elapsedSeconds").asLong());assertEquals("FINAL_PUBLICATION",result.at("/timeProgress/version").asText());
 }
 @Test void explicitFinalManuscriptWithUnreadableUnitsNeverSilentlyKeepsTheDraftClock(){
  var draft=json.createObjectNode().put("story","早先草稿").put("elapsedSeconds",600);var p=json.createObjectNode();p.putObject("publication").put("story","实际修订稿").putObject("timeProgress").putArray("elapsedParts").addObject().put("amount","invalid").put("unit","DAY");
  assertThrows(IllegalArgumentException.class,()->StoryPublicationRevision.apply(draft,p,json.createObjectNode().put("allowPublicationEdits",true)));
  assertEquals(draft,StoryPublicationRevision.apply(draft,p,json.createObjectNode().put("allowPublicationEdits",false)));
  ((ObjectNode)p.path("publication")).remove("timeProgress");
  assertThrows(IllegalArgumentException.class,()->StoryPublicationRevision.apply(draft,p,json.createObjectNode().put("allowPublicationEdits",true)),"A revised manuscript cannot silently borrow an unchecked draft clock");
 }
 @Test void immutableFinalManuscriptStillBindsItsPreviousPublishedSourceBeforeArchiving(){
  var facts=json.createObjectNode().put("allowPublicationEdits",true);facts.putArray("previousChapters").addObject().put("logId",50).put("recordedEndSeconds",600).put("story","此前已点灯歇下。");
  var draft=json.createObjectNode().put("story","此后七日生活，收船归家。");draft.putObject("timeProgress").putArray("elapsedParts").addObject().put("amount",7).put("unit","DAY");
  var pub=json.createObjectNode();pub.putObject("publication").put("unchanged",true).set("timeProgress",draft.path("timeProgress").deepCopy());var ready=StoryPublicationRevision.apply(draft,pub,facts);
  facts.put("allowPublicationEdits",false).put("finalManuscriptReady",true);var projection=json.createObjectNode();projection.putObject("continuityState").putObject("narrativeState").putObject("actualTime").put("to","归家");
  var service=new StoryArchiveService(null);var out=service.bindAuthoredTime(ready,projection,facts);assertEquals(50,out.at("/timeProgress/startSource/logId").asInt());assertEquals(600,out.at("/timeProgress/startSource/recordedEndSeconds").asLong());assertEquals(604800,out.at("/timeProgress/elapsedSeconds").asLong());assertEquals(ready.path("story"),service.merge(ready,projection,facts).path("story"));assertEquals(out,service.bindAuthoredTime(ready,out,facts));
 }
}
