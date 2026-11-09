package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Independent roles choose first; one main request resolves each frozen scene cohort. */
@Component
public class WorldCohortExecutor {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final NpcRequestExecutor requests;
    public WorldCohortExecutor(NpcRequestExecutor requests){this.requests=requests;}
    public record Outcome(List<NpcRequestExecutor.Result> results,List<JsonNode> audit){}
    private record Key(String contract,String scene,JsonNode snapshot){}
    private static final String CONTRACT="""
        【主API统一现场推进】各角色已经通过各自独立上下文选定话语和行动。本请求只结算这些已选行动，不重新决定动机、立场或替角色追加话语。
        sharedFacts为本组共用的冻结事实；attempts每项的facts补充或覆盖共用字段，合起来就是这一项的完整输入。所有项共享输入快照，不把返回顺序当作发生顺序。
        同一地点的经过需要相互协调，不能把别人的意图当成已成功结果，也不能让一个人同时占据矛盾位置。每项仍只负责自己的actorId和attempt；别人的回答、同意、攻击等须已有独立选择，不能代写。私有想法与私有感知不得转写为公开所见。
        唯一返回JSON：{"results":[{"jobId":"原样引用任务编号","result":原执行结果对象}]}。每项都返回一次，不漏项、不重复编号、不输出总故事替代各项结果。result沿用上方process、observations、durationSeconds、status、effects结构。
        BODY只需kind、actorId及after中的实际变化，不必抄写before；before由服务器从本次冻结快照绑定。ITEM_POSITION同样只需itemRef及after，原放置位置由服务器绑定。不要回显身体整表、health、时间戳或来源标记。场内实际移动仍写after.position。OBSERVE的本人、地点同样由服务器绑定。
        人物原话已经独立保存并按时交付，process不新增、改写或重复引用台词。自然经过可以连续完整地写，格式记账不能成为剧情障碍。未发生的状态变化不能靠正文宣称成功。
        每项公开动作须另附observations:[{channel:"VISUAL",text:"旁人实际看见的具体经过",action:"可见动作名",status:"实际可见的完成情况"}]；process可能含私有体会，不能用visibility:PUBLIC代替旁观描述。无人能看到的过程可为空数组，不靠空话概括真实动作。物理地改变既有环境（例如拨开古碑湿苔、开启门扇）必须在effects中用FACILITY记录原对象的真实状态变化，不能只改BODY或OBSERVE就让环境变化只存在于正文。仅查看未改动环境不填写FACILITY。
        """;
    public Outcome request(LLMProvider provider,List<String> jobs,List<List<Map<String,String>>> inputs,long deadline){
        var groups=new LinkedHashMap<Key,List<Integer>>();var facts=new ArrayList<ObjectNode>();
        for(int i=0;i<inputs.size();i++){
            try{var f=(ObjectNode)JSON.readTree(inputs.get(i).get(1).get("content"));facts.add(f);
                groups.computeIfAbsent(new Key(inputs.get(i).get(0).get("content"),f.path("sceneId").asText(),f.path("snapshotAtSeconds")),k->new ArrayList<>()).add(i);
            }catch(Exception invalid){throw new IllegalArgumentException("invalid frozen world input",invalid);}
        }
        var messages=new ArrayList<List<Map<String,String>>>();var members=new ArrayList<List<Integer>>();var audit=new ArrayList<JsonNode>();
        for(var group:groups.entrySet()){
            var indices=group.getValue();var visibleFacts=new ArrayList<ObjectNode>();
            for(int i:indices){var f=facts.get(i).deepCopy();f.remove("serverEffectBaselines");visibleFacts.add(f);}
            var shared=visibleFacts.get(0).deepCopy();
            var fields=new ArrayList<String>();shared.fieldNames().forEachRemaining(fields::add);
            for(String field:fields){
                if(Set.of("actorId","actorName","attempt","acceptedWork").contains(field) || visibleFacts.stream().anyMatch(f->!Objects.equals(shared.get(field),f.get(field))))shared.remove(field);
            }
            var packet=JSON.createObjectNode();packet.set("sharedFacts",shared);var attempts=packet.putArray("attempts");
            for(int n=0;n<indices.size();n++){var f=visibleFacts.get(n).deepCopy();shared.fieldNames().forEachRemaining(f::remove);var entry=attempts.addObject().put("jobId",jobs.get(indices.get(n)));entry.set("facts",f);}
            messages.add(List.of(Map.of("role","system","content",group.getKey().contract()+"\n"+CONTRACT),Map.of("role","user","content",packet.toString())));members.add(indices);
            audit.add(JSON.createObjectNode().put("sceneId",group.getKey().scene()).set("request",packet));
        }
        var responses=requests.request(provider,messages,deadline,LLMRequestPolicy.ACTION,false);
        var results=new ArrayList<NpcRequestExecutor.Result>(Collections.nCopies(jobs.size(),null));
        for(int g=0;g<members.size();g++){
            var response=responses.get(g);var trace=(ObjectNode)audit.get(g);
            if(response.error()!=null){trace.put("error",response.error().getMessage());for(int i:members.get(g))results.set(i,response);continue;}
            String raw=Objects.toString(response.response().parsedJson()!=null?response.response().parsedJson():response.response().content(),"");trace.put("rawResponse",raw);
            JsonNode envelope;try{envelope=ModelOutput.object(raw);}catch(IllegalArgumentException invalid){envelope=JSON.createObjectNode();}
            for(int i:members.get(g)){
                var matches=new ArrayList<JsonNode>();if(envelope.path("results").isArray())for(var entry:envelope.path("results"))if(jobs.get(i).equals(entry.path("jobId").asText()))matches.add(entry.path("result"));
                // A single frozen job is unambiguous. Accept common envelope omissions,
                // but never guess the recipient of a multi-job or explicitly foreign result.
                if(members.get(g).size()==1 && !envelope.has("results") && (!envelope.has("jobId") || jobs.get(i).equals(envelope.path("jobId").asText()))){
                    if(envelope.path("result").isObject())matches.add(envelope.path("result"));
                    else if(envelope.path("process").isTextual() && envelope.path("effects").isArray())matches.add(envelope);
                }
                if(matches.size()!=1 || !matches.get(0).isObject()){
                    results.set(i,new NpcRequestExecutor.Result(null,new LLMApiException("主API缺少或重复返回原行动结果："+jobs.get(i),false),new LLMUsage.Totals(0,0,0,0)));continue;
                }
                var candidate=bindBodyBaselines(matches.get(0),facts.get(i));String json=candidate.toString();
                results.set(i,new NpcRequestExecutor.Result(LLMResponse.json(json,json,0,0,0),null,new LLMUsage.Totals(0,0,0,0)));
            }
        }
        return new Outcome(List.copyOf(results),List.copyOf(audit));
    }
    /** Bind to frozen facts, never to newer live state. Apply still checks for real conflicts. */
    static JsonNode bindBodyBaselines(JsonNode original,JsonNode facts){
        var candidate=original.deepCopy();var states=facts.path("serverEffectBaselines").deepCopy();
        if(!states.isObject()){states=JSON.createObjectNode();var entries=facts.path("resources").fields();while(entries.hasNext()){var entry=entries.next();((ObjectNode)states).set(entry.getKey(),entry.getValue().path("body").deepCopy());}}
        var placements=new HashMap<String,JsonNode>();
        for(var item:facts.path("resources").path(facts.path("actorId").asText()).path("items"))if(item.path("ref").isTextual() && item.path("position").isTextual())placements.put(item.path("ref").asText(),item.path("position").deepCopy());
        if(candidate.path("effects").isArray())for(var effect:candidate.path("effects")){
            if("ITEM_POSITION".equals(effect.path("kind").asText()) && effect instanceof ObjectNode placement){
                String ref=placement.path("itemRef").asText();
                if(placements.containsKey(ref)){placement.set("before",placements.get(ref).deepCopy());if(placement.path("after").isTextual())placements.put(ref,placement.path("after").deepCopy());}
                continue;
            }
            if(!"BODY".equals(effect.path("kind").asText()) || !effect.path("after").isObject() || !(effect instanceof ObjectNode body))continue;
            String actor=body.path("actorId").asText(facts.path("actorId").asText());if(actor.isBlank())actor=facts.path("actorId").asText();body.put("actorId",actor);
            var before=body.putObject("before");var state=states.path(actor);
            var changes=body.path("after").fields();while(changes.hasNext()){var change=changes.next();if(!Set.of("awake","bound","activity","position","percepts").contains(change.getKey()) || !state.has(change.getKey()))continue;
                before.set(change.getKey(),state.path(change.getKey()).deepCopy());((ObjectNode)state).set(change.getKey(),change.getValue().deepCopy());}
        }
        return candidate;
    }
}
