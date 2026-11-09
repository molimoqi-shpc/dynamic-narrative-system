package com.bs.narrative.service;
import com.bs.narrative.domain.PlayerObjective;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ObjectiveProgressRegressionTest {
 @Mock LLMProvider model;@Mock PlayerObjectiveRepository repo;@Mock GameSessionRepository sessions;@Mock ObjectiveRuleService rules;
 @Mock org.springframework.jdbc.core.JdbcTemplate jdbc;
 @InjectMocks PlayerObjectiveService service;
 @Test void objectiveProgressNeverReadsAnInventedModelPercentage(){
  var goal=new PlayerObjective();goal.setStatus("ACTIVE");goal.setProgress(10);
  when(repo.findByPlayerIdAndSessionId("p","s")).thenReturn(List.of(goal));
  service.advanceObjectives("s","p","调查","成功",List.of("说法尚未核实"));
  verify(rules).refresh("s","p");verifyNoInteractions(model);assertEquals(10,goal.getProgress());
 }
 @Test void personalCompletionIsNotAWorldEnding(){
  var goal=new PlayerObjective();goal.setStatus("COMPLETED");when(repo.findByPlayerIdAndSessionId("p","s")).thenReturn(List.of(goal));
  when(jdbc.queryForObject(anyString(),eq(String.class),eq("s"))).thenReturn("PLAYING");
  assertEquals("ONGOING",service.evaluateEnding("s","p",false).get("ending"));
  assertEquals("ONGOING",service.evaluateEnding("s","p",false).get("ending"));verifyNoInteractions(rules,model);
  when(jdbc.queryForObject(anyString(),eq(String.class),eq("s"))).thenReturn("RETIRED");assertEquals("RETIREMENT",service.evaluateEnding("s","p",false).get("ending"));
  assertEquals("DEFEAT",service.evaluateEnding("s","p",true).get("ending"));
 }
}
