package com.bs.narrative.service;

import java.util.*;
import java.util.regex.*;

/** Renders existing public facts and speech in causal order. Never changes world state or asks a model again. */
final class NarrativeSequence {
    private NarrativeSequence() {}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(NarrativeSequence.class);
    private static final Set<String> EVENT_KINDS=Set.of("ARRIVE","LEAVE","SCENE_ACTION","SCENE_OBSERVATION",
            "ATTACK","SUPPORT","RESCUE","FOLLOW_UP","GIFT","GIFT_PENDING","OFFER","OFFER_FAILED","OPPOSE","INQUIRE","PLAYER_SPEECH");
    private static final Pattern MARKER=Pattern.compile("\\[\\[(QUOTE|EVENT):(\\d+)]]");
    record Anchor(String key,int phase,int stage,int eventIndex,String text,long sequence) {
        Anchor(String key,int phase,int stage,int eventIndex,String text){this(key,phase,stage,eventIndex,text,stage*10000L);}
        long rank(){return (long)phase*1000000+sequence;}
    }
    private static long order(Map<String,Object> event,int stage){return event.get("sequence") instanceof Number n?n.longValue():stage*10000L;}
    private static long speechOrder(List<Map<String,Object>> events,int index){return events.stream().filter(e->"SPEECH".equals(e.get("kind")) && e.get("decisionIndex") instanceof Number n && n.intValue()==index).findFirst().map(e->order(e,1)).orElse(10000L);}

    static boolean enabled(List<Map<String,Object>> events) {
        return events.stream().anyMatch(e->e.containsKey("npcStage"));
    }
    private static boolean publicSpeech(NpcAgentService.NpcDecision d,List<String> allowed) {
        return d!=null && !d.usedFallback() && allowed.contains(d.godId()) && d.speech()!=null && !d.speech().isBlank();
    }
    private static int phase(List<Integer> phases,int index) {
        return index<phases.size() && phases.get(index)!=null?phases.get(index):index+1;
    }
    private static List<Anchor> anchors(List<NpcAgentService.NpcDecision> decisions,List<String> allowed,
            Map<String,String> names,List<Integer> phases,List<Map<String,Object>> events) {
        var result=new ArrayList<Anchor>();
        for(int i=0;i<events.size();i++) {
            var event=events.get(i);
            if(!EVENT_KINDS.contains(event.get("kind")) || !(event.get("phase") instanceof Number p))continue;
            String text=Objects.toString(event.get("text"),"");if(text.isBlank())continue;
            result.add(new Anchor("EVENT:"+i,p.intValue(),"BEFORE_NPC".equals(event.get("npcStage"))?0:2,i,text,order(event,"BEFORE_NPC".equals(event.get("npcStage"))?0:2)));
        }
        for(int i=0;i<decisions.size();i++)if(publicSpeech(decisions.get(i),allowed)) {
            var d=decisions.get(i);
            result.add(new Anchor("QUOTE:"+i,phase(phases,i),1,-1,names.getOrDefault(d.godId(),"在场人物")+"说：“"+d.speech()+"”",speechOrder(events,i)));
        }
        // Stable sort preserves simultaneous speakers' source records without claiming they heard one another.
        result.sort(Comparator.comparingLong(Anchor::rank));
        return result;
    }

    static String prompt(List<NpcAgentService.NpcDecision> decisions,List<String> allowed,Map<String,String> names,
            List<Integer> phases,List<Map<String,Object>> events) {
        var out=new StringBuilder("\n【正文时间锚点】以下仅是已经执行的事件与实际原话，不是未来剧本。服务器依此顺序自动插入每条原文，只输出visible.bridges中的必要衔接，键为下列标记去掉方括号后的原始键。值放在该锚点前，不重复写锚点；可全部留空，不能省略真实结果。\n");
        for(var a:anchors(decisions,allowed,names,phases,events))out.append("[[").append(a.key()).append("]] · 第").append(a.phase()).append("刻 · ")
                .append(a.stage()==0?"角色本次发言之前已发生":a.stage()==1?"依据本刻开始时已知情况说出，早于本刻后续执行结果":"本刻发言后实际执行的结果")
                .append(" · ").append(a.text()).append('\n');
        return out.append("先按刻次，再按发言前事实→当时台词→后续执行结果组织；同刻发言按服务器记录的sequence排列；只有已在生成前送达的台词才能作为回应依据，不能交换问答顺序。台词中的“先检查/还需试一试”必须放在本次检查结果之前；不能将未来意图写成已完成事实。不要直接输出锚点说明、后台阶段或编号。无新状态的末尾不机械添加“仍等你回应”。").toString();
    }

