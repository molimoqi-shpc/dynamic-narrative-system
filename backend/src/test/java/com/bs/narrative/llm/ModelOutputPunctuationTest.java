package com.bs.narrative.llm;

import com.bs.narrative.service.SceneStoryService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelOutputPunctuationTest {
 @Test void realRoleReplyWithAFullWidthSeparatorIsRecoveredWithoutChangingItsWords(){
  String thought="水来得急，人先撤。";
  var role=new SceneStoryService(null).direction("{\"thought\":\""+thought+"\"，\"intention\":\"先撤到高处\",\"actionDirection\":\"岸上传话，递绳木\"}");
  assertEquals(thought,role.path("thought").asText());assertEquals("先撤到高处",role.path("intention").asText());assertEquals("岸上传话，递绳木",role.path("actionDirection").asText());
 }
 @Test void colonsAndCommasInsideEscapedQuotedTextStayVerbatim(){
  var parsed=ModelOutput.object("{\"text\"：\"他说：\\\"先撤，别回去\\\"。\"，\"nested\"：{\"a\"：1，\"b\":[1，2]}}");
  assertEquals("他说：\"先撤，别回去\"。",parsed.path("text").asText());assertEquals(2,parsed.at("/nested/b/1").asInt());
 }
 @Test void aTrailingSecondObjectOrBrokenReplyCannotBecomeAnAcceptedRole(){
  for(String raw:java.util.List.of("{\"intention\":\"甲\"} {\"intention\":\"乙\"}","{\"intention\":\"未完", "{\"intention\"：\"甲\"，", "{\"intention\":\"甲\"} trailing"))
   assertThrows(IllegalArgumentException.class,()->ModelOutput.object(raw));
 }
}
