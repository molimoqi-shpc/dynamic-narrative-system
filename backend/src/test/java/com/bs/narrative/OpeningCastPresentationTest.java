package com.bs.narrative;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.service.OpeningCastPresentation;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OpeningCastPresentationTest {
 @Test void introductionsAreGroundedInOpeningRatherThanPrivateOrLaterInformation(){
  var a=new GodProfile();a.setGodId("npc_123");a.setGodName("苏婉");
  var result=OpeningCastPresentation.describe("苏婉站在东侧，正拉住同行弟子的衣袖。【当前局势】苏婉想暗中夺走秘宝。","问道坪",List.of(a));
  assertEquals("苏婉",result.get(0).get("name"));assertTrue(result.get(0).get("introduction").contains("站在东侧"));assertFalse(result.get(0).get("introduction").contains("秘宝"));
  var unknown=OpeningCastPresentation.describe("云雾间隐约有人。","山门",List.of(a));assertEquals("此刻，你与苏婉同在山门。",unknown.get(0).get("introduction"));
 }
}
