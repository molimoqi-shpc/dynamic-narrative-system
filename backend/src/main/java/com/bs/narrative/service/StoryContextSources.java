package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Lossless author-only sharing; a reference does not change any role's knowledge. */
final class StoryContextSources {
 private StoryContextSources() {}
 static void compact(ObjectNode input){
  if(input.has("sharedContextMeaning"))return;
  var sources=input.putObject("sharedContextSources");
  var seen=new HashMap<JsonNode,String>();
  for(String group:List.of("previousChapters","plotSourcePages")){
   int i=0;for(var chapter:input.path(group)){var story=chapter.path("story");if(story.isTextual() && !story.asText().isBlank()){
    String ref="/"+group+"/"+i+"/story";seen.putIfAbsent(story,ref);
    if(story.asText().startsWith("narrator："))seen.putIfAbsent(TextNode.valueOf(story.asText().substring("narrator：".length())),ref);
   }i++;}
  }
  for(var dossier:input.path("characterDossiers"))if(dossier instanceof ObjectNode object){
   if(object.path("personallyKnown") instanceof ObjectNode known)
    for(String field:List.of("currentKnownState","lastObservedPeople")){
     if(known.path(field) instanceof ArrayNode values){
      for(int i=0;i<values.size();i++)values.set(i,share(values.get(i),sources,seen));
     }else shareField(known,field,sources,seen);
    }
   if(object.path("pastInformation") instanceof ObjectNode past){
    shareField(past,"initialSceneHistory",sources,seen);
    for(var record:past.path("ownRecords"))if(record.path("content") instanceof ObjectNode content)shareField(content,"text",sources,seen);
   }
  }
  if(sources.isEmpty())input.remove("sharedContextSources");
  else input.put("sharedContextMeaning","$ref是本次输入内的JSON Pointer，直接读取原值，无需另调API。共享只消除完全相同的原文副本，不合并不同人物的知情范围；引用仍属于原角色的所知或有来源亲历，不表示别人也知道。当前详细性格、心理、身体、意愿完整保留。narrator标注的章节正文与未带标注的相同原文是同一来源。");
 }
 private static void shareField(ObjectNode parent,String field,ObjectNode sources,Map<JsonNode,String> seen){
  var value=parent.get(field);if(value==null)return;parent.set(field,share(value,sources,seen));
 }
 private static JsonNode share(JsonNode value,ObjectNode sources,Map<JsonNode,String> seen){
  if(value.toString().length()<180 || value.has("$ref"))return value;
  String ref=seen.get(value);
  if(ref==null){String key="source"+sources.size();sources.set(key,value.deepCopy());ref="/sharedContextSources/"+key;seen.put(value.deepCopy(),ref);}
  return sources.objectNode().put("$ref",ref);
 }
}
