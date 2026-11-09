package com.bs.narrative.service;
import com.bs.narrative.llm.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class NpcRequestExecutorTest {
 NpcRequestExecutor executor;
 @BeforeEach void setup(){executor=new NpcRequestExecutor();LLMUsage.begin();}
 @AfterEach void cleanup(){executor.destroy();LLMUsage.clear();}
 List<Map<String,String>> message(String id){return List.of(Map.of("role","user","content",id));}
 @Test void requestsOverlapWithThreeWorkerCeilingAndResultsKeepInputOrder() throws Exception {
  var model=mock(LLMProvider.class);var entered=new CountDownLatch(3);var release=new CountDownLatch(1);var active=new AtomicInteger();var peak=new AtomicInteger();
  when(model.chatSubForJson(anyList())).thenAnswer(inv->{int a=active.incrementAndGet();peak.accumulateAndGet(a,Math::max);entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));assertEquals(LLMRequestPolicy.NPC,LLMRequestPolicy.current());List<Map<String,String>> m=inv.getArgument(0);return new LLMResponse(m.get(0).get("content"),"{}",1,2,3,0.01);}finally{active.decrementAndGet();}});
  var caller=Executors.newSingleThreadExecutor();
  try{var future=caller.submit(()->executor.request(model,List.of(message("A"),message("B"),message("C"),message("D"),message("E"),message("F"))));assertTrue(entered.await(5,TimeUnit.SECONDS),"independent requests must start before any response");release.countDown();var results=future.get(5,TimeUnit.SECONDS);assertEquals(List.of("A","B","C","D","E","F"),results.stream().map(r->r.response().content()).toList());assertEquals(3,peak.get());}finally{release.countDown();caller.shutdownNow();}
 }
 @Test void oneActorFailureDoesNotEraseOthersAndUsageMergesOnce(){
  var model=mock(LLMProvider.class);when(model.chatSubForJson(anyList())).thenAnswer(inv->{List<Map<String,String>> m=inv.getArgument(0);var r=new LLMResponse("{}","{}",10,5,15,0.1);LLMUsage.record(r);if("B".equals(m.get(0).get("content")))throw new LLMApiException("unavailable",false);return r;});
  LLMUsage.record(new LLMResponse("","",2,1,3,0.02));var results=executor.request(model,List.of(message("A"),message("B")));assertNull(results.get(0).error());assertNotNull(results.get(1).error());assertEquals(3,LLMUsage.totals().calls());assertEquals(22,LLMUsage.totals().input());assertEquals(0.22,LLMUsage.totals().cost(),0.00001);
 }
 @Test void workerUsageDoesNotLeakIntoNextOperation(){var model=mock(LLMProvider.class);when(model.chatSubForJson(anyList())).thenAnswer(inv->{var r=new LLMResponse("{}","{}",3,1,4,0);LLMUsage.record(r);return r;});executor.request(model,List.of(message("A")));LLMUsage.begin();executor.request(model,List.of(message("B")));assertEquals(1,LLMUsage.totals().calls());assertEquals(3,LLMUsage.totals().input());}
}
