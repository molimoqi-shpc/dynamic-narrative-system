package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Immutable, session-scoped public world charter, separate from changing story facts. */
@Service
public class WorldSettingService {
    @org.springframework.beans.factory.annotation.Autowired private CreationModelExchange exchanges;
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final LLMProvider model;
    public WorldSettingService(JdbcTemplate jdbc,LLMProvider model){this.jdbc=jdbc;this.model=model;}
    public static final String GENERATE_PROMPT="""
        你是互动小说的世界主创。先创建一个适合长期生活、探索和人物关系发展的原创世界，再提出三个与该世界相符的角色创建问题。
        题材可为东方历史或幻想、西方历史或幻想、现代悬疑、科幻等；每局选择一个清楚的时代与文化基调，不默认神明真实存在，不强制主角是神裔或有神职。
        世界有普通人和独立社会，不以主角为中心。只确立世界的基本背景、时代、文化、技术或超自然能力边界；不要预写主角身世、胜负、未来剧情、终局或谜底。
        introduction是给玩家阅读的400到700字开篇世界介绍，不剧透隐藏历史。summary是150到300字的固定简略世界观，用于所有后续提示词，包括时代文化、技术/能力边界、日常生活与基本矛盾。
        title是本局独有的中文世界名，四到六个汉字，有文学意象、辨识度并贴合本世界的核心气质。可化用适合的诗文、典籍或神话意象，东方、西方、现代等题材各自选取相符的文化来源，不机械堆砌玄幻词，不固定叫“神明之野”，也不照搬现成作品标题。namingBasis用一两句说明名字意象与世界的联系；若确实引用典籍须写准确出处，不确定原文时只说明意象，不能伪造名句或出处。命名在这一次世界生成内完成，后续固定沿用，不为命名单独调用模型。
        三个问题分别询问姓名/性别/年龄与出身、重视的事、面对困境的选择，语气和可选例子符合本世界，不强迫玩家接受某个身份。题目必须正好三个。
        仅返回JSON：{"title":"四到六字世界名","namingBasis":"命名意象与准确出处（如有）","genre":"题材","culturalStyle":"EASTERN/WESTERN/MODERN/SCIFI/MIXED","era":"时代和社会形态","introduction":"公开世界介绍","summary":"固定简略世界观","boundaries":["已确立的技术与超自然边界"],"questions":["第一问","第二问","第三问"]}。
        culturalStyle用于美术风格，不代表人物性别或种族。MIXED仅用于明确建立多文化共存的世界。全部内容为原创设定，不使用现成作品人物或地名。
        """;
    public ObjectNode find(String sid){
        if(sid==null || sid.isBlank())return JSON.createObjectNode();
        var rows=jdbc.queryForList("SELECT definition FROM session_world WHERE session_id=?",String.class,sid);
        if(rows.isEmpty())return JSON.createObjectNode();
        try{return (ObjectNode)JSON.readTree(rows.get(0));}catch(Exception e){throw new IllegalStateException("世界设定记录无法读取",e);}
    }
    /** Caller holds the session creation lock; retry never redraws an established world. */
    public ObjectNode generate(String sid){
        return generate(sid,null);
    }
    public ObjectNode generate(String sid,String theme){
        var saved=find(sid);if(!saved.isEmpty())return saved;
        String direction=switch(theme==null?"":theme){
            case "WESTERN" -> "玩家选择【西幻】：西方文化的原创幻想世界，可含古典或早期工业时代，魔法、异族等是否存在及其边界由你确立。culturalStyle必须为WESTERN，不混入东方修仙体系。";
            case "EASTERN" -> "玩家选择【中幻】：中国文化意象的原创幻想世界，时代、民俗、武学或超自然体系由你确立。culturalStyle必须为EASTERN，不混入西式骑士精灵体系。";
            case "MODERN" -> "玩家选择【现代】：原创现代社会，交通、通信、职业与生活符合现代；如有幻想或悬疑要素，请明确边界。culturalStyle必须为MODERN，不无故切入古典奇幻。";
            case "" -> "题材由你选择。";
            default -> throw new IllegalArgumentException("请选择西幻、中幻或现代");
        };
        var reply=exchanges.request(sid,ModelProtocol.Stage.WORLD,List.of(Map.of("role","system","content",GENERATE_PROMPT),
            Map.of("role","user","content",direction+"\n为这个新存档创建一份原创且自洽的世界设定。地点、矛盾与人物生活应有新意，不重复固定车站或固定人物。先定世界，再问角色。只输出JSON。")),model::chatMainForJson);
        var value=normalize(ModelProtocol.object(ModelProtocol.Stage.WORLD,reply.parsedJson()!=null?reply.parsedJson():reply.content()));
        if(theme!=null)value.put("selectedTheme",theme);
        jdbc.update("INSERT INTO session_world(session_id,definition) VALUES(?,?)",sid,value.toString());
        return value;
    }
    static ObjectNode normalize(JsonNode raw){
        var out=JSON.createObjectNode();
        for(String field:List.of("title","genre","culturalStyle","era","introduction","summary")){
            String value=ModelOutput.cap(raw.path(field).asText().trim(),field.equals("introduction")?7000:field.equals("summary")?1600:160);
            if(value.isBlank())throw new IllegalArgumentException("世界设定缺少："+field);
            out.put(field,value);
        }
        String style=out.path("culturalStyle").asText().toUpperCase(Locale.ROOT);
        if(!Set.of("EASTERN","WESTERN","MODERN","SCIFI","MIXED").contains(style))style="MIXED";
        out.put("culturalStyle",style);
        var qs=ModelOutput.names(raw.path("questions"),3,600);
        if(qs.size()!=3)throw new IllegalArgumentException("世界设定需要三个角色问题");
        out.set("questions",JSON.valueToTree(qs));
        out.set("boundaries",JSON.valueToTree(ModelOutput.names(raw.path("boundaries"),8,300)));
        String namingBasis=ModelOutput.cap(raw.path("namingBasis").asText().trim(),600);
        if(!namingBasis.isBlank())out.put("namingBasis",namingBasis);
        out.put("version",1);return out;
    }
    public ObjectNode brief(String sid){
        var saved=find(sid);var out=JSON.createObjectNode();
        if(!saved.isEmpty())for(String key:List.of("title","genre","culturalStyle","era","summary","boundaries","namingBasis"))out.set(key,saved.path(key).deepCopy());
        return out;
    }
    public String prompt(String sid){
        var value=brief(sid);return value.isEmpty()?"":"\n【本存档固定世界观】\n"+value+"\n时代、文化、技术与能力边界按此设定承接；后续故事扩展世界内的细节，不将其他题材机制无因混入。此背景不授予角色未亲历的秘密，也不预定行动成败。\n";
    }
    public String promptForScene(String sceneId){
        var ids=jdbc.queryForList("SELECT session_id FROM game_scene WHERE scene_id=?",String.class,sceneId);
        return ids.isEmpty()?"":prompt(ids.get(0));
    }
    public void inherit(String sid,String previous){
        var world=find(previous);if(!world.isEmpty() && find(sid).isEmpty())jdbc.update("INSERT INTO session_world(session_id,definition) VALUES(?,?)",sid,world.toString());
    }
}
