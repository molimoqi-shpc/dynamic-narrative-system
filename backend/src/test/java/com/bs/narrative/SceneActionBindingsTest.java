package com.bs.narrative;
import com.bs.narrative.service.*;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SceneActionBindingsTest {
 final ObjectMapper json=new ObjectMapper();
 SceneActionBindings bindings(){return new SceneActionBindings(List.of(Map.of("id","a","name","甲","attempt","检查现场","ownedItems",List.of(Map.of("ref","item_a","name","维修杆"))),Map.of("id","b","name","乙","attempt","观察","ownedItems",List.of())));}
 @Test void compactIdentitiesAndOwnedRefsAreBoundWithoutInventingAnOutcome()throws Exception{
  var n=json.readTree("{\"executionVersion\":2,\"events\":[{\"actorIndex\":0,\"text\":\"甲用维修杆检查。\",\"uses\":[\"r0\"],\"execution\":{\"status\":\"BLOCKED\"}}]}");bindings().normalize(n);
  var e=n.path("events").get(0);assertEquals("a",e.path("actorId").asText());assertEquals("item_a",e.path("uses").get(0).asText());assertEquals("检查现场",e.path("execution").path("task").asText());assertEquals("BLOCKED",e.path("execution").path("status").asText());assertTrue(n.path("sceneChanges").isArray());
  assertFalse(bindings().requestActors().get(0).containsKey("id"));
 }
 @Test void shortRefsNeverResolveAgainstAnotherParticipantsInventory()throws Exception{
  var n=json.readTree("{\"events\":[{\"actorIndex\":1,\"text\":\"乙用维修杆检查。\",\"uses\":[\"r0\"]}]}");bindings().normalize(n);assertEquals("r0",n.path("events").get(0).path("uses").get(0).asText());
  for(String event:List.of("{\"actorIndex\":8}","{\"actorIndex\":0,\"actorId\":\"b\"}","{\"actorIndex\":\"0\"}"))assertThrows(IllegalArgumentException.class,()->bindings().normalize(json.readTree("{\"events\":["+event+"]}")));
 }
 @Test void redundantBodyAnnotationsCannotAuthorizeAnItemOrChangePhysicalState()throws Exception{
  var n=json.readTree("{\"events\":[{\"actorIndex\":1,\"text\":\"乙以眼角余光观察。\",\"nonItemUses\":[{\"kind\":\"BODY\",\"name\":\"眼角\"},{\"kind\":\"BODY\",\"name\":\"铁锹\"}],\"execution\":{\"status\":\"ONGOING\"}}]}");bindings().normalize(n);var e=n.path("events").get(0);assertTrue(e.path("nonItemUses").isEmpty());assertTrue(e.path("uses").isEmpty());assertFalse(e.path("view").has("awake"));
 }
}
