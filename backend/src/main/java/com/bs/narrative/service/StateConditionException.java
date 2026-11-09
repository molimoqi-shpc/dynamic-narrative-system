package com.bs.narrative.service;
import java.util.*;
public final class StateConditionException extends IllegalArgumentException {
 private final Map<String,Object> details;
 public StateConditionException(String reason,String object,String field,Object expected,Object actual){
  super(reason);var d=new LinkedHashMap<String,Object>();d.put("objectRef",object);d.put("field",field);d.put("expectedBefore",expected);d.put("actualValue",actual);details=Collections.unmodifiableMap(d);
 }
 public Map<String,Object> details(){return details;}
}
