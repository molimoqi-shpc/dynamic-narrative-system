package com.bs.narrative;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.engine.*;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class StatFormulaParityIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GameActionService game;
    @Autowired WorldBookService world;
    @Autowired InquiryService inquiry;
    final String sid="formula_v2_test";
    @Test void independentMySqlFormulaMatchesAllDimensionsAcrossBoundaries() throws Exception {
        Map<String,GodProfile> profiles=new LinkedHashMap<>();Random random=new Random(20261007);
        for(int i=0;i<85;i++) {
            int[] values=i<5 ? new int[]{0,0,0,0,0,0} : new int[]{random.nextInt(10001),random.nextInt(10001),random.nextInt(10001),random.nextInt(10001),random.nextInt(101),random.nextInt(10001)};
            if(i<5) Arrays.fill(values,new int[]{0,1,99,100,101}[i]);
            var p=new GodProfile();p.setGodId("formula_v2_"+i);p.setBaseZhi(values[0]);p.setBaseHui(values[1]);p.setBaseDou(values[2]);p.setBaseWen(values[3]);p.setBaseBen(values[4]);p.setBaseGui(values[5]);profiles.put(p.getGodId(),p);
            jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui) VALUES(?,?,'公式验收','MORTAL','平静',?,?,?,?,?,?)",p.getGodId(),sid,values[0],values[1],values[2],values[3],Math.min(100,values[4]),values[5]);
        }
        String sql=new String(getClass().getResourceAsStream("/sql/verify-six-stats-v2.sql").readAllBytes(),StandardCharsets.UTF_8).replace("FROM god_profile","FROM god_profile WHERE session_id=?");
        var rows=jdbc.queryForList(sql,sid);assertEquals(profiles.size(),rows.size());
        for(var row:rows) {
            var actual=StatsCalculator.calcActualStats(profiles.get(row.get("god_id")));
            for(var key:StatKey.values()) assertEquals(actual.actualOf(key),((Number)row.get("actual_"+key.pinyin)).intValue(),row.get("god_id")+" "+key);
        }
    }
    @Test void sceneFormulaAndInquiryUseTheSameServerActualValues() {
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_zhi,base_hui,base_dou,base_wen,base_ben,base_gui) VALUES('formula_v2_player',?,'公式验收','MORTAL','平静',35,26,22,30,50,20)",sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES('formula_v2_scene',?,'验收水岸','[]','水岸静谧',0,0)",sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES('formula_v2_account','formula_v2_player','formula_v2_account','unused','formula_v2_scene',?)",sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,'formula_v2_account','PLAYING')",sid);
        var scene=game.describeScene("formula_v2_account",sid);
        assertEquals(Map.of("zhi",49,"hui",36,"dou",30,"wen",42,"ben",53,"gui",21),scene.get("playerActualStats"));
        var book=(Map<String,Object>)scene.get("playerFormula");assertEquals("six-stats-v2",book.get("version"));
        jdbc.update("INSERT INTO world_book(session_id,round_no,coord_x,coord_y,location_name,event_type,event_description,importance,is_secret) VALUES(?,0,0,0,'验收水岸','ACTION','公开消息',1,0)",sid);
        inquiry.processInquiry(sid,"formula_v2_player",0,0);
        assertEquals(StatsCalculator.inquiryAccuracy(49,36),jdbc.queryForObject("SELECT accuracy FROM npc_memory WHERE god_id='formula_v2_player' AND session_id=?",Integer.class,sid));
    }
}
