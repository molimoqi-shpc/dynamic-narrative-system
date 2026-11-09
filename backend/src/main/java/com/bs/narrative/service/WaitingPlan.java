package com.bs.narrative.service;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
/** Bounded continuation. Every follow-up must be an explicit substring of the user's request. */
public record WaitingPlan(String condition,String watchId,String followIntent,String followTarget,String followText,int phases,String eventText) {
    public WaitingPlan(String c,String w,String i,String t,String f,int p){this(c,w,i,t,f,p,"");}
    public static WaitingPlan parse(LLMResponse response,String text,Map<String,GodProfile> gods,String player) {
        JsonNode n=null;
        try {if(response!=null)n=ModelOutput.object(response.parsedJson()!=null?response.parsedJson():response.content()).path("waiting");}catch(IllegalArgumentException ignored){}
        String condition=n==null?"NONE":n.path("condition").asText("NONE");
        String watch=n==null?"":n.path("watchGodId").asText("");
        String event=n==null?"":n.path("eventText").asText("").strip();
        // A general next reaction is not a named event with a separate completion condition.
        if("EVENT".equals(condition) && event.matches("(?s).*(下一步|后续|接下来|之后).*(反应|回应|行动|举动|发展|变化).*")) {condition="CHANGE";event="";}
        if("EVENT".equals(condition) && !event.isBlank() && event.length()<=150 && text.contains(event)){if(!gods.containsKey(watch) || watch.equals(player))watch="";}
        else if ("CHANGE".equals(condition)) watch="";
        else if(!"LEAVE".equals(condition) || !gods.containsKey(watch) || watch.equals(player)) {condition="NONE";watch="";}
        if(condition.equals("NONE") && text.matches("(?s).*(等待|等到|等候|静候|继续等).*(变化|转机|机会|动静|事情发生).*"))condition="CHANGE";
        String intent=n==null?"NONE":n.path("followIntent").asText("NONE");
        String target=n==null?"":n.path("followTargetGodId").asText("");
        String follow=n==null?"":n.path("followText").asText("").strip();
        if(follow.isBlank() || !text.contains(follow) || !Set.of("RESCUE","TALK","ATTACK","EXAMINE").contains(intent)
            || (!intent.equals("EXAMINE") && (!gods.containsKey(target) || target.equals(player)))) {intent="NONE";target="";follow="";}
        // Avoid a model translating a rescue plan into an unrequested attack.
        if(intent.equals("ATTACK") && !follow.matches("(?s).*(攻击|袭击|杀|打倒|出手|动手).*")) {intent="NONE";target="";follow="";}
        if(text.matches("(?s).*(不要|不想|不能|不愿|别|勿)[^，。；]{0,8}(救|接应|攻击|杀|打倒|交谈).*")) {intent="NONE";target="";follow="";}
        // An unspecified opportunity never authorizes a rescue, attack or dialogue automatically.
        if(!condition.equals("LEAVE")) {intent="NONE";target="";follow="";}
        return new WaitingPlan(condition,watch,intent,target,follow,3,event);
    }
    public boolean ready(List<String> present) {return condition.equals("LEAVE") && !present.contains(watchId);}
}
