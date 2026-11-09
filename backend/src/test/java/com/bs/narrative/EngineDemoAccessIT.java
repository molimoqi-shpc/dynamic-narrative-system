package com.bs.narrative;

import com.bs.narrative.service.AuthService;
import com.bs.narrative.llm.LLMProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@AutoConfigureMockMvc @Transactional
class EngineDemoAccessIT {
 @Autowired MockMvc mvc;@Autowired AuthService auth;@Autowired JdbcTemplate jdbc;
 @MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper();
 @Test void differentLoggedInAccountsCanRunTheSameFixtureWithoutChangingTheirGames()throws Exception{
  String username="qa_engine_"+UUID.randomUUID().toString().substring(0,8);auth.register(username,"fixture-password");
  var accounts=List.of(auth.login("player_demo","123456"),auth.login(username,"fixture-password"));
  long logs=jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log",Long.class),book=jdbc.queryForObject("SELECT COUNT(*) FROM world_book",Long.class);
  var playerBefore=jdbc.queryForMap("SELECT base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory FROM god_profile WHERE god_id=(SELECT god_id FROM player_profile WHERE player_id='p_demo')");
  clearInvocations(model);
  com.fasterxml.jackson.databind.JsonNode firstSnapshot=null,firstAnalysis=null;
  for(var account:accounts){
   assertNotEquals("player_01",account.get("playerId"));
   for(int n=0;n<2;n++){
    var result=mvc.perform(post("/api/engine/demo/fengdu-walkthrough").header("Authorization","Bearer "+account.get("token")).contentType("application/json").content("{}"))
     .andExpect(status().isOk()).andExpect(jsonPath("$.outcomeType").value("EXPLOITATION_RESCUE")).andExpect(jsonPath("$.token.total").value(0)).andExpect(jsonPath("$.token.costCny").value(0)).andReturn();
    var reply=json.readTree(result.getResponse().getContentAsString());assertFalse(reply.path("factsChinese").isEmpty());assertEquals("player_01",reply.at("/lines/0/godId").asText());
    // The dedicated DTO keeps the public rule contract flat and adds a complete scene.
    assertFalse(reply.has("result"));
    var story=reply.path("story");assertEquals(3,story.path("cast").size());assertTrue(story.path("beats").size()>=8);
    assertEquals(story.path("beats").size(),reply.path("lines").size());
    Set<String> stages=new LinkedHashSet<>(),speakers=new HashSet<>();int lies=0,museumLines=0;
    for(var beat:story.path("beats")){
     stages.add(beat.path("stage").asText());speakers.add(beat.path("speakerId").asText());
     for(String field:List.of("action","speech","innerThought","explanation"))assertFalse(beat.path(field).asText().isBlank(),field);
     assertFalse(beat.path("speech").asText().contains("[模板演示]"));assertFalse(beat.path("innerThought").asText().contains("backstab="));
     if(beat.hasNonNull("lieReason")){lies++;assertEquals("museum_god",beat.path("speakerId").asText());}
     if("museum_god".equals(beat.path("speakerId").asText()))museumLines++;
    }
    assertEquals(Set.of("player_01","rain_god","museum_god"),speakers);assertEquals(4,stages.size());assertEquals(1,lies);assertTrue(museumLines>1);
    assertFalse(story.path("ending").asText().isBlank());
    var snapshot=reply.path("ruleSnapshot");assertEquals(30,snapshot.at("/initialRelationships/0/trust").asInt());
    assertEquals("museum_god",snapshot.at("/initialRelationships/0/sourceId").asText());
    assertEquals(10,snapshot.at("/initialRelationships/1/trust").asInt());
    assertEquals(-2,snapshot.at("/statDeltaPerGod/player_01/wen").asInt());
    assertEquals(3,snapshot.path("relationshipDeltas").size());
    assertEquals(0,snapshot.path("consumedItems").size());
    if(firstSnapshot==null)firstSnapshot=snapshot;else assertEquals(firstSnapshot,snapshot,"each request must rebuild the original demo seed");
    var analysis=reply.path("analysis");assertEquals(6,analysis.path("dimensions").size());assertEquals(3,analysis.path("behaviors").size());
    assertEquals(5,analysis.path("process").size());assertEquals(5,analysis.path("combatCases").size());
    // Formula books must describe the initial cast, not the sample's already-reduced WEN.
    assertEquals(12,analysis.at("/profiles/0/formulaBook/stats/3/base").asInt());
    assertEquals(14,analysis.at("/profiles/0/formulaBook/stats/3/actual").asInt());
    Map<String,com.fasterxml.jackson.databind.JsonNode> cases=new HashMap<>();
    for(var example:analysis.path("combatCases")){
     cases.put(example.path("id").asText(),example);
     assertEquals(6,example.at("/attacker/formulaBook/stats").size());assertEquals(6,example.at("/defender/formulaBook/stats").size());
    }
    var hostile=cases.get("hostile").path("comparison");assertEquals(12,hostile.path("actorActualDou").asInt());assertEquals(30,hostile.path("targetActualDou").asInt());
    assertEquals(-18,hostile.path("difference").asInt());assertEquals(10,hostile.path("threshold").asInt());assertEquals("DEFENDER_WIN",hostile.path("outcome").asText());
    var mastery=cases.get("mastery").path("comparison");assertEquals(32,mastery.path("actorActualDou").asInt());assertEquals(45,mastery.path("targetActualDou").asInt());
    var synergy=cases.get("synergy").path("comparison");assertEquals(39,synergy.path("actorActualDou").asInt());assertEquals(41,synergy.path("targetActualDou").asInt());assertEquals("DRAW",synergy.path("outcome").asText());
    assertEquals("ATTACKER_WIN",cases.get("sparring").at("/comparison/outcome").asText());
    assertEquals("DRAW",cases.get("draw").at("/comparison/outcome").asText());
    if(firstAnalysis==null)firstAnalysis=analysis;else assertEquals(firstAnalysis,analysis,"teaching choices cannot mutate the next request");
   }
  }
  assertEquals(logs,jdbc.queryForObject("SELECT COUNT(*) FROM interaction_log",Long.class));assertEquals(book,jdbc.queryForObject("SELECT COUNT(*) FROM world_book",Long.class));
  assertEquals(playerBefore,jdbc.queryForMap("SELECT base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui,inventory FROM god_profile WHERE god_id=(SELECT god_id FROM player_profile WHERE player_id='p_demo')"));verifyNoInteractions(model);
 }
 @Test void demoDoesNotBypassLoginOrAllowForgedAccountIdentity()throws Exception{
  mvc.perform(post("/api/engine/demo/fengdu-walkthrough").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
  var account=auth.login("player_demo","123456");
  mvc.perform(post("/api/engine/demo/fengdu-walkthrough").header("Authorization","Bearer "+account.get("token")).contentType("application/json").content("{\"playerId\":\"player_01\"}")).andExpect(status().isForbidden());
  verifyNoInteractions(model);
 }
}
