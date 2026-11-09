package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Projects the main API's stated progress from fixed prose; it never chooses plot outcomes. */
@Service
public class StoryObjectiveService {
 private final JdbcTemplate jdbc;
 public StoryObjectiveService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public List<Map<String,Object>> read(String sid,String pid){return jdbc.queryForList("SELECT objective_id AS objectiveId,title,status,current_step AS currentStep FROM player_objective WHERE session_id=? AND player_id=? ORDER BY objective_id",sid,pid);}
 public void apply(String sid,String pid,JsonNode updates){
  for(var update:updates){String status=update.path("status").asText(),id=update.path("objectiveId").asText();
   if(id.isBlank() || !Set.of("ACTIVE","COMPLETED","FAILED").contains(status))continue;
   String step=update.path("currentStep").asText();if(step.length()>255)step=step.substring(0,255);
   // No reading keywords, times, places, or mathematical rules to decide whether a story succeeded.
   jdbc.update("UPDATE player_objective SET status=?,progress=CASE WHEN ?='COMPLETED' THEN 100 WHEN ?='ACTIVE' THEN 0 ELSE progress END,current_step=CASE WHEN ?='' THEN current_step ELSE ? END,updated_at=CURRENT_TIMESTAMP WHERE session_id=? AND player_id=? AND objective_id=?",status,status,status,step,step,sid,pid,id);
  }
 }
}
