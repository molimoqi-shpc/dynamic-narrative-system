package com.bs.narrative.service;
import java.util.*;
/** Public historical exchanges remain in history; the composer shows only the final simulated phase. */
final class CurrentDecisionPoints {
 private CurrentDecisionPoints(){}
 static List<Map<String,Object>> atPhase(List<Map<String,Object>> points,int phase){
  return points.stream().filter(p->p.get("phase") instanceof Number n && n.intValue()==phase).toList();
 }
 @SuppressWarnings("unchecked")
 static List<Map<String,Object>> fromSnapshot(Map<String,Object> snapshot){
  if(snapshot.get("activeDecisionPoints") instanceof List<?> points)return (List<Map<String,Object>>)points;
  var points=(List<Map<String,Object>>)snapshot.getOrDefault("decisionPoints",List.of());
  if(snapshot.get("waiting") instanceof Map<?,?> wait && wait.get("phases") instanceof Number n)return atPhase(points,n.intValue());
  return points;
 }
}
