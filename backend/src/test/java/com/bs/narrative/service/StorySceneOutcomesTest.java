package com.bs.narrative.service;

import com.bs.narrative.llm.LLMProvider;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class StorySceneOutcomesTest {
    final ObjectMapper json=new ObjectMapper();
    final SceneStoryService service=new SceneStoryService(mock(LLMProvider.class));
    ObjectNode facts(){var f=json.createObjectNode().put("storyFirst",true).put("playerId","p");f.putObject("roleDirections");var w=f.putObject("currentWorld").put("sceneId","old");var r=w.putObject("resources");for(String who:List.of("p","a","b"))r.putObject(who);return f;}
    ObjectNode draft(){var d=json.createObjectNode().put("story","你和甲抵达店铺，乙留在原处。").put("elapsedSeconds",3600).put("stop","RESOLVED");d.putArray("roleExperiences");d.putArray("events");return d;}
    @Test void aLongAuthoredPassageAndContinuityArePreservedWithoutArbitraryLengthRejection(){
        var d=draft().put("story","实际正文。".repeat(4000)).put("continuity","此前已经发生的因果。".repeat(1000));
        var bound=service.bindAndValidate(d,facts());assertEquals(d.path("story"),bound.path("story"));assertEquals(d.path("continuity"),bound.path("continuity"));
    }
    @Test void finalWhereaboutsSaveActualSeparationWithoutPerActionClocks(){
        var d=draft();var os=d.putArray("sceneOutcomes");var arrived=os.addObject();arrived.putObject("scene").put("locationName","店铺").put("opening","井台边的店铺。");arrived.putArray("roleIds").add("p").add("a");os.addObject().put("sceneId","old").putArray("roleIds").add("b");
        d.withArray("events").addObject().put("kind","FACT").put("objectName","柜台").put("after","见到旧账本").put("atSeconds",3000).put("durationSeconds",3400);
        var bound=service.bindAndValidate(d,facts());var events=bound.path("events");assertEquals(5,events.size());assertTrue(events.get(0).path("effects").get(0).path("creationOnly").asBoolean());
        assertEquals(List.of("p","a"),List.of(events.get(1).path("actorId").asText(),events.get(2).path("actorId").asText()));
        for(var e:events){assertEquals(0,e.path("atSeconds").asLong());assertEquals(0,e.path("durationSeconds").asLong());}assertEquals(3600,bound.path("elapsedSeconds").asInt());
        assertEquals(3400,d.path("events").get(0).path("durationSeconds").asInt(),"Saved provider response must remain untouched");
    }
    @Test void unchangedPeopleNeedNoRepeatButOnePersonCannotHaveTwoActualDestinations(){
        var d=draft();d.putArray("sceneOutcomes").addObject().put("sceneId","old").putArray("roleIds").add("a");assertEquals(1,service.bindAndValidate(d,facts()).path("events").size());
        d.withArray("sceneOutcomes").addObject().put("sceneId","other").putArray("roleIds").add("a").add("p");assertThrows(IllegalArgumentException.class,()->service.bindAndValidate(d,facts()));
    }
    @Test void outcomePrefixPreservesDecisionSourceAndExplicitDepartureScene(){
        var d=draft().put("stop","DECISION");d.putArray("sceneOutcomes").addObject().put("sceneId","there").putArray("roleIds").add("p").add("a");
        d.withArray("events").addObject().put("kind","DROP").put("actorId","a").put("sceneId","old").put("itemRef","knife");
        d.withArray("events").addObject().put("kind","SPEECH").put("actorId","a").put("targetId","p").put("scope","PUBLIC").put("speech","你愿意结盟吗？");d.putObject("decision").put("type","ALLIANCE").put("question","是否结盟？").put("eventIndex",1);
        var bound=service.bindAndValidate(d,facts());assertEquals(3,bound.path("decision").path("eventIndex").asInt());assertEquals("old",bound.path("events").get(2).path("sceneId").asText());assertEquals(4,bound.path("events").size());
    }
    @Test void storyClockHasNoSixHourOrIndividualActionGateWithoutLocationUpdates(){
        var d=draft().put("elapsedSeconds",172800);d.withArray("events").addObject().put("kind","FACT").put("objectName","旅程").put("after","两日后抵达的消息已获证实").put("atSeconds",9999999).put("durationSeconds",9999999);
        var bound=service.bindAndValidate(d,facts());assertEquals(172800,bound.path("elapsedSeconds").asLong());assertEquals(0,bound.path("events").get(0).path("atSeconds").asLong());
    }
    @Test void environmentalDecisionNeedsNoManufacturedNpcSpeechOrEventIndex(){
        var d=draft().put("story","前方洪水正在漫过桥面，你需要决定是否冒险过桥。").put("stop","DECISION");d.putObject("decision").put("type","RISK").put("question","冒险过桥，还是另寻道路？");
        var bound=service.bindAndValidate(d,facts());assertTrue(bound.path("events").isEmpty());assertEquals("RISK",bound.path("decision").path("type").asText());
    }
    @Test void supportedLegacyResultNamesDoNotInvokeWordOrMaterialProofChecks(){
        var d=draft();var e=d.withArray("events").addObject().put("kind","ACTION").put("actorId","a");e.putArray("effects").addObject().put("kind","FACILITY").put("objectName","新线索").put("after","已查明的结果");e.withArray("effects").addObject().put("kind","CRAFT").put("name","信物");e.withArray("effects").addObject().put("kind","ITEM_POSITION").put("after","衣角旁");
        var bound=service.bindAndValidate(d,facts());assertEquals(2,bound.path("events").get(0).path("effects").size());assertEquals("FACT",bound.path("events").get(0).path("effects").get(0).path("kind").asText());assertEquals("GAIN",bound.path("events").get(0).path("effects").get(1).path("kind").asText());
    }
    @Test void anotherEstablishedActorIsNotRejectedByTheDepartureSceneRoster(){
        var d=draft();d.putArray("sceneOutcomes").addObject().put("sceneId","old").putArray("roleIds").add("established_off_scene_actor");assertDoesNotThrow(()->service.bindAndValidate(d,facts()));
    }
    @Test void aNestedExplicitExistingSceneReferenceDoesNotCreateADuplicateLocation(){
        var d=draft();var group=d.putArray("sceneOutcomes").addObject();group.putObject("scene").put("sceneId","old").put("locationName","旧北亭").put("opening","已建立的北亭。");group.putArray("roleIds").add("b");
        var bound=service.bindAndValidate(d,facts());assertEquals(1,bound.path("events").size());assertEquals("MOVE",bound.at("/events/0/effects/0/kind").asText());assertEquals("old",bound.at("/events/0/effects/0/toSceneId").asText());assertEquals("old",d.at("/sceneOutcomes/0/scene/sceneId").asText());
    }
    @Test void theExplicitOuterSceneReferenceTakesPriorityOverAnInconsistentNestedDescription(){
        var d=draft();var group=d.putArray("sceneOutcomes").addObject().put("sceneId","outer");group.putObject("scene").put("sceneId","inner");group.putArray("roleIds").add("a");assertEquals("outer",service.bindAndValidate(d,facts()).at("/events/0/effects/0/toSceneId").asText());
    }
}
