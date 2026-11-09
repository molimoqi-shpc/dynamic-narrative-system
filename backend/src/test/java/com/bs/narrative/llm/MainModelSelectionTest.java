package com.bs.narrative.llm;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class MainModelSelectionTest {
 @Test void selectedMainModelChangesWireRequestAndPriceButLeavesRoleChannelAlone(){
  var cfg=new LLMConfig();cfg.setMaxRetries(0);cfg.getMain().setApiKey("test");cfg.getMain().setModel(MainModelContext.FLASH);cfg.getSub().setApiKey("test");cfg.getSub().setModel(MainModelContext.FLASH);
  var client=RestClient.builder().baseUrl("https://model.test");var server=MockRestServiceServer.bindTo(client).build();var provider=new DeepSeekLLMProvider(cfg,client.build());
  String response="{\"choices\":[{\"message\":{\"content\":\"完成\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50}}";
  server.expect(requestTo("https://model.test/chat/completions")).andExpect(jsonPath("$.model").value(MainModelContext.PRO)).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
  server.expect(requestTo("https://model.test/chat/completions")).andExpect(jsonPath("$.model").value(MainModelContext.FLASH)).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
  try(var scope=MainModelContext.use(MainModelContext.PRO)){assertEquals(.00225,provider.chat(List.of()).costCny(),.00000001);provider.chatSub(List.of());}
  assertNull(MainModelContext.current());assertEquals(MainModelContext.FLASH,cfg.getMain().getModel());server.verify();
 }
 @Test void choicesRestoreAfterFailureAndDoNotLeakBetweenWorkerThreads()throws Exception{
  assertThrows(IllegalArgumentException.class,()->MainModelContext.use("unapproved-model"));
  try(var scope=MainModelContext.use(MainModelContext.PRO)){
   assertThrows(IllegalStateException.class,()->{try(var inner=MainModelContext.use(MainModelContext.FLASH)){throw new IllegalStateException();}});assertEquals(MainModelContext.PRO,MainModelContext.current());
  }
  assertNull(MainModelContext.current());var pool=Executors.newFixedThreadPool(2);var barrier=new CyclicBarrier(2);
  try{var jobs=new ArrayList<Future<String>>();for(String model:List.of(MainModelContext.FLASH,MainModelContext.PRO))jobs.add(pool.submit(()->{try(var scope=MainModelContext.use(model)){barrier.await(3,TimeUnit.SECONDS);return MainModelContext.current();}}));assertEquals(List.of(MainModelContext.FLASH,MainModelContext.PRO),List.of(jobs.get(0).get(),jobs.get(1).get()));assertNull(pool.submit(MainModelContext::current).get());}finally{pool.shutdownNow();}
 }
}
