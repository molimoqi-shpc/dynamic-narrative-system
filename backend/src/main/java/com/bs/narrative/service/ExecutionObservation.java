package com.bs.narrative.service;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import java.util.*;
/** An actor's full process is private input. Other people receive only a separate observable projection. */
public final class ExecutionObservation {
 private ExecutionObservation(){}
 public static void validate(JsonNode candidate){
  if(!candidate.has("observations"))return;
  var observations=candidate.path("observations");if(!observations.isArray() || observations.size()>12)throw new IllegalArgumentException("invalid execution observations");
  for(var item:observations)if(!item.isObject() || !"VISUAL".equals(item.path("channel").asText()) || !item.path("text").isTextual() || item.path("text").asText().isBlank() || item.path("text").asText().length()>2000 || item.has("action") && (!item.path("action").isTextual() || item.path("action").asText().length()>500))throw new IllegalArgumentException("invalid visual observation");
  for(var item:observations)if(item.has("status") && (!item.path("status").isTextual() || !Set.of("COMPLETED","BLOCKED","ONGOING").contains(item.path("status").asText())))throw new IllegalArgumentException("invalid visible outcome status");
  // A normal visible process need not identify a goal. Its unbound status is not
  // evidence of goal completion; forObserver only projects outcomes bound to actions.
 }
 public static boolean hasVisualEvidence(JsonNode candidate){
  if(!candidate.path("observations").isEmpty() && candidate.path("observations").isArray())return true;
  for(var effect:candidate.path("effects")){
   String kind=effect.path("kind").asText();
   if("BODY".equals(kind)){for(String field:List.of("awake","bound","position"))if(effect.path("after").has(field) && !effect.path("after").path(field).equals(effect.path("before").path(field)))return true;}
   else if(Set.of("ITEM_POSITION","FACILITY").contains(kind)){if(!effect.path("before").equals(effect.path("after")))return true;}
   else if(Set.of("TRANSFER","MOVE","CONTEST","EXPLORE","CRAFT","GATHER","CONSUME").contains(kind))return true;
  }
  return false;
 }
 public static ObjectNode forObserver(ObjectNode actual,JsonNode candidate,String recipient,String actorName){
  var event=actual.deepCopy();var lines=new ArrayList<String>();var labels=new ArrayList<String>();
  for(var observation:candidate.path("observations")){lines.add(observation.path("text").asText());if(!observation.path("action").asText().isBlank())labels.add(observation.path("action").asText());}
  // Goal completion belongs to the actor's report, not automatically to a witness.
  event.remove("status");
  var outcomes=new HashSet<String>();boolean complete=!labels.isEmpty();
  for(var observation:candidate.path("observations"))if(!observation.path("action").asText().isBlank()){
   String outcome=observation.path("status").asText();
   if(!Set.of("COMPLETED","BLOCKED","ONGOING").contains(outcome))complete=false;else outcomes.add(outcome);
  }
  if(complete && outcomes.size()==1)event.put("status",outcomes.iterator().next());
  // Missing legacy prose must not erase observable movement, and cannot expose
  // the actor's potentially private full process. Use committed public effects.
  if(lines.isEmpty())for(var effect:candidate.path("effects")){
   if("BODY".equals(effect.path("kind").asText()) && actual.path("actorId").asText().equals(effect.path("actorId").asText())){
    var before=effect.path("before");var after=effect.path("after");
    if(after.path("position").isTextual() && !after.path("position").asText().isBlank() && !after.path("position").equals(before.path("position")))lines.add(actorName+"移到"+after.path("position").asText()+"。");
    if(after.path("awake").isBoolean() && !after.path("awake").equals(before.path("awake")))lines.add(actorName+(after.path("awake").asBoolean()?"恢复了清醒。":"失去了意识。"));
    if(after.path("bound").isBoolean() && !after.path("bound").equals(before.path("bound")))lines.add(actorName+(after.path("bound").asBoolean()?"身上已有束缚。":"身上的束缚已解除。"));
   }
   if("FACILITY".equals(effect.path("kind").asText()) && effect.path("after").isTextual() && !effect.path("after").equals(effect.path("before")))lines.add(effect.path("objectName").asText()+"："+effect.path("after").asText());
  }
  event.put("text",lines.isEmpty()?actorName+"的动作带来了眼前可见的变化。":String.join("\n",lines));
  event.remove("attempt");if(!labels.isEmpty())event.put("attempt",String.join("；",labels));
  for(var effect:event.path("effects"))if(effect.isObject()){
   var fields=switch(effect.path("kind").asText()){
    case "BODY" -> List.of("kind","actorId","before","after");
    case "MOVE" -> List.of("kind","actorId","fromSceneId","toSceneId");
    case "TRANSFER" -> List.of("kind","fromId","toId","itemRef","mode");
    case "CRAFT" -> List.of("kind","name");
    case "GATHER" -> List.of("kind","name","objectName","after");
    case "CONSUME" -> List.of("kind","name");
    case "ITEM_POSITION" -> List.of("kind","itemRef","before","after");
    case "FACILITY" -> List.of("kind","objectName","before","after");
    case "OBSERVE" -> List.of("kind","actorId","sceneId","scope");
    case "CONTEST" -> List.of("kind","targetId","outcome","fingerprint");
    default -> List.of("kind");
   };((ObjectNode)effect).retain(fields);
   if("BODY".equals(effect.path("kind").asText()) && !recipient.equals(effect.path("actorId").asText()))
    for(String part:List.of("before","after"))if(effect.path(part).isObject())((ObjectNode)effect.path(part)).retain(List.of("awake","bound","position"));
  }
  return event;
 }
}
