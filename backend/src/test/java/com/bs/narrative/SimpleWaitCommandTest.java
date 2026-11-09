package com.bs.narrative;
import com.bs.narrative.llm.SimpleWaitCommand;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class SimpleWaitCommandTest{
 @Test void shortControlsKeepTheExactRequestedEvent(){
  assertEquals("掌门指示",SimpleWaitCommand.parse("继续等待掌门指示").eventText());
  assertEquals("他的答复",SimpleWaitCommand.parse("静候他的答复。").eventText());
  assertEquals("NONE",SimpleWaitCommand.parse("继续等待").condition());
 }
 @Test void complexActionsNegationsAndPhysicalEventsAreNeverLocallyGuessed(){
  for(String text:new String[]{"等待坏人离开后救人","等待机会偷袭","我不要等待他的回复","等待他的回复后再攻击","等待仪式开始","等待他的回复，之后追问","等待他不要开口"})assertNull(SimpleWaitCommand.parse(text),text);
 }
}
