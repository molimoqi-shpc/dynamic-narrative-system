package com.bs.narrative.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.bs.narrative.engine.enums.StatKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Distinguishes authored abilities from persistence placeholders; never decides a character's will. */
@Service
public class CharacterStatService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public CharacterStatService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    public static final String PROMPT="""
        【六维在故事中的作用】sixStats是服务器按six-stats-v2公式计算并四舍五入后的实际值，已含成长、神职感悟与协同，不再乘一次本或自行重算。知(zhi)体现知识与信息储备，慧(hui)体现推理、辨析与运用线索，斗(dou)体现交手能力，闻(wen)体现观察与局势感知，本(ben)是神职感悟度，归(gui)是忠诚意志与坚持，不是财富、诡计或对玩家的好感。
        六维都要在相关情节中产生可读的作用，不只是人物面板数字；结合本段实际事情选择相关能力，不为凑齐六项而硬塞六段展示。主创临写资料中的sixStatRanges提供已确立实际值的最小值、最大值与差距；差距明显时，人物在同类事情上的表现也应明显不同，不能让所有人观察、理解、应变与坚持都一样。差距由你结合具体处境理解，不套固定成功率或剧情门槛；低值仍有正常能力，可借助经验、协作和核实推进事情。
        知高：更能运用本人已有知识、辨认相关事物、联系已知历史与经验，并把知识用在当前问题上；知低者更常询问、对照或依赖熟悉者。区分知识储备与推理能力，高知不能凭空知道未接触的秘密、陌生人的身世或真实档案。
        慧高：更善于从实际证据发现因果、辨别矛盾、比较解释、识别漏洞并想到有效办法，判断清楚哪些已证实、哪些尚属猜测；慧低者可以较直观、需更多解释，但不强写愚蠢。高慧不读心，不把合理猜测直接升级为事实。
        斗高：交手中体现身手、力量运用、步法、反应和应对能力，非交手时也可在实际需要的身体技艺与自保中表现熟练；斗低者可笨拙、吃力、借助工具或求助。真实交手的胜负与损耗仍依下述服务器公式，不用漂亮描写抵消数值结果，也不把高斗写成无敌或已知之外的特殊能力。
        闻高：应当更加明察秋毫，在本人实际可见、可听、可感知的范围内，更容易先注意到细微痕迹、声音差异、神情变化、环境异常与局势动向。闻差距很大时，可以让高闻者指出低闻者尚未留意的具体细节，并让该发现帮助调查、应对或避免疏忽；不能只写一句“观察敏锐”后仍让所有发现都由别人代劳。闻侧重察觉，慧侧重解释；高闻不自动知道细节背后的真相，更不能隔墙读心、远处全知。
        本高：对本人已有神职、修行、力量运用和相关体验有更深感悟，更容易理解已有传承或经验中的要领，在实际修习与运用中体现理解深度；本低者可通过实践、请教和体会逐渐理解。本已在实际值中提供感悟加成，不再乘一次，不凭本高就新造法术、境界、神格或跳过修习直接突破。
        归高：在恐惧、诱惑、挫折、外界压力或利益冲突下更能坚持本人已确立的信念、承诺与选择，表现出定力和自制；归低者更可能犹疑、动摇、权衡或寻求支持。坚持的对象来自本人档案，不自动忠于玩家，不强迫接受结盟或表白，不因低归就必然背叛；充分的新事实仍可使高归者合理改变立场。
        用具体发现、办法、行动经过和关系后果体现能力，而不是在正文报数或照抄属性说明。人物的知识、实际证据、身体、经验与本人意愿共同影响表现；留意到的线索也可以由主创在符合已确立环境的调查中自然展开，不因未逐件登记就停止故事。私有比较资料不成为人物互知的属性表，不替玩家补定未表达的心理或重大立场。
        已确立六维的双方发生实质交手，依据serverContests结算胜负；尚未提供时先请求现有mechanics。角色API的contest意愿可在正文创作前提供公式参考，不增加一次主创调用。comparison展示实际斗、差值和阈值，health仅为当前生命，不是预设扣血。主创决定是否真正交手；发生时写出与公式一致的胜负并用CONTEST保存，不用巧合或FACT文字绕开公式。胜负不等于伤亡：结合双方combatDisposition与现场意愿创作切磋分寸、败后处置和可能救援；切磋可以认输、受制、被指点而没有生命损伤；敌对失败也不保证存活。实际受伤、治疗、死亡才用HEALTH保存最终生命，死亡写0，不能只报一串扣血就结束完整事件。未交手不登记CONTEST。serverContests不是已发生历史。sixStatsEstablished=false表示数值尚未确立，空表不是零能力；收到NARRATIVE时由主创依人设与意愿创作，不拿中性占位数裁定强弱。
        """;

    /** Exact ranges help the author read differences; there are no ability tiers or success gates. */
    static ObjectNode authorRanges(JsonNode dossiers){
        var ranges=JSON.createObjectNode();
        for(StatKey key:StatKey.values()){
            int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,count=0;
            for(var dossier:dossiers){
                var identity=dossier.path("identity");var value=identity.path("sixStats").path(key.pinyin);
                if(!identity.path("sixStatsEstablished").asBoolean(true) || !value.isIntegralNumber())continue;
                int actual=value.asInt();min=Math.min(min,actual);max=Math.max(max,actual);count++;
            }
            if(count>=2)ranges.putObject(key.pinyin).put("minActual",min).put("maxActual",max).put("gap",max-min);
        }
        return ranges;
    }

    public boolean established(String sid,String role){
        for(String raw:jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_type='SELF_PROFILE'",String.class,sid,role)){
            try{
                var profile=JSON.readTree(raw);
                if(profile==null || !profile.isObject())continue;
                if(profile.has("sixStatsEstablished")){
                    if(profile.path("sixStatsEstablished").isBoolean() && !profile.path("sixStatsEstablished").asBoolean())return false;
                }else if(profile.has("statsMeaning"))return false; // Old authored profiles already marked their neutral placeholders.
            }catch(com.fasterxml.jackson.core.JsonProcessingException legacyProse){/* Plain established backstory is not a placeholder marker. */}
        }
        return true;
    }
}
