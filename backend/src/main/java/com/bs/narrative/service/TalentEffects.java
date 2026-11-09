package com.bs.narrative.service;
import com.bs.narrative.domain.*;
import com.bs.narrative.llm.ModelOutput;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.*;
/** Opening stat changes are numeric; situational talents are retained author context, never automatic outcomes. */
public final class TalentEffects {
    private TalentEffects() {}
    private static final String[] KEYS={"hui","dou","wen","zhi"};
    private static final Map<String,String> LABELS=Map.of("zhi","知","hui","慧","dou","斗","wen","闻");
    private static final String NARRATIVE_MARKER="【情境天赋】";
    public static final String STORY_RULES="天赋是已选定的有限情境特长。相关场景要实际考虑它能带来的观察机会、做事方法或适应优势，不只提名字，也不必每段刻意触发；场景无关时自然略过。按卡面发挥时机、边界与代价结合已有能力、环境和对手描写，优势不是必然成功，不凭天赋名追加能力或信息，不让他人无故知晓私有天赋。STAT的开局数值已经计入六维，不再重复加点；NARRATIVE的特殊作用由唯一主创写出实际经过，不另掷检定，不凭文案直接改生命、六维、过去事实或固定世界规则。";
    public static String description(JsonNode candidate,int power,int index){
        var effect=candidate.path("special_effect");
        String benefit=ModelOutput.text(effect,"benefit","",300);
        // Old paid candidates remain usable without redrawing their choices.
        if(!effect.isObject() || benefit.isBlank())return description(candidate.path("stat_key").asText(""),power,index);
        String key=candidate.path("stat_key").asText("");
        String stat=LABELS.containsKey(key)?statDescription(key,Math.max(1,Math.min(2,power)))+"\n":"";
        String trigger=ModelOutput.text(effect,"trigger","与这项特长直接相关、且具备实际尝试条件时",120);
        String limits=ModelOutput.text(effect,"limits","只提供适度优势，不能凭空获取信息或保证结果，仍受自身能力与环境限制",240);
        String tradeoff=ModelOutput.text(effect,"tradeoff","",120);
        return stat+NARRATIVE_MARKER+benefit+"\n发挥时机："+trigger+"\n作用边界："+limits+(tradeoff.isBlank()?"":"\n代价与干扰："+tradeoff);
    }
    public static String description(String key,int power,int index) {
        if(!LABELS.containsKey(key))key=KEYS[index%KEYS.length];
        int bonus=Math.max(1,Math.min(3,power))*2;
        return statDescription(key,bonus);
    }
    private static String statDescription(String key,int bonus){return "【开局增益："+key+"+"+bonus+"】选定后，基础"+LABELS.get(key)+"增加"+bonus+"点，实际属性依六维公式计算。效果在入世时生效一次。";}
    public static String kind(Talent talent){
        String description=Objects.toString(talent.getTalentDescription(),"");
        if(description.startsWith(NARRATIVE_MARKER) || description.startsWith("【开局增益：") && description.contains("\n"+NARRATIVE_MARKER))return "NARRATIVE";
        return description.startsWith("【开局增益：")?"STAT":"LEGACY";
    }
    public static Map<String,Object> view(Talent talent){return Map.of("name",Objects.toString(talent.getTalentName(),""),"description",Objects.toString(talent.getTalentDescription(),""),"power",Objects.requireNonNullElse(talent.getPowerLevel(),1),"effectKind",kind(talent));}
    public static void apply(Talent talent,GodProfile god) {
        var m=Pattern.compile("^\\Q【开局增益：\\E(zhi|hui|dou|wen)\\+([1-6])\\Q】\\E").matcher(talent.getTalentDescription());
        if(!m.find())return;
        int n=Integer.parseInt(m.group(2));
        switch(m.group(1)) {case "zhi"->god.setBaseZhi(god.getBaseZhi()+n);case "hui"->god.setBaseHui(god.getBaseHui()+n);case "dou"->god.setBaseDou(god.getBaseDou()+n);case "wen"->god.setBaseWen(god.getBaseWen()+n);}
    }
}
