package com.bs.narrative;

import com.bs.narrative.llm.*;
import com.bs.narrative.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class CreationModelExchangeIT {
 @Autowired CreationModelExchange exchange;@Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager transactions;@Autowired ModelReplyJournal journal;
 @MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper();String sid;
 @BeforeEach void setup(){sid="wire_"+UUID.randomUUID().toString().substring(0,12);}
 List<Map<String,String>> messages(){return List.of(Map.of("role","system","content","本阶段职责"),Map.of("role","user","content","固定世界和玩家原始输入"));}
 LLMResponse reply(String raw){return new LLMResponse(raw,raw,10,20,30,0.0001);}
 @Test void malformedBirthSurvivesRollbackAndExplicitRetryRepairsOnlyTheSameText()throws Exception{
  var calls=new AtomicInteger();String failed="{\"birth_story\":\"已经完整生成的原始人生。\",\"initial_location\":\"错误类型\"}";
  assertThrows(LLMApiException.class,()->new TransactionTemplate(transactions).execute(status->exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{calls.incrementAndGet();return reply(failed);} )));
  assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM model_reply_journal WHERE session_id=?",Integer.class,sid));
  var fixed=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{calls.incrementAndGet();assertTrue(m.get(m.size()-1).get("content").contains("只修复同一候选"));assertTrue(m.toString().contains("已经完整生成的原始人生"));return reply("{\"birth_story\":\"修格式时试图改写的新人生。\",\"initial_location\":{\"location_name\":\"灰港\"}}");});
  assertEquals("已经完整生成的原始人生。",json.readTree(fixed.parsedJson()).path("birth_story").asText());assertEquals(2,calls.get());
  var reread=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{fail("A valid paid reply must not be regenerated");return null;});assertEquals(fixed.parsedJson(),reread.parsedJson());
  assertTrue(jdbc.queryForObject("SELECT original_response FROM model_reply_journal WHERE session_id=?",String.class,sid).contains("错误类型"));assertEquals(1,jdbc.queryForObject("SELECT repair_count FROM model_reply_journal WHERE session_id=?",Integer.class,sid));
 }
 @Test void tooFewTalentsAreNotFilledWithFixedChoicesAndRepairIsCached()throws Exception{
  var calls=new AtomicInteger();assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.TALENTS,messages(),m->{calls.incrementAndGet();return reply("{\"talents\":[{\"talent_name\":\"原始候选\"}]}");}));
  var fixed=exchange.request(sid,ModelProtocol.Stage.TALENTS,messages(),m->{calls.incrementAndGet();return reply("{\"talents\":[{\"talent_name\":\"原始候选\"},{\"talent_name\":\"主API第二候选\"},{\"talent_name\":\"主API第三候选\"}]}");});
  assertEquals(3,json.readTree(fixed.parsedJson()).path("talents").size());assertEquals(2,calls.get());assertEquals(fixed.parsedJson(),exchange.request(sid,ModelProtocol.Stage.TALENTS,messages(),m->{fail("No redraw");return null;}).parsedJson());
 }
 @Test void protocolInstructionChangesDoNotInvalidateAnAlreadyPaidBirth(){
  var first=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->reply("{\"birth_story\":\"固定原文\"}"));
  var changed=List.of(Map.of("role","system","content","升级后的统一格式规范"),messages().get(1));
  assertEquals(first.parsedJson(),exchange.request(sid,ModelProtocol.Stage.BIRTH,changed,m->{fail("Parser upgrades must reuse the paid candidate");return null;}).parsedJson());
 }
 @Test void separateNewSavesDoNotReuseEachOthersCandidate(){
  var calls=new AtomicInteger();for(String file:List.of(sid,sid+"_new"))exchange.request(file,ModelProtocol.Stage.BIRTH,messages(),m->reply("{\"birth_story\":\"第"+calls.incrementAndGet()+"份独立原文\"}"));
  assertEquals(2,calls.get());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM model_reply_journal WHERE session_id LIKE ?",Integer.class,sid+"%"));
 }
 @Test void reorderedJsonInputKeepsTheSamePaidReplyButChangedValuesDoNot(){
  var firstInput=List.of(messages().get(0),Map.of("role","user","content","{\"world\":{\"title\":\"灰港\",\"rules\":[\"一\",\"二\"]},\"operationId\":\"one\"}"));
  var reordered=List.of(messages().get(0),Map.of("role","user","content","{ \"operationId\":\"one\", \"world\":{\"rules\":[\"一\",\"二\"],\"title\":\"灰港\"} }"));
  var first=exchange.request(sid,ModelProtocol.Stage.BIRTH,firstInput,m->reply("{\"birth_story\":\"原始人生\"}"));
  assertEquals(first.parsedJson(),exchange.request(sid,ModelProtocol.Stage.BIRTH,reordered,m->{fail("Property order must not cause a paid redraw after restart");return null;}).parsedJson());
  var different=List.of(messages().get(0),Map.of("role","user","content",reordered.get(1).get("content").replace("\"one\"","\"two\"")));
  assertEquals("另一次人生",readStory(exchange.request(sid,ModelProtocol.Stage.BIRTH,different,m->reply("{\"birth_story\":\"另一次人生\"}"))));
 }
 String readStory(LLMResponse response){try{return json.readTree(response.parsedJson()).path("birth_story").asText();}catch(Exception failure){throw new AssertionError(failure);}}
 @Test void aTruncatedReplyRetainsCompleteEarlierStringsForTheExplicitRepair()throws Exception{
  String prefix="{\"birth_story\":\"保留完整前文\",\"initial_location\":";
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{throw new LLMApiException("finish_reason=length",false).withPartialResponse(prefix,"length");}));
  var fixed=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->reply("{\"birth_story\":\"不应替换\",\"initial_location\":{}}"));assertEquals("保留完整前文",json.readTree(fixed.parsedJson()).path("birth_story").asText());
 }
 @Test void anEmptyBirthCanBeCompletedWithoutReapplyingTheSavedEmptyField()throws Exception{
  var calls=new AtomicInteger();String original="{\"birth_story\":\"\",\"player_name\":\"艾文\"}";
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{calls.incrementAndGet();return reply(original);}));
  var fixed=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{calls.incrementAndGet();return reply("{\"birth_story\":\"艾文在灰港长大，渡船停航的清晨，他接过父亲留下的旧信。\",\"player_name\":\"不应换名\"}");});
  assertFalse(readStory(fixed).isBlank());assertEquals("艾文",json.readTree(fixed.parsedJson()).path("player_name").asText());assertEquals(2,calls.get());
  assertEquals(fixed.parsedJson(),exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{fail("The completed birth must advance from the paid repair");return null;}).parsedJson());
  assertEquals(original,json.readTree(jdbc.queryForObject("SELECT original_response FROM model_reply_journal WHERE session_id=?",String.class,sid)).path("content").asText());
  assertEquals(1,jdbc.queryForObject("SELECT repair_count FROM model_reply_journal WHERE session_id=?",Integer.class,sid));
 }
 @Test void blankWorldFieldsCanBeCompletedWhileTheFinishedIntroductionRemainsLiteral()throws Exception{
  var original=worldCandidate("WESTERN");original.put("era","   ").put("summary","");String raw=original.toString();var calls=new AtomicInteger();
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.WORLD,messages(),m->{calls.incrementAndGet();return reply(raw);}));
  var fixed=exchange.request(sid,ModelProtocol.Stage.WORLD,messages(),m->{calls.incrementAndGet();return reply(worldCandidate("WESTERN").put("introduction","格式修复不应重新创作世界介绍").toString());});
  var accepted=json.readTree(fixed.parsedJson());assertEquals("早期工业时代",accepted.path("era").asText());assertFalse(accepted.path("summary").asText().isBlank());
  assertEquals(original.path("introduction"),accepted.path("introduction"));assertEquals(2,calls.get());
  assertEquals(fixed.parsedJson(),exchange.request(sid,ModelProtocol.Stage.WORLD,messages(),m->{fail("An accepted world must not keep repairing the same empty original");return null;}).parsedJson());
  assertEquals(raw,json.readTree(jdbc.queryForObject("SELECT original_response FROM model_reply_journal WHERE session_id=?",String.class,sid)).path("content").asText());
 }
 @Test void anInvalidOriginalCultureDoesNotOverwriteTheRepairedWorldCulture()throws Exception{
  var original=worldCandidate("UNKNOWN");original.put("era","");String raw=original.toString();
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.WORLD,messages(),m->reply(raw)));
  var fixed=exchange.request(sid,ModelProtocol.Stage.WORLD,messages(),m->reply(worldCandidate("WESTERN").toString()));
  assertEquals("WESTERN",json.readTree(fixed.parsedJson()).path("culturalStyle").asText());
  assertEquals(fixed.parsedJson(),exchange.request(sid,ModelProtocol.Stage.WORLD,messages(),m->{fail("The repaired culture must remain usable on reread");return null;}).parsedJson());
  assertEquals(raw,json.readTree(jdbc.queryForObject("SELECT original_response FROM model_reply_journal WHERE session_id=?",String.class,sid)).path("content").asText());
 }
 @Test void aFinishedBirthInsideAnExplicitEnvelopeOrFenceIsNotRedrawnByAFieldRepair()throws Exception{
  String candidate="{\"birth_story\":\"原稿已写完的出生故事。\",\"initial_location\":\"错误类型\"}";int index=0;
  for(String raw:List.of("```json\n"+candidate+"\n```","{\"data\":"+candidate+"}","{\"type\":\"object\",\"properties\":"+candidate+"}","{\"result\":{\"data\":"+candidate+"}}")){
   String file=sid+"_"+index++;assertThrows(LLMApiException.class,()->exchange.request(file,ModelProtocol.Stage.BIRTH,messages(),m->reply(raw)));
   var fixed=exchange.request(file,ModelProtocol.Stage.BIRTH,messages(),m->reply("{\"birth_story\":\"不应重抽的新故事\",\"initial_location\":{}}"));
   assertEquals("原稿已写完的出生故事。",readStory(fixed));assertEquals(fixed.parsedJson(),exchange.request(file,ModelProtocol.Stage.BIRTH,messages(),m->{fail("Do not redraw a repaired wrapped birth");return null;}).parsedJson());
  }
 }
 @Test void aTruncatedEnvelopeKeepsOnlyItsAlreadyClosedBirthString()throws Exception{
  String raw="```json\n{\"data\":{\"birth_story\":\"已经闭合的原始正文\",\"initial_location\":";
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{throw new LLMApiException("finish_reason=length",false).withPartialResponse(raw,"length");}));
  assertEquals("已经闭合的原始正文",readStory(exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->reply("{\"birth_story\":\"不应重写\",\"initial_location\":{}}"))));
 }
 com.fasterxml.jackson.databind.node.ObjectNode worldCandidate(String culture){
  var value=json.createObjectNode().put("title","长夜余烬").put("genre","西方悬疑幻想").put("culturalStyle",culture).put("era","早期工业时代")
   .put("introduction","海雾封住灰港，议会与旧行会轮流敲响夜钟。港口居民以潮汐定工时，以旧契约互保生计。")
   .put("summary","早期工业港城以航运为生，议会与行会共同治理；没有已经证实的神明或魔法。");
  value.putArray("boundaries").add("蒸汽船与印刷术已普及");value.putArray("questions").add("你叫什么，来自哪里？").add("你最重视什么？").add("遇到困境会怎么做？");return value;
 }
 @Test void aSavedWellFormedButIncompleteItemTableCanBeRepairedInsteadOfLoopingOnTheCache(){
  var calls=new AtomicInteger();java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode> owners=value->{if(value.path("initial_inventory").isEmpty())throw new IllegalArgumentException("缺少已给定的PLAYER行");};
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.OPENING_ITEMS,messages(),m->{calls.incrementAndGet();return reply("{\"initial_inventory\":[]}");},owners));
  var fixed=exchange.request(sid,ModelProtocol.Stage.OPENING_ITEMS,messages(),m->{calls.incrementAndGet();return reply("{\"initial_inventory\":[{\"owner\":\"PLAYER\",\"items\":[]}]}");},owners);
  assertNotNull(fixed.parsedJson());assertEquals(2,calls.get());exchange.request(sid,ModelProtocol.Stage.OPENING_ITEMS,messages(),m->{fail("Complete rows must be reused");return null;},owners);
 }
 @Test void anAlreadyPaidFrameInsidePropertiesIsReparsedWithoutAnotherModelRequest()throws Exception{
  String raw="{\"type\":\"object\",\"properties\":{\"endMoment\":{\"year\":0,\"day\":1,\"period\":\"MORNING\"},\"scenes\":[{\"key\":\"lesson\",\"at\":{\"year\":0,\"day\":1,\"period\":\"DAWN\"},\"content\":\"继续受教并核对旧痕\"}],\"stop\":\"RESOLVED\"}}";
  String source=StoryArchiveService.source(json.valueToTree(List.of(messages().get(1))));
  journal.received(sid,ModelProtocol.Stage.STORY_FRAME,source,reply(raw),false);
  journal.issue(sid,ModelProtocol.Stage.STORY_FRAME,source,"旧读取器未找到根层endMoment");
  var accepted=exchange.request(sid,ModelProtocol.Stage.STORY_FRAME,messages(),m->{fail("A parser-only repair must reuse the paid frame");return null;},frame->{
   assertEquals("MORNING",frame.at("/endMoment/period").asText());assertEquals("DAWN",frame.at("/scenes/0/at/period").asText());
  });
  assertEquals("继续受教并核对旧痕",json.readTree(accepted.parsedJson()).at("/scenes/0/content").asText());
  var stored=journal.find(sid,ModelProtocol.Stage.STORY_FRAME,source);assertEquals(raw,stored.original().content());assertEquals(raw,stored.latest().content());
  assertEquals(0,stored.repairs());assertEquals("",stored.issue());
 }
 @Test void aParseableTransportTruncatedBirthMustBeCompletedBeforeReuse()throws Exception{
  String candidate="{\"birth_story\":\"已经写出的原始人生\"}";var calls=new AtomicInteger();
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{calls.incrementAndGet();throw new LLMApiException("finish_reason=length",false).withPartialResponse(candidate,"length");}));
  var fixed=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{calls.incrementAndGet();assertTrue(m.stream().anyMatch(x->"assistant".equals(x.get("role")) && candidate.equals(x.get("content"))));return reply("{\"birth_story\":\"不可重抽的人生\",\"player_name\":\"艾文\"}");});
  assertEquals(2,calls.get(),"A parseable fragment is not a transport-complete reply");assertEquals("艾文",json.readTree(fixed.parsedJson()).path("player_name").asText());assertEquals("已经写出的原始人生",readStory(fixed));
  assertEquals(fixed.parsedJson(),exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{fail("A completed repair must be reused");return null;}).parsedJson());
 }
 @RepeatedTest(3) void legacyAndNewIncompleteMarkersCannotBeClearedJustBecauseTheJsonParses(RepetitionInfo repetition)throws Exception{
  String candidate="{\"birth_story\":\"原有完整段落\"}";
  String source=StoryArchiveService.source(json.valueToTree(List.of(messages().get(1))));
  if(repetition.getCurrentRepetition()<3){
   journal.received(sid,ModelProtocol.Stage.BIRTH,source,reply(candidate),false);
   journal.issue(sid,ModelProtocol.Stage.BIRTH,source,repetition.getCurrentRepetition()==1?"MODEL_OUTPUT_TRUNCATED；费用以服务商用量为准":"MODEL_TIMEOUT；费用以服务商用量为准");
  }else journal.receivedIncomplete(sid,ModelProtocol.Stage.BIRTH,source,reply(candidate),false,"供应商未完成当前候选");
  assertTrue(journal.find(sid,ModelProtocol.Stage.BIRTH,source).incomplete());var calls=new AtomicInteger();
  var fixed=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{calls.incrementAndGet();return reply("{\"birth_story\":\"不应改变的旧故事\",\"player_name\":\"艾文\"}");});
  assertEquals(1,calls.get());assertEquals("原有完整段落",readStory(fixed));assertEquals("艾文",json.readTree(fixed.parsedJson()).path("player_name").asText());
  var stored=journal.find(sid,ModelProtocol.Stage.BIRTH,source);assertFalse(stored.incomplete());assertEquals(candidate,stored.original().content());assertEquals(1,stored.repairs());
  exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{fail("Completed replies must remain reusable");return null;});
 }
 @Test void aCompleteFormatFailureIsReparsedWithoutPayingForAnotherCandidate(){
  String candidate="{\"birth_story\":\"已经完成的原始人生\"}";
  assertThrows(LLMApiException.class,()->exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{throw new LLMApiException("完整原稿的旧读取失败",false).withPartialResponse(candidate,"FORMAT");}));
  var accepted=exchange.request(sid,ModelProtocol.Stage.BIRTH,messages(),m->{fail("A complete FORMAT candidate must be reparsed locally");return null;});
  assertEquals("已经完成的原始人生",readStory(accepted));
 }
 @AfterEach void cleanup(){jdbc.update("DELETE FROM model_reply_journal WHERE session_id LIKE ?",sid+"%");}
}
