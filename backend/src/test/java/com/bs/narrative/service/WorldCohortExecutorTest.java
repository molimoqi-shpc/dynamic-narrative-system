package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorldCohortExecutorTest {
 @ParameterizedTest @ValueSource(strings={"wrapped","bare","foreign"})
 void singleJobCanUseUnambiguousResultWithoutAnotherPaidRequest(String shape){
  var executor=mock(NpcRequestExecutor.class);var service=new WorldCohortExecutor(executor);var envelope=shape.equals("bare")?result():new ObjectMapper().createObjectNode();
  if(!shape.equals("bare"))envelope.set("result",result());if(shape.equals("foreign"))envelope.put("jobId","other");
  when(executor.request(any(),anyList(),anyLong(),eq(LLMRequestPolicy.ACTION),eq(false))).thenReturn(List.of(response(envelope)));
  var output=service.request(mock(LLMProvider.class),List.of("a"),List.of(input(facts("a","one",0))),Long.MAX_VALUE);
  assertEquals(shape.equals("foreign"),output.results().get(0).error()!=null);verify(executor,times(1)).request(any(),anyList(),anyLong(),eq(LLMRequestPolicy.ACTION),eq(false));
 }
 @Test void itemPlacementBeforeUsesOnlyFrozenOwnedItemsAndTracksRepeatedPlacement(){
  var f=facts("a","one",0);((ObjectNode)f.path("resources").path("a")).putArray("items").addObject().put("ref","tool").put("position","手中");var original=f.deepCopy();var r=result();
  r.withArray("effects").addObject().put("kind","ITEM_POSITION").put("itemRef","tool").put("before","错误旧位置").put("after","膝上");
  r.withArray("effects").addObject().put("kind","ITEM_POSITION").put("itemRef","tool").put("after","背上");
  r.withArray("effects").addObject().put("kind","ITEM_POSITION").put("itemRef","foreign").put("before","未经授权").put("after","手中");
  var bound=WorldCohortExecutor.bindBodyBaselines(r,f);assertEquals("手中",bound.path("effects").get(0).path("before").asText());assertEquals("膝上",bound.path("effects").get(1).path("before").asText());assertEquals("未经授权",bound.path("effects").get(2).path("before").asText());assertEquals(original,f);assertEquals("错误旧位置",r.path("effects").get(0).path("before").asText());
 }
 final ObjectMapper json=new ObjectMapper();
 ObjectNode facts(String actor,String scene,int time){var f=json.createObjectNode().put("actorId",actor).put("actorName",actor).put("attempt",actor+"行动").put("sceneId",scene).put("snapshotAtSeconds",time);f.putObject("resources").putObject(actor).putObject("body").put("awake",true).put("activity","旧活动").put("position","原地");return f;}
 ObjectNode result(){var r=json.createObjectNode().put("process","实际经过").put("durationSeconds",2).put("status","COMPLETED");r.putArray("effects");return r;}
 List<Map<String,String>> input(JsonNode facts){return List.of(Map.of("role","system","content","原执行合同"),Map.of("role","user","content",facts.toString()));}
 NpcRequestExecutor.Result response(JsonNode n){String raw=n.toString();return new NpcRequestExecutor.Result(LLMResponse.json(raw,raw,20,5,0.001),null,new LLMUsage.Totals(1,20,5,0.001));}
 @Test void cohortsNeverMixDifferentScenesSnapshotsOrContracts()throws Exception{
  var executor=mock(NpcRequestExecutor.class);var service=new WorldCohortExecutor(executor);var seen=new ArrayList<JsonNode>();
  when(executor.request(any(),anyList(),anyLong(),eq(LLMRequestPolicy.ACTION),eq(false))).thenAnswer(call->{List<List<Map<String,String>>> batch=call.getArgument(1);var outputs=new ArrayList<NpcRequestExecutor.Result>();for(var messages:batch){var request=json.readTree(messages.get(1).get("content"));seen.add(request);var out=json.createObjectNode();var results=out.putArray("results");for(var attempt:request.path("attempts"))results.addObject().put("jobId",attempt.path("jobId").asText()).set("result",result());outputs.add(response(out));}return outputs;});
  var a=facts("a","one",0);var b=facts("b","one",0);var c=facts("c","two",0);var d=facts("d","one",1);var other=List.of(Map.of("role","system","content","不同执行合同"),Map.of("role","user","content",a.toString()));
  var output=service.request(mock(LLMProvider.class),List.of("a","b","c","d","e"),List.of(input(a),input(b),input(c),input(d),other),Long.MAX_VALUE);
  assertEquals(4,seen.size());assertEquals(2,seen.get(0).path("attempts").size());assertEquals(5,output.results().size());assertTrue(output.results().stream().allMatch(r->r.error()==null));assertEquals(4,output.audit().size());assertEquals(a.path("resources"),seen.get(0).path("attempts").get(0).path("facts").path("resources"));
 }
 @ParameterizedTest @ValueSource(strings={"missing","duplicate","malformed-envelope","unknown-extra"})
 void perJobMappingCannotSwapActorsAndMissingMembersDoNotDestroyHealthyOnes(String fault)throws Exception{
  var executor=mock(NpcRequestExecutor.class);var service=new WorldCohortExecutor(executor);var out=json.createObjectNode();var results=out.putArray("results");results.addObject().put("jobId","b").set("result",result().put("process","乙的原结果"));
  if(!fault.equals("missing"))results.addObject().put("jobId","a").set("result",result().put("process","甲的原结果"));if(fault.equals("duplicate"))results.addObject().put("jobId","a").set("result",result());if(fault.equals("unknown-extra"))results.addObject().put("jobId","foreign").set("result",result());if(fault.equals("malformed-envelope"))out.remove("results");
  when(executor.request(any(),anyList(),anyLong(),eq(LLMRequestPolicy.ACTION),eq(false))).thenReturn(List.of(response(out)));
  var output=service.request(mock(LLMProvider.class),List.of("a","b"),List.of(input(facts("a","one",0)),input(facts("b","one",0))),Long.MAX_VALUE);
  assertEquals(2,output.results().size());assertEquals(fault.equals("missing")||fault.equals("duplicate")||fault.equals("malformed-envelope"),output.results().get(0).error()!=null);
  if(!fault.equals("malformed-envelope")){assertNull(output.results().get(1).error());assertEquals("乙的原结果",json.readTree(output.results().get(1).response().parsedJson()).path("process").asText());}
 }
 @Test void bodyBeforeComesFromFrozenFactsAndTracksSequentialChangesWithoutMutatingThem(){
  var f=facts("a","one",0);f.putObject("serverEffectBaselines").putObject("a").put("activity","冻结活动").put("position","冻结位置").putArray("percepts").add("自己的感知");var original=f.deepCopy();var r=result();
  var first=r.withArray("effects").addObject().put("kind","BODY");first.putObject("before").put("activity","模型抄错");first.putObject("after").put("activity","第一步");r.withArray("effects").addObject().put("kind","BODY").putObject("after").put("activity","第二步").putArray("percepts").add("新感知");
  var bound=WorldCohortExecutor.bindBodyBaselines(r,f);assertEquals("冻结活动",bound.path("effects").get(0).path("before").path("activity").asText());assertEquals("第一步",bound.path("effects").get(1).path("before").path("activity").asText());assertEquals("自己的感知",bound.path("effects").get(1).path("before").path("percepts").get(0).asText());assertEquals("a",bound.path("effects").get(0).path("actorId").asText());assertEquals(original,f);assertEquals("模型抄错",r.path("effects").get(0).path("before").path("activity").asText());
 }
 @ParameterizedTest @ValueSource(strings={"health","updatedAtTick","awake"})
 void bindingNeverGrantsUnsupportedChangesOrFixesInvalidAfterValues(String field){var f=facts("a","one",0);var r=result();r.withArray("effects").addObject().put("kind","BODY").put("actorId","a").putObject("after").put(field,200);var bound=WorldCohortExecutor.bindBodyBaselines(r,f);assertThrows(IllegalArgumentException.class,()->new OperationEffectService(null,null,null,null,null,null).validateCandidate(bound));}
}
