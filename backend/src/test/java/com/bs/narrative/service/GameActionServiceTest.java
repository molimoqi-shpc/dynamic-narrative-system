package com.bs.narrative.service;

import com.bs.narrative.domain.PlayerProfile;
import com.bs.narrative.domain.SceneConnection;
import com.bs.narrative.dto.ActionResponse;
import com.bs.narrative.engine.enums.OutcomeType;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.llm.LLMResponse;
import com.bs.narrative.llm.Pass1Service;
import com.bs.narrative.llm.Pass2Service;
import com.bs.narrative.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * GameActionService 单测（P3 门禁 + 场景匹配校验）。
 *
 * 覆盖：
 *   1) moveScene 正常：玩家在丰渡，出口"北去彼岸"存在 → 切到彼岸
 *   2) moveScene 出口不存在 → MOVE_EXIT_NOT_FOUND
 *   3) moveScene required_item 未持有 → MOVE_GATE_ITEM_REQUIRED
 *   4) moveScene required_objective 未完成 → MOVE_GATE_OBJECTIVE_REQUIRED
 *   5) moveByCoord 坐标有记录 → 切到该场景
 *   6) moveByCoord 坐标无记录 → MOVE_COORD_NOT_FOUND
 *   7) moveScene 玩家不存在 → PLAYER_NOT_FOUND
 */
@ExtendWith(MockitoExtension.class)
class GameActionServiceTest {

    @Mock private RuleEngineFacadeService ruleEngine;
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
    @Mock private PlayerMemoryService memoryService;

    @Mock private SessionStateService stateService;
    @Mock private WorldTurnService worldTurns;
    @Mock private KnowledgeService knowledge;
    @org.mockito.Spy private OfferService offers=new OfferService();
    @Mock private WorldBookService worldBookServiceMock;
    @Mock private EncounterService encounters;
    @InjectMocks
    private GameActionService gameService;

    private PlayerProfile player;

    @BeforeEach
    void setUp() {
        lenient().when(memoryService.buildContextForPrompt(anyString(),anyString())).thenReturn("");
        lenient().when(worldTurns.advance(anyString(),anyInt(),anyString(),any())).thenReturn(new WorldTurnService.Turn(1,List.of(),java.util.Set.of()));
        lenient().when(worldTurns.time(anyString())).thenReturn("世界时序 · 第1刻");
        player = new PlayerProfile();
        player.setPlayerId("p_demo");
        player.setCurrentSceneId("scene_fengdu");
        player.setInventory(List.of("骨刃", "水囊"));
        // Pass2 默认返回空叙事（Fallback 风格），测试聚焦场景/门禁逻辑
        // 用 lenient：门禁失败的测试在到达 Pass2 前就抛异常，stub 不会被调用
        LLMResponse emptyResp = new LLMResponse("", "", 0, 0, 0, 0.0);
        Pass2Service.Pass2Result emptyP2 = new Pass2Service.Pass2Result(
                List.of(), Map.of(), emptyResp, Pass2Service.Status.FALLBACK,
                "test fallback", null);
        lenient().when(pass2Service.generateEnsembleNarrativeWithMemory(anyString(), anyString(), anyString(),
                any(), any(), anyString(), anyString())).thenReturn(emptyP2);
    }

    // ——— moveScene 测试 ———