    /** Model owns optional connectives, server owns mandatory public facts and their causal order. */
    static String composeBridges(Map<String,String> bridges,List<NpcAgentService.NpcDecision> decisions,
            List<String> allowed,Map<String,String> names,List<Integer> phases,List<Map<String,Object>> events) {
        var ordered=new ArrayList<>(anchors(decisions,allowed,names,phases,events));
        for(int i=0;i<events.size();i++) {
            var e=events.get(i);
            if("ACTION".equals(e.get("kind")) && e.get("phase") instanceof Number p)
                ordered.add(new Anchor("ACTION:"+i,p.intValue(),"BEFORE_NPC".equals(e.get("npcStage"))?0:2,i,Objects.toString(e.get("text"),""),order(e,"BEFORE_NPC".equals(e.get("npcStage"))?0:2)));
        }
        ordered.sort(Comparator.comparingLong(Anchor::rank).thenComparingInt(Anchor::eventIndex));
        var paragraphs=new ArrayList<String>();var part=new StringBuilder();int phase=-1,rejected=0,kept=0;
        var previous=new HashSet<String>();
        for(var a:ordered) {
            if(a.phase()!=phase && !part.isEmpty()){paragraphs.add(part.toString());part.setLength(0);}
            phase=a.phase();
            String raw=Objects.toString(bridges.get(a.key()),"").strip();
            String bridge=cleanBridge(raw,ordered,previous);
            if(!raw.isBlank() && bridge.isBlank())rejected++;else if(!bridge.isBlank())kept++;
            part.append(bridge).append(a.text());
        }
        if(!part.isEmpty())paragraphs.add(part.toString());
        LOG.info("[STORY-BRIDGES] anchors={} kept={} rejected={}",ordered.size(),kept,rejected);
        return paragraphs.isEmpty()?"此刻没有新的可确认变化。":cleanPunctuation(String.join("\n\n",paragraphs)).strip();
    }
    private static String cleanBridge(String text,List<Anchor> anchors,Set<String> previous) {
        if(text.isBlank() || text.length()>48 || text.contains("[[") || text.contains("]]"))return "";
        if(text.matches("(?s).*(这话点明|这话里把|这话的意思是|这句话的意思是|这番话的意思是|BEFORE_NPC|AFTER_NPC|EVENT:|QUOTE:).*"))return "";
        if(text.matches("(?s).*[“”「」『』].*") || anchors.stream().anyMatch(a->text.contains(a.text())))return "";
        // Connectives cannot create a second version of an action or a new full factual sentence.
        if(text.matches("(?s).*[。！？!?；;\\n\\r].*"))return "";
        if(!text.endsWith("，") && !text.endsWith(","))return "";
        if(text.matches("(?s).*(蹲|起身|转身|伸手|停手|操持|轻敲|敲击|取出|拿出|抽出|抬|推|拉|握|说|问|喊|检查|探|拨|清|送|接受|同意|拒绝|答|发现|确认|成功|失败|受伤|苏醒|昏迷|松绑|出现|离开|到达|上涨|涨高|亮起|落下|打开|关上|发光|水位|风声|火光|血池).*"))return "";
        // A connective is unnecessary if it copies a substantial span from an already retained fact.
        String normalized=text.replaceAll("[\\p{P}\\p{Z}\\s]","");
        for(var a:anchors) {
            String fact=a.text().replaceAll("[\\p{P}\\p{Z}\\s]","");
            for(int i=0;i+3<=normalized.length();i++)if(fact.contains(normalized.substring(i,i+3)))return "";
        }
        if(!previous.add(text))return "";
        return text;
    }

