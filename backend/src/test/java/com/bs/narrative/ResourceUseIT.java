package com.bs.narrative;

import com.bs.narrative.repository.*;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.LLMProvider;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Physical resource accounting in MySQL; all fixtures roll back and no model is called. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class ResourceUseIT {
 @Autowired OperationEffectService effects; @Autowired ItemStateService items;
 @Autowired GodProfileRepository actors; @Autowired GameSceneRepository scenes;
 @Autowired VitalStateService vitality; @Autowired JdbcTemplate jdbc;
 @MockBean LLMProvider model;
 final ObjectMapper json=new ObjectMapper(); String sid,player,npc,scene,pid;
 @BeforeEach void seed(){
  String tag=UUID.randomUUID().toString().substring(0,8);sid="use_"+tag;player="up_"+tag;npc="un_"+tag;scene="us_"+tag;pid="uu_"+tag;
  for(String id:List.of(player,npc))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','平静','[]')",id,sid,id.equals(player)?"旅人":"过客");
  jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'林边',JSON_ARRAY(?),'树下散落着一根枯枝。地上有碎石。',0,0)",scene,sid,npc);
  jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,?,'unused',?,?,'[]')",pid,player,pid,scene,sid);
  jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,?,?,'PLAYING')",sid,pid,player,scene);
 }
 @AfterEach void noCharge(){verifyNoInteractions(model);}
 void stock(String id,String... names){var actor=actors.findById(id).orElseThrow();actor.setInventory(new ArrayList<>(List.of(names)));actors.saveAndFlush(actor);}
 void life(int value){jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','生命',?,'VITAL_STATE','','','',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,player,"{\"health\":"+value+"}");}
 JsonNode packet(String process,int seconds,Object... changes){return json.valueToTree(Map.of("process",process,"durationSeconds",seconds,"status","COMPLETED","effects",List.of(changes)));}
 void apply(JsonNode result){effects.apply(sid,scenes.findById(scene).orElseThrow(),player,player,result,new HashSet<>());}
 JsonNode sources(){return json.valueToTree(effects.facts(sid,scenes.findById(scene).orElseThrow(),player,player)).path("gatherableResources");}
 JsonNode gather(String name){String ref="missing";for(var source:sources())if(name.equals(source.path("name").asText()))ref=source.path("ref").asText();return packet("你拾起"+name+"，已收入背包。",2,Map.of("kind","GATHER","sourceRef",ref,"basis","拾起"+name));}
 String held(String name){return items.owned(sid,actors.findById(player).orElseThrow()).stream().filter(i->name.equals(i.name())).findFirst().orElseThrow().ref();}
 JsonNode consume(String name,String purpose,int seconds){String ref=held(name);var result=(com.fasterxml.jackson.databind.node.ObjectNode)packet(purpose.equals("FIRST_AID")?"你用"+name+"完成包扎。":"你将"+name+"用尽。",seconds,Map.of("kind","CONSUME","itemRef",ref,"purpose",purpose,"basis",purpose.equals("FIRST_AID")?"用"+name+"完成包扎":"将"+name+"用尽"));result.set("uses",json.valueToTree(List.of(ref)));return result;}
 @Test void groundResourcesBecomeExactItemsWithTruthfulProvenanceAndCannotRespawn(){
  assertEquals(2,sources().size());var candidate=gather("枯枝");apply(candidate);
  var item=items.owned(sid,actors.findById(player).orElseThrow()).get(0);assertEquals("枯枝",item.name());assertTrue(item.origin().contains("林边"));assertEquals("GATHER",item.history().get(0).get("kind"));
  assertEquals(1,sources().size());assertThrows(IllegalArgumentException.class,()->apply(candidate));assertEquals(1,actors.findById(player).orElseThrow().getInventory().size());
  var other=actors.findById(npc).orElseThrow();assertThrows(IllegalArgumentException.class,()->effects.apply(sid,scenes.findById(scene).orElseThrow(),npc,player,candidate,new HashSet<>()));assertTrue(other.getInventory().isEmpty());
 }
 @ParameterizedTest @ValueSource(strings={"树下没有枯枝。","过客手中握着枯枝。","树上长着树枝。","地上有护身符。","地上有枯枝雕像。","如果地上有枯枝就好了。","地上有枯枝，但已被过客收走。"})
 void unsupportedOrOwnedResourcesCannotBeInvented(String opening){jdbc.update("UPDATE game_scene SET scene_summary=? WHERE scene_id=?",opening,scene);scenes.findById(scene).ifPresent(s->s.setSceneSummary(opening));assertTrue(sources().isEmpty());assertThrows(IllegalArgumentException.class,()->apply(gather("枯枝")));}
 @ParameterizedTest @ValueSource(strings={"BLOCKED","ONGOING","negated","forged"})
 void incompleteOrUngroundedGatherCannotGiveAnItem(String mode){var n=(com.fasterxml.jackson.databind.node.ObjectNode)gather("枯枝");if(mode.equals("negated"))n.put("process","你未能拾起枯枝。");else if(mode.equals("forged"))((com.fasterxml.jackson.databind.node.ObjectNode)n.path("effects").get(0)).put("sourceRef","invented");else n.put("status",mode).put("remainingSeconds",2);assertThrows(IllegalArgumentException.class,()->apply(n));assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());assertEquals(2,sources().size());}
 @Test void consumedMaterialCanNoLongerBeCraftedOrTransferred(){apply(gather("枯枝"));String ref=held("枯枝");apply(consume("枯枝","USE_UP",2));assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());assertEquals(100,vitality.health(sid,player));assertEquals("CONSUMED",jsonTree(ref).path("state").asText());assertEquals(1,sources().size());assertThrows(IllegalArgumentException.class,()->apply(packet("你把枯枝送出。",1,Map.of("kind","TRANSFER","fromId",player,"toId",npc,"itemRef",ref,"mode","GIFT"))));}
 @Test void changedSourceAndConflictingCollectorCannotAcquireFreeResources(){
  var candidate=gather("枯枝");var claims=new HashSet<String>();claims.add("resource:"+candidate.path("effects").get(0).path("sourceRef").asText());assertThrows(IllegalArgumentException.class,()->effects.apply(sid,scenes.findById(scene).orElseThrow(),player,player,candidate,claims));assertTrue(actors.findById(player).orElseThrow().getInventory().isEmpty());
  apply(packet("你将地上的枯枝烧成灰烬。",2,Map.of("kind","FACILITY","objectName","枯枝","before","","after","已烧成灰烬")));assertThrows(IllegalArgumentException.class,()->apply(candidate));assertEquals(1,sources().size());
 }
 @Test void healingCannotRaceAContestOrConsumeTheSharedBandage(){stock(player,"布绷带");life(75);var candidate=consume("布绷带","FIRST_AID",10);var claims=new HashSet<String>();claims.add("stats:"+player);assertThrows(IllegalArgumentException.class,()->effects.apply(sid,scenes.findById(scene).orElseThrow(),player,player,candidate,claims));assertEquals(75,vitality.health(sid,player));assertEquals(List.of("布绷带"),actors.findById(player).orElseThrow().getInventory());}
 JsonNode jsonTree(String ref){try{return json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,player,ref));}catch(Exception e){throw new RuntimeException(e);}}
 @Test void firstAidConsumesOnlyOneExactBandageAndCapsServerHealth(){stock(player,"布绷带","布绷带");life(95);var n=consume("布绷带","FIRST_AID",10);String ref=n.path("effects").get(0).path("itemRef").asText();apply(n);assertEquals(100,vitality.health(sid,player));assertEquals(List.of("布绷带"),actors.findById(player).orElseThrow().getInventory());assertEquals("CONSUMED",jsonTree(ref).path("state").asText());assertThrows(IllegalArgumentException.class,()->apply(n));}
 @ParameterizedTest @ValueSource(strings={"full","dead","too-fast","forged-work","unregistered","undeclared","borrowed","unfinished","negated","foreign-target","invented-health"})
 void invalidTreatmentDoesNotConsumeOrHeal(String mode){
  stock(player,mode.equals("unregistered")?"神奇树叶":"布绷带");life(mode.equals("full")?100:mode.equals("dead")?0:75);String name=mode.equals("unregistered")?"神奇树叶":"布绷带";
  var n=(com.fasterxml.jackson.databind.node.ObjectNode)consume(name,"FIRST_AID",mode.equals("too-fast") || mode.equals("forged-work")?1:10);var e=(com.fasterxml.jackson.databind.node.ObjectNode)n.path("effects").get(0);if(mode.equals("forged-work"))n.put("completedWorkSeconds",100);
  if(mode.equals("undeclared"))n.putArray("uses");if(mode.equals("unfinished"))n.put("status","ONGOING").put("remainingSeconds",10);if(mode.equals("negated"))n.put("process","你未能用布绷带完成包扎。");if(mode.equals("foreign-target"))e.put("targetId",npc);if(mode.equals("invented-health"))e.put("healthAfter",1000);
  if(mode.equals("borrowed")){stock(player);items.owned(sid,actors.findById(player).orElseThrow());stock(npc,"布绷带");var owner=actors.findById(npc).orElseThrow();String ref=items.owned(sid,owner).get(0).ref();items.transferExact(sid,owner,actors.findById(player).orElseThrow(),ref,"LEND");n=(com.fasterxml.jackson.databind.node.ObjectNode)consume("布绷带","FIRST_AID",10);}
  JsonNode candidate=n;int before=vitality.health(sid,player);assertThrows(IllegalArgumentException.class,()->apply(candidate));assertEquals(before,vitality.health(sid,player));assertEquals(List.of(name),actors.findById(player).orElseThrow().getInventory());
 }
 @Test void newEffectsAreVisibleWithoutLeakingPrivateReferencesOrHealth(){stock(player,"布绷带");life(75);var n=consume("布绷带","FIRST_AID",10);apply(n);var event=json.createObjectNode().put("actorId",player);event.set("effects",n.path("effects").deepCopy());assertTrue(ExecutionObservation.hasVisualEvidence(n));var projection=ExecutionObservation.forObserver(event,n,npc,"旅人");assertFalse(projection.toString().contains(heldRef(n)));assertFalse(projection.toString().contains("healthAfter"));assertFalse(projection.toString().contains("basis"));assertEquals("布绷带",projection.path("effects").get(0).path("name").asText());assertTrue(ExecutionObservation.hasVisualEvidence(gather("枯枝")));}
 String heldRef(JsonNode n){return n.path("effects").get(0).path("itemRef").asText();}
}
