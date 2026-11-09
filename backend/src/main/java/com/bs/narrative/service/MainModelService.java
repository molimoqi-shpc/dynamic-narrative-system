package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.bs.narrative.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

@Service
public class MainModelService {
 private final JdbcTemplate jdbc;private final LLMConfig config;
 public MainModelService(JdbcTemplate jdbc,LLMConfig config){this.jdbc=jdbc;this.config=config;}
 public String selected(String sid){
  var rows=jdbc.queryForList("SELECT model_name FROM session_main_model WHERE session_id=?",String.class,sid);
  if(!rows.isEmpty() && MainModelContext.supported(rows.get(0)))return rows.get(0);
  return MainModelContext.supported(config.getMain().getModel())?config.getMain().getModel():MainModelContext.FLASH;
 }
 public Map<String,Object> read(String sid){return Map.of("model",selected(sid),"options",List.of(
  Map.of("id",MainModelContext.FLASH,"label","DeepSeek Flash","note","响应更快，费用较低"),
  Map.of("id",MainModelContext.PRO,"label","DeepSeek Pro","note","更强的创作与推理，费用更高")),"appliesTo","NEXT_OPERATION");}
 public Map<String,Object> change(String sid,String model){
  if(!MainModelContext.supported(model))throw new BusinessException(ErrorCode.PARAM_INVALID,"主 API 仅支持 DeepSeek Flash 或 Pro");
  jdbc.update("INSERT INTO session_main_model(session_id,model_name) VALUES(?,?) ON DUPLICATE KEY UPDATE model_name=VALUES(model_name)",sid,model);
  return read(sid);
 }
}
