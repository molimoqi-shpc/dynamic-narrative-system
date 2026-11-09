package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** A paid reply and its completion marker survive rollback of unfinished game work. */
@Service @DependsOn("operationSchema")
public class ModelReplyJournal {
 private final JdbcTemplate jdbc;private final ObjectMapper json=new ObjectMapper();
 public ModelReplyJournal(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private static final String INCOMPLETE_PREFIX="INCOMPLETE:";
 // Compatibility with records written before the explicit marker existed.
 // These are transport status codes, never classifications of story prose.
 private static final java.util.Set<String> LEGACY_INCOMPLETE=java.util.Set.of(
  "MODEL_OUTPUT_TRUNCATED","MODEL_CALL_LIMIT","MODEL_PAYMENT_REQUIRED","MODEL_RATE_LIMITED",
  "MODEL_REQUEST_INVALID","MODEL_TIMEOUT","MODEL_CONNECTION_FAILED","MODEL_UNAVAILABLE","正文为空");
 public record Saved(LLMResponse original,LLMResponse latest,String issue,int repairs){
  public boolean incomplete(){return issue.startsWith(INCOMPLETE_PREFIX) || LEGACY_INCOMPLETE.contains(issue.split("；",2)[0]);}
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
 public Saved find(String sid,ModelProtocol.Stage stage,String source){
  var rows=jdbc.queryForList("SELECT original_response,latest_response,issue,repair_count FROM model_reply_journal WHERE session_id=? AND stage=? AND source_hash=?",sid,stage.name(),source);
  if(rows.isEmpty())return null;var row=rows.get(0);
  try{return new Saved(json.readValue(row.get("original_response").toString(),LLMResponse.class),json.readValue(row.get("latest_response").toString(),LLMResponse.class),row.get("issue").toString(),((Number)row.get("repair_count")).intValue());}
  catch(java.io.IOException invalid){throw new IllegalStateException("已保存模型原回复无法读取，未重新生成",invalid);}
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void received(String sid,ModelProtocol.Stage stage,String source,LLMResponse reply,boolean repair){
  storeReply(sid,stage,source,reply,repair,"");
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void receivedIncomplete(String sid,ModelProtocol.Stage stage,String source,LLMResponse reply,boolean repair,String issue){
  if(issue==null || issue.isBlank())throw new IllegalArgumentException("Incomplete replies need a durable issue marker");
  storeReply(sid,stage,source,reply,repair,INCOMPLETE_PREFIX+issue);
 }
 private void storeReply(String sid,ModelProtocol.Stage stage,String source,LLMResponse reply,boolean repair,String issue){
  try{
   String raw=json.writeValueAsString(reply);
   // A crash after this one statement cannot leave a partial reply marked complete.
   jdbc.update("INSERT INTO model_reply_journal(session_id,stage,source_hash,original_response,latest_response,repair_count,issue) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE latest_response=VALUES(latest_response),issue=VALUES(issue),repair_count=repair_count+?",sid,stage.name(),source,raw,raw,repair?1:0,ModelOutput.cap(issue,1200),repair?1:0);
  }catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new IllegalStateException(invalid);}
 }
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public void issue(String sid,ModelProtocol.Stage stage,String source,String issue){
  jdbc.update("UPDATE model_reply_journal SET issue=? WHERE session_id=? AND stage=? AND source_hash=?",ModelOutput.cap(issue,1200),sid,stage.name(),source);
 }
}
