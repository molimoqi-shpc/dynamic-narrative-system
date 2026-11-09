package com.bs.narrative.llm;
import java.util.regex.Pattern;
/** Only unambiguous short wait controls; compound plans still use the intent model. */
public record SimpleWaitCommand(String condition,String eventText){
 private static final Pattern CONTROL=Pattern.compile("^(?:继续)?(?:等待|等候|静候)([\\p{IsHan}]{0,18})[。！!]?$");
 public static SimpleWaitCommand parse(String input){
  if(input==null)return null;var match=CONTROL.matcher(input.strip());if(!match.matches())return null;
  String event=match.group(1);
  if(event.isEmpty())return new SimpleWaitCommand("NONE","");
  if(event.matches(".*(?:后|再|趁|直到|等到|尝试|看看|伺机|救|偷|攻击|杀|打|不要|不想|不愿|别|勿).*"))return null;
  if(!event.matches(".*(?:指示|答复|回复|回答|发言|说完|开口|表态)$"))return null;
  return new SimpleWaitCommand("EVENT",event);
 }
}
