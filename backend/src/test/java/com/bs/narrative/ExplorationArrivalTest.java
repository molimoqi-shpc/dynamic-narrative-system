package com.bs.narrative;

import com.bs.narrative.service.OperationWorldExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplorationArrivalTest {
    final ObjectMapper json=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"actor","source","destination","new-node","gated-route","no-permission"})
    void onlyTheExplicitExistingDestinationCanBeBound(String mismatch){
        ObjectNode effect=json.valueToTree(Map.of("kind","MOVE","actorId","p","fromSceneId","a","toSceneId","b"));
        ObjectNode permission=json.valueToTree(Map.of("canCreate",false,"direction","WEST","existingDestination",Map.of("sceneId","b","locationName","旧地点")));
        switch(mismatch){case "actor"->effect.put("actorId","npc");case "source"->effect.put("fromSceneId","other");case "destination"->effect.put("toSceneId","other");case "new-node"->permission.put("canCreate",true);case "gated-route"->permission.set("existingRoutes",json.valueToTree(List.of(Map.of("required_item","钥匙"))));default->permission.removeAll();}
        var candidate=json.valueToTree(Map.of("effects",List.of(effect)));var before=candidate.deepCopy();
        OperationWorldExpansionService.normalizeExistingArrival(candidate,permission,"p","a");assertEquals(before,candidate);
    }
}
