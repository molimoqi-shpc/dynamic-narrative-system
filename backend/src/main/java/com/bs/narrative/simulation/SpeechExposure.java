package com.bs.narrative.simulation;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
/** Full-utterance delivery needs continuous evidence over [start,end), never endpoint membership. */
public final class SpeechExposure {
 private SpeechExposure(){}
 public static ObjectNode advance(ObjectNode previous,long begin,long end,long from,long to,Collection<String> eligible,String speaker){
  if(to<from || end<begin)throw new IllegalArgumentException("invalid exposure interval");
  var state=previous.deepCopy();long left=Math.max(begin,from),right=Math.min(end,to);if(right<=left)return state;
  var full=new LinkedHashSet<String>();
  boolean continuous=state.has("checkedThrough") && state.path("checkedThrough").asLong()==left && state.path("startSeconds").asLong()==begin;
  if(continuous)state.path("recipients").forEach(n->full.add(n.asText()));
  else if(left==begin)full.addAll(eligible);
  else full.add(speaker); // A legacy record or missing interval cannot prove an observer heard it.
  full.retainAll(new HashSet<>(eligible));
  state.put("startSeconds",begin);state.put("checkedThrough",right);var ids=state.putArray("recipients");full.forEach(ids::add);return state;
 }
}
