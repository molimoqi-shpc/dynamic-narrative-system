package com.bs.narrative;

import com.bs.narrative.llm.LLMProvider;
import com.bs.narrative.repository.*;
import com.bs.narrative.service.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Local physical-state regressions: real MySQL, rolled-back fixtures, no paid model requests. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class PhysicalEffectBoundaryIT {
    @Autowired OperationEffectService effects;
    @Autowired OpenSceneService physical;
    @Autowired GodProfileRepository actors;
    @Autowired GameSceneRepository scenes;
    @Autowired ItemStateService items;
    @Autowired JdbcTemplate jdbc;
    @MockBean LLMProvider model;
    final ObjectMapper json=new ObjectMapper();
    String sid,player,npc,pid,source,destination;
    @BeforeEach void seed(){
        String tag=UUID.randomUUID().toString().substring(0,8);
        sid="boundary_"+tag;player="bp_"+tag;npc="bn_"+tag;pid="bu_"+tag;source="bs_"+tag;destination="bd_"+tag;
        for(String id:List.of(player,npc))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','平静',?)",id,sid,id.equals(player)?"旅人":"守门人",id.equals(player)?"[\"布条\"]":"[]");
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门内',JSON_ARRAY(?),'门内有石柱，守门人清醒。',0,0)",source,sid,npc);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门外','[]','门外无人。',1,0)",destination,sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,?,'unused',?,?,'[]')",pid,player,pid,source,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,?,?,'PLAYING')",sid,pid,player,source);
        jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,'EAST','门')",sid,source,destination);
    }
    JsonNode packet(Object... changes){return json.valueToTree(Map.of("process","实际动作经过。","durationSeconds",2,"status","COMPLETED","effects",List.of(changes)));}
    Map<String,Object> move(){return Map.of("kind","MOVE","actorId",player,"fromSceneId",source,"toSceneId",destination);}
    Map<String,Object> body(String id,String field,Object before,Object after){return Map.of("kind","BODY","actorId",id,"before",Map.of(field,before),"after",Map.of(field,after));}
    Map<String,Object> gift(){return Map.of("kind","TRANSFER","fromId",player,"toId",npc,"itemRef",items.owned(sid,actors.findById(player).orElseThrow()).get(0).ref(),"mode","GIFT");}
    void apply(JsonNode packet){effects.apply(sid,scenes.findById(source).orElseThrow(),player,player,packet,new HashSet<>());}
    void life(String id,int health){jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'vital:v1','生命',?,'VITAL_STATE','','','',0)",sid,id,"{\"health\":"+health+"}");}
    @AfterEach void noModelCost(){verifyNoInteractions(model);}

    String held(String actor,String name){return items.owned(sid,actors.findById(actor).orElseThrow()).stream().filter(i->name.equals(i.name())).findFirst().orElseThrow().ref();}
    JsonNode ordinaryCraft(String material,String tool,String text,String basis){
        var result=(com.fasterxml.jackson.databind.node.ObjectNode)packet(Map.of("kind","CRAFT","name","布绷带","materials",List.of(material),"tools",tool.isEmpty()?List.of():List.of(tool),"basis",basis));
        result.put("process",text);result.set("uses",json.valueToTree(tool.isEmpty()?List.of(material):List.of(material,tool)));return result;
    }
    @Test void ordinaryCraftConsumesRealMaterialPreservesToolsAndStoresStableProvenance(){
        var self=actors.findById(player).orElseThrow();self.setInventory(new ArrayList<>(List.of("布条","小刀")));actors.saveAndFlush(self);String cloth=held(player,"布条"),knife=held(player,"小刀");
        apply(ordinaryCraft(cloth,knife,"你用小刀将布条加工成布绷带。","将布条加工成布绷带"));
        assertEquals(List.of("小刀","布绷带"),self.getInventory());var output=items.owned(sid,self).stream().filter(i->i.name().equals("布绷带")).findFirst().orElseThrow();
        assertEquals(cloth,output.history().get(0).get("materials"));assertEquals(knife,output.history().get(0).get("tools"));assertEquals(output,items.owned(sid,self).stream().filter(i->i.ref().equals(output.ref())).findFirst().orElseThrow());
        assertEquals("CONSUMED",readItemState(player,cloth));assertEquals(knife,held(player,"小刀"));
    }
    String readItemState(String owner,String ref){try{return json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,owner,ref)).path("state").asText();}catch(Exception e){throw new RuntimeException(e);}}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"未能","准备","尝试","如果有空便","尚未"})
    void plannedOrNegatedCraftDoesNotCreateAnItem(String prefix){
        String cloth=held(player,"布条");assertThrows(IllegalArgumentException.class,()->apply(ordinaryCraft(cloth,"","你"+prefix+"制成布绷带。","制成布绷带")));assertEquals("HELD",readItemState(player,cloth));assertEquals(List.of("布条"),actors.findById(player).orElseThrow().getInventory());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"BLOCKED","ONGOING"})
    void incompleteCraftDoesNotConsumeMaterialOrCreateOutput(String status){
        String cloth=held(player,"布条");var n=(com.fasterxml.jackson.databind.node.ObjectNode)ordinaryCraft(cloth,"","你制成布绷带。","制成布绷带");n.put("status",status).put("remainingSeconds",5);assertThrows(IllegalArgumentException.class,()->apply(n));assertEquals("HELD",readItemState(player,cloth));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"unknown","foreign","undeclared","duplicate","material-tool","magic","invented-basis"})
    void invalidCraftCannotCreateFreeItems(String mode){
        var other=actors.findById(npc).orElseThrow();other.setInventory(new ArrayList<>(List.of("木料")));actors.saveAndFlush(other);String cloth=held(player,"布条");var n=(com.fasterxml.jackson.databind.node.ObjectNode)ordinaryCraft(cloth,"","你制成布绷带。","制成布绷带");var craft=(com.fasterxml.jackson.databind.node.ObjectNode)n.path("effects").get(0);
        switch(mode){
            case "unknown" -> {craft.set("materials",json.valueToTree(List.of("item_missing")));n.set("uses",json.valueToTree(List.of("item_missing")));}
            case "foreign" -> {String foreign=held(npc,"木料");craft.set("materials",json.valueToTree(List.of(foreign)));n.set("uses",json.valueToTree(List.of(foreign)));}
            case "undeclared" -> n.putArray("uses");
            case "duplicate" -> craft.set("materials",json.valueToTree(List.of(cloth,cloth)));
            case "material-tool" -> craft.set("tools",json.valueToTree(List.of(cloth)));
            case "magic" -> {craft.put("name","护身符").put("basis","制成护身符");n.put("process","你制成护身符。");}
            case "invented-basis" -> craft.put("basis","已加工成布绷带");
        }
        assertThrows(IllegalArgumentException.class,()->apply(n));assertEquals("HELD",readItemState(player,cloth));assertEquals(List.of("布条"),actors.findById(player).orElseThrow().getInventory());
    }
    @Test void borrowedMaterialCannotBeConsumedButBorrowedToolCanBeUsedAndReturned(){
        var self=actors.findById(player).orElseThrow();var other=actors.findById(npc).orElseThrow();other.setInventory(new ArrayList<>(List.of("布料","小刀")));actors.saveAndFlush(other);String borrowedCloth=held(npc,"布料"),knife=held(npc,"小刀");items.transferExact(sid,other,self,borrowedCloth,"LEND");items.transferExact(sid,other,self,knife,"LEND");actors.flush();
        assertThrows(IllegalArgumentException.class,()->apply(ordinaryCraft(borrowedCloth,knife,"你用小刀制成布绷带。","制成布绷带")));assertEquals("HELD",readItemState(player,borrowedCloth));
        apply(ordinaryCraft(held(player,"布条"),knife,"你用小刀制成布绷带。","制成布绷带"));items.transferExact(sid,self,other,knife,"RETURN");assertEquals(npc,ItemStateService.legalOwner(items.owned(sid,other).stream().filter(i->i.ref().equals(knife)).findFirst().orElseThrow()));
    }
    @Test void repositioningTheSameMaterialBeforeCraftingIsSequentialWork(){
        String cloth=held(player,"布条");var n=(com.fasterxml.jackson.databind.node.ObjectNode)ordinaryCraft(cloth,"","你把布条放在膝上，随后制成布绷带。","制成布绷带");((com.fasterxml.jackson.databind.node.ArrayNode)n.path("effects")).insert(0,json.valueToTree(Map.of("kind","ITEM_POSITION","itemRef",cloth,"before","","after","膝上")));apply(n);assertEquals(List.of("布绷带"),actors.findById(player).orElseThrow().getInventory());
    }
    @Test void separateSimultaneousWorkStillCannotClaimTheSameMaterial(){
        String cloth=held(player,"布条");var claims=new HashSet<String>();claims.add("item:"+cloth);assertThrows(IllegalArgumentException.class,()->effects.apply(sid,scenes.findById(source).orElseThrow(),player,player,ordinaryCraft(cloth,"","你制成布绷带。","制成布绷带"),claims));assertEquals("HELD",readItemState(player,cloth));
    }
    @Test void observersSeeCraftedObjectWithoutPrivateMaterialReferences(){
        String cloth=held(player,"布条");var candidate=ordinaryCraft(cloth,"","你制成布绷带。","制成布绷带");var event=json.createObjectNode().put("actorId",player).put("text",candidate.path("process").asText());event.set("effects",candidate.path("effects").deepCopy());assertTrue(ExecutionObservation.hasVisualEvidence(candidate));var observed=ExecutionObservation.forObserver(event,candidate,npc,"旅人");assertEquals("布绷带",observed.path("effects").get(0).path("name").asText());assertFalse(observed.toString().contains(cloth));assertFalse(observed.toString().contains("basis"));
    }

    @Test void departureCannotDeliverAnItemBackToSomeoneInTheSourceScene(){
        var candidate=packet(move(),gift());
        assertThrows(IllegalArgumentException.class,()->apply(candidate));
        assertEquals(source,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
        assertEquals(List.of("布条"),actors.findById(player).orElseThrow().getInventory());
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void carriedItemsCannotKeepASourceScenePlacementAfterTravel(boolean playerMoves)throws Exception{
        String mover=playerMoves?player:npc;var holder=actors.findById(mover).orElseThrow();
        if(!playerMoves){holder.setInventory(new ArrayList<>(List.of("布条")));actors.saveAndFlush(holder);}
        String ref=held(mover,"布条");items.owned(sid,holder);var originalOwner=ItemStateService.legalOwner(items.owned(sid,holder).get(0));
        var placements=new ItemPlacementService(jdbc,items);placements.place(sid,holder,source,ref,"","门内石柱旁");
        effects.apply(sid,scenes.findById(source).orElseThrow(),mover,player,packet(Map.of("kind","MOVE","actorId",mover,"fromSceneId",source,"toSceneId",destination)),new HashSet<>());
        assertEquals("",placements.position(sid,mover,ref),"a source-scene placement is not a confirmed destination placement");
        assertEquals(false,placements.own(sid,holder).get(0).get("positionRecorded"));
        assertEquals(originalOwner,ItemStateService.legalOwner(items.owned(sid,holder).get(0)));assertEquals(ref,held(mover,"布条"));
        assertEquals(destination,jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,mover,"placement:"+ref));
        placements.place(sid,holder,destination,ref,"","门外掌心");assertEquals("门外掌心",placements.position(sid,mover,ref));
        assertFalse(placements.own(sid,holder).toString().contains("门内石柱旁"));
    }
    @Test void departureResetsOnlyTheMovingHoldersPlacements(){
        var other=actors.findById(npc).orElseThrow();other.setInventory(new ArrayList<>(List.of("小刀")));actors.saveAndFlush(other);
        var placements=new ItemPlacementService(jdbc,items);String cloth=held(player,"布条"),knife=held(npc,"小刀");
        placements.place(sid,actors.findById(player).orElseThrow(),source,cloth,"","石柱旁");placements.place(sid,other,source,knife,"","守门人手中");
        apply(packet(move()));assertEquals("",placements.position(sid,player,cloth));assertEquals("守门人手中",placements.position(sid,npc,knife));
        assertEquals(source,jdbc.queryForObject("SELECT scene_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,npc,"placement:"+knife));
    }
    @Test void borrowedItemRemainsReturnableAfterTravel(){
        var lender=actors.findById(npc).orElseThrow();var borrower=actors.findById(player).orElseThrow();lender.setInventory(new ArrayList<>(List.of("小刀")));actors.saveAndFlush(lender);
        String knife=held(npc,"小刀");items.transferExact(sid,lender,borrower,knife,"LEND");actors.flush();
        var placements=new ItemPlacementService(jdbc,items);placements.place(sid,borrower,source,knife,"","石柱旁右手");apply(packet(move()));
        assertEquals("",placements.position(sid,player,knife));assertEquals(npc,ItemStateService.legalOwner(items.owned(sid,borrower).stream().filter(i->knife.equals(i.ref())).findFirst().orElseThrow()));
        items.transferExact(sid,borrower,lender,knife,"RETURN");assertEquals(knife,held(npc,"小刀"));assertEquals(List.of("布条"),borrower.getInventory());
    }
    @Test void localBodyChangesDoNotEraseAnUnmovedItemsPlacement(){
        var placements=new ItemPlacementService(jdbc,items);String cloth=held(player,"布条");placements.place(sid,actors.findById(player).orElseThrow(),source,cloth,"","左手");
        apply(packet(body(player,"activity","","查看门边")));assertEquals("左手",placements.position(sid,player,cloth));
    }
    @Test void departureCannotOverwriteArrivalWithASourcePositionOrFacility(){
        for(Object tail:List.of(body(player,"position","抵达门外","石柱旁"),Map.of("kind","FACILITY","objectName","石柱","before","","after","已擦净"))){
            assertThrows(IllegalArgumentException.class,()->apply(packet(move(),tail)));
            assertEquals(source,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
        }
    }
    @Test void givingBeforeDepartureAndObservingOnArrivalRemainValid(){
        var candidate=packet(gift(),move(),Map.of("kind","OBSERVE","scope","VISIBLE_SCENE"));apply(candidate);
        assertEquals(destination,candidate.path("effects").get(2).path("sceneId").asText());
        assertEquals(destination,jdbc.queryForObject("SELECT current_scene_id FROM player_profile WHERE player_id=?",String.class,pid));
        assertEquals(List.of("布条"),actors.findById(npc).orElseThrow().getInventory());
        assertEquals("抵达门外",physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(destination).orElseThrow()).get("position"));
    }
    @Test void deadActorCannotReportSuccessfulSelfAwakening(){
        life(player,0);assertThrows(IllegalArgumentException.class,()->apply(packet(body(player,"awake",false,true))));
        assertFalse((Boolean)physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(source).orElseThrow()).get("awake"));
    }
    @Test void ordinaryRescueCannotReportResurrectingADeadTarget(){
        life(npc,0);assertThrows(IllegalArgumentException.class,()->apply(packet(body(npc,"awake",false,true))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='BODY_STATE'",Integer.class,sid));
    }
    @Test void unconsciousLivingActorCanWakeWithoutBeingHealed(){
        life(player,25);jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'body:v2','身体','{\"awake\":false}','BODY_STATE','',?,'',0)",sid,player,source);
        apply(packet(body(player,"awake",false,true)));
        assertTrue((Boolean)physical.view(sid,actors.findById(player).orElseThrow(),scenes.findById(source).orElseThrow()).get("awake"));
        assertEquals(25,new VitalStateService(jdbc).health(sid,player));
    }
    @Test void losingConsciousnessCannotBeFollowedByGivingAnItem(){
        assertThrows(IllegalArgumentException.class,()->apply(packet(body(player,"awake",true,false),gift())));
        assertEquals(List.of("布条"),actors.findById(player).orElseThrow().getInventory());
        assertTrue(actors.findById(npc).orElseThrow().getInventory().isEmpty());
    }
    @Test void executionFactsDistinguishDeathFromLivingUnconsciousness(){
        life(player,25);life(npc,0);
        var facts=json.valueToTree(effects.facts(sid,scenes.findById(source).orElseThrow(),player,player));
        assertEquals(25,facts.path("resources").path(player).path("body").path("health").asInt(-1));
        assertTrue(facts.path("resources").path(player).path("body").path("alive").asBoolean());
        assertFalse(facts.path("resources").path(npc).path("body").path("alive").asBoolean(true));
    }
}
