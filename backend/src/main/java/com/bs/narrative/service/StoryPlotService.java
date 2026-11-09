package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Important plot memory only. The model authors its meaning; this service stores and retrieves references. */
@Service
public class StoryPlotService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public StoryPlotService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private JsonNode decode(Object value){try{return JSON.readTree(Objects.toString(value,"null"));}catch(Exception e){throw new IllegalStateException("剧情资料无法读取",e);}}
    private String str(Object value){return Objects.toString(value,"");}
    private String id(String sid,long log,int frame,String kind,String key){return kind+"_"+UUID.nameUUIDFromBytes((sid+":"+log+":"+frame+":"+kind+":"+key).getBytes(StandardCharsets.UTF_8)).toString().replace("-","").substring(0,24);}
    private boolean exists(String table,String column,String sid,String id){return !id.isBlank() && jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE session_id=? AND "+column+"=?",Integer.class,sid,id)>0;}
    private JsonNode compact(JsonNode raw){
        var record=raw.deepCopy();if(record instanceof ObjectNode object){
            var facts=object.path("establishedFacts");if(facts.isArray() && facts.size()>6){var selection=object.putArray("establishedFacts");selection.add(facts.get(0)).add(facts.get(1));for(int i=facts.size()-4;i<facts.size();i++)selection.add(facts.get(i));object.put("archivedFactCount",facts.size());}
            var steps=object.path("developments");if(steps.isArray() && steps.size()>4){var selection=object.putArray("developments");for(int i=steps.size()-4;i<steps.size();i++)selection.add(steps.get(i));object.put("archivedDevelopmentCount",steps.size());}
        }return record;
    }

    /** Optional supplementary records cannot make a completed story unplayable because of an invented reference. */
    @org.springframework.transaction.annotation.Transactional
    public ArrayNode apply(String sid,long log,int frame,int round,long seconds,JsonNode updates){
        var issues=JSON.createArrayNode();if(updates.isMissingNode() || updates.isNull())return issues;
        if(!updates.isObject()){issues.add("plotUpdates应为对象；原回复保留在本轮归档中，正文照常保存。");return issues;}
        for(String field:List.of("events","foreshadows"))if(updates.has(field) && !updates.path(field).isArray())issues.add("plotUpdates."+field+"应为数组；格式有误的补充资料保留原回复，不中断正文。");
        var aliases=new LinkedHashMap<String,String>();var seen=new HashSet<String>();
        int index=0;
        for(var raw:updates.path("events").isArray()?updates.path("events"):JSON.createArrayNode()){
            index++;if(!(raw instanceof ObjectNode patch)){issues.add("忽略非对象的剧情条目");continue;}
            String ref=patch.path("eventId").asText(),key=patch.path("key").asText("event"+index),event=ref;
            if(key.isBlank())key="event"+index;
            if(ref.isBlank()){
                if(patch.path("title").asText().isBlank()){issues.add("新剧情缺少标题，未建立空条目");continue;}
                if(aliases.containsKey(key)){issues.add("新剧情临时key重复："+key);continue;}
                event=id(sid,log,frame,"evt",key);aliases.put(key,event);
            }else if(!exists("story_plot_event","event_id",sid,ref)){issues.add("剧情引用不存在于当前存档："+ref+"；沿用索引中的编号，不必重写正文。");continue;}
            if(!seen.add(event)){issues.add("同一批剧情编号重复："+event);continue;}
            save(sid,event,null,log,frame,round,seconds,patch);
        }
        index=0;seen.clear();
        for(var raw:updates.path("foreshadows").isArray()?updates.path("foreshadows"):JSON.createArrayNode()){
            index++;if(!(raw instanceof ObjectNode patch)){issues.add("忽略非对象的伏笔条目");continue;}
            String ref=patch.path("threadId").asText(),thread=ref,event=patch.path("eventId").asText();
            event=aliases.getOrDefault(event,event);
            if(!ref.isBlank()){
                var old=jdbc.queryForList("SELECT event_id FROM story_foreshadow WHERE session_id=? AND thread_id=?",sid,ref);
                if(old.isEmpty()){issues.add("伏笔引用不存在于当前存档："+ref);continue;}
                if(event.isBlank())event=str(old.get(0).get("event_id"));
            }else{
                if(patch.path("title").asText().isBlank()){issues.add("新伏笔缺少标题，未建立空条目");continue;}
                String key=patch.path("key").asText("thread"+index);if(key.isBlank())key="thread"+index;
                thread=id(sid,log,frame,"fo",key);
            }
            if(!exists("story_plot_event","event_id",sid,event)){issues.add("伏笔尚无可关联的重要剧情："+event+"；请在下一次归档补关联，不为此新增故事。");continue;}
            if(!seen.add(thread)){issues.add("同一批伏笔编号重复："+thread);continue;}
            save(sid,thread,event,log,frame,round,seconds,patch);
        }
        return issues;
    }

    private void save(String sid,String ref,String parent,long log,int frame,int round,long seconds,ObjectNode patch){
        boolean thread=parent!=null;String table=thread?"story_foreshadow":"story_plot_event",column=thread?"thread_id":"event_id";
        var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE session_id=? AND "+column+"=?",sid,ref);
        var old=rows.isEmpty()?Map.<String,Object>of():rows.get(0);
        var record=old.isEmpty()?JSON.createObjectNode():(ObjectNode)decode(old.get("record_json"));
        var origin=record.path("originalSource").isObject()?record.path("originalSource").deepCopy():firstSource(sid,ref,thread,record.path("source"));
        // Merge supplied fields only. Missing/empty model arrays must not erase established causes or the original setup.
        var fields=patch.properties();for(var field:fields){
            String name=field.getKey();var value=field.getValue();
            if(Set.of("eventId","threadId","key","playerView","source","originalSource").contains(name) || value.isNull())continue;
            if(value.isArray()){
                var existing=record.path(name).isArray()?(ArrayNode)record.path(name):record.putArray(name);
                for(var entry:value){boolean found=false;for(var prior:existing)if(prior.equals(entry)){found=true;break;}if(!found)existing.add(entry.deepCopy());}
            }else if(!value.isTextual() || !value.asText().isBlank())record.set(name,value.deepCopy());
        }
        String title=record.path("title").asText(ref);if(title.length()>240)title=title.substring(0,240);
        String state=record.path("status").asText("OPEN"),category=record.path("category").asText("OTHER");
        if(state.length()>32)state="OPEN";if(category.length()>32)category="OTHER";
        record.put("title",title).put("status",state);if(!thread)record.put("category",category);
        record.put(thread?"threadId":"eventId",ref);if(thread)record.put("eventId",parent);
        var source=JSON.createObjectNode().put("logId",log).put("frame",frame).put("round",round).put("storySeconds",seconds);
        source.put("quote",patch.path("sourceQuote").asText());
        // A display repair or uncommitted author plan is not a new factual
        // development. Keep the chapter that actually established this record.
        if(old.isEmpty() || !record.path("source").isObject()
                || !evidenceContent(record).equals(evidenceContent(decode(old.get("record_json")))))record.set("source",source.deepCopy());
        record.set("originalSource",origin.isObject() && !origin.isEmpty()?origin:source.deepCopy());
        JsonNode visible=old.isEmpty()?JSON.nullNode():decode(old.get("player_view"));
        if(patch.path("playerView").isObject() && !patch.at("/playerView/summary").asText().isBlank()){
            // This explicit projection is the only player-facing source in limited view. Never copy author notes.
            var view=JSON.createObjectNode();for(String field:List.of("title","summary","status","category")){
                if(visible.path(field).isTextual())view.set(field,visible.path(field).deepCopy());
                if(patch.path("playerView").path(field).isTextual())view.set(field,patch.path("playerView").path(field).deepCopy());
            }
            if(!view.hasNonNull("title") || view.path("title").asText().isBlank())view.put("title","已知剧情");
            // Repeating the same public knowledge during a private development
            // must not expose that hidden development through time or ordering.
            if(!withoutSource(view).equals(withoutSource(visible))){
                view.set("source",source.deepCopy());((ObjectNode)view.path("source")).remove("quote");visible=view;
            }
        }
        // A later projection of the same published chapter may complete a public
        // card omitted earlier. Conversely, unchanged cards in a new chapter must
        // not inflate history or replace their original evidence timestamp.
        if(!old.isEmpty() && withoutSource(record).equals(withoutSource(decode(old.get("record_json"))))
                && withoutSource(visible).equals(withoutSource(decode(old.get("player_view")))))return;
        String publicJson=visible.isNull()?null:visible.toString();
        if(thread)jdbc.update("INSERT INTO story_foreshadow(session_id,thread_id,event_id,title,status,record_json,player_view,source_log_id,source_frame,updated_seconds,round_no) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE event_id=VALUES(event_id),title=VALUES(title),status=VALUES(status),record_json=VALUES(record_json),player_view=VALUES(player_view),source_log_id=VALUES(source_log_id),source_frame=VALUES(source_frame),updated_seconds=VALUES(updated_seconds),round_no=VALUES(round_no)",sid,ref,parent,title,state,record.toString(),publicJson,log,frame,seconds,round);
        else jdbc.update("INSERT INTO story_plot_event(session_id,event_id,title,category,status,record_json,player_view,source_log_id,source_frame,updated_seconds,round_no) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE title=VALUES(title),category=VALUES(category),status=VALUES(status),record_json=VALUES(record_json),player_view=VALUES(player_view),source_log_id=VALUES(source_log_id),source_frame=VALUES(source_frame),updated_seconds=VALUES(updated_seconds),round_no=VALUES(round_no)",sid,ref,title,category,state,record.toString(),publicJson,log,frame,seconds,round);
        var snapshot=record.deepCopy();if(!visible.isNull())snapshot.set("playerView",visible.deepCopy());
        jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,location_name,event_type,actor_ids,event_description,importance,is_secret,info_source) VALUES(?,?,?,'',?,'[]',?,8,true,'PERSONAL')",sid,round,"故事第"+seconds+"秒",thread?"PLOT_THREAD":"PLOT_EVENT",snapshot.toString());
    }
    private JsonNode withoutSource(JsonNode value){
        var copy=value.deepCopy();if(copy instanceof ObjectNode object)object.remove("source");return copy;
    }
    private JsonNode evidenceContent(JsonNode value){
        var copy=value.deepCopy();if(copy instanceof ObjectNode object)object.remove(List.of("source","originalSource","authorNotes"));return copy;
    }

    private JsonNode firstSource(String sid,String ref,boolean thread,JsonNode fallback){
        String field=thread?"threadId":"eventId",type=thread?"PLOT_THREAD":"PLOT_EVENT";
        Long key=jdbc.queryForObject("SELECT MIN(entry_id) FROM world_book WHERE session_id=? AND event_type=? AND JSON_UNQUOTE(JSON_EXTRACT(event_description,'$."+field+"'))=?",Long.class,sid,type,ref);
        if(key!=null){var record=decode(jdbc.queryForObject("SELECT event_description FROM world_book WHERE session_id=? AND entry_id=?",String.class,sid,key));if(record.path("source").isObject())return record.path("source").deepCopy();}
        return fallback.deepCopy();
    }

    /** Fetch a few complete, short published sources. Never truncate a chapter or summarize it as new evidence. */
    public void attachPublishedSources(String sid,ObjectNode facts){
        var pages=facts.putArray("plotSourcePages");var known=new HashSet<Long>();
        for(var chapter:facts.path("previousChapters"))if(chapter.path("logId").asLong()>0)known.add(chapter.path("logId").asLong());
        if(facts.at("/openingPublishedChapter/logId").asLong()>0)known.add(facts.at("/openingPublishedChapter/logId").asLong());
        int remaining=6000;
        for(var entry:facts.at("/plotMemory/recentEvents")){
            var records=new ArrayList<JsonNode>();records.add(entry.path("event"));entry.path("foreshadows").forEach(records::add);
            for(var record:records){
                if(pages.size()>=3)return;boolean thread=record.has("threadId");String ref=record.path(thread?"threadId":"eventId").asText();if(ref.isBlank())continue;
                var origin=record.path("originalSource").isObject()?record.path("originalSource"):firstSource(sid,ref,thread,record.path("source"));
                for(var cited:List.of(record.path("source"),origin)){
                    if(pages.size()>=3)return;long log=cited.path("logId").asLong();if(log<1 || !known.add(log))continue;
                    var rows=jdbc.queryForList("SELECT round_no,processed_dialog FROM interaction_log WHERE session_id=? AND log_id=?",sid,log);if(rows.isEmpty())continue;
                    var page=pages.addObject().put("logId",log).put("round",((Number)rows.get(0).get("round_no")).intValue()).put("eventId",record.path("eventId").asText());if(thread)page.put("threadId",ref);
                    page.put("sourceRole",cited.equals(record.path("source"))?"LATEST_RECORDED_DEVELOPMENT":"ORIGINAL_SETUP");
                    String prose=str(rows.get(0).get("processed_dialog"));
                    if(prose.length()<=remaining){page.put("story",prose);remaining-=prose.length();}
                    else page.put("lookupNote","原章节较长，未截断或替换为摘要。需要原文时按eventId/threadId查询plotRequests并使用历史分页includePassage。");
                }
            }
        }
    }

    /** Small index plus recent records, not all plot history on every paid request. Main-author only. */
    public ObjectNode authorContext(String sid){
        var out=JSON.createObjectNode();out.put("meaning","重要剧情及伏笔的存档资料；事实、人物所知与authorNotes创作设想分开。编号只供关联和查阅，不触发剧情或预定结果。");
        var events=out.putArray("eventIndex");
        var rows=jdbc.queryForList("SELECT event_id,title,category,status FROM story_plot_event WHERE session_id=? ORDER BY updated_seconds DESC,source_log_id DESC,event_id LIMIT 100",sid);
        int recent=0;var details=out.putArray("recentEvents");
        var known=out.putArray("playerKnownState");
        for(var row:rows){
            var entry=events.addObject().put("eventId",str(row.get("event_id"))).put("title",str(row.get("title"))).put("category",str(row.get("category"))).put("status",str(row.get("status")));
            var record=decode(jdbc.queryForObject("SELECT record_json FROM story_plot_event WHERE session_id=? AND event_id=?",String.class,sid,row.get("event_id")));entry.set("openQuestion",record.path("openQuestion").deepCopy());
            if(recent++<4){var full=details.addObject();full.set("event",compact(record));var list=full.putArray("foreshadows");for(var thread:threads(sid,str(row.get("event_id")),true,0,8))list.add(compact(thread));full.put("lookupNote","此处为精简近期资料，原始记录完整保存；旧事实和更多伏笔按eventId/keyword查plotRequests，可带threadPage翻页。");}
            if(known.size()<12){
                // Keep the player's last saved knowledge beside author facts so the
                // same archive call can notice a stale public card without exposing
                // author-only changes or requiring a separate repair model call.
                var view=decode(jdbc.queryForObject("SELECT player_view FROM story_plot_event WHERE session_id=? AND event_id=?",String.class,sid,row.get("event_id")));
                var state=known.addObject().put("eventId",str(row.get("event_id")));state.set("eventView",view);var visibleThreads=state.putArray("foreshadows");for(var thread:threads(sid,str(row.get("event_id")),false,0,8))visibleThreads.add(thread);
            }
        }
        out.put("indexHasMore",jdbc.queryForObject("SELECT COUNT(*) FROM story_plot_event WHERE session_id=?",Long.class,sid)>rows.size());
        out.put("publicCardReviewHasMore",rows.size()>known.size());
        out.put("lookup","plotRequests:[{eventId或threadId或keyword,category可选,limit:4,beforeEntryId可选,includePassage:false}]。可按编号查旧事实、原始铺垫和进展；关键词是资料查询，不是剧情触发条件。");
        out.put("playerKnownStateMeaning","playerKnownState是玩家面板上次保存的可见状态，可能落后于已发表正文。归档时仅依据玩家实际见闻补playerView；作者已解决不代表玩家已知，不能用作者状态自动覆盖限知卡。");
        return out;
    }

    /** Uses the existing author lookup round trip; there is no extra evaluator or plot model call. */
    public ArrayNode lookup(String sid,JsonNode requests){
        var out=JSON.createArrayNode();if(!requests.isArray())return out;
        int count=0;for(var request:requests){if(count++>=4)break;
            int limit=Math.max(1,Math.min(6,request.path("limit").asInt(4)));long before=request.path("beforeEntryId").asLong(Long.MAX_VALUE);if(before<1)before=Long.MAX_VALUE;
            String event=request.path("eventId").asText(),thread=request.path("threadId").asText(),keyword=request.path("keyword").asText(),category=request.path("category").asText();
            var page=out.addObject();page.set("request",request.deepCopy());var records=page.putArray("records");
            if(!thread.isBlank()){
                for(var row:jdbc.queryForList("SELECT record_json FROM story_foreshadow WHERE session_id=? AND thread_id=?",sid,thread))records.add(decode(row.get("record_json")));
            }else{
                var args=new ArrayList<Object>();args.add(sid);String where="session_id=?";
                if(!event.isBlank()){where+=" AND event_id=?";args.add(event);}
                if(!keyword.isBlank()){where+=" AND (LOCATE(?,title)>0 OR LOCATE(?,CAST(record_json AS CHAR))>0 OR EXISTS (SELECT 1 FROM story_foreshadow f WHERE f.session_id=story_plot_event.session_id AND f.event_id=story_plot_event.event_id AND LOCATE(?,CAST(f.record_json AS CHAR))>0))";args.add(keyword);args.add(keyword);args.add(keyword);}
                if(!category.isBlank()){where+=" AND category=?";args.add(category);}
                args.add(limit);
                // Materialize only small metadata before filesort. MySQL can otherwise carry WHERE's JSON
                // expressions into the sort tuple even when the outer projection asks only for IDs.
                for(var row:jdbc.queryForList("SELECT matched.event_id FROM (SELECT event_id,updated_seconds,source_log_id FROM story_plot_event WHERE "+where+" LIMIT 18446744073709551615) matched ORDER BY matched.updated_seconds DESC,matched.source_log_id DESC,matched.event_id LIMIT ?",args.toArray())){
                    int threadPage=Math.max(0,Math.min(100000,request.path("threadPage").asInt()));String ref=str(row.get("event_id"));
                    var detail=records.addObject();detail.set("event",decode(jdbc.queryForObject("SELECT record_json FROM story_plot_event WHERE session_id=? AND event_id=?",String.class,sid,ref)));detail.set("foreshadows",threads(sid,ref,true,threadPage,20));detail.put("threadPage",threadPage);detail.put("threadHasMore",jdbc.queryForObject("SELECT COUNT(*) FROM story_foreshadow WHERE session_id=? AND event_id=?",Long.class,sid,ref)>(threadPage+1L)*20);
                }
            }
            if(!event.isBlank() || !thread.isBlank()){
                String ref=thread.isBlank()?event:thread,field=thread.isBlank()?"eventId":"threadId",type=thread.isBlank()?"PLOT_EVENT":"PLOT_THREAD";
                var history=page.putArray("history");var rows=jdbc.queryForList("SELECT matched.entry_id FROM (SELECT entry_id FROM world_book WHERE session_id=? AND event_type=? AND JSON_UNQUOTE(JSON_EXTRACT(event_description,'$."+field+"'))=? AND entry_id<? LIMIT 18446744073709551615) matched ORDER BY matched.entry_id DESC LIMIT ?",sid,type,ref,before,limit);
                for(var row:rows){var entry=history.addObject().put("entryId",((Number)row.get("entry_id")).longValue());entry.set("record",decode(jdbc.queryForObject("SELECT event_description FROM world_book WHERE session_id=? AND entry_id=?",String.class,sid,row.get("entry_id"))));}
                if(!rows.isEmpty())page.put("nextBeforeEntryId",((Number)rows.get(rows.size()-1).get("entry_id")).longValue());
                if(request.path("includePassage").asBoolean() && !records.isEmpty()){
                    var record=records.get(0);var selected=history.isEmpty()?(record.has("event")?record.path("event"):record):history.get(0).path("record");long log=selected.at("/source/logId").asLong(-1);
                    var prose=jdbc.queryForList("SELECT processed_dialog FROM interaction_log WHERE session_id=? AND log_id=?",String.class,sid,log);if(!prose.isEmpty())page.put("publishedPassage",prose.get(0));
                }
            }
        }return out;
    }

    private ArrayNode threads(String sid,String event,boolean author,int page,int limit){
        var out=JSON.createArrayNode();String visible=author?"":" AND player_view IS NOT NULL";
        String time=author?"updated_seconds":"CAST(JSON_UNQUOTE(JSON_EXTRACT(player_view,'$.source.storySeconds')) AS UNSIGNED)";
        for(var key:jdbc.queryForList("SELECT matched.thread_id FROM (SELECT thread_id,"+time+" AS sort_seconds FROM story_foreshadow WHERE session_id=? AND event_id=?"+visible+" LIMIT 18446744073709551615) matched ORDER BY matched.sort_seconds DESC,matched.thread_id LIMIT ? OFFSET ?",sid,event,limit,page*limit)){
            var row=jdbc.queryForMap("SELECT thread_id,record_json,player_view FROM story_foreshadow WHERE session_id=? AND thread_id=?",sid,key.get("thread_id"));
            if(author)out.add(decode(row.get("record_json")));else{var view=(ObjectNode)decode(row.get("player_view"));view.put("threadId",str(row.get("thread_id")));out.add(view);}
        }return out;
    }

    /** All fields are explicitly projected. A limited player never searches private titles, states or explanations. */
    public Map<String,Object> browse(String sid,boolean omni,String keyword,String eventId,String category,int page,int threadPage){
        keyword=Objects.requireNonNullElse(keyword,"").strip();eventId=Objects.requireNonNullElse(eventId,"");category=Objects.requireNonNullElse(category,"");
        if(keyword.length()>120 || eventId.length()>40 || category.length()>32 || page<0 || page>100000 || threadPage<0 || threadPage>100000)throw new IllegalArgumentException("剧情查询参数无效");
        var args=new ArrayList<Object>();args.add(sid);String where="e.session_id=?"+(omni?"":" AND e.player_view IS NOT NULL");
        if(!eventId.isBlank()){where+=" AND e.event_id=?";args.add(eventId);}
        String view=omni?"e.record_json":"e.player_view";
        if(!category.isBlank()){where+=" AND JSON_UNQUOTE(JSON_EXTRACT("+view+",'$.category'))=?";args.add(category);}
        if(!keyword.isBlank()){
            // Even omniscient UI searches actual story memory, not unrevealed author planning notes.
            where+=" AND (LOCATE(?,"+searchable("e",omni,false)+")>0 OR EXISTS (SELECT 1 FROM story_foreshadow f WHERE f.session_id=e.session_id AND f.event_id=e.event_id"+(omni?"":" AND f.player_view IS NOT NULL")+" AND LOCATE(?,"+searchable("f",omni,true)+")>0))";
            args.add(keyword);args.add(keyword);
        }
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM story_plot_event e WHERE "+where,Long.class,args.toArray());
        var queryArgs=new ArrayList<>(args);queryArgs.add(page*12);var result=JSON.createArrayNode();
        String time=omni?"e.updated_seconds":"CAST(JSON_UNQUOTE(JSON_EXTRACT(e.player_view,'$.source.storySeconds')) AS UNSIGNED)";
        // Sort reference metadata only; accumulated long JSON must not occupy MySQL's sort buffer.
        for(var key:jdbc.queryForList("SELECT matched.event_id FROM (SELECT e.event_id,"+time+" AS sort_seconds FROM story_plot_event e WHERE "+where+" LIMIT 18446744073709551615) matched ORDER BY matched.sort_seconds DESC,matched.event_id LIMIT 12 OFFSET ?",queryArgs.toArray())){
            var row=jdbc.queryForMap("SELECT event_id,record_json,player_view FROM story_plot_event WHERE session_id=? AND event_id=?",sid,key.get("event_id"));
            var record=decode(row.get(omni?"record_json":"player_view"));var item=result.addObject().put("eventId",str(row.get("event_id")));
            for(String field:omni?List.of("title","category","status","openQuestion","establishedFacts","development","outcome","source"):List.of("title","category","status","summary","source"))if(record.has(field))item.set(field,record.path(field).deepCopy());
            String ref=str(row.get("event_id"));var list=item.putArray("foreshadows");
            var raw=omni?threads(sid,ref,true,threadPage,20):JSON.createArrayNode();
            if(omni)for(var thread:raw){var detail=list.addObject();for(String field:List.of("threadId","title","status","setup","question","developments","payoff","knownBy","source"))if(thread.has(field))detail.set(field,thread.path(field).deepCopy());}
            else item.set("foreshadows",threads(sid,ref,false,threadPage,20));
            long n=jdbc.queryForObject("SELECT COUNT(*) FROM story_foreshadow WHERE session_id=? AND event_id=?"+(omni?"":" AND player_view IS NOT NULL"),Long.class,sid,ref);item.put("threadHasMore",(threadPage+1L)*20<n);
        }
        return Map.of("events",result,"total",total,"page",page,"hasMore",(page+1L)*12<total,"threadPage",threadPage);
    }
    private String searchable(String alias,boolean omni,boolean thread){
        if(!omni)return "CAST("+alias+".player_view AS CHAR)";
        var fields=thread?List.of("title","setup","question","developments","payoff"):List.of("title","openQuestion","establishedFacts","development","outcome");
        var expressions=new ArrayList<String>();for(String field:fields){expressions.add("'"+field+"'");expressions.add("JSON_EXTRACT("+alias+".record_json,'$."+field+"')");}
        return "CAST(JSON_OBJECT("+String.join(",",expressions)+") AS CHAR)";
    }
}
