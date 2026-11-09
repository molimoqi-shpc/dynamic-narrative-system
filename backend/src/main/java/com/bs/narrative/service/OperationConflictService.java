package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.GodProfileRepository;
import com.bs.narrative.engine.*;
import com.bs.narrative.engine.enums.*;
import com.bs.narrative.engine.model.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** The approved P4 comparison, separated from role choices and legacy name-based magic. */
@Service
public class OperationConflictService {
    @org.springframework.beans.factory.annotation.Autowired private VitalStateService vitality;
    @org.springframework.beans.factory.annotation.Autowired private CharacterStatService characterStats;
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private final GodProfileRepository actors;private final OpenSceneService physical;private final JdbcTemplate jdbc;
    public OperationConflictService(GodProfileRepository actors,OpenSceneService physical,JdbcTemplate jdbc){this.actors=actors;this.physical=physical;this.jdbc=jdbc;}
    private GodProfile actor(String sid,String id){return actors.findById(id).filter(a->sid.equals(a.getSessionId())).orElseThrow(()->new IllegalArgumentException("contest actor unavailable"));}
    public void validate(JsonNode c){
        if(!c.isObject() || !"ATTACK".equals(c.path("kind").asText()) || !"DOU".equals(c.path("statKey").asText("DOU")) || c.path("targetId").asText().isBlank())throw new IllegalArgumentException("contest mechanism unsupported");
    }
    public Map<String,Object> proposal(String sid,GameScene scene,String role,String player,JsonNode contest){
        return proposal(sid,scene,role,player,contest,false);
    }
    public Map<String,Object> proposal(String sid,GameScene scene,String role,String player,JsonNode contest,boolean authored){
        validate(contest);String target=contest.path("targetId").asText();if(role.equals(target))throw new IllegalArgumentException("self contest");
        var a=actor(sid,role);var b=actor(sid,target);
        if(authored && (!characterStats.established(sid,role) || !characterStats.established(sid,target)))return Map.of("status","NARRATIVE","targetId",target,"reason","人物的具体六维尚未确立，中性占位不是能力；依据人物档案与意愿创作实际结果。");
        if(!authored && (!present(sid,scene,role,player) || !present(sid,scene,target,player)))return Map.of("status","UNREACHABLE","targetId",target,"basis","目标当前不在这个现场，原先在场的事实不能证明仍可触及");
        var bodyA=physical.view(sid,a,scene);var bodyB=physical.view(sid,b,scene);
        if(!authored && (!Boolean.TRUE.equals(bodyA.get("awake")) || !Boolean.TRUE.equals(bodyB.get("awake"))))throw new IllegalArgumentException("active opposed-combat mechanism requires conscious participants");
        var statsA=StatsCalculator.calcActualStats(a);var statsB=StatsCalculator.calcActualStats(b);int difference=statsA.actualDou()-statsB.actualDou(),threshold=ConflictResolver.clearWinThreshold(statsA.actualDou(),statsB.actualDou());
        String result=difference>=threshold?"ATTACKER_WIN":difference<=-threshold?"DEFENDER_WIN":"DRAW";
        var health=authored?Map.of("actorBefore",vitality.health(sid,role),"targetBefore",vitality.health(sid,target)):vitality.preview(sid,role,target,result);
        var basis=new LinkedHashMap<String,Object>();basis.put("actor",role);basis.put("target",target);basis.put("actualActor",statsA.actualsAsMap());basis.put("actualTarget",statsB.actualsAsMap());
        if(!authored){basis.put("actorBody",Map.of("awake",bodyA.get("awake"),"bound",bodyA.get("bound"),"position",bodyA.get("position")));basis.put("targetBody",Map.of("awake",bodyB.get("awake"),"bound",bodyB.get("bound"),"position",bodyB.get("position")));}
        if(!authored)basis.put("health",health);
        var comparison=Map.of("actorActualDou",statsA.actualDou(),"targetActualDou",statsB.actualDou(),"difference",difference,"threshold",threshold,"rule","差值≥阈值攻方胜；差值≤负阈值守方胜；其余平局。阈值=max(10,round(0.10×双方实际斗之和))。");
        try{
            var settled=new LinkedHashMap<String,Object>(Map.of("status","RESOLVED","kind","ATTACK","targetId",target,"statKey","DOU","outcome",result,"threshold",threshold,"fingerprint",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(basis))),"mechanicsVersion",StatsCalculator.FORMULA_VERSION,"health",health,"comparison",comparison));
            settled.put("physicalConsequence",authored?"此参考只确定交手胜负，不预设伤势或处置，不固定扣血。health仅为战前生命。按双方combatDisposition、性格、过去、关系与现场意愿创作完整后果：切磋可以点到为止、认输与指点而不受伤；敌对胜者可放行、俘获、夺物或杀害，旁人救援是否发生及成败同样由主创据其本人意愿与实际情境创作，不保证救援或生还。仅实际受伤、治疗、死亡时用HEALTH保存最终生命，死亡写0；切磋认输、受制或被俘不等于死亡。actorId仍为攻方，targetId仍为守方，不按获胜方互换。":"仅当本场实际发生时保存：攻方"+role+"生命"+health.get("actorBefore")+"→"+health.get("actorAfter")+"；守方"+target+"生命"+health.get("targetBefore")+"→"+health.get("targetAfter")+"。CONTEST包含旧版损耗，不另附HEALTH重复扣血。");
            return settled;
        }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public void apply(String sid,GameScene scene,String role,String player,JsonNode effect,Set<String> claimed){
        apply(sid,scene,role,player,effect,claimed,false);
    }
    public void apply(String sid,GameScene scene,String role,String player,JsonNode effect,Set<String> claimed,boolean authored){
        var c=JSON.createObjectNode().put("kind","ATTACK").put("targetId",effect.path("targetId").asText()).put("statKey","DOU");var current=proposal(sid,scene,role,player,c,authored);
        if(!"RESOLVED".equals(current.get("status")) || !Objects.equals(current.get("fingerprint"),effect.path("fingerprint").asText()) || !Objects.equals(current.get("outcome"),effect.path("outcome").asText()))throw new IllegalArgumentException("contest facts changed; re-settle with current facts");
        String target=c.path("targetId").asText();for(String id:List.of(role,target))if(!claimed.add("stats:"+id))throw new IllegalArgumentException("simultaneous contested stats need joint resolution");
        OutcomeType outcome=OutcomeType.valueOf(current.get("outcome").toString());var deltas=new LinkedHashMap<String,StatDelta>();
        if(outcome==OutcomeType.DRAW){deltas.put(role,new StatDelta(0,0,-1,0,0,0));deltas.put(target,new StatDelta(0,0,-1,0,0,0));}
        else{String winner=outcome==OutcomeType.ATTACKER_WIN?role:target,loser=winner.equals(role)?target:role;deltas.put(winner,new StatDelta(0,0,2,1,0,0));deltas.put(loser,new StatDelta(0,0,-2,-1,0,0));}
        var profiles=Map.of(role,actor(sid,role),target,actor(sid,target));
        var result=new ResolutionResult(outcome,null,null,null,deltas,Map.of(),List.of(),"");DeltaApplier.applyAll(result,profiles,List.of());actors.saveAll(profiles.values());
        if(!authored)vitality.applyContest(sid,role,target,outcome.name());
    }
    private boolean present(String sid,GameScene s,String role,String player){return role.equals(player)?jdbc.queryForObject("SELECT COUNT(*) FROM player_profile WHERE active_session_id=? AND god_id=? AND current_scene_id=?",Integer.class,sid,role,s.getSceneId())>0:s.getPresentGods().contains(role);}
}
