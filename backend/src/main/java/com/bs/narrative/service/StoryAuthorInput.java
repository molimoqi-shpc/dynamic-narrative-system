package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Read-only author projection. Stored dossiers and each role's private input remain complete. */
final class StoryAuthorInput {
 private StoryAuthorInput() {}
 static String pointer(String role){return "/characterDossiers/"+role.replace("~","~0").replace("/","~1");}
 static ObjectNode prepare(ObjectNode facts){
  var input=facts.deepCopy();StoryCastService.refreshPlanning(input);
  input.remove(List.of("serverEffectBaselines","sceneDraft","uncommittedStoryProposal"));
  StoryContinuationContext.separateOpening(input);
  if(!input.path("characterDossiers").isObject())return input;
  input.remove(List.of("privateRoleContexts","playerContext"));
  var archived=new HashSet<String>();for(var entry:input.path("worldBookIndex"))if(entry.path("entries").asLong()>0)archived.add(entry.path("roleId").asText());
  for(var entry:input.path("characterDossiers").properties())if(entry.getValue() instanceof ObjectNode dossier){
   var past=dossier.path("pastInformation");
   if(past instanceof ObjectNode history && history.path("ownRecords").isArray()){
    var actual=input.arrayNode();var subjective=input.arrayNode();int subjectiveCount=0;
    for(var record:history.path("ownRecords")){
     // Source types classify records; no prose words, event times or locations decide the plot.
     if(Set.of("ROLE_PROFILE","ROLE_DECISION","SCENE_ATTEMPT","ROLE_TASK").contains(record.path("source_type").asText())){
      subjectiveCount++;if(!archived.contains(entry.getKey()) || !"ROLE_PROFILE".equals(record.path("source_type").asText()))subjective.add(record);
     }
     else if(!("SELF_PROFILE".equals(record.path("source_type").asText()) && archived.contains(entry.getKey()) && !dossier.path("identity").path("establishedSelf").isEmpty()))actual.add(record);
    }
    history.set("ownRecords",actual);
    if(subjectiveCount>0)history.put("historicalSubjectiveRecordCount",subjectiveCount);
    if(!subjective.isEmpty())history.set("historicalSubjectiveRecords",subjective);
    if("NEWEST_TO_OLDEST".equals(history.path("historyOrder").asText())){
     for(var group:List.of(actual,subjective)){var records=new ArrayList<JsonNode>();group.forEach(records::add);Collections.reverse(records);group.removeAll();records.forEach(group::add);}history.put("historyOrder","OLDEST_TO_NEWEST");
    }
    if(archived.contains(entry.getKey()))history.putObject("historyLookup").put("roleId",entry.getKey()).put("source","worldBookIndex/worldBookRequests");
   }
   if(dossier.path("currentState") instanceof ObjectNode current && current.has("plannedWork"))dossier.set("previouslyPlannedActivity",current.remove("plannedWork"));
   if(dossier.path("identity") instanceof ObjectNode identity && identity.has("goals"))dossier.set("personalAims",identity.remove("goals"));
   if(input.path("roleDirections") instanceof ObjectNode directions && dossier.path("intentions").isObject() && !dossier.path("intentions").isEmpty() && directions.has(entry.getKey())){
    // Deduplicate only equal values. A frozen, older dossier must not hide a newer original reply.
    var original=directions.path(entry.getKey()).deepCopy();
    if(original instanceof ObjectNode object)object.remove(List.of("characterTemplate","narrativeTier","tierReason"));
    if(original.equals(dossier.path("intentions")))directions.putObject(entry.getKey()).put("$ref",pointer(entry.getKey())+"/intentions");
   }
  }
  for(var direction:input.path("roleDirections"))if(direction instanceof ObjectNode object)object.remove("characterTemplate");
  for(var candidate:input.path("castPlanning").path("candidates"))if(candidate instanceof ObjectNode object && input.path("characterDossiers").has(candidate.path("roleId").asText())){
   object.remove("profile");object.put("dossierRef",pointer(candidate.path("roleId").asText()));
  }
  StoryContextSources.compact(input);
  return input;
 }
}
