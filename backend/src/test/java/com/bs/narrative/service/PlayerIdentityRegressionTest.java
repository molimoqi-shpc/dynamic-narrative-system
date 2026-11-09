package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.dto.*;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class PlayerIdentityRegressionTest {
    @Spy private RuleEngineFacadeService ruleEngine = new RuleEngineFacadeService();
    @Mock private GameSceneRepository sceneRepo;
    @Mock private GodProfileRepository godRepo;
    @Mock private GodRelationshipRepository relRepo;
    @Mock private InteractionLogRepository logRepo;
    @Mock private SceneConnectionRepository connRepo;
    @Mock private PlayerObjectiveRepository objRepo;
    @Mock private PlayerProfileRepository playerRepo;
    @Mock private StorySummaryService storySummaryService;
    @Mock private Pass1Service pass1Service;
    @Mock private Pass2Service pass2Service;
    @Mock private PlayerObjectiveService objectiveService;
    @Mock private NpcAgentService npcAgentService;
    @Mock private PlayerMemoryService memoryService;
    @Mock private NpcDecisionLogRepository npcDecisionRepo;
    @Mock private SessionStateService stateService;
    @Mock private WorldTurnService worldTurns;
    @Mock private KnowledgeService knowledge;
    @Mock private PromiseService promises;
    @Mock private SceneCastService cast;
    @org.mockito.Spy private OfferService offers=new OfferService();
    @Mock private WorldBookService worldBookServiceMock;
    @Mock private WorldSettingService worldSettings;
    @Mock private EncounterService encounters;
    @InjectMocks private GameActionService service;
    private PlayerProfile player;

    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"worldSettings",worldSettings);
        lenient().when(worldSettings.prompt(anyString())).thenReturn("");
        lenient().when(worldSettings.brief(anyString())).thenReturn(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        lenient().when(cast.missingOpeningSource(anyString(),any(),anyString())).thenReturn("");
        lenient().when(cast.recoverWithRetry(anyString(),any(),any(),anyString())).thenReturn(List.of());
        lenient().when(worldTurns.advance(anyString(),anyInt(),anyString(),any())).thenReturn(new WorldTurnService.Turn(1,List.of(),java.util.Set.of()));
        lenient().when(worldTurns.time(anyString())).thenReturn("世界时序 · 第1刻");
        player = new PlayerProfile();
        player.setPlayerId("account_1"); player.setGodId("role_1");
        player.setCurrentSceneId("scene_1");
        GameScene scene = new GameScene();
        scene.setSceneId("scene_1"); scene.setSessionId("session_1");
        scene.setPresentGods(List.of("npc_1")); scene.setSceneSummary("水岸");
        when(sceneRepo.findBySessionIdAndSceneId("session_1", "scene_1"))
                .thenReturn(Optional.of(scene));
        when(playerRepo.findById("account_1")).thenReturn(Optional.of(player));
    }

    private void mockPipeline() {
        GodProfile role = god("role_1", 30);
        GodProfile npc = god("npc_1", 10);
        when(godRepo.findAllById(any())).thenReturn(List.of(role, npc));
        when(memoryService.buildContextForPrompt("account_1", "session_1")).thenReturn("");
        LLMResponse zero = new LLMResponse("{}", "{}", 0, 0, 0, 0);
        when(pass2Service.generateEnsembleNarrativeWithMemory(anyString(), anyString(), anyString(),
                anyList(), anyList(), anyString(), anyString()))
                .thenReturn(new Pass2Service.Pass2Result(List.of(), Map.of(), zero,
                        Pass2Service.Status.SUCCESS, "test", null));
        when(logRepo.save(any())).thenAnswer(call -> {
            InteractionLog entry = call.getArgument(0); entry.setLogId(42L); return entry;
        });
    }

    private GodProfile god(String id, int dou) {
        GodProfile god = new GodProfile(); god.setGodId(id); god.setGodName(id);
        god.setBaseDou(dou); god.setBaseGui(75); return god;
    }

    @Test void freeTextUsesRoleForRulesAndAccountForPersistence() {
        mockPipeline();
        var intent = new ActionRequest.StructuredIntent("ATTACK", "role_1", "npc_1",
                "dou", null, 1.0, "攻击");
        when(storySummaryService.buildContextForPrompt("session_1")).thenReturn("");
        when(pass1Service.extractIntent(eq("role_1"), eq("scene_1"), eq("攻击"), anyList(), anyString()))
                .thenReturn(new Pass1Service.Pass1Result(intent, null, Pass1Service.Status.SUCCESS, "test"));
        ActionResponse response = service.processActionWithDb(
                new ActionRequest("account_1", "scene_1", "攻击", null), "session_1");
        assertEquals("ATTACKER_WIN", response.outcomeType());
        assertEquals(42L, response.logId());
        assertTrue(((Map<?, ?>) response.ruleSnapshot().get("before_actuals_per_god")).containsKey("role_1"));
        ArgumentCaptor<InteractionLog> saved = ArgumentCaptor.forClass(InteractionLog.class);
        verify(logRepo).save(saved.capture());
        assertEquals("account_1", saved.getValue().getPlayerId());
        assertNotNull(saved.getValue().getRuleSnapshot());
        verify(memoryService).recordRoundMemory(eq("account_1"), eq("session_1"), eq("42"), anyString(), eq(5));
    }

    @Test void suppliedNpcActorCannotReplacePlayerRole() {
        mockPipeline();
        var forged = new ActionRequest.StructuredIntent("ATTACK", "npc_1", "npc_1",
                "dou", null, 1.0, "攻击");
        ActionResponse response = service.processActionWithDb(
                new ActionRequest("account_1", "scene_1", "攻击", forged), "session_1");
        assertEquals("ATTACKER_WIN", response.outcomeType());
        ArgumentCaptor<ActionRequest> request = ArgumentCaptor.forClass(ActionRequest.class);
        verify(ruleEngine).resolveWithSeeds(request.capture(), any(), anyMap(), anyList());
        assertEquals("role_1", request.getValue().playerId());
        assertEquals("role_1", request.getValue().structuredIntent().actorGodId());
        verifyNoInteractions(pass1Service);
    }

    @Test void staleSceneIsRejectedBeforeModelCalls() {
        player.setCurrentSceneId("different_scene");
        assertThrows(BusinessException.class, () -> service.processActionWithDb(
                new ActionRequest("account_1", "scene_1", "攻击", null), "session_1"));
        verifyNoInteractions(pass1Service, pass2Service, logRepo);
    }

    @Test void npcAttackUsesRulesAndAppearsInTheFinalSnapshot() {
        mockPipeline();
        when(npcAgentService.decideAllNpcBehaviors(anyString(),anyList(),anyString(),anyString(),anyList(),anyMap()))
                .thenReturn(List.of(new NpcAgentService.NpcDecision("npc_1","ATTACK","接招","反击",false,false,"role_1","","")));
        var intent=new ActionRequest.StructuredIntent("ATTACK","role_1","npc_1","dou",null,1.0,"攻击");
        var response=service.processActionWithDb(new ActionRequest("account_1","scene_1","攻击",intent),"session_1");
        var reactions=(List<?>)response.ruleSnapshot().get("npcActions");
        assertEquals(1,reactions.size());
        assertTrue(response.factsChinese().size()>1);
        verify(ruleEngine,times(2)).resolveWithSeeds(any(),any(),anyMap(),anyList());
    }

    @Test void npcLeaveUpdatesSceneMembership() {
        mockPipeline();
        when(npcAgentService.decideAllNpcBehaviors(anyString(),anyList(),anyString(),anyString(),anyList(),anyMap()))
                .thenReturn(List.of(new NpcAgentService.NpcDecision("npc_1","LEAVE","告辞","离去",false,false)));
        var intent=new ActionRequest.StructuredIntent("OBSERVE","role_1","","dou",null,1.0,"观察");
        // OBSERVE has no attack key; the model must not sneak in a combat attribute.
        intent=new ActionRequest.StructuredIntent("OBSERVE","role_1","",null,null,1.0,"观察");
        service.processActionWithDb(new ActionRequest("account_1","scene_1","观察",intent),"session_1");
        ArgumentCaptor<GameScene> saved=ArgumentCaptor.forClass(GameScene.class);
        verify(sceneRepo).save(saved.capture());
        assertFalse(saved.getValue().getPresentGods().contains("npc_1"));
    }
}
