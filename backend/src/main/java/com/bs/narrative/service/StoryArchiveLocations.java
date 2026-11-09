package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Stores the main API's explicit final-location table; never reads prose or judges travel. */
final class StoryArchiveLocations {
 private StoryArchiveLocations(){}
 static ArrayNode project(ObjectNode archive,ObjectNode facts){
  var result=archive.arrayNode();var covered=new HashSet<String>();
  // An explicit empty canonical reference means whereabouts were not established.
  // Do not fall back to an obsolete scene ID or force this person beside the player.
  for(var row:archive.path("endingCast"))if(row.has("placeKey") && row.path("placeKey").asText().isBlank() && !row.path("roleId").asText().isBlank())covered.add(row.path("roleId").asText());
  var groups=new LinkedHashMap<String,ObjectNode>();
  // The author names each final place once. Server IDs never compete with a
  // different authored place name inside the same row.
  for(var row:archive.path("endingCast")){
   String key=row.path("placeKey").asText();var place=archive.path("endingPlaces").path(key);
   if(!key.isBlank() && place.isObject() && !place.path("locationName").asText().isBlank())
    append(archive,facts,row,place,true,result,covered,groups);
  }
  // Retain legacy drafts and already paid replies. A named-place row is the
  // newer authoritative representation for that person; other old rows survive.
  var namedCovered=Set.copyOf(covered);
  for(var raw:archive.path("sceneOutcomes")){
   if(!(raw instanceof ObjectNode object) || !raw.path("roleIds").isArray()){result.add(raw.deepCopy());continue;}
   var kept=object.deepCopy();var roles=kept.putArray("roleIds");
   for(var role:raw.path("roleIds"))if(!namedCovered.contains(role.asText())){roles.add(role.deepCopy());covered.add(role.asText());}
   if(!roles.isEmpty() || raw.path("roleIds").isEmpty())result.add(kept);
  }
  for(var row:archive.path("endingCast"))append(archive,facts,row,row.path("scene"),false,result,covered,groups);
  return result;
 }
 private static void append(ObjectNode archive,ObjectNode facts,JsonNode row,JsonNode place,boolean named,
     ArrayNode result,Set<String> covered,Map<String,ObjectNode> groups){
   String role=row.path("roleId").asText();if(role.isBlank() || covered.contains(role))return;
   String id=named?"":row.path("sceneId").asText(place.path("sceneId").asText());
   String label=place.path("locationName").asText(named?"":row.path("location").asText());
   if(id.isBlank() && label.isBlank())return;
   // Exact, unique dictionary labels are references, not inferred destinations or fuzzy matches.
   if(id.isBlank()){
    var matches=new LinkedHashSet<String>();
    for(var known:facts.path("locationIndex"))if(label.equals(known.path("name").asText()) && !known.path("sceneId").asText().isBlank())matches.add(known.path("sceneId").asText());
    if(matches.size()==1)id=matches.iterator().next();
   }
   String key=id.isBlank()?"label:"+label:"id:"+id;
   var group=groups.get(key);
   if(group==null){
    group=archive.objectNode();group.putArray("roleIds");
    if(!id.isBlank())group.put("sceneId",id);
    else{
     ObjectNode description=place instanceof ObjectNode object?object.deepCopy():archive.objectNode();
     if(named)description.remove("sceneId");
     description.put("locationName",label);
     if(description.path("opening").asText().isBlank())description.put("opening",row.path("basis").asText().isBlank()?label:row.path("basis").asText());
     group.set("scene",description);
    }
    groups.put(key,group);result.add(group);
   }
   group.withArray("roleIds").add(role);covered.add(role);
 }
}
