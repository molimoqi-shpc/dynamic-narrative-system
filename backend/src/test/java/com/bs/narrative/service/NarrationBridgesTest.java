package com.bs.narrative.service;
import com.bs.narrative.dto.ActionResponse.NarrativeLine;
import com.bs.narrative.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NarrationBridgesTest {
    NpcAgentService.NpcDecision say(String id,String speech){return new NpcAgentService.NpcDecision(id,"RESPOND",speech,"私有动机不可公开",false,false);}
    Map<String,Object> event(int phase,String stage,String kind,String text){return Map.of("phase",phase,"npcStage",stage,"kind",kind,"text",text);}
    String render(Map<String,String> bridges,List<NpcAgentService.NpcDecision> decisions,List<Integer> phases,List<Map<String,Object>> events){return NarrativeSequence.composeBridges(bridges,decisions,List.of("a","b"),Map.of("a","木匠","b","看守"),phases,events);}
    @Test void missingConnectivesCannotLoseFactsOrDialogue(){
        String body=render(Map.of("QUOTE:0","在岸边，"),List.of(say("a","我先检查裂缝。")),List.of(1),List.of(event(1,"AFTER_NPC","SCENE_ACTION","木匠检查结束，确认门扇卡死。")));
        assertEquals("在岸边，木匠说：“我先检查裂缝。”木匠检查结束，确认门扇卡死。",body);
        assertFalse(body.contains("私有动机"));
    }
    @Test void responseKeyOrderCannotChangeTimeOrDropAnArrival(){
        String body=render(Map.of("EVENT:1","之后，","QUOTE:0","此时，"),List.of(say("b","我守在岸边。")),List.of(2),List.of(event(1,"BEFORE_NPC","ARRIVE","看守从山路来到闸边。"),event(2,"AFTER_NPC","SCENE_ACTION","看守实际检查绞盘，确认完好。")));
        assertTrue(body.indexOf("来到闸边")<body.indexOf("我守在岸边"));assertTrue(body.indexOf("我守在岸边")<body.indexOf("实际检查"));assertTrue(body.contains("\n\n"));
    }
    @Test void invalidConnectiveDoesNotDiscardOtherNaturalProse(){
        String body=render(Map.of("QUOTE:0","此时，","EVENT:0","[[EVENT:secret]]","END","这话点明他的请求。","QUOTE:foreign","陌生人突然现身。"),List.of(say("a","我先检查。")),List.of(1),List.of(event(1,"AFTER_NPC","SCENE_ACTION","木匠查明裂缝。")));
        assertTrue(body.startsWith("此时，"));assertTrue(body.contains("查明裂缝"));assertFalse(body.contains("[["));assertFalse(body.contains("这话点明"));assertFalse(body.contains("陌生人"));
    }
    @Test void duplicatedOrOversizedConnectivesDoNotFloodStory(){
        String body=render(Map.of("QUOTE:0","此时，","EVENT:0","此时，","END","长".repeat(161)),List.of(say("a","检查。")),List.of(1),List.of(event(1,"AFTER_NPC","SCENE_ACTION","检查完成。")));
        assertEquals(1,body.split("此时",-1).length-1);assertFalse(body.contains("长"));
    }
    @Test void playerActionsAndEmptyBridgeMapRemainChronological(){
        String body=render(Map.of(),List.of(say("a","先探一探。")),List.of(1),List.of(event(1,"AFTER_NPC","SCENE_ACTION","木匠发现裂缝。"),event(2,"BEFORE_NPC","ACTION","你随后递过木料。")));
        assertTrue(body.indexOf("先探一探")<body.indexOf("发现裂缝"));assertTrue(body.indexOf("发现裂缝")<body.indexOf("随后递过"));
    }
    @Test void newProtocolParsesWithOneCallAndMergesRealWords(){
        var model=mock(LLMProvider.class);String json="{\"visible\":{\"bridges\":{\"QUOTE:0\":\"此时，\",\"EVENT:999\":false}}}";
        when(model.chatForJson(anyList())).thenReturn(new LLMResponse(json,json,10,12,22,0.01));
        var service=new Pass2Service(model,new LLMConfig(),new StorySummaryValidator());
        var r=service.generateEnsembleNarrativeWithMemory("NO_OP","交谈","等待",List.of("a"),List.of("已经检查"),"【正文时间锚点】","");
        assertEquals(Pass2Service.Status.SUCCESS,r.status());assertEquals(Map.of("QUOTE:0","此时，"),r.narrativeBridges());
        var merged=EnsembleNarrative.merge(r,List.of(say("a","我先检查。")),List.of("a"),Map.of("a","木匠"),List.of(1),List.of(event(1,"AFTER_NPC","SCENE_ACTION","木匠检查结束。")));
        assertEquals("此时，木匠说：“我先检查。”木匠检查结束。",merged.narrativeLines().get(0).speech());
        assertSame(r.llmResponse(),merged.llmResponse());verify(model,times(1)).chatForJson(anyList());
    }
    @Test void emptyObjectIsValidAndWrongShapeRetainsLegacyProtocol(){
        var model=mock(LLMProvider.class);String json="{\"visible\":{\"bridges\":{}}}";
        when(model.chatForJson(anyList())).thenReturn(new LLMResponse(json,json,0,0,0,0));
        var service=new Pass2Service(model,new LLMConfig(),new StorySummaryValidator());
        assertEquals(Map.of(),service.generateEnsembleNarrativeWithMemory("NO_OP","","",List.of(),List.of(),"【正文时间锚点】","").narrativeBridges());
        json="{\"visible\":{\"bridges\":[],\"lines\":[{\"godId\":\"narrator\",\"speech\":\"已有实际过程。\"}]}}";
        when(model.chatForJson(anyList())).thenReturn(new LLMResponse(json,json,0,0,0,0));
        assertNull(service.generateEnsembleNarrativeWithMemory("NO_OP","","",List.of(),List.of(),"【正文时间锚点】","").narrativeBridges());
    }

    @Test void actionRetellingAndInventedStateAreDroppedButFactsRemain(){
        for(String bad:List.of("岑衡蹲到闸边，","水面又涨高一线，","他已经成功打开木闸。","他说完再次检查，")){
            String body=render(Map.of("EVENT:0",bad,"QUOTE:0","此时，"),List.of(say("a","先试一试。")),List.of(1),List.of(event(1,"AFTER_NPC","SCENE_ACTION","木匠用维修杆探过闸缝，确认受阻。")));
            assertFalse(body.contains(bad));assertTrue(body.startsWith("此时，"));assertTrue(body.contains("确认受阻"));
        }
    }

    @Test void observedRetellingOfTheSameActionIsRemovedWithoutLosingItsActualResult(){
        String fact="岑衡用维修杆铁头轻敲横垫的木料，使木料更深地楔入轴座外侧与岸石之间。";
        for(String bridge:List.of("岑衡随即用铁头轻敲木料，","木匠闻言略一停手，","他随即操持起来，","轴座外侧与岸石之间，")){
            String body=render(Map.of("EVENT:0",bridge),List.of(),List.of(),List.of(event(1,"AFTER_NPC","SCENE_ACTION",fact)));
            assertEquals(fact,body);
        }
    }
}
