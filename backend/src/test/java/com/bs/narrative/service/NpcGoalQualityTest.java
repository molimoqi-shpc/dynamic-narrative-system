package com.bs.narrative.service;

import com.bs.narrative.domain.NpcGoal;
import com.bs.narrative.repository.NpcGoalRepository;
import com.bs.narrative.llm.NpcAgentPromptTemplate;
import com.bs.narrative.llm.ModelOutput;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NpcGoalQualityTest {
    private static final String CONTEXT="渡者守着旧船。他已经没有饮水，手中有一枚暂时不用的通行印。";
    private static final String GOAL="换取饮水以继续留守旧船";
    private static final String BASIS="他已经没有饮水";
    private static final String SUCCESS="实际取得饮水且仍能守船";
    private static final String COST="愿放弃暂时不用的通行印，不离开旧船";
    @Test void groundedFuturePurposeIsAllowed() {
        assertTrue(NpcGoalService.validProposal(GOAL,BASIS,SUCCESS,COST,CONTEXT));
    }
    @Test void inventedHistoryAndMissingTradeoffCannotBecomeDurableGoals() {
        assertFalse(NpcGoalService.validProposal(GOAL,"昔日家人被雨神所害",SUCCESS,COST,CONTEXT));
        assertFalse(NpcGoalService.validProposal(GOAL,BASIS,SUCCESS,"",CONTEXT));
        assertFalse(NpcGoalService.validProposal(GOAL,BASIS,GOAL,COST,CONTEXT));
    }
    @Test void genericAndOversizedPurposesAreRejected() {
        assertFalse(NpcGoalService.validProposal("观察局势，寻找机会",BASIS,SUCCESS,COST,CONTEXT));
        assertFalse(NpcGoalService.validProposal("目的".repeat(100),BASIS,SUCCESS,COST,CONTEXT));
    }
    @Test void adoptionAbandonsBootstrapAndPersistsConditionsOnlyOnce() {
        var repo=mock(NpcGoalRepository.class);var service=new NpcGoalService(repo,mock(JdbcTemplate.class));
        var old=new NpcGoal();old.setGoalDescription(NpcGoalService.BOOTSTRAP);
        when(repo.findBySessionIdAndGodIdAndStatusOrderByPriorityDesc("s","n","ACTIVE")).thenReturn(List.of(old));
        when(repo.save(any())).thenAnswer(i->i.getArgument(0));
        assertTrue(service.adoptGroundedGoal("s","n",GOAL,BASIS,SUCCESS,COST,CONTEXT));
        assertEquals("ABANDONED",old.getStatus());
        var captured=org.mockito.ArgumentCaptor.forClass(NpcGoal.class);verify(repo,times(2)).save(captured.capture());
        var goal=captured.getAllValues().get(1);assertEquals("s",goal.getSessionId());assertEquals("n",goal.getGodId());
        assertEquals("ACTIVE",goal.getStatus());assertTrue(goal.getGoalDescription().contains(SUCCESS));
        assertTrue(goal.getGoalDescription().contains(COST));
        when(repo.findBySessionIdAndGodIdAndStatusOrderByPriorityDesc("s","n","ACTIVE")).thenReturn(List.of(goal));
        clearInvocations(repo);assertFalse(service.adoptGroundedGoal("s","n",GOAL,BASIS,SUCCESS,COST,CONTEXT));verify(repo,never()).save(any());
    }
    @Test void invalidProposalKeepsBootstrapAndCustomGoalsArePreserved() {
        var repo=mock(NpcGoalRepository.class);var service=new NpcGoalService(repo,mock(JdbcTemplate.class));
        var old=new NpcGoal();old.setGoalDescription(NpcGoalService.BOOTSTRAP);
        when(repo.findBySessionIdAndGodIdAndStatusOrderByPriorityDesc("s","n","ACTIVE")).thenReturn(List.of(old));
        assertFalse(service.adoptGroundedGoal("s","n",GOAL,"未给出的旧事",SUCCESS,COST,CONTEXT));assertEquals("ACTIVE",old.getStatus());
        old.setGoalDescription("保留自己已经承诺的安排");
        assertFalse(service.adoptGroundedGoal("s","n",GOAL,BASIS,SUCCESS,COST,CONTEXT));verify(repo,never()).save(any());
    }
    @Test void initializationDoesNotAssignAnIdentityBasedVillainOrOverwriteEstablishedGoals() {
        var repo=mock(NpcGoalRepository.class);var service=new NpcGoalService(repo,mock(JdbcTemplate.class));
        when(repo.findBySessionIdAndGodIdAndStatusOrderByPriorityDesc("s","museum_god","ACTIVE")).thenReturn(List.of());
        service.initPresetGoals("s","museum_god","馆主");
        var captured=org.mockito.ArgumentCaptor.forClass(NpcGoal.class);verify(repo).save(captured.capture());
        assertEquals(NpcGoalService.BOOTSTRAP,captured.getValue().getGoalDescription());
        when(repo.findBySessionIdAndGodIdAndStatusOrderByPriorityDesc("s","museum_god","ACTIVE")).thenReturn(List.of(captured.getValue()));
        clearInvocations(repo);service.initPresetGoals("s","museum_god","馆主");verify(repo,never()).save(any());
    }
    @Test void fallbackCannotInjectSchemaInstructionsAsGoalsOrAcceptance() {
        var node=ModelOutput.object(NpcAgentPromptTemplate.fallbackNpcJson());
        assertEquals("",node.path("personal_goal").asText());assertTrue(node.path("accept_offer").isNull());
        assertEquals("",node.path("target_god_id").asText());
    }
}
