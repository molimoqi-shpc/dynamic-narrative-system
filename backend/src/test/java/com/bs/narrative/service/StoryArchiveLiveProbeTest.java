package com.bs.narrative.service;

import com.bs.narrative.llm.LLMUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, paid read-only reprojection of a fixed published passage. Never writes a game result. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@EnabledIfEnvironmentVariable(named="STORY_ARCHIVE_LIVE_INPUT",matches=".+")
class StoryArchiveLiveProbeTest {
 @Autowired StoryArchiveService archive;
 @Test void reprojectTheSavedFixedPassageWithoutRewritingItOrAdvancingTheGame()throws Exception{
  var json=new ObjectMapper();var saved=json.readTree(Files.readString(Path.of(System.getenv("STORY_ARCHIVE_LIVE_INPUT"))));
  var facts=(ObjectNode)saved.path("sceneFrameFacts").deepCopy();var draft=json.createObjectNode().put("story",saved.path("story").asText());
  // Committed payloads can clear sceneDraft; current provisional identities remain in the dossiers.
  draft.putArray("newCharacters");LLMUsage.begin();
  try{
   var projected=archive.project(facts,draft,null);var evidence=json.createObjectNode();evidence.set("projection",projected);evidence.set("usage",json.valueToTree(LLMUsage.totals()));evidence.put("readOnly",true);evidence.put("fixedStory",draft.path("story").asText());
   Files.writeString(Path.of(System.getenv("STORY_ARCHIVE_LIVE_OUTPUT")),json.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
   assertEquals(1,LLMUsage.totals().calls(),"The probe must use exactly one archive call");
   String player=facts.path("playerId").asText(),near="feature_near_4eb632bd96",far="feature_far_4eb632bd96";boolean visitors=false,departed=false;
   for(var group:projected.path("sceneOutcomes")){
    boolean hasPlayer=false,hasNear=false,hasFar=false,hasHost=false;
    for(var role:group.path("roleIds")){hasPlayer|=player.equals(role.asText());hasNear|=near.equals(role.asText());hasFar|=far.equals(role.asText());hasHost|="徐伯".equals(facts.path("characterDossiers").path(role.asText()).path("name").asText());}
    if(hasPlayer){assertTrue(hasNear);assertTrue(hasHost);assertFalse(hasFar);assertTrue(group.at("/scene/locationName").asText().contains("院"));visitors=true;}
    if(hasFar){assertNotEquals("feature_north_4eb632bd96",group.path("sceneId").asText());assertFalse(hasPlayer);departed=true;}
   }
   assertTrue(visitors);assertTrue(departed);
  }finally{LLMUsage.clear();}
 }
}
