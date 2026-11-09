package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Short request-local identities, resolved against server-owned participants and inventories. */
public final class SceneActionBindings {
 private static final ObjectMapper JSON=new ObjectMapper();
 private final List<Map<String,Object>> actors;
 private final List<Map<String,String>> itemRefs=new ArrayList<>();
 public SceneActionBindings(List<Map<String,Object>> actors){
  this.actors=List.copyOf(actors);
  for(var actor:actors){var refs=new LinkedHashMap<String,String>();int index=0;
   for(var item:JSON.valueToTree(actor.get("ownedItems")))refs.put("r"+index++,item.path("ref").asText());
   itemRefs.add(refs);
  }
 }
 public List<Map<String,Object>> requestActors(){
  var result=new ArrayList<Map<String,Object>>();
  for(int index=0;index<actors.size();index++){
   var source=actors.get(index);var wire=new LinkedHashMap<>(source);wire.remove("id");wire.put("actorIndex",index);
   var items=new ArrayList<Map<String,String>>();int n=0;
   for(var item:JSON.valueToTree(source.get("ownedItems")))items.add(Map.of("ref","r"+n++,"name",item.path("name").asText()));
   wire.put("ownedItems",items);result.add(wire);
  }
  return result;
 }
 public void normalize(JsonNode result){
  if(result instanceof ObjectNode root && result.path("executionVersion").asInt()==2 && !root.has("sceneChanges"))root.putArray("sceneChanges");
  for(var entry:result.path("events"))if(entry instanceof ObjectNode event){
   int index=actorIndex(event);if(index<0)continue; // Unknown identities remain invalid; never guess by array order.
   String id=Objects.toString(actors.get(index).get("id"),"");
   if(event.has("actorIndex") && event.hasNonNull("actorId") && !event.path("actorId").asText().isBlank() && !id.equals(event.path("actorId").asText()))
    throw new IllegalArgumentException("actorIndex and actorId disagree at index="+index);
   event.put("actorId",id);resolveRefs(event,"uses",index);
   if(!event.has("uses"))event.putArray("uses");
   ObjectNode execution=event.path("execution") instanceof ObjectNode e?e:event.putObject("execution");
   // The chosen task is already supplied by the role, not a second freely generated label.
   String task=Objects.toString(actors.get(index).get("attempt"),"");
   execution.put("task",task.substring(0,Math.min(240,task.length())));
   ObjectNode view=event.path("view") instanceof ObjectNode v?v:event.putObject("view");
   if(!view.has("activity"))view.put("activity",event.path("text").asText());
   ArrayNode resources=event.path("nonItemUses") instanceof ArrayNode a?a:event.putArray("nonItemUses");
   // BODY labels are optional descriptive metadata. They cannot authorize portable tools;
   // actual text and inventory refs are still checked independently by ItemStateService.
   for(int i=resources.size()-1;i>=0;i--)if("BODY".equals(resources.get(i).path("kind").asText()))resources.remove(i);
  }
  for(var entry:result.path("crafts"))if(entry instanceof ObjectNode craft){int index=actorIndex(craft);if(index<0)continue;
   craft.put("actorId",Objects.toString(actors.get(index).get("id"),""));resolveRefs(craft,"materials",index);resolveRefs(craft,"tools",index);
  }
 }
 private int actorIndex(JsonNode event){
  if(event.has("actorIndex")){
   if(!event.path("actorIndex").isIntegralNumber())throw new IllegalArgumentException("actorIndex must be an integer");
   int n=event.path("actorIndex").asInt(-1);if(n<0 || n>=actors.size())throw new IllegalArgumentException("unknown actorIndex="+n);return n;
  }
  for(int i=0;i<actors.size();i++)if(event.path("actorId").asText().equals(actors.get(i).get("id")))return i;
  return -1;
 }
 private void resolveRefs(ObjectNode event,String field,int index){
  if(!(event.path(field) instanceof ArrayNode uses))return;
  for(int i=0;i<uses.size();i++){JsonNode use=uses.get(i);String ref=use.isTextual()?use.asText():use.path("ref").asText();
   String resolved=itemRefs.get(index).get(ref);if(resolved!=null)uses.set(i,TextNode.valueOf(resolved));
  }
 }
}
