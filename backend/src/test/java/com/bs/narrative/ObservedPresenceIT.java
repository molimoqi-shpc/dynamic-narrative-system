package com.bs.narrative;

import com.bs.narrative.llm.LLMProvider;
import com.bs.narrative.repository.*;
import com.bs.narrative.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Observations are delivered facts: disappearance is not proof of departure or a hidden destination. */
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class ObservedPresenceIT {
    @Autowired PlayerSceneProjectionService projection;
    @Autowired KnownStateService known;
    @Autowired JdbcTemplate jdbc;
    @Autowired GameSceneRepository scenes;
    @MockBean LLMProvider model;
    final ObjectMapper json=new ObjectMapper();
    String sid,observer,npc,other,scene;
    @BeforeEach void seed(){
        String tag=UUID.randomUUID().toString().substring(0,8);
        sid="presence_"+tag;observer="po_"+tag;npc="pn_"+tag;other="px_"+tag;scene="ps_"+tag;
        for(String id:List.of(observer,npc,other))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','平静','[]')",id,sid,id.equals(observer)?"旅人":id.equals(npc)?"守门人":"路人");
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'门内',JSON_ARRAY(?,?,?),'旅人、守门人和路人在门内。',0,0)",scene,sid,observer,npc,other);
        projection.enter(sid,scenes.findById(scene).orElseThrow(),observer,0);
    }
    void event(String ref,long seconds,Object... effects)throws Exception{
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'已送达事实',?,'TURN_EVENT','',?,'门内',0)",sid,observer,ref,json.writeValueAsString(Map.of("endSeconds",seconds,"effects",List.of(effects))),scene);
    }
    Map<String,Object> fact(String id,String field){return known.current(sid,observer,scene).stream().filter(v->id.equals(v.get("subject")) && field.equals(v.get("field"))).findFirst().orElseThrow();}
    void hide()throws Exception{
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'真实隐藏',?,'SCENE_VIEW','',?,'门内',0)",sid,npc,"view:"+scene,json.writeValueAsString(Map.of("hidden",true,"position","未知暗处","activity","秘密开锁")),scene);
    }
    @AfterEach void freeOfModelCalls(){verifyNoInteractions(model);}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"false,亲见变化","true,亲见变化","false,亲见到来","true,亲见到来"})
    void pastVisualProcessIsKeptAsHistoryInsteadOfContinuingActivity(boolean modern,String basis)throws Exception{
        String process="守门人已收起纸张，完成了检查。";var notice=new LinkedHashMap<String,Object>();notice.put("id",npc);notice.put("name","守门人");notice.put("presence","LAST_SEEN");notice.put("position","门边");notice.put("observedAtSeconds",5);notice.put("basis",basis);notice.put("activity",modern?"":process);
        if(modern){notice.put("activityHistorical",true);notice.put("lastObservedProcess",process);}
        String ref="observed:"+scene+":"+npc;jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND god_id=? AND source_ref=?",json.writeValueAsString(notice),sid,observer,ref);
        event("finished-process",5,Map.of("kind","BODY","actorId",npc,"after",Map.of("position","门边")));
        var body=projection.bodies(sid,scenes.findById(scene).orElseThrow(),observer).stream().filter(v->npc.equals(v.get("id"))).findFirst().orElseThrow();
        assertEquals("",body.get("activity"));assertEquals(process,body.get("lastObservedProcess"));assertEquals("",fact(npc,"activity").get("value"));assertEquals("门边",fact(npc,"position").get("value"));
        assertEquals(json.readTree(json.writeValueAsString(notice)),json.readTree(jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",String.class,sid,observer,ref)),"reading legacy evidence must not rewrite it");
        event("actual-own-posture",6,Map.of("kind","BODY","actorId",observer,"after",Map.of("activity","右手按住自己的伤口")));
        assertEquals("右手按住自己的伤口",fact(observer,"activity").get("value"),"actual bodily state is not erased by historical prose normalization");
    }
    @Test void lookingAgainRecordsNotSeenRatherThanReusingOldPresence()throws Exception{
        hide();projection.enter(sid,scenes.findById(scene).orElseThrow(),observer,10);
        assertEquals("NOT_SEEN",fact(npc,"presence").get("value"));
        assertEquals(10L,fact(npc,"presence").get("occurredAtSeconds"));
        assertFalse(projection.bodies(sid,scenes.findById(scene).orElseThrow(),observer).toString().contains(npc));
        assertTrue(projection.bodies(sid,scenes.findById(scene).orElseThrow(),observer).toString().contains(other));
        assertFalse(known.current(sid,observer,scene).toString().contains("未知暗处"));
        assertFalse(known.current(sid,observer,scene).toString().contains("秘密开锁"));
    }
    @Test void disappearanceDoesNotKeepOldPositionOrLetDelayedHistoryReviveIt()throws Exception{
        hide();projection.enter(sid,scenes.findById(scene).orElseThrow(),observer,10);
        event("late-old-position",2,Map.of("kind","BODY","actorId",npc,"after",Map.of("position","门边旧位置","awake",true)));
        assertEquals("NOT_SEEN",fact(npc,"presence").get("value"));
        assertTrue(known.current(sid,observer,scene).stream().noneMatch(v->npc.equals(v.get("subject")) && Set.of("position","awake","bound","activity").contains(v.get("field"))));
    }
    @Test void witnessedDepartureInvalidatesThePersonsLocalBodyFacts()throws Exception{
        event("departure",5,Map.of("kind","MOVE","actorId",npc,"fromSceneId",scene));
        assertEquals("DEPARTED",fact(npc,"presence").get("value"));
        assertTrue(known.current(sid,observer,scene).stream().noneMatch(v->npc.equals(v.get("subject")) && !"presence".equals(v.get("field"))));
        assertEquals("LAST_SEEN",fact(other,"presence").get("value"));
    }
    @Test void aNewActualSightingRestoresBodyFactsAfterDisappearance()throws Exception{
        hide();projection.enter(sid,scenes.findById(scene).orElseThrow(),observer,10);
        assertEquals("NOT_SEEN",fact(npc,"presence").get("value"));
        jdbc.update("UPDATE role_knowledge SET content=? WHERE session_id=? AND god_id=? AND source_ref=?",json.writeValueAsString(Map.of("hidden",false,"position","门前石阶")),sid,npc,"view:"+scene);
        projection.enter(sid,scenes.findById(scene).orElseThrow(),observer,20);
        assertEquals("LAST_SEEN",fact(npc,"presence").get("value"));assertEquals("门前石阶",fact(npc,"position").get("value"));
        assertTrue(projection.bodies(sid,scenes.findById(scene).orElseThrow(),observer).toString().contains(npc));
    }
}
