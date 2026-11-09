package com.bs.narrative;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.GodProfileRepository;
import com.bs.narrative.service.*;
import com.fasterxml.jackson.databind.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic local fixtures only; the enclosing transaction rolls every test back. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class ItemOwnershipIT {
    @Autowired ItemStateService items;
    @Autowired GodProfileRepository gods;
    @Autowired KnowledgeService knowledge;
    @Autowired SessionStateService checkpoints;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    final String sid="item_ownership_it",a="item_role_a_it",b="item_role_b_it",pid="item_user_it",scene="item_scene_it";
    final ObjectMapper json=new ObjectMapper();
    GodProfile first,second;

    @BeforeEach void seed() {
        for(String id:List.of(a,b))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',75,?)",id,sid,id.equals(a)?"木匠":"行人",id.equals(a)?"[\"木料\",\"小刀\"]":"[\"铜针\"]");
        first=gods.findById(a).orElseThrow();second=gods.findById(b).orElseThrow();
    }
    Map<String,GodProfile> actors(){return Map.of(a,first,b,second);}
    String ref(GodProfile owner,String name){return items.owned(sid,owner).stream().filter(i->name.equals(i.name())).findFirst().orElseThrow().ref();}
    JsonNode craft(String name,String text,String basis,List<String> materials,List<String> tools,List<String> uses) {
        return json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text",text,"uses",uses)),"crafts",List.of(Map.of("actorId",a,"ref","crafted_0","name",name,"materials",materials,"tools",tools,"basis",basis))));
    }
    JsonNode normalCraft() {
        String wood=ref(first,"木料"),knife=ref(first,"小刀");
        String text="木匠用[[ITEM:"+knife+"]]削去[[ITEM:"+wood+"]]的边角，制成了[[ITEM:crafted_0]]。";
        return craft("木楔",text,"制成了[[ITEM:crafted_0]]",List.of(wood),List.of(knife),List.of(wood,knife,"crafted_0"));
    }
    @Test void duplicateNamesReceiveSeparateStableReferencesAndPanelReadsDoNotWrite() {
        first.setInventory(new ArrayList<>(List.of("木料","木料","小刀")));gods.saveAndFlush(first);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid));
        var legacy=items.describeOwned(sid,first);assertEquals(3,legacy.stream().map(i->i.get("ref")).distinct().count());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid));
        var refs=items.owned(sid,first);assertEquals(3,refs.stream().map(ItemStateService.Item::ref).distinct().count());
        assertEquals(refs,items.owned(sid,first));
        assertEquals("原已持有，历史来源未记录",refs.get(0).origin());
    }
    @Test void actualCraftConsumesExactlyTheMaterialAndPreservesTheTool() {
        var response=normalCraft();String material=ref(first,"木料"),knife=ref(first,"小刀");
        var plan=items.prepare(sid,actors(),response);items.apply(sid,plan,actors());gods.saveAndFlush(first);
        assertEquals(List.of("小刀","木楔"),first.getInventory());
        var held=items.owned(sid,first);assertTrue(held.stream().anyMatch(i->i.ref().equals(knife)));
        var output=held.stream().filter(i->i.name().equals("木楔")).findFirst().orElseThrow();
        assertEquals("由实际持有材料制作",output.origin());assertEquals(material,output.history().get(0).get("materials"));
        assertFalse(items.render(response,plan).toString().contains("[[ITEM:"));
        assertEquals("CONSUMED",readState(a,material));
    }
    @Test void anotherUnitCanBeCraftedWithoutReplacingAnExistingSameNamedItem(){
        first.setInventory(new ArrayList<>(List.of("木料","小刀","木楔")));gods.saveAndFlush(first);
        String existing=ref(first,"木楔"),wood=ref(first,"木料"),knife=ref(first,"小刀");
        var plan=items.prepare(sid,actors(),normalCraft());items.apply(sid,plan,actors());gods.saveAndFlush(first);
        var outputs=items.owned(sid,first).stream().filter(i->"木楔".equals(i.name())).toList();
        assertEquals(2,outputs.size());assertEquals(2,outputs.stream().map(ItemStateService.Item::ref).distinct().count());
        assertTrue(outputs.stream().anyMatch(i->existing.equals(i.ref())));assertEquals(knife,ref(first,"小刀"));assertEquals("CONSUMED",readState(a,wood));
    }
    @Test void twoSameNamedOutputsHaveDistinctMaterialsAndStableIdentities(){
        first.setInventory(new ArrayList<>(List.of("木料","木料","小刀")));gods.saveAndFlush(first);
        var woods=items.owned(sid,first).stream().filter(i->"木料".equals(i.name())).map(ItemStateService.Item::ref).toList();String knife=ref(first,"小刀");
        var packet=(com.fasterxml.jackson.databind.node.ObjectNode)normalCraft();
        ((com.fasterxml.jackson.databind.node.ArrayNode)packet.path("crafts")).add(json.valueToTree(Map.of("actorId",a,"ref","crafted_1","name","木楔","materials",List.of(woods.get(1)),"tools",List.of(knife),"basis","制成了[[ITEM:crafted_1]]")));
        var event=(com.fasterxml.jackson.databind.node.ObjectNode)packet.path("events").get(0);event.put("text","木匠先制成了[[ITEM:crafted_0]]，再制成了[[ITEM:crafted_1]]。");event.set("uses",json.valueToTree(List.of(woods.get(0),woods.get(1),knife,"crafted_0","crafted_1")));
        var plan=items.prepare(sid,actors(),packet);items.apply(sid,plan,actors());gods.saveAndFlush(first);
        var outputs=items.owned(sid,first).stream().filter(i->"木楔".equals(i.name())).toList();
        assertEquals(2,outputs.size());assertEquals(2,outputs.stream().map(ItemStateService.Item::ref).distinct().count());
        assertEquals(new HashSet<>(woods),outputs.stream().map(i->i.history().get(0).get("materials")).collect(java.util.stream.Collectors.toSet()));
        for(String wood:woods)assertEquals("CONSUMED",readState(a,wood));assertEquals(knife,ref(first,"小刀"));
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"GIFT","RETURN"})
    void panelDescribesTheActualDeliveryRatherThanTheOriginalInventory(String mode){
        String wood=ref(first,"木料");GodProfile holder;
        if(mode.equals("GIFT")){items.transferExact(sid,first,second,wood,"GIFT");holder=second;}
        else{items.transferExact(sid,first,second,wood,"LEND");gods.saveAllAndFlush(List.of(first,second));items.transferExact(sid,second,first,wood,"RETURN");holder=first;}
        gods.saveAllAndFlush(List.of(first,second));int before=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid);
        var detail=items.describeOwned(sid,holder).stream().filter(i->wood.equals(i.get("ref"))).findFirst().orElseThrow();
        assertTrue(detail.get("source").toString().contains(mode.equals("GIFT")?"赠予":"收回"));assertEquals(false,detail.get("isBorrowed"));
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid));
    }
    String readState(String owner,String ref) {
        try{return json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,owner,ref)).path("state").asText();}catch(Exception e){throw new RuntimeException(e);}
    }
    @Test void foreignOwnerCannotUseOrConsumeAnItem() {
        String foreign=ref(second,"铜针");
        var event=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","用[[ITEM:"+foreign+"]]探查。","uses",List.of(foreign)))));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),event));
        var response=normalCraft();((com.fasterxml.jackson.databind.node.ObjectNode)response.path("crafts").get(0)).set("materials",json.valueToTree(List.of(foreign)));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),response));
        assertEquals(List.of("木料","小刀"),first.getInventory());
    }
    @Test void copiedReferenceObjectsResolveOnlyThroughServerOwnership() {
        String knife=ref(first,"小刀"),foreign=ref(second,"铜针");
        var event=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","木匠用小刀检查。","uses",List.of(Map.of("ref",knife,"name","假名称","ownerId",b))))));
        var plan=items.prepare(sid,actors(),event);
        assertDoesNotThrow(()->items.checkPortableClaims("木匠用小刀检查。",new GameScene(),plan,a,event.path("events").get(0).path("uses")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)event.path("events").get(0)).set("uses",json.valueToTree(List.of(Map.of("ref",foreign,"ownerId",a))));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),event));
        ((com.fasterxml.jackson.databind.node.ObjectNode)event.path("events").get(0)).set("uses",json.valueToTree(List.of(knife,Map.of("ref",knife))));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),event));
    }
    @Test void countsOfRepeatedOperationsAreNotImaginaryTools(){
        var scene=new GameScene();scene.setSceneSummary("岸边有木闸。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        assertDoesNotThrow(()->items.checkPortableClaims("木匠每掏出两三把便俯身检查，确认堵塞已经清完。",scene,plan,a,json.createArrayNode(),json.createArrayNode()));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("木匠掏出两把小刀检查。",scene,plan,a,json.createArrayNode(),json.createArrayNode()));
    }
    @Test void unknownAndUndeclaredReferencesAreRejected() {
        for(String ref:List.of("item_missing",ref(first,"小刀"))) {
            var event=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","用[[ITEM:"+ref+"]]探查。","uses",List.of()))));
            assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),event));
        }
    }
    @Test void aPlanOrFailedAttemptCannotCreateAnItem() {
        String wood=ref(first,"木料"),knife=ref(first,"小刀");
        for(String prefix:List.of("准备","尝试","想要","未能","无法","如果有空便")) {
            String text=prefix+"制成了[[ITEM:crafted_0]]。";
            var response=craft("木楔",text,"制成了[[ITEM:crafted_0]]",List.of(wood),List.of(knife),List.of(wood,knife,"crafted_0"));
            assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),response),prefix);
        }
    }
    @Test void craftingCannotRenameOrdinaryMaterialIntoAMagicalRuleItem() {
        var response=normalCraft();((com.fasterxml.jackson.databind.node.ObjectNode)response.path("crafts").get(0)).put("name","护身符");
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),response));
    }
    @Test void missingMaterialOrToolDeclarationRejectsTheCraft() {
        var response=normalCraft();((com.fasterxml.jackson.databind.node.ObjectNode)response.path("events").get(0)).set("uses",json.valueToTree(List.of("crafted_0")));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),response));
    }
    @Test void aToolCannotAlsoBeConsumedAcrossTwoCrafts() {
        var response=normalCraft();String wood=ref(first,"木料"),knife=ref(first,"小刀");
        ((com.fasterxml.jackson.databind.node.ArrayNode)response.path("crafts")).add(json.valueToTree(Map.of("actorId",a,"ref","crafted_1","name","刀片","materials",List.of(knife),"tools",List.of(),"basis","制成了[[ITEM:crafted_1]]")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("events").get(0)).put("text","制成了[[ITEM:crafted_0]]，制成了[[ITEM:crafted_1]]。").set("uses",json.valueToTree(List.of(wood,knife,"crafted_0","crafted_1")));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),response));
    }
    @Test void theSameMaterialCannotProduceTwoItems() {
        var response=normalCraft();var secondCraft=response.path("crafts").get(0).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)secondCraft).put("ref","crafted_1").put("name","木片");
        ((com.fasterxml.jackson.databind.node.ArrayNode)response.path("crafts")).add(secondCraft);
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),response));
    }
    @Test void anExchangePreservesBothItemIdentitiesAndChangesBothOwners() {
        String wood=ref(first,"木料"),needle=ref(second,"铜针");
        items.exchange(sid,first,second,"木料","铜针");
        first.setInventory(new ArrayList<>(List.of("小刀","铜针")));second.setInventory(new ArrayList<>(List.of("木料")));gods.saveAllAndFlush(List.of(first,second));
        assertEquals(wood,ref(second,"木料"));assertEquals(needle,ref(first,"铜针"));
        assertEquals("TRANSFERRED",readState(a,wood));assertEquals("HELD",readState(b,wood));
        assertEquals(a,items.owned(sid,second).get(0).history().get(1).get("from"));
        var event=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","用[[ITEM:"+wood+"]]探查。","uses",List.of(wood)))));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),event));
    }
    @Test void failedExchangeDoesNotMoveTheOfferedItem() {
        String wood=ref(first,"木料");
        assertThrows(IllegalArgumentException.class,()->items.exchange(sid,first,second,"木料","不存在的物品"));
        assertEquals("HELD",readState(a,wood));assertTrue(items.owned(sid,second).stream().noneMatch(i->i.ref().equals(wood)));
    }
    @Test void inventoryConsumptionRetiresItsReferenceInsteadOfRestoringIt() {
        String wood=ref(first,"木料");first.setInventory(new ArrayList<>(List.of("小刀")));gods.saveAndFlush(first);
        assertEquals(1,items.owned(sid,first).size());assertEquals("REMOVED_BY_ENGINE",readState(a,wood));
        var event=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","用[[ITEM:"+wood+"]]探查。","uses",List.of(wood)))));
        assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),event));
    }
    @Test void provenanceIsNotLeakedThroughTheNotebookOrThePrivateRoleContext() {
        items.owned(sid,first);assertTrue(knowledge.list(sid,a).isEmpty());assertEquals("",knowledge.privateContext(sid,a));
        String context=items.ownContext(sid,first);assertTrue(context.contains("小刀"));assertFalse(context.contains("EXISTING_INVENTORY"));assertFalse(context.contains("history="));
    }
    @Test void fakeToolsAreRejectedButBlockedAttemptsAndNaturalBodyActionsRemainValid() {
        var scene=new GameScene();scene.setSceneSummary("木闸下方水位上涨，闸轴固定在岸边。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("木匠取来铁箍压住闸轴。",scene,plan,a));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("木匠用小刀检查裂口。",scene,plan,a));
        for(String text:List.of("木匠没有工具，无法用铁箍固定。","木匠用手推动闸轴，保持平衡。","木匠持续检查，用来确认裂口是否扩大。"))assertDoesNotThrow(()->items.checkPortableClaims(text,scene,plan,a),text);
    }
    @Test void rewindRestoresInventoryAndItsExactProvenanceTogether() {
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'水岸','[]','测试场景')",scene,sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,'item_ownership_user','unused',?,?,'[\"木料\",\"小刀\"]')",pid,a,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,status) VALUES(?,?,?,'PLAYING')",sid,pid,a);
        var before=items.owned(sid,first);checkpoints.initialize(sid,pid);checkpoints.begin(sid,pid);
        var plan=items.prepare(sid,actors(),normalCraft());items.apply(sid,plan,actors());gods.saveAndFlush(first);checkpoints.complete(sid,"实际制作木楔");
        checkpoints.restore(sid,0);em.clear();var restored=gods.findById(a).orElseThrow();
        assertEquals(List.of("木料","小刀"),restored.getInventory());assertEquals(before,items.owned(sid,restored));
        assertFalse(items.describeOwned(sid,restored).toString().contains("木楔"));
    }

    @Test void aDeclaredToolMayUseItsFullNameAndPartsWithoutInventingAnotherItem() {
        first.setInventory(new ArrayList<>(List.of("维修杆")));gods.saveAndFlush(first);
        String rod=ref(first,"维修杆");var scene=new GameScene();scene.setSceneSummary("岸边有木闸。");
        var response=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","木匠用维修杆探查裂缝，再用杆尖拨开泥块。","uses",List.of(rod)))));
        var plan=items.prepare(sid,actors(),response);
        assertDoesNotThrow(()->items.checkPortableClaims(response.path("events").get(0).path("text").asText(),scene,plan,a,response.path("events").get(0).path("uses")));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("木匠用刀尖探查裂缝。",scene,plan,a,json.valueToTree(List.of(rod))));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("木匠取来铁箍固定裂口。",scene,plan,a,json.valueToTree(List.of(rod))));
        assertEquals(List.of("维修杆"),first.getInventory());
    }

    @Test void witnessingVerifiedToolUseDoesNotTransferOwnershipOrExcuseLaterPersonalUse() {
        String knife=ref(first,"小刀");var scene=new GameScene();scene.setSceneSummary("岸边有木闸。");
        var response=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","木匠用小刀检查接缝。","uses",List.of(knife)),Map.of("actorId",b,"text","行人见木匠已用小刀检查接缝，便俯身观察。","uses",List.of()))));
        var plan=items.prepare(sid,actors(),response);var observed=items.observedUses(actors(),response,plan,b);
        assertFalse(observed.isEmpty());
        assertDoesNotThrow(()->items.checkPortableClaims("行人见木匠已用小刀检查接缝，便俯身观察。",scene,plan,b,json.createArrayNode(),json.createArrayNode(),observed));
        for(String text:List.of("行人见木匠已用小刀检查接缝，自己用小刀削木料。","行人用小刀检查接缝。","行人见陌生人已用小刀检查接缝。"))
            assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims(text,scene,plan,b,json.createArrayNode(),json.createArrayNode(),observed),text);
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("行人见木匠已用小刀检查接缝。",scene,plan,b,json.createArrayNode(),json.createArrayNode(),List.of()));
        var proposal=json.valueToTree(Map.of("events",List.of(Map.of("actorId",a,"text","木匠站在岸边。","uses",List.of()))));
        assertTrue(items.observedUses(actors(),proposal,plan,b).isEmpty());
        assertEquals(List.of("铜针"),second.getInventory());assertEquals(List.of("木料","小刀"),first.getInventory());
    }
    @Test void craftingVerbsCannotUseThePlayersUnreceivedMaterialsAsNpcSupplies() {
        var scene=new GameScene();scene.setSceneSummary("岸边有木闸，玩家身上有木料和小刀，木匠并未收到这些物品。");
        var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        String needle=ref(second,"铜针");
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("行人削了一段随身木料，准备垫塞。",scene,plan,b,json.valueToTree(List.of(needle))));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("行人用随身小刀削成楔形。",scene,plan,b,json.valueToTree(List.of(needle))));
        assertDoesNotThrow(()->items.checkPortableClaims("行人没有收到木料，无法用小刀加工。",scene,plan,b,json.valueToTree(List.of(needle))));
    }
    @Test void aGenuineMaterialWithAnItemMarkerCanBeCutWithoutAFalsePortableClaim() {
        var response=normalCraft();var plan=items.prepare(sid,actors(),response);var scene=new GameScene();scene.setSceneSummary("岸边有木闸。");
        var event=response.path("events").get(0);
        assertDoesNotThrow(()->items.checkPortableClaims(event.path("text").asText(),scene,plan,a,event.path("uses")));
    }
    @Test void aFuturePurposeDoesNotExcuseAnAlreadyUsedUnownedMaterial() {
        var scene=new GameScene();scene.setSceneSummary("玩家持有木料，小刀在玩家手里，行人没有收到。");
        var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("行人削了一段随身木料准备垫塞。",scene,plan,b,json.createArrayNode()));
        assertDoesNotThrow(()->items.checkPortableClaims("行人希望用木料修理，但未收到任何材料。",scene,plan,b,json.createArrayNode()));
    }
    @Test void verifiedItemMarkersDoNotDuplicateAdjacentLiteralNames(){
        var response=normalCraft();var plan=items.prepare(sid,actors(),response);
        var event=(com.fasterxml.jackson.databind.node.ObjectNode)response.path("events").get(0);
        event.put("text","用小刀[[ITEM:"+ref(first,"小刀")+"]]加工[[ITEM:"+ref(first,"木料")+"]]木料，制成[[ITEM:crafted_0]]木楔。");
        String text=items.render(response,plan).path("events").get(0).path("text").asText();
        assertEquals("用小刀加工木料，制成木楔。",text);assertEquals("木楔",items.render(response,plan).path("crafts").get(0).path("name").asText());
    }

    @Test void uniqueDeclaredOwnedToolMayUseBareTypeButMissingOrForeignReferenceCannot(){
        var scene=new GameScene();scene.setSceneSummary("木闸固定在岸边。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        var knife=json.valueToTree(List.of(ref(first,"小刀")));
        assertDoesNotThrow(()->items.checkPortableClaims("用刀切开接缝。",scene,plan,a,knife));
        assertDoesNotThrow(()->items.checkPortableClaims("用刀背压住接缝。",scene,plan,a,knife));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("用刀切开接缝。",scene,plan,a,json.createArrayNode()));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("用针探查接缝。",scene,plan,a,json.valueToTree(List.of(ref(second,"铜针")))));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("用刀片切开接缝。",scene,plan,a,knife));
    }
    @Test void twoDeclaredToolsOfSameTypeRequireExplicitIdentification(){
        first.setInventory(new ArrayList<>(List.of("木料","小刀","长刀")));gods.saveAndFlush(first);
        var scene=new GameScene();scene.setSceneSummary("木闸固定在岸边。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        var both=json.valueToTree(List.of(ref(first,"小刀"),ref(first,"长刀")));
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("用刀切开接缝。",scene,plan,a,both));
        assertDoesNotThrow(()->items.checkPortableClaims("用小刀切开接缝。",scene,plan,a,both));
    }

    @Test void anatomicalActionsAreNotInventoryButSimilarlyNamedToolsStillNeedEvidence(){
        var scene=new GameScene();scene.setSceneSummary("木闸下方水位上涨，闸轴固定在岸边。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        for(String text:List.of("木匠用指节敲击闸轴。","木匠用指甲刮去淤泥。","看守用掌心压住闸门。","木匠用指节轻敲裂口。"))assertDoesNotThrow(()->items.checkPortableClaims(text,scene,plan,a),text);
        for(String text:List.of("木匠用指甲刀切去障碍。","木匠用指针探查裂缝。","木匠用指节铁箍固定闸轴。"))assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims(text,scene,plan,a),text);
    }

    @Test void postVerbNegationDoesNotClaimAUsedToolOrFinishedProcessing(){
        var scene=new GameScene();scene.setSceneSummary("木闸轴座有裂缝。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        for(String text:List.of("木匠削不了木料，缺少工具。","木匠用不了铁锹，现场没有铁锹。","看守切不开淤泥里的硬块，暂时受阻。","看守劈不动木料，停下来。"))assertDoesNotThrow(()->items.checkPortableClaims(text,scene,plan,a),text);
        assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims("木匠用铁锹挖走淤泥。",scene,plan,a));
    }

    @Test void unregisteredPickedUpToolsCannotAppearOnlyInNarration(){
        var scene=new GameScene();scene.setSceneSummary("木闸下方水位上涨。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        for(String text:List.of("看守拾起一根枯枝探查裂缝。","木匠捡起铁箍固定闸轴。"))assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims(text,scene,plan,a),text);
    }

    @Test void craftingRepairFeedbackIdentifiesMissingOutputMarkerWithoutInventingTheResult(){
        var n=(com.fasterxml.jackson.databind.node.ObjectNode)normalCraft();((com.fasterxml.jackson.databind.node.ObjectNode)n.path("crafts").get(0)).put("basis","制成了木楔");
        var error=assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),n));assertTrue(error.getMessage().contains("output marker [[ITEM:crafted_0]]"));assertEquals(List.of("木料","小刀"),first.getInventory());
    }
    @Test void craftingRepairFeedbackDistinguishesMissingDeclarationsAndNonliteralEvidence(){
        var missing=(com.fasterxml.jackson.databind.node.ObjectNode)normalCraft();((com.fasterxml.jackson.databind.node.ObjectNode)missing.path("events").get(0)).set("uses",json.createArrayNode());
        assertTrue(assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),missing)).getMessage().contains("event.uses"));
        var mismatch=(com.fasterxml.jackson.databind.node.ObjectNode)normalCraft();((com.fasterxml.jackson.databind.node.ObjectNode)mismatch.path("crafts").get(0)).put("basis","制作完成了[[ITEM:crafted_0]]");
        assertTrue(assertThrows(IllegalArgumentException.class,()->items.prepare(sid,actors(),mismatch)).getMessage().contains("exact continuous excerpt"));
    }

    @Test void shoulderAndJointAliasesAreBodilyActionsWhileArmourStillRequiresInventory(){
        var scene=new GameScene();scene.setSceneSummary("木闸立在岸边。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        for(String text:List.of("看守用肩抵住门扇试推。","木匠用肘轻敲木料。","看守用膝抵住门扇。"))assertDoesNotThrow(()->items.checkPortableClaims(text,scene,plan,a),text);
        for(String text:List.of("看守用肩甲抵住门扇。","木匠用肘甲砸开木料。"))assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims(text,scene,plan,a),text);
    }

    @Test void teethAreAnatomyButToothToolsStillNeedProvenance(){
        var scene=new GameScene();scene.setSceneSummary("石柱上有麻绳。");var plan=items.prepare(sid,actors(),json.valueToTree(Map.of("events",List.of())));
        for(String text:List.of("孩童用牙啃咬麻绳。","孩童用牙咬麻绳。","孩童用牙齿咬住绳结。","孩童用嘴咬住麻绳。"))assertDoesNotThrow(()->items.checkPortableClaims(text,scene,plan,a),text);
        for(String text:List.of("孩童用牙签挑开绳结。","孩童用牙刀切断绳索。","孩童用牙套磨绳索。"))assertThrows(IllegalArgumentException.class,()->items.checkPortableClaims(text,scene,plan,a),text);
    }
}
