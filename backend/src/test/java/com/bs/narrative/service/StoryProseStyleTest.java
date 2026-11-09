package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StoryProseStyleTest {
 final ObjectMapper json=new ObjectMapper();
 ObjectNode facts(){return json.createObjectNode().put("sessionId","save").put("operationId","original").put("narrativeMode","NORMAL");}
 ObjectNode frame(){return json.createObjectNode().put("stop","RESOLVED");}

 @Test void aPreUpgradeOperationRetainsTheLegacyPromptAndRequestIdentity(){
  LLMProvider model=mock(LLMProvider.class);ModelReplyJournal journal=mock(ModelReplyJournal.class);
  var service=new SceneStoryService(model);ReflectionTestUtils.setField(service,"replyJournal",journal);
  var facts=facts();var frame=frame();var oldInput=json.createObjectNode();oldInput.set("chapterFrame",frame);oldInput.put("operationId","original").put("narrativeMode","NORMAL");
  oldInput.set("worldSetting",json.missingNode());oldInput.put("previousPublishedPassage","");
  String expected=StoryArchiveService.source(oldInput);
  var reply=LLMResponse.raw("已经生成并保存的正文。",0,0,0);
  when(journal.find("save",ModelProtocol.Stage.STORY_TEXT,expected)).thenReturn(new ModelReplyJournal.Saved(reply,reply,"",0));
  assertEquals(reply.content(),service.writeManuscript(facts,frame));verifyNoInteractions(model);
  assertFalse(facts.has("proseStyleVersion"));
 }
 @Test void legacyAndNewProseRequestsKeepTheSameFrameAndCharacterExpression()throws Exception{
  for(boolean current:List.of(false,true)){
   LLMProvider model=mock(LLMProvider.class);var service=new SceneStoryService(model);var facts=facts();var frame=frame();
   facts.putObject("characterDossiers").putObject("friend").putObject("psychology").putObject("expression").put("register","直率").put("rhythm","简短");
   if(current)facts.put("proseStyleVersion",StoryProseStyle.CURRENT);
   var before=facts.deepCopy();var frameBefore=frame.deepCopy();
   when(model.chat(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);var background=json.readTree(m.get(1).get("content"));var focus=json.readTree(m.get(3).get("content"));
    assertEquals("直率",background.at("/characterDossiers/friend/psychology/expression/register").asText());
    assertEquals(frameBefore,json.readTree(m.get(2).get("content")));assertEquals(current,focus.has("styleExamples"));
    assertTrue(focus.path("lengthGuidance").asText().contains("至少500字"));
    if(current){assertTrue(m.get(0).get("content").contains("psychology.expression"));assertTrue(focus.path("styleExamples").asText().contains("与本局世界和经历无关"));}
    else assertEquals(StoryDirectContract.TEXT_PROMPT,m.get(0).get("content"));
    return LLMResponse.raw("朋友直接说明来意，两人继续处理眼前的事。",0,0,0);
   });
   assertFalse(service.writeManuscript(facts,frame).isBlank());verify(model,times(1)).chat(anyList());verifyNoMoreInteractions(model);
   assertEquals(before,facts);assertEquals(frameBefore,frame);
  }
 }
}
