package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Narrative coordinates, not travel permission or an estimate extracted from prose. */
@Service
public class StoryClockService {
 private static final ObjectMapper JSON=new ObjectMapper();
 private static final List<String> PERIODS=List.of("BEFORE_DAWN","DAWN","MORNING","NOON","AFTERNOON","EVENING","NIGHT","LATE_NIGHT");
 private static final List<String> LABELS=List.of("凌晨","清晨","上午","正午","下午","傍晚","夜间","深夜");
 private static final int[] REPRESENTATIVES={120,360,540,720,900,1080,1260,1380};
 private final JdbcTemplate jdbc;
 public StoryClockService(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public ObjectNode current(String sid,String role){
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='story:clock'",String.class,sid,role);
  if(rows.isEmpty())return JSON.createObjectNode();
  try{return moment(JSON.readTree(rows.get(0)));}catch(Exception invalid){throw new IllegalStateException("已保存故事时点无法读取，未改写时间",invalid);}
 }
 public void store(String sid,String role,JsonNode value){
  if(value.isMissingNode() || value.isNull())return;
  var point=moment(value);
  jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,'story:clock','当前故事时点',?,'STORY_CLOCK','','','',0) ON DUPLICATE KEY UPDATE content=VALUES(content)",sid,role,point.toString());
 }
 static ObjectNode moment(JsonNode value){
  if(!value.isObject() || !value.path("year").isIntegralNumber() || !value.path("year").canConvertToLong() || value.path("year").asLong()<0 || !value.path("day").isIntegralNumber() || value.path("day").asInt()<1 || value.path("day").asLong()>365 || !PERIODS.contains(value.path("period").asText()))throw new IllegalArgumentException("时点须为故事相对year、1至365的day及明确period；不从正文猜时间");
  var out=JSON.createObjectNode().put("year",value.path("year").asLong()).put("day",value.path("day").asInt()).put("period",value.path("period").asText());
  if(value.hasNonNull("minuteOfDay")){
   if(!value.path("minuteOfDay").isIntegralNumber() || value.path("minuteOfDay").asLong()<0 || value.path("minuteOfDay").asLong()>1439)throw new IllegalArgumentException("minuteOfDay须为0至1439的整数，无精确钟点时省略");
   out.put("minuteOfDay",value.path("minuteOfDay").asInt());
   int minute=out.path("minuteOfDay").asInt();int[] starts={0,300,480,720,780,1020,1140,1380};int period=0;for(int i=1;i<starts.length;i++)if(minute>=starts[i])period=i;out.put("period",PERIODS.get(period));
  }
  out.put("precision",out.has("minuteOfDay")?"MINUTE":"PERIOD");position(out);return out;
 }
 static long position(JsonNode point){
  try{long days=Math.addExact(Math.multiplyExact(point.path("year").asLong(),365),point.path("day").asLong()-1);int minutes=point.has("minuteOfDay")?point.path("minuteOfDay").asInt():REPRESENTATIVES[PERIODS.indexOf(point.path("period").asText())];return Math.addExact(Math.multiplyExact(days,86400),minutes*60L);}
  catch(ArithmeticException overflow){throw new IllegalArgumentException("故事时点超出可存储范围",overflow);}
 }
 public static String label(JsonNode point){return (point.path("year").asLong()>0?"第"+point.path("year").asLong()+"年·":"")+"第"+point.path("day").asInt()+"日·"+LABELS.get(PERIODS.indexOf(point.path("period").asText()));}
 static ObjectNode bind(ObjectNode frame,ObjectNode facts){
  var out=frame.deepCopy();var start=moment(!facts.path("currentMoment").isEmpty()?facts.path("currentMoment"):frame.path("startMoment"));var end=moment(frame.path("endMoment"));
  long begin=position(start),finish=position(end);if(finish<begin)throw new IllegalArgumentException("本篇终点早于存档起点；请修正同一候选的时点字段，不改已发表历史");
  var scenes=frame.path("scenes");if(!scenes.isArray() || scenes.isEmpty())throw new IllegalArgumentException("本篇缺少与正文对应的场景纲要");
  var keys=new HashSet<String>();long previous=begin;ObjectNode previousMoment=start;
  for(var scene:out.withArray("scenes")){
   if(!scene.isObject() || scene.path("key").asText().isBlank() || !keys.add(scene.path("key").asText()) || scene.path("content").asText().isBlank())throw new IllegalArgumentException("场景须有唯一key和实际经过content");
   var at=moment(scene.path("at"));String kind=scene.path("kind").asText("CURRENT");
   if(!Set.of("CURRENT","PARALLEL","FLASHBACK","IMAGINED").contains(kind))throw new IllegalArgumentException("场景kind类型不明确");
   if(Set.of("CURRENT","PARALLEL").contains(kind)){
    long when=position(at);if(when<begin || when>finish || "CURRENT".equals(kind) && when<previous)throw new IllegalArgumentException("实际场景时点须在本篇范围内，回忆或设想须明确标注");
    if("CURRENT".equals(kind)){
     var window=((ObjectNode)scene).putObject("calendarWindow");window.set("from",previousMoment.deepCopy());window.set("to",at.deepCopy());
     window.put("fromLabel",label(previousMoment)).put("toLabel",label(at));window.put("daysSincePassageStart",when/86400-begin/86400).put("daysSincePreviousScene",when/86400-previous/86400);
     window.put("meaning","at/to是本场结束时点；本场只写这个区间内的经过，过去事件的回述不重新发生，不在本场末尾另加区间外的次日尾声。");previous=when;previousMoment=at;
    }
   }
   ((ObjectNode)scene).set("at",at);((ObjectNode)scene).put("timeLabel",label(at));
  }
  out.set("startMoment",start);out.set("endMoment",end);out.remove(List.of("timeProgress","elapsedSeconds"));
  out.putObject("manuscriptEndpoint").put("label",label(end)).put("meaning","本篇最后的实际场景停在此时点，余韵也留在此刻；之后的次晨、几日后或新一轮生活留待下一次玩家行动，不为收尾擅自追加。").set("moment",end.deepCopy());
  var time=out.putObject("timeProgress").put("scope","THIS_PASSAGE").put("from",label(start)).put("to",label(end)).put("recordingMethod","FIXED_FRAME_COORDINATES").put("precision",start.has("minuteOfDay") && end.has("minuteOfDay")?"MINUTE":"PERIOD");
  time.set("startMoment",start);time.set("endMoment",end);time.putArray("elapsedParts").addObject().put("amount",finish-begin).put("unit","SECOND");time.put("basis","程序仅以同一份场景框架起止坐标换算；PERIOD秒数是兼容显示估值，不表示精确耗时。");
  return out;
 }
}
