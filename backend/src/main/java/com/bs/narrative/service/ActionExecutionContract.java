package com.bs.narrative.service;

import com.bs.narrative.domain.GameScene;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** The model chooses tasks and outcomes; this class checks evidence, resources and stopping points. */
public final class ActionExecutionContract {
    private ActionExecutionContract() {}
    private static final Set<String> BODY = Set.of("手", "双手", "手指", "指节", "指甲", "指尖", "指腹", "掌心", "掌根", "掌缘", "掌背", "拳头", "拇指", "食指", "中指", "无名指", "小指", "脚", "双脚", "脚尖", "脚掌", "肩", "肩膀", "肩部", "肘", "肘部", "膝", "膝盖", "膝部", "前臂", "手臂", "后背", "背部", "腰", "腰部", "牙", "牙齿", "嘴", "嘴唇", "舌", "舌头", "头", "头部", "身体", "声音", "目光", "力", "力量", "意识");
    private static final Pattern TOOL_COMPOUND = Pattern.compile("^(?:部|膀|节|缘|根|心|背|腹|尖|掌|脚|足|手|臂|腿|头|眼|内|外|侧){0,2}(?:铁|钢|木|铜|玉|石|骨|灵|银|金|护|防){0,3}(?:刀|剑|锯|针|签|甲|套|箍|环|杆|杖|棍|锤|斧|锹|铲|刃|符|咒|器|钳|剪|绳|链|牌|令|铐|盔).*");
    // Anatomical word formation, independent of action verbs. Tool suffixes never fit this grammar.
    private static final Pattern ANATOMY = Pattern.compile("^(?:双|两|左|右|前|后|上|下|全|自身的|自己的)?(?:眼睛|眼|目|耳朵|耳|鼻子|鼻|嘴巴|嘴|口|牙齿|牙|舌头|舌|头|颈|脖子|肩膀|肩|胸|背|腹|腰|臀|手臂|臂|肘|腕|手|掌|指|腿|膝|踝|脚|足|趾|皮肤|皮|躯干|身)(?:部|边|缘|沿|背|心|根|尖|端|腹|节|掌|指|趾|底|跟|肚|皮|骨|脚|足|内|外|侧){0,2}$");
    public static boolean anatomy(String name){return BODY.contains(name) || ANATOMY.matcher(name).matches() || name.matches("^(?:(?:视|听|嗅|触|味|感)觉|目光|视线|声音|声带|气息|呼吸|体重|血肉|骨骼|皮肉|唾液|汗水)$");}
    public static boolean versioned(JsonNode result) { return result.path("executionVersion").asInt()==2; }
    /** Physical prose cannot create an utterance. Deleting that overreach preserves the speech channel. */
    public static void discardInventedSpeech(JsonNode result){
        for(var event:result.path("events"))if(event instanceof com.fasterxml.jackson.databind.node.ObjectNode node){
            String before=event.path("text").asText();
            var textBuilder=new StringBuilder();var sentence=new StringBuilder();var quotes=new ArrayDeque<Character>();boolean speechTail=false;
            for(char c:before.toCharArray()){
                sentence.append(c);
                if(c=='“')quotes.push('”');else if(c=='‘')quotes.push('’');else if(c=='"'){if(!quotes.isEmpty() && quotes.peek()=='"')quotes.pop();else quotes.push('"');}
                else if(!quotes.isEmpty() && c==quotes.peek())quotes.pop();
                if(quotes.isEmpty() && "，。！？；\n".indexOf(c)>=0){speechTail=appendPhysicalClause(textBuilder,sentence.toString(),speechTail);sentence.setLength(0);if(c!='，')speechTail=false;}
            }
            appendPhysicalClause(textBuilder,sentence.toString(),speechTail);String text=textBuilder.toString().trim().replaceFirst("，$","。");
            if(!before.equals(text)){
                node.put("text",text);
                if(event.path("view") instanceof com.fasterxml.jackson.databind.node.ObjectNode view)view.put("activity",text);
            }
        }
    }
    private static boolean appendPhysicalClause(StringBuilder result,String clause,boolean speechTail){
        boolean hearing=clause.matches("(?s).*(?:听到|听见|听闻|听着|闻声|听)[^，。！？；]{0,28}(?:说|问|喊|开口|吩咐).*" ) && !clause.matches(".*[“‘\"].*");
        boolean utterance=!hearing && Pattern.compile("说(?!明|法|服|书)|(?<!疑)问(?!题|号)|回答|答道|喊|开口|招呼|告诉|交代|低语|言罢|向[^，。！？；]{1,24}打听").matcher(clause).find();
        // An unquoted utterance can continue after a comma (开口，请借一把刀).
        // Only an explicit narrative continuation reopens physical prose, never the quoted text.
        boolean physicalContinuation=clause.matches("^(?:随后|接着|然后|继而|随即|顺势|便|又|并|转而)(?:他|她|他们|她们|自己)?(?:上前|前行|后退|退|回|转身|走|站|抬|放|收|移|挪|蹲|俯|侧身|停|观察|检查).*");
        if(!utterance && (!speechTail || physicalContinuation))result.append(clause);
        else if(utterance){
            // A speech clause can start with an independently completed movement. Keep only
            // that prefix, never the following request, answer or quoted speech contents.
            var start=Pattern.compile("(?:向|对)[^，。！？；]{1,24}(?:说|问|喊|招呼|告诉|回答|交代|打听)|(?:压低声音|低声|沉声|含笑)?(?:说|问|回答|答道|开口|低语)").matcher(clause);
            if(start.find()){
                String prefix=clause.substring(0,start.start()).replaceFirst("(?:并|又|便|转头|随后|低声|压低声音)$","");
                if(prefix.matches(".*(?:走近|上前|后退|退开|退到|退回|挪到|移步|站定|蹲下|起身|侧身|俯身|理正|整理|抬手|放下|转身).*") && !prefix.matches(".*[“‘\"].*"))result.append(prefix).append('，');
            }
        }
        return utterance || speechTail && !physicalContinuation;
    }
    public static void retainCurrentSnapshots(JsonNode result,Collection<Map<String,Object>> current){
        if(!versioned(result) || !(result.path("sceneChanges") instanceof com.fasterxml.jackson.databind.node.ArrayNode changes))return;
        for(int i=changes.size()-1;i>=0;i--){String name=changes.get(i).path("objectName").asText();
            boolean known=current.stream().anyMatch(s->name.equals(s.get("objectName")));boolean touched=false;
            for(var event:result.path("events"))if(event.path("text").asText().contains(name) || mentionsFacility(event,name))touched=true;
            // A copied historical summary is not a new transition. The persisted state remains intact.
            if(known && !touched)changes.remove(i);
        }
    }
    /** Evidence links belong to the server, not to another free-form paraphrase of the same result. */
    public static void bindEvidence(JsonNode result) {
        if(!versioned(result))return;
        for(var e:result.path("events")){
            String text=e.path("text").asText();
            if(e.path("execution") instanceof com.fasterxml.jackson.databind.node.ObjectNode execution){
                // Providers may use the conventional synonym; it must not turn an unfinished
                // task into success or invalidate otherwise identical execution evidence.
                String status=execution.path("status").asText().trim().toUpperCase(Locale.ROOT);
                if("IN_PROGRESS".equals(status))status="ONGOING";
                execution.put("status",status);
                execution.put("basis",text);
                if(!"COMPLETED".equals(execution.path("status").asText()) && (execution.path("stopReason").asText().isBlank() || !text.contains(execution.path("stopReason").asText())))execution.put("stopReason",text);
            }
            for(var use:e.path("nonItemUses"))if(use instanceof com.fasterxml.jackson.databind.node.ObjectNode object
                    && !use.path("name").asText().isBlank())object.put("basis",text);
        }
        if(!(result.path("sceneChanges") instanceof com.fasterxml.jackson.databind.node.ArrayNode changes))return;
        // A second paraphrase must not decide whether an already executed result can be saved.
        // Missing snapshots use the actual event verbatim; no new outcome or state is invented.
        for(var e:result.path("events"))for(var use:e.path("nonItemUses")){
            String name=use.path("name").asText();
            if(!"SCENE".equals(use.path("kind").asText()) || name.isBlank())continue;
            if(java.util.stream.StreamSupport.stream(changes.spliterator(),false).noneMatch(c->name.equals(c.path("objectName").asText())))changes.addObject().put("objectName",name);
        }
        for(var change:changes)if(change instanceof com.fasterxml.jackson.databind.node.ObjectNode object){
            String name=change.path("objectName").asText(),state=change.path("state").asText();JsonNode source=null;
            for(var e:result.path("events"))if(!name.isBlank() && (e.path("text").asText().contains(name) || mentionsFacility(e,name)))source=e;
            if(source==null)continue;
            String text=source.path("text").asText();
            if(state.isBlank() || !text.contains(state)){
                String factual=text;
                if(text.matches("(?s).*(打算|准备|将要|希望|如果|可能会|也许会).*")){
                    factual="";
                    for(String sentence:text.split("(?<=[。！？；\\n])"))if(!sentence.isBlank() && !sentence.matches("(?s).*(打算|准备|将要|希望|如果|可能会|也许会).*")){factual=sentence.trim();if(sentence.contains(name))break;}
                }
                object.put("state",factual);
            }
            object.put("basis",text);
        }
    }
    public static void validate(JsonNode result, GameScene scene, Collection<Map<String,Object>> currentFacilities,
                                Collection<String> roleNames, Collection<String> itemNames) {
        boolean v2=versioned(result);
        if(result.has("executionVersion") && (!v2 || !result.path("sceneChanges").isArray()))throw new IllegalArgumentException("executionVersion=2 requires sceneChanges and complete per-actor execution");
        String initial=Objects.toString(scene.getSceneSummary(),"").split("【当前局势】",2)[0];
        var invalidNames=new LinkedHashSet<String>();var groundedNames=new LinkedHashSet<String>();
        currentFacilities.forEach(f->groundedNames.add(Objects.toString(f.get("objectName"),"")));
        for(var event:result.path("events"))for(var use:event.path("nonItemUses"))if("SCENE".equals(use.path("kind").asText())){
            String name=use.path("name").asText();
            if(!name.isBlank() && (initial.contains(name) || groundedNames.contains(name)))groundedNames.add(name);else invalidNames.add(name);
        }
        if(!invalidNames.isEmpty())throw new IllegalArgumentException("Replace ALL unknown SCENE names="+invalidNames+" with verified object names="+groundedNames+" in both nonItemUses and sceneChanges. Parts/measurements belong to that existing object; do not replace with another invented alias. Keep the actual event, not a new outcome.");
        Set<String> facilities=new HashSet<>();
        for(var e:result.path("events")){
            String text=e.path("text").asText();JsonNode execution=e.path("execution");
            String status=execution.path("status").asText(),task=execution.path("task").asText(),basis=execution.path("basis").asText(),reason=execution.path("stopReason").asText();
            if(v2 && (!Set.of("COMPLETED","BLOCKED","INTERRUPTED","ONGOING").contains(status) || task.isBlank() || task.length()>240 || basis.isBlank() || !text.contains(basis)))
                throw new IllegalArgumentException("execution needs actual status and event.text basis: actorId="+e.path("actorId").asText()+", status="+status+", taskLength="+task.length()+", textLength="+text.length());
            if(v2 && !"COMPLETED".equals(status) && (reason.isBlank() || !text.contains(reason)))throw new IllegalArgumentException("unfinished execution needs an actual stopping condition quoted from event.text");
            if(v2 && "ONGOING".equals(status) && reason.matches("(?:移出|移了|移动|挪了|挪动|拔出|取出|清出)?(?:半寸|一寸|指节长|几片|少许|一点|略微)[。\\s]*"))throw new IllegalArgumentException("small movement alone is not a stopping condition; execute the chosen method or state a real blocker");
            if(!v2 && !e.has("nonItemUses"))continue; // Old results may omit the new fields, but new declarations are never trusted unvalidated.
            var uses=e.path("nonItemUses");if(!uses.isArray() || uses.size()>12)throw new IllegalArgumentException("nonItemUses must classify BODY/SCENE references separately from inventory uses");
            for(var use:uses){
                String kind=use.path("kind").asText(),name=use.path("name").asText(),proof=use.path("basis").asText();
                if(name.isBlank() || name.length()>40 || proof.isBlank() || !text.contains(proof) || itemNames.contains(name) || roleNames.contains(name))throw new IllegalArgumentException("non-item reference is not grounded: kind="+kind+", name="+name+"; inventory and roles cannot be relabeled");
                if("BODY".equals(kind)){if(!anatomy(name) || text.contains(name) && !bodyReference(text,name))throw new IllegalArgumentException("BODY must identify anatomy, not a similarly named tool: "+name);}
                else if("SCENE".equals(kind)){
                    if(!initial.contains(name) && currentFacilities.stream().noneMatch(f->name.equals(f.get("objectName"))))throw new IllegalArgumentException("SCENE name="+name+" is not an existing object name; use its exact opening/current-state object name, not a part, alias, action or description. Confirmed names="+currentFacilities.stream().map(f->f.get("objectName")).toList());
                    facilities.add(name);
                }else throw new IllegalArgumentException("nonItemUses accepts BODY or existing SCENE only; portable items require owned refs");
            }
        }
        for(String name:facilities)if(v2 && java.util.stream.StreamSupport.stream(result.path("sceneChanges").spliterator(),false).noneMatch(c->name.equals(c.path("objectName").asText())))
            throw new IllegalArgumentException("sceneChanges must retain actual final state of referenced facility: "+name+"; unchanged state is allowed, proposals are not state");
    }
    public static boolean bodyReference(String text,String name){
        if(!anatomy(name))return false;
        int start=0,at;while((at=text.indexOf(name,start))>=0){String suffix=text.substring(at+name.length());if(!suffix.matches("(?s)^(?:中|里|上面)(?:的)?.*") && !TOOL_COMPOUND.matcher(suffix).lookingAt())return true;start=at+name.length();}return false;
    }
    public static boolean bodyPhrase(String phrase){
        for(int n=Math.min(8,phrase.length());n>0;n--){String name=phrase.substring(0,n);if(anatomy(name))return bodyReference(phrase,name);}
        return false;
    }
    public static boolean mentionsFacility(JsonNode event,String name){
        for(var use:event.path("nonItemUses"))if("SCENE".equals(use.path("kind").asText()) && name.equals(use.path("name").asText()))return true;
        return false;
    }
    public static boolean declaredNonItem(String phrase,JsonNode declarations){
        for(var use:declarations){String name=use.path("name").asText(),proof=use.path("basis").asText();
            if(!name.isBlank() && phrase.startsWith(name) && proof.contains(name) && (!"BODY".equals(use.path("kind").asText()) || bodyReference(phrase,name)))return true;
        }return false;
    }
    public static Map<String,Object> executionState(JsonNode event){
        var e=event.path("execution");return Map.of("task",e.path("task").asText(),"status",e.path("status").asText(),"basis",e.path("basis").asText(),"stopReason",e.path("stopReason").asText());
    }
    public static boolean reachedCheckpoint(JsonNode result){
        for(var e:result.path("events"))if(Set.of("COMPLETED","BLOCKED","INTERRUPTED").contains(e.path("execution").path("status").asText()))return true;
        return false;
    }
}
