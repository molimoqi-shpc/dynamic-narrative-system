package com.bs.narrative.service;

import com.bs.narrative.dto.ActionResponse.NarrativeLine;
import com.bs.narrative.llm.Pass2Service;
import java.util.*;

/** One narrator, one final spoken response per actor; no new model call or private-context mixing. */
final class EnsembleNarrative {
    private EnsembleNarrative() {}
    static Pass2Service.Pass2Result merge(Pass2Service.Pass2Result narration,
            List<NpcAgentService.NpcDecision> decisions, List<String> allowedActors) {
        return merge(narration,decisions,allowedActors,Map.of());
    }
    static Pass2Service.Pass2Result merge(Pass2Service.Pass2Result narration,
            List<NpcAgentService.NpcDecision> decisions,List<String> allowedActors,Map<String,String> names){
        return merge(narration,decisions,allowedActors,names,java.util.stream.IntStream.range(0,decisions==null?0:decisions.size()).map(i->i+1).boxed().toList(),List.of());
    }
    static Pass2Service.Pass2Result merge(Pass2Service.Pass2Result narration,List<NpcAgentService.NpcDecision> decisions,List<String> allowedActors,Map<String,String> names,List<Integer> phases,List<Map<String,Object>> events){
        var lines = new ArrayList<NarrativeLine>();
        var flags = new LinkedHashMap<String,Boolean>();
        // Narration cannot replace actor statements or manufacture their thoughts.
        if (narration.narrativeLines() != null) for (var line : narration.narrativeLines())
            if ("narrator".equals(line.godId())) lines.add(new NarrativeLine("narrator",narration.narrativeBridges()!=null && NarrativeSequence.enabled(events)
                    ?NarrativeSequence.composeBridges(narration.narrativeBridges(),decisions==null?List.of():decisions,allowedActors,names,phases,events)
                    :weave(line.speech(),decisions,allowedActors,names,phases,events),""));
        var ordered = new ArrayList<NpcAgentService.NpcDecision>();
        if (decisions != null) for (var d : decisions)
            if (d != null && !d.usedFallback() && allowedActors.contains(d.godId())
                    && d.speech() != null && !d.speech().isBlank()) ordered.add(d);
        for (var d : ordered) {
            lines.add(new NarrativeLine(d.godId(),d.speech(),d.innerThought()));
            flags.put(d.godId(),d.isLying());
        }
        return new Pass2Service.Pass2Result(List.copyOf(lines),flags,narration.llmResponse(),
                narration.status(),narration.note(),narration.hiddenSummary());
    }

