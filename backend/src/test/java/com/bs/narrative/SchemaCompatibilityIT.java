package com.bs.narrative;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.PlayerProfileRepository;
import com.bs.narrative.repository.InteractionLogRepository;
import com.bs.narrative.dto.ActionRequest;
import com.bs.narrative.llm.*;
import com.bs.narrative.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Explicit opt-in integration check; reads the local schema without calling LLMs. */
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false"})
@Transactional(readOnly = true)
class SchemaCompatibilityIT {
    @Autowired EntityManager entityManager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired SessionStateService state;
    @Autowired PlayerProfileRepository playerRepo;
    @Autowired GameActionService actionService;
    @Autowired InteractionLogRepository logRepo;
    @MockBean Pass1Service pass1Service;
    @MockBean Pass2Service pass2Service;
    @MockBean PlayerObjectiveService objectiveService;
    @MockBean NpcAgentService npcAgentService;

    @Test void everyMappedEntityCanBeReadWithoutChangingTheSchema() {
        var entities = entityManager.getMetamodel().getEntities();
        assertEquals(16, entities.size());
        for (var entity : entities) {
            entityManager.createQuery("select e from " + entity.getName() + " e")
                    .setMaxResults(1).getResultList();
        }
        var player = playerRepo.findById("p_demo").orElseThrow();
        assertNotNull(player.getGodId());
        assertNotNull(player.getCurrentSceneId());
        assertNotNull(entityManager.find(GodProfile.class,player.getGodId()));
    }

    /** Uses real MySQL repositories and engine; test transaction rolls all state changes back. */
    @Test @Transactional
    void freeTextActionPersistsMappedRoleAndAccountWithoutCallingRemoteModels() {
        String pid="schema_it_user",sid="schema_it",role="schema_it_role",scene="schema_it_scene",npc="schema_it_npc";
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_dou,base_gui) VALUES(?,?,'测试行者','MORTAL','坚定',30,75),(?,?,'测试守桥人','MORTAL','平静',10,75)",role,sid,npc,sid);
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'验收水岸',?,'独立测试场景',0,0)",scene,sid,"[\""+npc+"\"]");
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,?,'schema_test','unused',?,?)",pid,role,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status) VALUES(?,?,'PLAYING')",sid,pid);state.initialize(sid,pid);
        var player=playerRepo.findById(pid).orElseThrow();

        var intent = new ActionRequest.StructuredIntent("ATTACK", player.getGodId(), npc,
                "dou", null, 1.0, "攻击雨神");
        when(pass1Service.extractIntent(eq(player.getGodId()), eq(scene),
                eq("攻击雨神"), anyList(), anyString()))
                .thenReturn(new Pass1Service.Pass1Result(intent, null, Pass1Service.Status.SUCCESS, "test"));
        when(pass2Service.generateEnsembleNarrativeWithMemory(anyString(), anyString(), anyString(),
                anyList(), anyList(), anyString(), anyString()))
                .thenReturn(new Pass2Service.Pass2Result(List.of(), Map.of(), null,
                        Pass2Service.Status.SUCCESS, "test", null));
        var response = actionService.processActionWithDb(
                new ActionRequest(pid,scene,"攻击雨神",null),sid);
        assertNotEquals("INVALID", response.outcomeType());
        assertNotNull(response.logId());
        entityManager.flush();
        entityManager.clear();
        var persisted = logRepo.findById(response.logId()).orElseThrow();
        assertEquals(pid,persisted.getPlayerId());
        assertTrue(persisted.getTrueIntent().contains("actorGodId="+role));
        assertTrue(((Map<?, ?>) persisted.getRuleSnapshot().get("before_actuals_per_god"))
                .containsKey(role));
        assertTrue(persisted.getRoundNo() > 0);
    }
}
