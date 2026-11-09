package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local replay of a saved request into a mocked provider. No network, story advancement or billing. */
@EnabledIfEnvironmentVariable(named="STORY_CONTEXT_SAMPLE_INPUT",matches=".+")
class StoryAuthorSampleTest {
 @Test void measureARealSavedContextWithoutCallingAModel()throws Exception{
  var json=new ObjectMapper();var saved=json.readTree(Files.readString(Path.of(System.getenv("STORY_CONTEXT_SAMPLE_INPUT"))));var facts=(ObjectNode)saved.path("sceneFrameFacts").deepCopy();var original=facts.deepCopy();
  var previous=facts.deepCopy();StoryCastService.refreshPlanning(previous);previous.remove(List.of("serverEffectBaselines","privateRoleContexts","playerContext"));for(var d:previous.path("roleDirections"))if(d instanceof ObjectNode o)o.remove("characterTemplate");
  var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenAnswer(call->{
   List<Map<String,String>> messages=call.getArgument(0);var current=json.readTree(messages.get(1).get("content"));var result=json.createObjectNode().put("paidModelCalls",0).put("beforeDataChars",previous.toString().length()).put("afterDataChars",messages.get(1).get("content").length()).put("afterFinalGuideChars",messages.get(messages.size()-1).get("content").length()).put("beforeSystemChars",StoryFirstContract.PROMPT.length()).put("afterSystemChars",messages.get(0).get("content").length());
   result.put("dataReductionPercent",100.0*(previous.toString().length()-messages.get(1).get("content").length())/previous.toString().length());
   for(var d:facts.path("characterDossiers").properties())assertEquals(d.getValue().path("psychology"),current.path("characterDossiers").path(d.getKey()).path("psychology"));
   assertTrue(messages.get(1).get("content").length()<previous.toString().length());assertTrue(StoryFirstContract.AUTHORING_PROMPT.length()<StoryFirstContract.PROMPT.length());
   result.put("detailedPsychologyPreserved",true).put("scope","character counts, not token or cost estimates");Files.writeString(Path.of(System.getenv("STORY_CONTEXT_SAMPLE_OUTPUT")),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));return new LLMResponse("{}","{}",0,0,0,0);
  });new SceneStoryService(model).write(facts,null,"",System.nanoTime()+60_000_000_000L);verify(model,times(1)).chatForJson(anyList());assertEquals(original,facts);
 }
}
