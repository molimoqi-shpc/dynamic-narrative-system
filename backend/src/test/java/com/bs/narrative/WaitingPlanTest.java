package com.bs.narrative;
import com.bs.narrative.service.WaitingPlan;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.llm.LLMResponse;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class WaitingPlanTest {
 @Test void negatedAttackCannotBecomeAnAuthorizedFollowUp(){
  GodProfile bad=new GodProfile(),victim=new GodProfile();
  String json="{\"waiting\":{\"condition\":\"LEAVE\",\"watchGodId\":\"bad\",\"followIntent\":\"ATTACK\",\"followTargetGodId\":\"victim\",\"followText\":\"攻击旅人\"}}";
  var plan=WaitingPlan.parse(new LLMResponse(json,json,0,0,0,0),"等待恶人离开后不要攻击旅人",Map.of("bad",bad,"victim",victim),"player");
  assertEquals("NONE",plan.followIntent());assertEquals(3,plan.phases());
 }
 @Test void unknownConditionAndForeignTargetsDoNotTriggerContinuation(){
  String json="{\"waiting\":{\"condition\":\"PLAYER_WINS\",\"watchGodId\":\"foreign\",\"followIntent\":\"RESCUE\",\"followTargetGodId\":\"foreign\",\"followText\":\"救人\"}}";
  var plan=WaitingPlan.parse(new LLMResponse(json,json,0,0,0,0),"等待后救人",Map.of(),"player");assertEquals("NONE",plan.condition());assertEquals("NONE",plan.followIntent());assertEquals(3,plan.phases());
 }

    @org.junit.jupiter.api.Test void generalNextReactionIsChangeEvenWhenModelCallsItAnEvent() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("waiting",java.util.Map.of("condition","EVENT","eventText","等他们作出下一步实际反应")));
        var response=new com.bs.narrative.llm.LLMResponse(json,json,0,0,0,0);
        var plan=com.bs.narrative.service.WaitingPlan.parse(response,"我继续观察，等他们作出下一步实际反应再决定",java.util.Map.of(),"player");
        org.junit.jupiter.api.Assertions.assertEquals("CHANGE",plan.condition());
        org.junit.jupiter.api.Assertions.assertEquals("",plan.eventText());
        org.junit.jupiter.api.Assertions.assertEquals("NONE",plan.followIntent());
    }
    @org.junit.jupiter.api.Test void concreteDoorOpeningStillUsesEventWait() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("waiting",java.util.Map.of("condition","EVENT","eventText","门打开")));
        var plan=com.bs.narrative.service.WaitingPlan.parse(new com.bs.narrative.llm.LLMResponse(json,json,0,0,0,0),"等到门打开再决定",java.util.Map.of(),"player");
        org.junit.jupiter.api.Assertions.assertEquals("EVENT",plan.condition());
        org.junit.jupiter.api.Assertions.assertEquals("门打开",plan.eventText());
    }
}
