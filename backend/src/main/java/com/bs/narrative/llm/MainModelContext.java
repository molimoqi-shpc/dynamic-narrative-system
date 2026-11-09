package com.bs.narrative.llm;

/** Immutable per-request/per-operation choice; never mutate the shared provider configuration. */
public final class MainModelContext {
 public static final String FLASH="deepseek-v4-flash",PRO="deepseek-v4-pro";
 private static final ThreadLocal<String> CURRENT=new ThreadLocal<>();
 private MainModelContext(){}
 public static boolean supported(String value){return FLASH.equals(value) || PRO.equals(value);}
 public static String current(){return CURRENT.get();}
 public static Scope use(String model){
  if(model!=null && !supported(model))throw new IllegalArgumentException("Unsupported main model");
  var scope=new Scope(CURRENT.get());if(model==null)CURRENT.remove();else CURRENT.set(model);return scope;
 }
 public static final class Scope implements AutoCloseable {
  private final String previous;private Scope(String value){previous=value;}
  @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
 }
}