    @Test
    void moveScene_normalExit_switchesScene() {
        when(playerRepo.findById("p_demo")).thenReturn(Optional.of(player));
        SceneConnection conn = new SceneConnection();
        conn.setFromSceneId("scene_fengdu");
        conn.setToSceneId("scene_bian");
        conn.setExitName("北去彼岸");
        conn.setNote("北行十里");
        when(connRepo.findByFromSceneId("scene_fengdu")).thenReturn(List.of(conn));
        when(playerRepo.save(any(PlayerProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        // 目标场景查询（供 Pass2 生成叙事）
        com.bs.narrative.domain.GameScene toScene = new com.bs.narrative.domain.GameScene();
        toScene.setSceneId("scene_bian");
        toScene.setLocationName("彼岸");
        toScene.setSceneSummary("彼岸花开");
        toScene.setPresentGods(List.of("museum_god"));
        when(sceneRepo.findBySessionIdAndSceneId("sess_1", "scene_bian"))
                .thenReturn(Optional.of(toScene));

        ActionResponse resp = gameService.moveScene("p_demo", "sess_1", "北去彼岸");

        assertEquals(OutcomeType.NO_OP.name(), resp.outcomeType());
        assertEquals("scene_bian", player.getCurrentSceneId());
        verify(playerRepo).save(player);
        verify(pass2Service).generateEnsembleNarrativeWithMemory(anyString(), anyString(), anyString(),
                any(), any(), anyString(), anyString());
    }

    @Test
    void moveScene_exitNotFound_throwsMoveExitNotFound() {
        when(playerRepo.findById("p_demo")).thenReturn(Optional.of(player));
        when(connRepo.findByFromSceneId("scene_fengdu")).thenReturn(List.of());

        BusinessException e = assertThrows(BusinessException.class,
                () -> gameService.moveScene("p_demo", "sess_1", "不存在出口"));
        assertEquals(ErrorCode.MOVE_EXIT_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void moveScene_requiredItemMissing_throwsGateItemRequired() {
        when(playerRepo.findById("p_demo")).thenReturn(Optional.of(player));
        SceneConnection conn = new SceneConnection();
        conn.setFromSceneId("scene_fengdu");
        conn.setToSceneId("scene_huangquan");
        conn.setExitName("南下黄泉");
        conn.setRequiredItem("忘情汤"); // 玩家背包无此物
        when(connRepo.findByFromSceneId("scene_fengdu")).thenReturn(List.of(conn));

        BusinessException e = assertThrows(BusinessException.class,
                () -> gameService.moveScene("p_demo", "sess_1", "南下黄泉"));
        assertEquals(ErrorCode.MOVE_GATE_ITEM_REQUIRED, e.getErrorCode());
    }

    @Test
    void moveScene_requiredObjectiveIncomplete_throwsGateObjectiveRequired() {
        when(playerRepo.findById("p_demo")).thenReturn(Optional.of(player));
        SceneConnection conn = new SceneConnection();
        conn.setFromSceneId("scene_fengdu");
        conn.setToSceneId("scene_wangchuan");
        conn.setExitName("西去忘川");
        conn.setRequiredObjective("talk_to_judge");
        when(connRepo.findByFromSceneId("scene_fengdu")).thenReturn(List.of(conn));
        when(objRepo.findByPlayerIdAndSessionIdAndObjectiveId(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty()); // 目标未完成

        BusinessException e = assertThrows(BusinessException.class,
                () -> gameService.moveScene("p_demo", "sess_1", "西去忘川"));
        assertEquals(ErrorCode.MOVE_GATE_OBJECTIVE_REQUIRED, e.getErrorCode());
    }

    @Test
    void moveScene_playerNotFound_throwsPlayerNotFound() {
        when(playerRepo.findById("p_demo")).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> gameService.moveScene("p_demo", "sess_1", "北去彼岸"));
        assertEquals(ErrorCode.PLAYER_NOT_FOUND, e.getErrorCode());
    }

    // ——— moveByCoord 测试 ———

    @Test
    void moveByCoord_existingScene_switchesToIt() {
        when(playerRepo.findById("p_demo")).thenReturn(Optional.of(player));
        com.bs.narrative.domain.GameScene scene = new com.bs.narrative.domain.GameScene();
        scene.setSceneId("scene_fengdu");
        scene.setLocationName("丰渡水岸");
        scene.setSceneSummary("丰渡水岸，雨师凭栏。");
        scene.setPresentGods(List.of("rain_god"));
        when(storySummaryService.findExistingSceneByCoord("sess_1", 0, 0))
                .thenReturn(Optional.of(scene));
        when(playerRepo.save(any(PlayerProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ActionResponse resp = gameService.moveByCoord("p_demo", "sess_1", 0, 0);

        assertEquals(OutcomeType.NO_OP.name(), resp.outcomeType());
        assertEquals("scene_fengdu", player.getCurrentSceneId());
    }

    @Test
    void moveByCoord_noSceneAtCoord_throwsMoveCoordNotFound() {
        when(playerRepo.findById("p_demo")).thenReturn(Optional.of(player));
        when(storySummaryService.findExistingSceneByCoord("sess_1", 99, 99))
                .thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> gameService.moveByCoord("p_demo", "sess_1", 99, 99));
        assertEquals(ErrorCode.MOVE_COORD_NOT_FOUND, e.getErrorCode());
    }
}

