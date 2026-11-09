package com.bs.narrative.service;
import com.bs.narrative.domain.GodProfile;
import java.util.*;

/** Public opening descriptions only: never expose private profiles, goals or NPC IDs as prose. */
public final class OpeningCastPresentation {
 private OpeningCastPresentation(){}
 public static List<Map<String,String>> describe(String description,String location,Collection<GodProfile> actors){
  String opening=Objects.toString(description,"").split("【当前局势】",2)[0];
  var result=new ArrayList<Map<String,String>>();
  for(var actor:actors){String name=Objects.toString(actor.getGodName(),"");
   if(name.isBlank() || name.matches("(?:npc|player|qa)_[A-Za-z0-9_]+"))name="未曾自报姓名的人";
   var excerpts=new LinkedHashSet<String>();
   for(String sentence:opening.split("(?<=[。！？；\\n])"))if(sentence.contains(name))excerpts.add(sentence.trim());
   String intro=String.join("",excerpts);
   if(intro.isBlank())intro="此刻，你与"+name+"同在"+location+"。";
   result.add(Map.of("id",actor.getGodId(),"name",name,"introduction",intro));
  }
  return List.copyOf(result);
 }
}
