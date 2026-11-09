package com.bs.narrative.web;
import com.bs.narrative.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class GameAccessFilterTest {
 private GameAccessFilter filter(){var auth=mock(AuthService.class);when(auth.authenticate("valid")).thenReturn("owner");return new GameAccessFilter(auth,mock(com.bs.narrative.service.MainModelService.class));}
 private MockHttpServletRequest request(String path,byte[] body){var req=new MockHttpServletRequest("POST",path);req.addHeader("Authorization","Bearer valid");req.setContentType("application/json");req.setContent(body);return req;}
 @ParameterizedTest @ValueSource(booleans={false,true})
 void acceptsFullLengthChineseDialogueWithRawOrEscapedJson(boolean escaped)throws Exception{
  var json=new ObjectMapper();json.getFactory().configure(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(),escaped);
  String words="言".repeat(4000);
  byte[] body=json.writeValueAsBytes(Map.of("sessionId","own-save","sceneId","here","playerText",words,"dialogue",Map.of("targetGodId","friend","spokenText",words)));
  assertTrue(body.length>16384);var response=new MockHttpServletResponse();var called=new AtomicBoolean();
  filter().doFilter(request("/api/game/operations",body),response,(req,res)->{called.set(true);var accepted=json.readTree(req.getInputStream());assertEquals(words,accepted.path("playerText").asText());assertEquals(words,accepted.path("dialogue").path("spokenText").asText());assertEquals("owner",accepted.path("playerId").asText());});
  assertTrue(called.get());assertEquals(200,response.getStatus());
 }
 @Test void keepsOtherEndpointsBoundedAndRejectsOversizedOperationsBeforeControllers()throws Exception{
  for(var entry:Map.of("/api/game/start",16385,"/api/game/operations",65537).entrySet()){
   var response=new MockHttpServletResponse();var called=new AtomicBoolean();
   filter().doFilter(request(entry.getKey(),"x".repeat(entry.getValue()).getBytes(StandardCharsets.UTF_8)),response,(req,res)->called.set(true));
   assertFalse(called.get());assertEquals(400,response.getStatus());
  }
 }
}
