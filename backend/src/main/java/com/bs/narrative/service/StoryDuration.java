package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.util.*;

/** Converts author-supplied quantities only; never parses or permits story events. */
final class StoryDuration {
 static final Map<String,Long> SECONDS_PER_UNIT=com.bs.narrative.llm.ModelProtocol.SECONDS_PER_UNIT;
 static final String CALENDAR_PROMPT="""
  【时长只做一次算术】短时或明确日期的经过可在timeProgress给calendarInterval:{from:{day:0,hour:起点钟点,minute:起点分钟},to:{day:结束日相对序号,hour:结束钟点,minute:结束分钟}}。
  day是本篇起点所在历日为0、翌日为1、此后依次累计的日期坐标，不是经过了几天；hour为0至23，minute为0至59。只填写你已写出的起止时点，不心算两者差值。服务器用两时点相减得到新增秒数。
  起点据publishedEndpoint与实际原文确认；若有clockAtEnd，from沿用它的钟点、分钟并把day归0。上午接到翌日上午，就是day0上午到day1上午；正文略去白天仍经过白天，不能把from悄悄改成夜里。起点当日午后到翌晨也按两坐标相减，不凑整日。明确无事经过多日则to.day如实增加。
  milestones可保留少量转折与原文短句，不必给累计耗时。calendarInterval优先于旧elapsedParts和各转折的心算数，不能同时另起一套起点。模糊时段由主创合理估计钟点，程序不按文字关键词判断发生了什么，也不改变故事。
  月年尺度的概述、归隐终老不必编造精确历日或换算数千天，省略calendarInterval，直接用elapsedParts的原单位WEEK/MONTH/YEAR。通用故事时钟约定WEEK=7日、MONTH=30日、YEAR=365日；这是存档显示的统一折算，不规定世界历法。若正文已明确特殊历法或确切起止日，则按该正文换算DAY或给日期坐标。数十年不能缩为数十DAY，也不能只记录最后一场的一个月；从本篇真正起点到最终停点的全部新增年月均须计入。模糊的几十年由主创结合年龄和已写经历合理估计年数，不为凑时钟扩写琐事。
  """;
 private StoryDuration() {}
 static OptionalLong calendarSeconds(JsonNode interval){
  var from=calendarPoint(interval.path("from"));var to=calendarPoint(interval.path("to"));
  if(from.isEmpty() || to.isEmpty() || to.getAsLong()<from.getAsLong())return OptionalLong.empty();
  return OptionalLong.of(to.getAsLong()-from.getAsLong());
 }
 private static OptionalLong calendarPoint(JsonNode point){
  var day=point.path("day");var hour=point.path("hour");var minute=point.path("minute");
  if(!day.isIntegralNumber() || !day.canConvertToLong() || day.asLong()<0 || !hour.isIntegralNumber() || !hour.canConvertToInt() || hour.asLong()<0 || hour.asLong()>23 || (!minute.isMissingNode() && (!minute.isIntegralNumber() || !minute.canConvertToInt() || minute.asLong()<0 || minute.asLong()>59)))return OptionalLong.empty();
  try{return OptionalLong.of(Math.addExact(Math.multiplyExact(day.asLong(),86400),Math.addExact(hour.asLong()*3600,minute.asLong()*60)));}
  catch(ArithmeticException overflow){return OptionalLong.empty();}
 }
 static OptionalLong seconds(JsonNode parts){
  if(!parts.isArray() || parts.isEmpty())return OptionalLong.empty();
  BigDecimal total=BigDecimal.ZERO;
  for(var part:parts){
   var unit=SECONDS_PER_UNIT.get(part.path("unit").asText());var amount=part.path("amount");
   if(unit==null || !amount.isNumber() || amount.decimalValue().signum()<0)return OptionalLong.empty();
   total=total.add(amount.decimalValue().multiply(BigDecimal.valueOf(unit)));
  }
  try{return OptionalLong.of(total.setScale(0,RoundingMode.HALF_UP).longValueExact());}
  catch(ArithmeticException overflow){return OptionalLong.empty();}
 }
}
