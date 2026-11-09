package com.bs.narrative;
import com.bs.narrative.service.*;import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;import org.junit.jupiter.api.Test;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class JointAuthorizationTest {
 final ConditionWaitService service=new ConditionWaitService();final ObjectMapper json=new ObjectMapper();
 JsonNode n(Object v){return json.valueToTree(v);}
 @Test void omittedNegationAndContextCannotPassFragmentValidation(){
  assertThrows(IllegalArgumentException.class,()->service.normalizeAuthorization("我不拿木工凿，只观察",n(Map.of("steps",List.of(Map.of("type","ACTION","text","拿木工凿"))))));
 }
 @Test void optionalNullBindingsAreAbsentWithoutMutatingFrozenResponse()throws Exception{
  var supplied=json.readTree("{\"schemaVersion\":2,\"rawText\":\"等待5秒\",\"units\":[{\"unitId\":\"w\",\"type\":\"WAIT\",\"text\":\"等待5秒\",\"authorization\":\"EXECUTE\",\"seconds\":5,\"targetId\":null,\"condition\":null,\"conditionUnitId\":null,\"contextText\":null}]}");
  var result=service.normalizeAuthorization("等待5秒",supplied);assertFalse(result.path("steps").get(0).has("targetId"));assertFalse(result.path("steps").get(0).has("condition"));assertTrue(supplied.path("units").get(0).path("targetId").isNull());
 }
 @Test void legacyNullTargetIsAbsentButExplicitUnknownIdentityIsRetained()throws Exception{
  var supplied=json.readTree("{\"steps\":[{\"type\":\"WAIT\",\"text\":\"等待5秒\",\"seconds\":5,\"targetId\":null}]}");assertFalse(service.normalizeAuthorization("等待5秒",supplied).path("steps").get(0).has("targetId"));
  ((com.fasterxml.jackson.databind.node.ObjectNode)supplied.path("steps").get(0)).put("targetId","unknown");assertEquals("unknown",service.normalizeAuthorization("等待5秒",supplied).path("steps").get(0).path("targetId").asText());
 }
 @Test void missingConditionalFlagDoesNotAuthorizeImmediateAction(){
  var wait=Map.of("type","WAIT","text","等他答应","condition",Map.of("type","SEMANTIC_EVENT","text","他答应"));
  assertThrows(IllegalArgumentException.class,()->service.normalizeAuthorization("等他答应，再拿",n(Map.of("steps",List.of(wait,Map.of("type","ACTION","text","再拿"))))));
 }
 @Test void versionedGraphKeepsTheSpecificConditionAndOriginalText(){
  String input="等他答应，再拿";var a=n(Map.of("schemaVersion",2,"rawText",input,"units",List.of(
   Map.of("unitId","w","type","WAIT","text","等他答应","authorization","EXECUTE","condition",Map.of("type","SEMANTIC_EVENT","text","他答应")),
   Map.of("unitId","a","type","ACTION","text","再拿","authorization","CONDITIONAL","conditionUnitId","w"))));
  var result=service.normalizeAuthorization(input,a);assertTrue(result.path("steps").get(1).path("whenWaitSatisfied").asBoolean());assertEquals("他答应",result.path("steps").get(1).path("conditionText").asText());
 }
 @Test void unresolvedOrCyclicConditionCannotBeGuessed(){
  var a=n(Map.of("schemaVersion",2,"rawText","拿","units",List.of(Map.of("unitId","x","type","ACTION","text","拿","authorization","CONDITIONAL","conditionUnitId","x"))));
  assertThrows(IllegalArgumentException.class,()->service.normalizeAuthorization("拿",a));
 }
 @Test void completeMixedNegationIsRetainedRatherThanFilteredByAKeyword(){
  var result=service.normalizeAuthorization("不拿凿子，把绳子放下",n(Map.of("steps",List.of(Map.of("type","ACTION","text","不拿凿子，把绳子放下")))));
  assertEquals("不拿凿子，把绳子放下",result.path("steps").get(0).path("text").asText());
 }
 @Test void paymentRateLimitTimeoutAndConnectionAreDistinct(){
  assertEquals("MODEL_PAYMENT_REQUIRED",new LLMApiException("DeepSeek HTTP 402 channel=sub",false).category());
  assertEquals("MODEL_RATE_LIMITED",new LLMApiException("HTTP 429",true).category());
  assertEquals("MODEL_TIMEOUT",new LLMApiException("Read timed out",true).category());
  assertEquals("MODEL_CONNECTION_FAILED",new LLMApiException("Connection refused",true).category());
 }
 @Test void mismatchCarriesTheValueAtComparisonRatherThanThePostRollbackState(){
  var e=new StateConditionException("body condition changed","role","activity","old","actual");assertEquals("actual",e.details().get("actualValue"));assertEquals("body condition changed",e.getMessage());
 }
}
