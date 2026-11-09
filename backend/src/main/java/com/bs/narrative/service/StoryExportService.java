package com.bs.narrative.service;

import com.bs.narrative.exception.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/** A bounded-page, repeatable-read export of published prose; never reads model candidates or calls a model. */
@Service
public class StoryExportService {
 public static final String COMPLETE_MARKER="—— 故事文本导出完毕 ——";
 private static final int PAGE_SIZE=100;
 private static final Set<String> TERMINAL=Set.of("DEAD","RETIRED","SUMMARIZED","VICTORY");
 private static final String PUBLIC_COLUMNS="log_id,COALESCE(round_no,0) AS story_round,processed_dialog,"
  +"CASE WHEN JSON_TYPE(JSON_EXTRACT(rule_snapshot,'$.timeProgress.from'))='STRING' THEN JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.timeProgress.from')) END AS story_from,"
  +"CASE WHEN JSON_TYPE(JSON_EXTRACT(rule_snapshot,'$.timeProgress.to'))='STRING' THEN JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.timeProgress.to')) END AS story_to,"
  +"JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.operationStatus')) AS operation_status";
 private final JdbcTemplate jdbc;private final AuthService auth;private final WorldSettingService worlds;private final GameTranscriptService transcripts;
 private final ObjectMapper json=new ObjectMapper();
 public StoryExportService(JdbcTemplate jdbc,AuthService auth,WorldSettingService worlds,GameTranscriptService transcripts){this.jdbc=jdbc;this.auth=auth;this.worlds=worlds;this.transcripts=transcripts;}
 public record Download(String filename,long branchEpoch,long revision){}
 private record Chapter(int round,String from,String to,String status,List<Map<String,Object>> actors){}
 private static final class Cursor {boolean first=true,done;int round;long id;}

 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public void write(String sid,String player,OutputStream output,Consumer<Download> ready)throws IOException{
  if(player==null || player.isBlank())throw new BusinessException(ErrorCode.AUTH_REQUIRED,"请先登录");
  auth.requireOwnedSession(sid,player);
  var session=jdbc.queryForMap("SELECT status,player_god_id,death_summary FROM game_session WHERE session_id=? AND player_id=?",sid,player);
  var versions=jdbc.queryForList("SELECT branch_epoch,revision FROM game_runtime WHERE session_id=?",sid);
  long branch=versions.isEmpty()?0:((Number)versions.get(0).get("branch_epoch")).longValue();
  long revision=versions.isEmpty()?0:((Number)versions.get(0).get("revision")).longValue();
  var world=worlds.find(sid);String title=world.path("title").asText("此生行记").strip();if(title.isEmpty())title="此生行记";
  var names=transcripts.speakerNames(sid);String hero=Objects.toString(session.get("player_god_id"),"");String name=names.getOrDefault(hero,"");
  String opening=Objects.toString(transcripts.opening(sid,player).get("text"),"");
  String[] ending=ending(sid,hero,session);
  var cursor=new Cursor();var chapters=nextNonemptyPage(sid,player,cursor,names);
  if(opening.isBlank() && chapters.isEmpty() && ending[0].isBlank() && ending[1].isBlank())throw new BusinessException(ErrorCode.PARAM_INVALID,"此存档还没有可以导出的已发表故事");
  // Headers and the first byte follow validation. All subsequent pages share
  // this same database snapshot even if a chapter commits or a rewind occurs.
  ready.accept(new Download(filename(title,name,branch),branch,revision));
  var out=new OutputStreamWriter(output,StandardCharsets.UTF_8);out.write('\ufeff');
  section(out,title);if(!name.isBlank())paragraph(out,"主角："+oneLine(name));
  paragraph(out,"导出范围：当前存档已留存的故事 · 分支 "+branch);
  paragraph(out,"导出时间："+OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")));
  paragraph(out,"生成中的未发表内容不在本次导出范围内。");
  String introduction=world.path("introduction").asText();if(!introduction.isBlank()){section(out,"卷首 · 此间天地");paragraph(out,introduction);}
  if(!opening.isBlank()){section(out,"序章 · 来路");paragraph(out,opening);}
  int count=0;
  while(!chapters.isEmpty()){
   for(var chapter:chapters){
    count++;String suffix=Set.of("RUNNING","PARTIAL","CANCELLED").contains(chapter.status())?" · 已留存片段":"";
    section(out,"第 "+chapter.round()+" 段故事"+suffix);
    if(!chapter.from().isBlank() || !chapter.to().isBlank())paragraph(out,"故事时刻："+oneLine(chapter.from())+(chapter.from().isBlank() || chapter.to().isBlank() || chapter.from().equals(chapter.to())?"":" → ")+(!chapter.from().equals(chapter.to())?oneLine(chapter.to()):""));
    for(var actor:chapter.actors()){
     String id=actor.get("godId").toString(),text=actor.get("displayedDialogue").toString();
     // Remove only a duplicated serializer prefix at the start of this known
     // speaker's public segment; the stored story itself is never rewritten.
     while(text.startsWith(id+"："))text=text.substring(id.length()+1);
     if(!text.isBlank())paragraph(out,("narrator".equals(id)?"":oneLine(actor.get("godName").toString())+"：")+text);
    }
   }
   out.flush();chapters=nextNonemptyPage(sid,player,cursor,names);
  }
  if(!ending[0].isBlank()){section(out,"终章 · 此生回望");paragraph(out,ending[0]);}
  if(!ending[1].isBlank()){section(out,"此生评价");paragraph(out,ending[1]);}
  paragraph(out,"本次共导出 "+count+" 段已发表故事。");out.write(COMPLETE_MARKER+"\r\n");out.flush();
 }
 private List<Chapter> nextNonemptyPage(String sid,String player,Cursor cursor,Map<String,String> names){
  while(!cursor.done){
   var args=new ArrayList<Object>(List.of(sid,player));String after="";
   if(!cursor.first){after=" AND (COALESCE(round_no,0)>? OR (COALESCE(round_no,0)=? AND log_id>?))";args.add(cursor.round);args.add(cursor.round);args.add(cursor.id);}
   var rows=jdbc.queryForList("SELECT "+PUBLIC_COLUMNS+" FROM interaction_log WHERE session_id=? AND player_id=?"+after+" ORDER BY COALESCE(round_no,0),log_id LIMIT "+PAGE_SIZE,args.toArray());
   cursor.first=false;cursor.done=rows.size()<PAGE_SIZE;var chapters=new ArrayList<Chapter>();
   for(var row:rows){cursor.round=((Number)row.get("story_round")).intValue();cursor.id=((Number)row.get("log_id")).longValue();
    var actors=GameTranscriptService.publicActors(Objects.toString(row.get("processed_dialog"),""),names).stream().filter(a->!a.get("displayedDialogue").toString().isBlank()).toList();
    if(!actors.isEmpty())chapters.add(new Chapter(cursor.round,Objects.toString(row.get("story_from"),""),Objects.toString(row.get("story_to"),""),Objects.toString(row.get("operation_status"),""),actors));
   }
   if(!chapters.isEmpty())return chapters;
  }
  return List.of();
 }
 private String[] ending(String sid,String role,Map<String,Object> session){
  if(!TERMINAL.contains(Objects.toString(session.get("status"),"")))return new String[]{"",""};
  String summary=Objects.toString(session.get("death_summary"),""),evaluation="";
  var rows=jdbc.queryForList("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref='life:ending' AND source_type='LIFE_ENDING'",String.class,sid,role);
  if(!rows.isEmpty())try{var value=json.readTree(rows.get(0));if(value.path("death_summary").isTextual())summary=value.path("death_summary").asText();if(value.path("final_evaluation").isTextual())evaluation=value.path("final_evaluation").asText();}catch(com.fasterxml.jackson.core.JsonProcessingException legacy){/* The already-public session summary remains available. */}
  return new String[]{summary,evaluation};
 }
 private static void paragraph(Writer out,String text)throws IOException{out.write(text.replace("\r\n","\n").replace('\r','\n').replace("\n","\r\n"));out.write("\r\n\r\n");}
 private static void section(Writer out,String title)throws IOException{paragraph(out,"════════ "+oneLine(title)+" ════════");}
 private static String oneLine(String text){return text.replaceAll("[\\p{Cntrl}]"," ").strip();}
 private static String filename(String title,String name,long branch){
  String base=title+(name.isBlank()?"":"-"+name)+"-故事-分支"+branch;var safe=new StringBuilder();
  base.codePoints().limit(100).forEach(c->{if(c<32 || c==127 || "<>:\"/\\|?*".indexOf(c)>=0)safe.append('_');else safe.appendCodePoint(c);});
  return safe.toString().strip().replaceAll("[. ]+$","")+".txt";
 }
}
