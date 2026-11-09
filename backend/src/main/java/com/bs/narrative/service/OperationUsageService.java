package com.bs.narrative.service;
import com.bs.narrative.llm.LLMUsage;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.util.*;
import java.util.function.Consumer;
/** Financial evidence is independent of world state, leases, cancellation and rewind. */
@Service
public class OperationUsageService {
 @org.springframework.beans.factory.annotation.Value("${game.operation-max-model-calls:24}") private int maxModelCalls=24;
 private final JdbcTemplate jdbc;private final TransactionTemplate independent;
 public OperationUsageService(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;independent=new TransactionTemplate(manager);independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public Consumer<LLMUsage.CallEvent> sink(String sid,long epoch,String operation,long logId){
  return sink(sid,epoch,operation,logId,callAllowance());
 }
 public int callAllowance(){return Math.max(1,Math.min(100,maxModelCalls));}
 public long nextCallCeiling(String sid,long epoch,String operation){return ((Number)forOperation(sid,epoch,operation).get("calls")).longValue()+callAllowance();}
 public Consumer<LLMUsage.CallEvent> sink(String sid,long epoch,String operation,long logId,long ceiling){
  return event->independent.executeWithoutResult(status->{
   if("STARTED".equals(event.state())){
    // Serialize reservations across parallel NPC workers before any paid HTTP request is sent.
    jdbc.queryForList("SELECT operation_id FROM game_operation WHERE session_id=? AND branch_epoch=? AND operation_id=? FOR UPDATE",sid,epoch,operation);
    boolean duplicate=jdbc.queryForObject("SELECT COUNT(*) FROM llm_operation_usage WHERE call_id=? AND session_id=? AND branch_epoch=? AND operation_id=?",Integer.class,event.id(),sid,epoch,operation)>0;
    long used=jdbc.queryForObject("SELECT COUNT(*) FROM llm_operation_usage WHERE session_id=? AND branch_epoch=? AND operation_id=?",Long.class,sid,epoch,operation);
    if(!duplicate && used>=ceiling)throw new com.bs.narrative.llm.LLMApiException("MODEL_CALL_LIMIT: 本次模型请求额度已用完，已发生内容保留",false);
    jdbc.update("INSERT IGNORE INTO llm_operation_usage(call_id,session_id,branch_epoch,operation_id,log_id,model_name,state) VALUES(?,?,?,?,?,?,?)",event.id(),sid,epoch,operation,logId,event.model(),event.state());
   }
   else if(event.response()!=null){var response=event.response();jdbc.update("UPDATE llm_operation_usage SET state='RESPONSE',usage_known=?,token_input=?,token_output=?,cost_cny=? WHERE call_id=? AND session_id=? AND branch_epoch=? AND operation_id=?",event.known(),response.tokenInput(),response.tokenOutput(),response.costCny(),event.id(),sid,epoch,operation);}
   else jdbc.update("UPDATE llm_operation_usage SET state='UNCONFIRMED' WHERE call_id=? AND state='STARTED' AND session_id=? AND branch_epoch=? AND operation_id=?",event.id(),sid,epoch,operation);
   // Only billing columns may change after cancellation. Never revive a lease or rewrite prose.
   jdbc.queryForList("SELECT log_id FROM interaction_log WHERE session_id=? AND log_id=? FOR UPDATE",sid,logId);
   var totals=forLog(sid,logId);
   jdbc.update("UPDATE interaction_log SET token_input=?,token_output=?,token_total=?,cost_cny=? WHERE session_id=? AND log_id=?",totals.get("input"),totals.get("output"),totals.get("total"),totals.get("costCny"),sid,logId);
  });
 }
 private Map<String,Object> sum(String predicate,Object...args){
  var row=jdbc.queryForMap("SELECT COUNT(*) AS calls,COALESCE(SUM(token_input),0) AS input,COALESCE(SUM(token_output),0) AS output,COALESCE(SUM(cost_cny),0) AS cost,COALESCE(SUM(CASE WHEN usage_known=FALSE THEN 1 ELSE 0 END),0) AS unconfirmed FROM llm_operation_usage WHERE "+predicate,args);
  long input=((Number)row.get("input")).longValue(),output=((Number)row.get("output")).longValue();
  return Map.of("calls",row.get("calls"),"input",input,"output",output,"total",input+output,"costCny",row.get("cost"),"unconfirmedCalls",row.get("unconfirmed"),"estimated",true);
 }
 public Map<String,Object> forOperation(String sid,long epoch,String id){return sum("session_id=? AND branch_epoch=? AND operation_id=?",sid,epoch,id);}
 public Map<String,Object> forLog(String sid,long log){
  var result=sum("session_id=? AND log_id=?",sid,log);if(((Number)result.get("calls")).longValue()>0)return result;
  var rows=jdbc.queryForList("SELECT token_input,token_output,token_total,cost_cny FROM interaction_log WHERE session_id=? AND log_id=?",sid,log);
  if(rows.isEmpty())return result;var old=rows.get(0);var fallback=new HashMap<String,Object>(result);
  fallback.put("input",Objects.requireNonNullElse(old.get("token_input"),0));fallback.put("output",Objects.requireNonNullElse(old.get("token_output"),0));fallback.put("total",Objects.requireNonNullElse(old.get("token_total"),0));fallback.put("costCny",Objects.requireNonNullElse(old.get("cost_cny"),0));fallback.put("legacy",true);return fallback;
 }
}