    static String compose(String prose,List<NpcAgentService.NpcDecision> decisions,List<String> allowed,
            Map<String,String> names,List<Integer> phases,List<Map<String,Object>> events) {
        var anchors=anchors(decisions,allowed,names,phases,events);
        var indexed=new LinkedHashMap<String,Anchor>();for(var a:anchors)indexed.put(a.key(),a);
        String base=Objects.toString(prose,"");
        // Some providers emit the exact facts instead of markers. Bind those existing spans without rewriting them.
        for(var a:anchors)if(!base.contains("[["+a.key()+"]]")){
            String literal=a.text();
            if(a.key().startsWith("QUOTE:")){int i=Integer.parseInt(a.key().substring(6));literal="“"+decisions.get(i).speech()+"”";}
            if(base.contains(literal))base=base.replaceFirst(Pattern.quote(literal),Matcher.quoteReplacement("[["+a.key()+"]]"));
        }
        var seen=new HashSet<String>();var matcher=MARKER.matcher(base);
        long lastRank=-1;int lastEvent=-1;
        while(matcher.find()) {
            String key=matcher.group(1)+":"+matcher.group(2);var a=indexed.get(key);
            if(a==null)return fallback(anchors,events,"unknown anchor");
            if(!seen.add(key))continue;
            if(a.rank()<lastRank || a.eventIndex()>=0 && a.eventIndex()<lastEvent)return fallback(anchors,events,"speech/result order crossed");
            lastRank=a.rank();if(a.eventIndex()>=0)lastEvent=a.eventIndex();
        }
        if(!seen.containsAll(indexed.keySet()))return fallback(anchors,events,"essential speech or event omitted: "+indexed.keySet().stream().filter(k->!seen.contains(k)).toList());
        if(base.replaceAll(MARKER.pattern(),"").matches("(?s).*\\[\\[(?:QUOTE|EVENT):.*"))return fallback(anchors,events,"malformed anchor");
        // Avoid redundant explanations of a just-quoted demand; keep the independently recorded facts and words.
        if(base.matches("(?s).*(这话点明|这话里把|这话的意思是|这句话的意思是|这番话的意思是).*"))return fallback(anchors,events,"redundant quote explanation");
        matcher=MARKER.matcher(base);var body=new StringBuffer();seen.clear();
        while(matcher.find()) {
            String key=matcher.group(1)+":"+matcher.group(2);var a=indexed.get(key);String text="";
            if(seen.add(key)) {
                text=a.text();
                if(key.startsWith("QUOTE:")) {
                    int i=Integer.parseInt(matcher.group(2));var d=decisions.get(i);
                    boolean introduced=base.substring(Math.max(0,matcher.start()-80),matcher.start()).matches("(?s).*(?:[：:]|说了句|说道|说|问道|应道|回答|问|解释|提醒|低声道|喊道)[，]?\\s*$");
                    if(introduced)text="“"+d.speech()+"”";
                }
            }
            matcher.appendReplacement(body,Matcher.quoteReplacement(text));
        }
        matcher.appendTail(body);
        return cleanPunctuation(body.toString()).strip();
    }
    private static String fallback(List<Anchor> anchors,List<Map<String,Object>> events,String reason) {
        LOG.warn("[STORY-SEQUENCE] factual composition reason={}",reason);
        var parts=new ArrayList<Anchor>(anchors);
        // Retain player actions at their actual tick rather than moving every action to the beginning.
        for(int i=0;i<events.size();i++){var event=events.get(i);if("ACTION".equals(event.get("kind")) && event.get("phase") instanceof Number p)
            parts.add(new Anchor("ACTION:"+i,p.intValue(),"BEFORE_NPC".equals(event.get("npcStage"))?0:2,i,Objects.toString(event.get("text"),""),order(event,"BEFORE_NPC".equals(event.get("npcStage"))?0:2)));}
        parts.sort(Comparator.comparingLong(Anchor::rank).thenComparingInt(Anchor::eventIndex));
        return parts.isEmpty()?"此刻没有新的可确认变化。":cleanPunctuation(String.join("\n\n",parts.stream().map(Anchor::text).toList())).strip();
    }
    private static String cleanPunctuation(String text) {
        return text.replaceAll("([。！？!?])”[。.]","$1”");
    }
}