    /** Markers refer to actual public speech, never private thoughts. Missing quotes remain readable. */
    static String weave(String prose,List<NpcAgentService.NpcDecision> decisions,List<String> allowed,Map<String,String> names){
        return weave(prose,decisions,allowed,names,java.util.stream.IntStream.range(0,decisions==null?0:decisions.size()).map(i->i+1).boxed().toList(),List.of());
    }
    static String weave(String prose,List<NpcAgentService.NpcDecision> decisions,List<String> allowed,Map<String,String> names,List<Integer> phases,List<Map<String,Object>> events){
        var safe=decisions==null?List.<NpcAgentService.NpcDecision>of():decisions;
        if(NarrativeSequence.enabled(events))return NarrativeSequence.compose(prose,safe,allowed,names,phases,events);
        String base=Objects.toString(prose,"");var pattern=java.util.regex.Pattern.compile("\\[\\[QUOTE:(\\d+)]]");var check=pattern.matcher(base);int lastPhase=-1;
        while(check.find())try{int n=Integer.parseInt(check.group(1));if(n<0 || n>=safe.size() || !publicSpeech(safe.get(n),allowed))return factualStory(safe,allowed,names,phases,events);int phase=phases.get(n);if(phase<lastPhase)return factualStory(safe,allowed,names,phases,events);lastPhase=phase;}catch(NumberFormatException e){return factualStory(safe,allowed,names,phases,events);}
        var referenced=new HashSet<Integer>();var refs=pattern.matcher(base);while(refs.find())referenced.add(Integer.parseInt(refs.group(1)));
        // An omitted earlier question must never be appended after a later referenced answer.
        for(int i=0;i<safe.size();i++)if(publicSpeech(safe.get(i),allowed) && !referenced.contains(i) && !base.contains(safe.get(i).speech()) && phases.get(i)<lastPhase)return factualStory(safe,allowed,names,phases,events);
        var matcher=pattern.matcher(base);var text=new StringBuffer();var used=new HashSet<Integer>();
        while(matcher.find()){int i=Integer.parseInt(matcher.group(1));var d=safe.get(i);boolean introduced=base.substring(Math.max(0,matcher.start()-80),matcher.start()).matches("(?s).*(?:[：:]|说了句|说道|说|问道|应道|回答|问|解释|提醒|低声道|喊道)[，]?\\s*$");String quote=used.add(i)?(introduced?"":attribution(d,names))+"“"+d.speech()+"”":"";matcher.appendReplacement(text,java.util.regex.Matcher.quoteReplacement(quote));}matcher.appendTail(text);
        for(int i=0;i<safe.size();i++){var d=safe.get(i);if(publicSpeech(d,allowed) && !used.contains(i) && !text.toString().contains(d.speech()))text.append("\n\n").append(attribution(d,names)).append("“").append(d.speech()).append("”");}
        return text.toString();
    }
    private static String factualStory(List<NpcAgentService.NpcDecision> decisions,List<String> allowed,Map<String,String> names,List<Integer> phases,List<Map<String,Object>> events){
        var periods=new TreeSet<Integer>(phases);for(var event:events)periods.add(((Number)event.get("phase")).intValue());var body=new StringBuilder();
        for(int phase:periods){var part=new StringBuilder();for(var event:events)if(((Number)event.get("phase")).intValue()==phase && "ACTION".equals(event.get("kind")))part.append(event.get("text")).append("\n\n");for(var event:events)if(((Number)event.get("phase")).intValue()==phase && Set.of("ARRIVE","LEAVE").contains(event.get("kind")))part.append(event.get("text")).append("\n\n");
            for(int i=0;i<decisions.size();i++)if(phases.get(i)==phase && publicSpeech(decisions.get(i),allowed)){var d=decisions.get(i);part.append(attribution(d,names)).append("“").append(d.speech()).append("”\n\n");}
            for(var event:events)if(((Number)event.get("phase")).intValue()==phase && !Set.of("ACTION","ARRIVE","LEAVE","DIALOGUE","INQUIRE","DECISION","SITUATION","OPPORTUNITY","WAITING","PAUSED").contains(event.get("kind")))part.append(event.get("text")).append("\n\n");
            if(!part.isEmpty())body.append(body.isEmpty()?"其间，":"接着，").append(part);
        }return body.isEmpty()?"此刻没有新的可确认变化。":body.toString().strip();
    }
    private static boolean publicSpeech(NpcAgentService.NpcDecision d,List<String> allowed){return d!=null && !d.usedFallback() && allowed.contains(d.godId()) && d.speech()!=null && !d.speech().isBlank();}

    private static String attribution(NpcAgentService.NpcDecision d,Map<String,String> names){return names.getOrDefault(d.godId(),"在场人物")+"说：";}
    static String introduceArrival(String body,String arrival,Collection<String> actorNames){
        if(body.contains(arrival))return body;String actorName=actorNames.stream().filter(arrival::startsWith).findFirst().orElse("");int mention=actorName.isBlank()?-1:body.indexOf(actorName);int separator=mention<0?-1:body.lastIndexOf("\n\n",mention);int paragraph=separator<0?0:separator+2;
        return body.substring(0,paragraph)+"这段时间里，"+arrival+"\n\n"+body.substring(paragraph);
    }
}
