package com.bs.narrative.service;
import com.bs.narrative.domain.PlayerMemory;
import com.bs.narrative.repository.PlayerMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class MemoryConsolidationTest {
    @Mock PlayerMemoryRepository repo;
    @InjectMocks PlayerMemoryService service;
    private PlayerMemory round(String content) {
        PlayerMemory m=new PlayerMemory();m.setContent(content);m.setRefId("10");return m;
    }
    @Test void tenthRoundProducesSceneSummaryAndCriticalEventProducesGlobalMemory() {
        when(repo.findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(eq("p"),eq("s"),eq("ROUND"),any()))
                .thenReturn(List.of(round("玩家询问雨神"),round("神明选择援助")));
        when(repo.save(any())).thenAnswer(call->call.getArgument(0));
        service.consolidate("p","s","scene",10,"THIRD_PARTY_INTERVENE");
        ArgumentCaptor<PlayerMemory> saved=ArgumentCaptor.forClass(PlayerMemory.class);
        verify(repo,times(2)).save(saved.capture());
        var records=saved.getAllValues();
        assertEquals("SCENE",records.get(0).getScope());
        assertEquals("scene",records.get(0).getRefId());
        assertTrue(records.get(0).getContent().contains("玩家询问雨神"));
        assertEquals("GLOBAL",records.get(1).getScope());
        assertEquals("10",records.get(1).getRefId());
    }
    @Test void sceneSummaryUpdatesExistingRecordInsteadOfAccumulatingDuplicates() {
        PlayerMemory previous=round("旧摘要");previous.setScope("SCENE");previous.setRefId("scene");previous.setImportance(8);
        when(repo.findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(eq("p"),eq("s"),eq("SCENE"),any()))
                .thenReturn(List.of(previous));
        when(repo.save(any())).thenAnswer(call->call.getArgument(0));
        var updated=service.upsertSceneMemory("p","s","scene","新摘要",6);
        assertSame(previous,updated);assertEquals("新摘要",updated.getContent());assertEquals(8,updated.getImportance());
    }
}
