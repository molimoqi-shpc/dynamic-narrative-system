package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlayerInquiryIdentityTest {
    @Mock private WorldBookService worldBookService;
    @Mock private NpcMemoryRepository memoryRepo;
    @Mock private GodProfileRepository godRepo;
    @Mock private PlayerProfileRepository playerRepo;
    @Mock private SessionStateService stateService;
    @Mock private KnowledgeService knowledge;
    @Mock private WorldTurnService worldTurns;
    @org.junit.jupiter.api.BeforeEach void clock(){lenient().when(worldTurns.advance(anyString(),anyInt(),any(),anyString())).thenReturn(new WorldTurnService.Turn(1,List.of(),Set.of()));}
    @InjectMocks private InquiryService service;

    @Test void inquiryUsesMappedRoleAttributesAndMemoryOwner() {
        PlayerProfile player = new PlayerProfile();
        player.setPlayerId("account_1"); player.setGodId("role_1");
        GodProfile role = new GodProfile(); role.setBaseWen(70); role.setBaseHui(60);
        WorldBook event = new WorldBook(); event.setEntryId(7L); event.setRoundNo(1);
        event.setLocationName("水岸"); event.setEventDescription("神明现身");
        when(playerRepo.findById("account_1")).thenReturn(Optional.of(player));
        when(godRepo.findById("role_1")).thenReturn(Optional.of(role));
        when(worldBookService.queryForInquiryWeighted("session_1", 0, 0, 70, 60))
                .thenReturn(List.of(event));
        assertTrue(service.processPlayerInquiry("session_1", "account_1", 0, 0).contains("神明现身"));
        verify(godRepo, never()).findById("account_1");
        ArgumentCaptor<NpcMemory> memory = ArgumentCaptor.forClass(NpcMemory.class);
        verify(memoryRepo).save(memory.capture());
        assertEquals("role_1", memory.getValue().getGodId());
        assertEquals(7L, memory.getValue().getWorldEntryId());
    }

    @Test void missingAccountDoesNotSilentlyBecomeZeroStats() {
        when(playerRepo.findById("missing")).thenReturn(Optional.empty());
        assertThrows(BusinessException.class,
                () -> service.processPlayerInquiry("session_1", "missing", 0, 0));
        verifyNoInteractions(godRepo, memoryRepo, worldBookService);
    }
}
