package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** A reading guide to existing sources, never a truth classifier or a plot/decision validator. */
final class StoryEvidenceContext {
 private static final ObjectMapper JSON=new ObjectMapper();
 private StoryEvidenceContext(){}
 static ObjectNode guide(ObjectNode facts){
  var out=JSON.createObjectNode();out.put("meaning","以下是给主创的阅读顺序与原问题锚点，不是裁决结果。原文仍看sourceRef；记录中的人物说法保留说话者与来源，私有构思不是证据，也不会广播给角色。");
  var sources=out.putArray("publishedSourceRefs");
  if(facts.has("openingPublishedChapter"))sources.add("/openingPublishedChapter/story");
  for(int i=0;i<facts.path("previousChapters").size();i++)sources.add("/previousChapters/"+i+"/story");
  for(int i=0;i<facts.path("alreadyHappenedThisChapter").size();i++)sources.add("/alreadyHappenedThisChapter/"+i);
  for(int i=0;i<facts.path("plotSourcePages").size();i++)if(facts.path("plotSourcePages").get(i).has("story"))sources.add("/plotSourcePages/"+i+"/story");
  for(int i=0;i<facts.path("plotResults").size();i++)if(facts.path("plotResults").get(i).has("publishedPassage"))sources.add("/plotResults/"+i+"/publishedPassage");
  var anchors=out.putArray("plotAnchors");var seen=new HashSet<String>();var preferred=new HashSet<String>();
  for(var ref:facts.at("/episodePlan/content/eventIds"))preferred.add(ref.asText());
  // Explicit author-selected IDs determine reading emphasis, not whether any event may happen.
  for(boolean priority:List.of(true,false)){
   for(int i=0;i<facts.at("/plotMemory/recentEvents").size();i++){
    var entry=facts.at("/plotMemory/recentEvents").get(i);if(preferred.contains(entry.at("/event/eventId").asText())==priority)add(anchors,seen,entry,"/plotMemory/recentEvents/"+i);
   }
   for(int i=0;i<facts.path("plotResults").size();i++)for(int j=0;j<facts.path("plotResults").get(i).path("records").size();j++){
    var entry=facts.path("plotResults").get(i).path("records").get(j);String id=entry.path("event").path("eventId").asText(entry.path("eventId").asText());if(preferred.contains(id)==priority)add(anchors,seen,entry,"/plotResults/"+i+"/records/"+j);
   }
  }
  out.put("evidenceUse","先在authorPlan.establishedCauses用sourceRef说明本段依赖的关键事实实际能证明什么、还不能证明什么，再写经过。没有出处的金额、日期、动机可作为本段新发现或人物说法呈现，不能补写成玩家此前已查过的事实。旧问题的对象、数量和性质按原文承接。");
  out.put("playerBoundary","本次playerDirection是唯一玩家新授权来源。主角由人类控制，NPC才按本人意愿自主决策。玩家保留的判断不在对白、内心或行为中提前代选，也不替玩家选择暂缓、以后再说、初步相信或另找旁证再定。需要表态的真实新提议及其后果已说明时，就返回DECISION与question、stakes，交给下一次玩家输入；无需再等角色API允许主角表态。decision.stakes只写正文中玩家已经能知道的新增义务、立场或风险。");
  out.put("closure","先处理原问题的答案或有依据的受阻，写出对人物和局势的影响，再判断是否出现新的重大选择。已解释线索可兑现，普通善后自然接续；尚待玩家决定的后果与已解决的问题分开承接，不用再造疑点维持开放状态。");
  out.put("continuation","现有角色意愿足以支持的问答、展示材料、说明条件和普通善后，在本次正文中自然写完。不要每展开一张纸、每问一个问题就CONTINUE刷新相同人物。CONTINUE仅在本段真实的新变故使接续所需意愿尚不可用时采用，可在authorPlan.continuationNeed说明是哪位角色、哪种新变故；这不替代已经到来的玩家重大选择。");
  return out;
 }
 private static void add(ArrayNode out,Set<String> seen,JsonNode entry,String path){
  if(out.size()>=12)return;
  if(entry.has("event")){
   var event=entry.path("event");String id=event.path("eventId").asText();
   if(!id.isBlank() && seen.add(id)){
    var anchor=out.addObject().put("kind","EVENT_RECORD").put("sourceRef",path+"/event").put("eventId",id);
    for(String field:List.of("title","openQuestion","development","outcome"))if(event.has(field))anchor.set(field,event.path(field).deepCopy());
    anchor.put("findingsRef",path+"/event/establishedFacts");
   }
   for(int i=0;i<entry.path("foreshadows").size() && out.size()<12;i++)thread(out,seen,entry.path("foreshadows").get(i),path+"/foreshadows/"+i);
  }else if(entry.has("threadId"))thread(out,seen,entry,path);
 }
 private static void thread(ArrayNode out,Set<String> seen,JsonNode thread,String path){
  String id=thread.path("threadId").asText();if(id.isBlank() || !seen.add(id) || out.size()>=12)return;
  var anchor=out.addObject().put("kind","FORESHADOW_RECORD").put("sourceRef",path).put("threadId",id);
  for(String field:List.of("eventId","status","setup","question","payoff"))if(thread.has(field))anchor.set(field,thread.path(field).deepCopy());
  if(thread.has("originalSource"))anchor.put("originalSourceRef",path+"/originalSource");
 }
}
