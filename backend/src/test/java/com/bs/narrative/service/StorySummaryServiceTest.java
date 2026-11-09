package com.bs.narrative.service;

import com.bs.narrative.domain.StorySummary;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.llm.Pass2Service;
import com.bs.narrative.repository.GameSceneRepository;
import com.bs.narrative.repository.StorySummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * StorySummaryService 单测（P2 并发 + P4 坐标冲突）。
 *
 * 覆盖：
 *   1) 新 session 首次落库 → round_no = 1
 *   2) 已有记录 → round_no 递增
 *   3) 同坐标不同场景 → COORD_CONFLICT
 *   4) 同坐标同场景但地点名不同 → COORD_CONFLICT
 *   5) 同坐标同场景同地点名 → 允许，round 递增
 *   6) hs 为 null → 返回 null（跳过落库）
 */
@ExtendWith(MockitoExtension.class)
class StorySummaryServiceTest {

    @Mock
    private StorySummaryRepository summaryRepo;

    @Mock
    private GameSceneRepository sceneRepo;

    @InjectMocks
    private StorySummaryService service;

    private Pass2Service.HiddenSummary hs;

    @BeforeEach
    void setUp() {
        hs = new Pass2Service.HiddenSummary(
                "丰渡水岸", 0, 0, "戌时",
                "玩家挑衅雨神",
                List.of("player_01", "rain_god"),
                List.of("玩家挑衅"),
                "雨神dou+1");
    }

    @Test
    void newSession_roundNoStartsAtOne() {
        when(summaryRepo.findBySessionIdOrderByRoundNoDesc(anyString(), any(PageRequest.class)))
                .thenReturn(List.of());
        when(summaryRepo.findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc(any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
        when(summaryRepo.saveAndFlush(any(StorySummary.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StorySummary saved = service.persistSummary("sess_1", "scene_fengdu", hs);

        assertNotNull(saved);
        assertEquals(1, saved.getRoundNo());
        assertEquals("scene_fengdu", saved.getSceneId());
        assertEquals("丰渡水岸", saved.getLocationName());
    }

    @Test
    void existingSession_roundNoIncrements() {
        StorySummary existing = new StorySummary();
        existing.setRoundNo(5);
        when(summaryRepo.findBySessionIdOrderByRoundNoDesc(anyString(), any(PageRequest.class)))
                .thenReturn(List.of(existing));
        when(summaryRepo.findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc(any(), anyInt(), anyInt()))
                .thenReturn(Optional.empty());
        when(summaryRepo.saveAndFlush(any(StorySummary.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StorySummary saved = service.persistSummary("sess_1", "scene_fengdu", hs);

        assertEquals(6, saved.getRoundNo());
    }

    @Test
    void sameCoordDifferentScene_throwsCoordConflict() {
        StorySummary existing = new StorySummary();
        existing.setSceneId("scene_bian");
        existing.setLocationName("彼岸渡口");
        when(summaryRepo.findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc("sess_1", 0, 0))
                .thenReturn(Optional.of(existing));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.persistSummary("sess_1", "scene_fengdu", hs));
        assertEquals(ErrorCode.COORD_CONFLICT, e.getErrorCode());
    }

    @Test
    void sameCoordSameSceneDifferentName_throwsCoordConflict() {
        StorySummary existing = new StorySummary();
        existing.setSceneId("scene_fengdu");
        existing.setLocationName("丰渡古渡"); // 同名场景但 LLM 取了不同名字
        when(summaryRepo.findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc("sess_1", 0, 0))
                .thenReturn(Optional.of(existing));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.persistSummary("sess_1", "scene_fengdu", hs));
        assertEquals(ErrorCode.COORD_CONFLICT, e.getErrorCode());
    }

    @Test
    void sameCoordSameSceneSameName_allowed() {
        StorySummary existing = new StorySummary();
        existing.setSceneId("scene_fengdu");
        existing.setLocationName("丰渡水岸");
        existing.setRoundNo(3);
        when(summaryRepo.findFirstBySessionIdAndCoordXAndCoordYOrderByRoundNoDesc("sess_1", 0, 0))
                .thenReturn(Optional.of(existing));
        when(summaryRepo.findBySessionIdOrderByRoundNoDesc(anyString(), any(PageRequest.class)))
                .thenReturn(List.of(existing));
        when(summaryRepo.saveAndFlush(any(StorySummary.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        StorySummary saved = service.persistSummary("sess_1", "scene_fengdu", hs);

        assertEquals(4, saved.getRoundNo()); // round 递增
    }

    @Test
    void nullHs_returnsNull() {
        StorySummary result = service.persistSummary("sess_1", "scene_fengdu", null);
        assertNull(result);
        verify(summaryRepo, never()).saveAndFlush(any());
    }
}
