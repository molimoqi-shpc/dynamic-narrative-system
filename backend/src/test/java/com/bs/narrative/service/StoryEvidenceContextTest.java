package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StoryEvidenceContextTest {
 final ObjectMapper json=new ObjectMapper();
 @Test void originalQuestionAndSourcePointersRemainExactWhileFuturePlansAreNotEvidence(){
  var facts=json.createObjectNode().put("playerDirection","先听完，再决定是否担保");facts.putArray("previousChapters").addObject().put("story","名册第十七栏空着，纸张完好。");
  var entry=facts.putObject("plotMemory").putArray("recentEvents").addObject();var event=entry.putObject("event").put("eventId","evt_17").put("openQuestion","第十七栏为何空着？");event.putObject("authorNotes").put("guess","UNCOMMITTED_SECRET");
  entry.putArray("foreshadows").addObject().put("threadId","fo_17").put("eventId","evt_17").put("question","空栏与收条有什么关系？").put("setup","纸张完好，只有一栏未登记。");
  var before=facts.deepCopy();var guide=StoryEvidenceContext.guide(facts);assertEquals("第十七栏为何空着？",guide.at("/plotAnchors/0/openQuestion").asText());assertEquals("纸张完好，只有一栏未登记。",guide.at("/plotAnchors/1/setup").asText());
  assertEquals(event,facts.at(guide.at("/plotAnchors/0/sourceRef").asText()));assertEquals(facts.at("/previousChapters/0/story"),facts.at(guide.at("/publishedSourceRefs/0").asText()));assertFalse(guide.toString().contains("UNCOMMITTED_SECRET"));assertEquals(before,facts);
 }
 @Test void originalSourcePagesAndRequestedFullPassagesAreAddressableWithoutDuplicatingProse(){
  var facts=json.createObjectNode();facts.putArray("plotSourcePages").addObject().put("story","VERBATIM_OLD_SOURCE");facts.putArray("plotResults").addObject().put("publishedPassage","VERBATIM_LOOKUP");var guide=StoryEvidenceContext.guide(facts);
  assertEquals(2,guide.path("publishedSourceRefs").size());assertEquals("VERBATIM_OLD_SOURCE",facts.at(guide.at("/publishedSourceRefs/0").asText()).asText());assertFalse(guide.toString().contains("VERBATIM_OLD_SOURCE"));
 }
 @Test void authorChosenEventIdsFocusTheExistingSourceWithoutInventingAPlotOutcome(){
  var facts=json.createObjectNode();var events=facts.putObject("plotMemory").putArray("recentEvents");events.addObject().putObject("event").put("eventId","evt_other");events.addObject().putObject("event").put("eventId","evt_focus").put("outcome","旧问题已解释，但新担保未接受。");
  facts.putObject("episodePlan").putObject("content").putArray("eventIds").add("evt_focus");var guide=StoryEvidenceContext.guide(facts);assertEquals("evt_focus",guide.at("/plotAnchors/0/eventId").asText());assertEquals(2,guide.path("plotAnchors").size());
  assertEquals("旧问题已解释，但新担保未接受。",guide.at("/plotAnchors/0/outcome").asText());assertFalse(guide.has("stop"));assertFalse(guide.has("decision"));
 }
}
