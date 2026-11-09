package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class WorldSettingFlowIT {
 @Autowired CharacterCreationService creation;
 @Autowired WorldSettingService worlds;
 @Autowired JdbcTemplate jdbc;
 @Autowired GameSessionService sessionService;
 @MockBean LLMProvider model;
 private static final String WORLD="""
 {"title":"灰港","genre":"西方幻想悬疑","culturalStyle":"WESTERN","era":"早期工业时代与古老城邦并存","introduction":"灰港的人们在旧城与车站之间生活。超自然现象罕见，人们对其知之甚少。人物可以是普通旅人。","summary":"灰港是西方文化的早期工业城邦。普通人与少数精灵共同生活，列车已通行；技术与超自然力量各有边界，不默认神明真实存在。","boundaries":["不能以未知法术任意改变他人记忆"],"questions":["你叫什么，性别、年龄与出身如何？","你最重视什么？","面对困境会怎样选择？"]}
 """;
 @BeforeEach void stub(){when(model.chatMainForJson(anyList())).thenReturn(new LLMResponse(WORLD,WORLD,0,0,0,0));}
 @Test void chosenThemeReachesMainModelAndSavedWorldNeverRedraws(){
  for(String theme:List.of("WESTERN","EASTERN","MODERN")){
   String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();creation.generateWorld(sid,"p_demo",theme);
   assertEquals(theme,worlds.find(sid).path("selectedTheme").asText());
   verify(model).chatMainForJson(argThat(messages->messages.get(1).get("content").contains(switch(theme){case "WESTERN"->"玩家选择【西幻】";case "EASTERN"->"玩家选择【中幻】";default->"玩家选择【现代】";})));
   creation.generateWorld(sid,"p_demo",theme.equals("WESTERN")?"MODERN":"WESTERN");
   assertEquals(theme,worlds.find(sid).path("selectedTheme").asText());
  }
  verify(model,times(3)).chatMainForJson(anyList());
 }
 @Test void literaryNameIsGeneratedAndRetainedWithWorldInOneCall(){
  String named=WORLD.replace("\"title\":\"灰港\"","\"title\":\"长夜渡星\",\"namingBasis\":\"取长夜与渡星意象，未引用古籍原文。\"");
  when(model.chatMainForJson(anyList())).thenReturn(new LLMResponse(named,named,0,0,0,0));
  String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();creation.generateWorld(sid,"p_demo");creation.generateWorld(sid,"p_demo");
  assertEquals("长夜渡星",worlds.brief(sid).path("title").asText());assertTrue(worlds.find(sid).path("namingBasis").asText().contains("未引用"));
  verify(model,times(1)).chatMainForJson(argThat(messages->messages.get(0).get("content").contains("四到六个汉字") && messages.get(0).get("content").contains("不能伪造")));
 }
 @Test void worldIsIntroducedBeforeQuestionsAndRetryDoesNotRedraw(){
  var start=creation.startGame("p_demo","DREAMER");String sid=start.get("sessionId").toString();
  assertEquals("WORLD_PENDING",start.get("status"));assertTrue(((List<?>)start.get("questions")).isEmpty());
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->creation.submitAnswers(sid,"p_demo","一","二","三"));
  var ready=creation.generateWorld(sid,"p_demo");
  assertEquals("QUESTIONS",ready.get("status"));assertEquals(3,((List<?>)ready.get("questions")).size());
  assertEquals("灰港",((com.fasterxml.jackson.databind.JsonNode)ready.get("worldSetting")).path("title").asText());
  creation.generateWorld(sid,"p_demo");creation.creationState(sid,"p_demo");
  verify(model,times(1)).chatMainForJson(anyList());
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM session_world WHERE session_id=?",Integer.class,sid));
 }
 @Test void briefIsFixedAndOnlyContainsPublicWorldContext(){
  String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();creation.generateWorld(sid,"p_demo");
  var brief=worlds.brief(sid);assertEquals("WESTERN",brief.path("culturalStyle").asText());
  assertFalse(brief.has("questions"));assertFalse(brief.has("introduction"));assertTrue(worlds.prompt(sid).contains("不默认神明"));
  assertEquals(brief,worlds.brief(sid));
 }
 @Test void subsequentTalentRequestCarriesTheSameFixedWorld(){
  String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();creation.generateWorld(sid,"p_demo");
  String talents="{\"personality_tags\":[\"谨慎\"],\"talents\":[{\"talent_name\":\"观察入微\",\"stat_key\":\"wen\",\"power_level\":1}]}";
  String canonical=CreationWorldFixture.candidatesJson(talents);when(model.chatForJson(anyList())).thenReturn(new LLMResponse(canonical,canonical,0,0,0,0));
  creation.submitAnswers(sid,"p_demo","林舟，男，二十五岁，普通修理工","亲友","观察退路");
  verify(model).chatForJson(argThat(messages->messages.get(0).get("content").contains(worlds.brief(sid).path("summary").asText()) && messages.get(0).get("content").contains("WESTERN")));
 }
 @Test void failedGenerationKeepsTheCreatedSessionAndNoInventedFallbackWorld(){
  String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();
  when(model.chatMainForJson(anyList())).thenThrow(new LLMApiException("模拟服务中断",false));
  assertThrows(LLMApiException.class,()->creation.generateWorld(sid,"p_demo"));
  assertTrue(worlds.find(sid).isEmpty());
  assertEquals("WORLD_PENDING",creation.creationState(sid,"p_demo").get("status"));
 }
 @Test void worldIsSessionScopedAndForeignOwnerCannotGenerate(){
  String a=creation.startGame("p_demo","DREAMER").get("sessionId").toString();
  String b=creation.startGame("p_demo","DREAMER").get("sessionId").toString();creation.generateWorld(a,"p_demo");
  assertTrue(worlds.find(b).isEmpty());
  assertThrows(RuntimeException.class,()->creation.generateWorld(b,"someone_else"));
  verify(model,times(1)).chatMainForJson(anyList());
 }
 @Test void saveLibraryNamesEachSavedWorldWithoutGeneratingAnotherWorld(){
  String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();creation.generateWorld(sid,"p_demo");
  var row=sessionService.getPlayerSessions("p_demo").stream().filter(s->sid.equals(s.get("sessionId"))).findFirst().orElseThrow();
  assertEquals("灰港",row.get("worldTitle"));verify(model,times(1)).chatMainForJson(anyList());
 }
 @Test void untouchedAndFailedWorldRequestsAreNotSavesAndCanBeDiscarded(){
  String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();
  assertEquals(false,creation.creationState(sid,"p_demo").get("archiveReady"));
  assertTrue(sessionService.getPlayerSessions("p_demo").stream().noneMatch(s->sid.equals(s.get("sessionId"))));
  when(model.chatMainForJson(anyList())).thenThrow(new LLMApiException("模拟失败",false));
  assertThrows(LLMApiException.class,()->creation.generateWorld(sid,"p_demo"));
  assertTrue(sessionService.getPlayerSessions("p_demo").stream().noneMatch(s->sid.equals(s.get("sessionId"))));
  assertEquals(true,creation.abandonEmptyDraft(sid,"p_demo").get("discarded"));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_session WHERE session_id=?",Integer.class,sid));
  assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM character_creation WHERE session_id=?",Integer.class,sid));
  assertEquals(false,creation.abandonEmptyDraft(sid,"p_demo").get("discarded"));
 }
 @Test void abandoningNeverRemovesAnAuthoredWorldOrAnotherPlayersDraft(){
  String sid=creation.startGame("p_demo","DREAMER").get("sessionId").toString();
  assertThrows(com.bs.narrative.exception.BusinessException.class,()->creation.abandonEmptyDraft(sid,"someone_else"));
  creation.generateWorld(sid,"p_demo");
  assertEquals(true,creation.creationState(sid,"p_demo").get("archiveReady"));
  assertEquals(false,creation.abandonEmptyDraft(sid,"p_demo").get("discarded"));
  assertTrue(sessionService.getPlayerSessions("p_demo").stream().anyMatch(s->sid.equals(s.get("sessionId"))));
  assertEquals("灰港",worlds.find(sid).path("title").asText());
 }
}

