package com.bs.narrative;

import com.bs.narrative.llm.*;
import com.bs.narrative.service.CharacterCreationService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Legacy gameplay fixtures still enter through the real, now-required world generation step. */
final class CreationWorldFixture {
 private CreationWorldFixture(){}
 /** Ordinary lifecycle stubs supply the current three-candidate API contract.
  * Malformed/incomplete candidate tests pass raw replies directly instead. */
 static String candidatesJson(String raw){
  try{
   var json=new com.fasterxml.jackson.databind.ObjectMapper();var root=json.readTree(raw);
   if(!root.path("talents").isArray())return raw;
   var entries=(com.fasterxml.jackson.databind.node.ArrayNode)root.path("talents");
   for(int i=entries.size();i<3;i++)entries.addObject().put("talent_name","测试候选"+(i+1)).put("stat_key",i==1?"wen":"zhi").put("power_level",1);
   return root.toString();
  }catch(Exception invalid){return raw;}
 }
 static void ready(CharacterCreationService creation,LLMProvider model,String sid,String pid){
  String world="""
   {"title":"江湖行记","genre":"东方冒险","culturalStyle":"EASTERN","era":"手工业与行船的时代","introduction":"河畔村镇与集市通过舟路相连，旅人凭手艺与人情生活，世界并不默认人人具有神明身份。","summary":"东方河畔村镇，手工业与行船并存，人物按照已有身份生活。","boundaries":["新发现承接已建立事实"],"questions":["你的出身如何？","你最在乎什么？","遇到困境如何行动？"]}
   """;
  when(model.chatMainForJson(anyList())).thenReturn(new LLMResponse(world,world,0,0,0,0));
  creation.generateWorld(sid,pid,"EASTERN");
 }
}
