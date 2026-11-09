package com.bs.narrative.llm;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class LLMRequestPolicyTest {
 LLMConfig cfg;MockRestServiceServer server;DeepSeekLLMProvider provider;
 @BeforeEach void setup(){cfg=new LLMConfig();cfg.setMaxRetries(2);cfg.getMain().setApiKey("primary-test");cfg.getMain().setBackupApiKey("backup-test");cfg.getMain().setModel("test-model");var b=RestClient.builder().baseUrl("https://example.test");server=MockRestServiceServer.bindTo(b).build();provider=new DeepSeekLLMProvider(cfg,b.build());LLMUsage.begin();}
 @AfterEach void cleanup(){LLMUsage.clear();assertEquals(LLMRequestPolicy.DEFAULT,LLMRequestPolicy.current());}
 String response(String finish){return "{\"choices\":[{\"finish_reason\":\""+finish+"\",\"message\":{\"content\":\"{\\\"intent\\\":\\\"TALK\\\"}\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";}
 @Test void intentRetainsThinkingAndExplicitOutputLimit(){server.expect(requestTo("https://example.test/chat/completions")).andExpect(content().string(org.hamcrest.Matchers.containsString("\"reasoning_effort\":\"low\""))).andExpect(content().string(org.hamcrest.Matchers.containsString("\"max_tokens\":4096"))).andRespond(withSuccess(response("stop"),MediaType.APPLICATION_JSON));LLMRequestPolicy.INTENT.call(()->provider.chatForJson(List.of()));assertEquals(1,LLMUsage.totals().calls());server.verify();}
 @Test void narratorDisablesThinkingWithoutIncompatibleEffort(){server.expect(requestTo("https://example.test/chat/completions")).andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"disabled\""))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("reasoning_effort")))).andRespond(withSuccess(response("stop"),MediaType.APPLICATION_JSON));LLMRequestPolicy.NARRATION.call(()->provider.chatForJson(List.of()));server.verify();}
 @Test void longStoryAuthorHasItsOwnJsonCompatibleOutputAllowanceWithoutChangingArchivePolicy(){
  server.expect(requestTo("https://example.test/chat/completions")).andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"disabled\""))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("reasoning_effort")))).andExpect(content().string(org.hamcrest.Matchers.containsString("\"max_tokens\":12288"))).andRespond(withSuccess(response("stop"),MediaType.APPLICATION_JSON));
  LLMRequestPolicy.STORY_AUTHOR.call(()->provider.chatForJson(List.of()));assertEquals(1,LLMUsage.totals().calls());assertFalse(LLMRequestPolicy.ACTION.thinking());assertEquals(8192,LLMRequestPolicy.ACTION.maxTokens());server.verify();
 }
 @Test void focusedReviewKeepsJsonTransportWithoutTheGatewayThinkingCombination(){
  server.expect(requestTo("https://example.test/chat/completions"))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("\"type\":\"disabled\"")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("\"max_tokens\":12288")))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("response_format")))
   .andRespond(withSuccess(response("stop"),MediaType.APPLICATION_JSON));
  var reply=LLMRequestPolicy.STORY_REVIEW.call(()->provider.chatForJson(List.of(Map.of("role","system","content","Return the final manuscript as JSON."))));
  assertTrue(reply.content().contains("TALK"));assertEquals(1,LLMUsage.totals().calls());server.verify();
 }
 @Test void primaryIsAlwaysFirstAndUnauthorizedCanSwitchOnce(){server.expect(requestTo("https://example.test/chat/completions")).andExpect(header("Authorization","Bearer primary-test")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));server.expect(requestTo("https://example.test/chat/completions")).andExpect(header("Authorization","Bearer backup-test")).andRespond(withSuccess(response("stop"),MediaType.APPLICATION_JSON));LLMRequestPolicy.INTENT.call(()->provider.chatForJson(List.of()));server.verify();}
 @Test void permanentBadRequestNeverRetries(){server.expect(requestTo("https://example.test/chat/completions")).andRespond(withStatus(HttpStatus.BAD_REQUEST));assertThrows(LLMApiException.class,()->LLMRequestPolicy.INTENT.call(()->provider.chatForJson(List.of())));server.verify();}
 @Test void truncatedEvenValidJsonCannotBecomeGameActionButUsageIsCounted(){server.expect(requestTo("https://example.test/chat/completions")).andRespond(withSuccess(response("length"),MediaType.APPLICATION_JSON));var truncated=assertThrows(LLMApiException.class,()->LLMRequestPolicy.INTENT.call(()->provider.chatForJson(List.of())));assertEquals("MODEL_OUTPUT_TRUNCATED",truncated.category());assertEquals("length",truncated.finishReason());assertTrue(truncated.partialResponse().contains("TALK"));assertEquals(1,LLMUsage.totals().calls());server.verify();}
 @Test void timeoutDoesNotReplayAmbiguousChargedRequest(){server.expect(requestTo("https://example.test/chat/completions")).andRespond(withException(new java.net.SocketTimeoutException("simulated")));assertThrows(LLMApiException.class,()->LLMRequestPolicy.INTENT.call(()->provider.chatForJson(List.of())));server.verify();}
 @Test void zeroRetriesDoesNotStartWithBackup(){cfg.setMaxRetries(0);server.expect(requestTo("https://example.test/chat/completions")).andExpect(header("Authorization","Bearer primary-test")).andRespond(withSuccess(response("stop"),MediaType.APPLICATION_JSON));provider.chatForJson(List.of());server.verify();}
 @Test void nestedPolicyRestoresAfterFailure(){LLMRequestPolicy.INTENT.call(()->{assertThrows(IllegalStateException.class,()->LLMRequestPolicy.NPC.call(()->{throw new IllegalStateException();}));assertEquals(LLMRequestPolicy.INTENT,LLMRequestPolicy.current());return null;});}
}
