package com.bs.narrative.llm;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.*;
class LLMBillingObserverTest {
 LLMConfig cfg;MockRestServiceServer server;DeepSeekLLMProvider provider;List<LLMUsage.CallEvent> events=new ArrayList<>();
 @BeforeEach void setup(){cfg=new LLMConfig();cfg.setMaxRetries(0);cfg.getMain().setApiKey("test");cfg.getMain().setModel("test");var builder=RestClient.builder().baseUrl("https://example.test");server=MockRestServiceServer.bindTo(builder).build();provider=new DeepSeekLLMProvider(cfg,builder.build());LLMUsage.begin();}
 @AfterEach void cleanup(){LLMUsage.clear();}
 void expect(String raw){server.expect(requestTo("https://example.test/chat/completions")).andRespond(withSuccess(raw,MediaType.APPLICATION_JSON));}
 @Test void malformedChoicesStillRecordsUsageBeforeRejectingAnswer()throws Exception{
  expect("{\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}");
  try(var scope=LLMUsage.observe(events::add)){assertThrows(LLMApiException.class,()->provider.chatForJson(List.of()));}
  assertEquals(List.of("STARTED","RESPONSE"),events.stream().map(LLMUsage.CallEvent::state).toList());assertTrue(events.get(1).known());assertEquals(100,events.get(1).response().tokenInput());assertEquals(1,LLMUsage.totals().calls());server.verify();
 }
 @Test void missingUsageAndUnparseableEnvelopeStayUnconfirmed()throws Exception{
  expect("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}");expect("not-json");
  try(var scope=LLMUsage.observe(events::add)){provider.chatForJson(List.of());assertThrows(LLMApiException.class,()->provider.chatForJson(List.of()));}
  assertFalse(events.get(1).known());assertEquals("UNCONFIRMED",events.get(3).state());server.verify();
 }
 @Test void failedAccountingDoesNotSendOrRetryPaidRequest()throws Exception{
  try(var scope=LLMUsage.observe(e->{throw new IllegalStateException("database unavailable");})){assertThrows(LLMApiException.class,()->provider.chatForJson(List.of()));}
  server.verify();assertEquals(0,LLMUsage.totals().calls());
 }
 @Test void nestedObserverDoesNotLeakToNextRequest()throws Exception{
  try(var outer=LLMUsage.observe(events::add)){try(var inner=LLMUsage.observe(e->{})){try(var c=LLMUsage.startCall("inner")){}}try(var c=LLMUsage.startCall("outer")){}}
  try(var c=LLMUsage.startCall("unscoped")){}assertEquals(2,events.size());assertTrue(events.stream().allMatch(e->e.model().equals("outer")));assertNull(LLMUsage.observer());
 }
 @Test void jsonModeAddsRequiredFormatInstructionWithoutMutatingFrozenInput(){
  var original=List.of(Map.of("role","system","content","Return the specified object."),Map.of("role","user","content","Observe the rope."));
  server.expect(requestTo("https://example.test/chat/completions")).andExpect(jsonPath("$.messages[0].content").value(org.hamcrest.Matchers.containsString("MODEL_PROTOCOL_V1/GENERAL"))).andExpect(jsonPath("$.messages[1].content").value("Observe the rope.")).andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}",MediaType.APPLICATION_JSON));
  assertEquals("{}",provider.chatForJson(original).content());assertEquals(2,original.size());assertEquals("Return the specified object.",original.get(0).get("content"));server.verify();
 }
 @Test void requestRejectionIsDistinguishedFromNetworkFailure(){assertEquals("MODEL_REQUEST_INVALID",new LLMApiException("DeepSeek HTTP 400 channel=main",false).category());}
 @Test void exhaustedCallAllowanceKeepsItsCategoryAndNeverSendsAnHttpRequest()throws Exception{
  cfg.setMaxRetries(1);
  try(var scope=LLMUsage.observe(e->{throw new LLMApiException("MODEL_CALL_LIMIT: stop before transport",false);})){var error=assertThrows(LLMApiException.class,()->provider.chatForJson(List.of()));assertEquals("MODEL_CALL_LIMIT",error.category());assertFalse(error.isRetriable());}
  assertEquals(0,LLMUsage.totals().calls());server.verify();
 }
}
