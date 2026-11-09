package com.bs.narrative.service;

import com.bs.narrative.exception.*;
import com.bs.narrative.repository.GameSceneRepository;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** Player-facing read model. Private dossiers never enter the limited-view result. No LLM calls. */
@Service
public class WorldBookBrowserService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int PAGE_SIZE=20;
    private static final String KNOWN_SOURCES="'PERSONAL','HEARD','TOLD','INQUIRED','INVESTIGATED','SOCIAL'";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final AuthService auth;
    private final CharacterDossierService dossiers;
    private final GameSceneRepository scenes;
    private final PlayerSceneProjectionService projection;
    @org.springframework.beans.factory.annotation.Autowired private StoryPlotService plots;
    @org.springframework.beans.factory.annotation.Autowired private PlayerJournalService portraitJournal;
    public WorldBookBrowserService(JdbcTemplate jdbc,AuthService auth,CharacterDossierService dossiers,
            GameSceneRepository scenes,PlayerSceneProjectionService projection){
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.auth=auth;
        this.dossiers=dossiers;this.scenes=scenes;this.projection=projection;
    }
    private JsonNode decode(Object raw){try{return JSON.readTree(Objects.toString(raw,"null"));}catch(Exception e){return JSON.getNodeFactory().textNode(Objects.toString(raw,""));}}
    private long number(Object raw){return raw instanceof Number n?n.longValue():0;}
    private String text(Object raw){return Objects.toString(raw,"");}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> browsePlots(String sid,String pid,String keyword,String eventId,String category,int page,int threadPage){
        auth.requireOwnedSession(sid,pid);
        String mode=jdbc.queryForObject("SELECT view_mode FROM game_session WHERE session_id=?",String.class,sid);
        try{
            var result=new LinkedHashMap<>(plots.browse(sid,"OMNISCIENT".equals(mode),keyword,eventId,category,page,threadPage));
            result.put("sessionId",sid);result.put("viewMode",mode);return result;
        }catch(IllegalArgumentException invalid){throw new BusinessException(ErrorCode.PARAM_INVALID,invalid.getMessage());}
    }

    @Transactional(readOnly=true)
    public Map<String,Object> plotPassage(String sid,String pid,long logId){
        auth.requireOwnedSession(sid,pid);
        var rows=jdbc.queryForList("SELECT log_id,round_no,processed_dialog FROM interaction_log WHERE session_id=? AND player_id=? AND log_id=?",sid,pid,logId);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.PARAM_INVALID,"此存档没有这段已发布故事");
        return Map.of("sessionId",sid,"logId",logId,"round",rows.get(0).get("round_no"),"story",text(rows.get(0).get("processed_dialog")));
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> browse(String sid,String pid,String keyword,String role,Long from,Long to,int page){
        auth.requireOwnedSession(sid,pid);
        keyword=Objects.requireNonNullElse(keyword,"").strip();role=Objects.requireNonNullElse(role,"");
        if(keyword.length()>120 || page<0 || page>100000 || (from!=null && from<0) || (to!=null && to<0) || (from!=null && to!=null && from>to))
            throw new BusinessException(ErrorCode.PARAM_INVALID,"请检查查询词、页码和世界时间范围");
        var session=jdbc.queryForMap("SELECT s.player_god_id,COALESCE(p.current_scene_id,s.current_scene_id) current_scene_id,s.view_mode FROM game_session s LEFT JOIN player_profile p ON p.player_id=s.player_id AND p.active_session_id=s.session_id WHERE s.session_id=?",sid);
        String self=text(session.get("player_god_id"));
        if(self.isBlank())self=jdbc.queryForList("SELECT p.god_id FROM player_profile p JOIN god_profile g ON g.god_id=p.god_id AND g.session_id=? WHERE p.player_id=?",String.class,sid,pid).stream().findFirst().orElse("");
        boolean omni="OMNISCIENT".equals(session.get("view_mode"));
        var versions=jdbc.queryForList("SELECT branch_epoch,revision,story_seconds FROM game_runtime WHERE session_id=?",sid);
        var version=versions.isEmpty()?Map.<String,Object>of():versions.get(0);
        long now=number(version.get("story_seconds"));
        var params=new HashMap<String,Object>();params.put("sid",sid);params.put("pid",pid);params.put("self",self);
        // A global public event is not necessarily known to the player. Limited view reads only delivered prose/knowledge.
        String sources="""
            SELECT CONCAT('chapter:',log_id) entry_key,log_id rank_id,'CHAPTER' kind,
              CONCAT('第 ',round_no,' 段故事') title,processed_dialog content,'' location_name,'' story_time,
              CASE WHEN JSON_TYPE(JSON_EXTRACT(rule_snapshot,'$.storyStartSeconds'))='INTEGER' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.storyStartSeconds')) AS SIGNED) ELSE NULL END start_seconds,
              CASE WHEN JSON_TYPE(JSON_EXTRACT(rule_snapshot,'$.storySeconds'))='INTEGER' THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(rule_snapshot,'$.storySeconds')) AS SIGNED) ELSE NULL END end_seconds,
              JSON_ARRAY() actor_ids,'亲历故事' source_label,NULL learned_tick
            FROM interaction_log WHERE session_id=:sid AND player_id=:pid
            UNION ALL
            SELECT CONCAT('known:',knowledge_id),knowledge_id,'KNOWN',title,content,location_name,'',NULL,NULL,
              JSON_ARRAY(source_god_id),'已获知记录',learned_tick
            FROM role_knowledge WHERE session_id=:sid AND god_id=:self AND source_type IN (
            """+KNOWN_SOURCES+")";
        if(omni)sources+="""
            UNION ALL
            SELECT CONCAT('world:',entry_id),entry_id,event_type,event_type,event_description,location_name,story_time,
              CASE WHEN story_time REGEXP '^故事第[0-9]+秒$' THEN CAST(SUBSTRING(story_time,4,CHAR_LENGTH(story_time)-4) AS SIGNED) ELSE NULL END,
              CASE WHEN story_time REGEXP '^故事第[0-9]+秒$' THEN CAST(SUBSTRING(story_time,4,CHAR_LENGTH(story_time)-4) AS SIGNED) ELSE NULL END,
              actor_ids,'天眼档案',NULL
            FROM world_book WHERE session_id=:sid AND event_type IN
              ('CHARACTER_DOSSIER','CHARACTER_HISTORY','STORY_MILESTONE','MOVE','COMBAT','DIALOGUE','INQUIRY','ITEM_GAIN','ITEM_LOSS','NPC_DEATH','NPC_SPAWN','DEATH')
            """;
        var active=cards(sid,pid,self,text(session.get("current_scene_id")),omni,now);
        var roles=new ArrayList<>(knownRoles(sid,pid,self,omni));
        for(var card:active)if(roles.stream().noneMatch(r->r.get("id").equals(card.get("id"))))roles.add(Map.of("id",card.get("id"),"name",card.get("name")));
        String roleName="";
        if(!role.isBlank()){
            String selected=role;
            roleName=roles.stream().filter(r->selected.equals(r.get("id"))).map(r->text(r.get("name"))).findFirst().orElse("");
            // Unknown IDs and nonexistent IDs have identical empty results; no private existence oracle.
        }
        String where=" WHERE 1=1";
        if(!keyword.isBlank()){where+=" AND (LOCATE(:keyword,content)>0 OR LOCATE(:keyword,title)>0 OR LOCATE(:keyword,location_name)>0)";params.put("keyword",keyword);}
        if(!role.isBlank()){
            where+=roleName.isBlank()?" AND 1=0":" AND (JSON_CONTAINS(actor_ids,JSON_QUOTE(:role)) OR LOCATE(:role,content)>0 OR LOCATE(:roleName,content)>0 OR (kind='CHAPTER' AND :role=:self))";
            params.put("role",role);params.put("roleName",roleName);
        }
        if(from!=null){where+=" AND end_seconds>=:from";params.put("from",from);}
        if(to!=null){where+=" AND COALESCE(start_seconds,end_seconds)<=:to";params.put("to",to);}
        String base=" FROM ("+sources+") book"+where;
        long total=named.queryForObject("SELECT COUNT(*)"+base,params,Long.class);
        params.put("offset",(long)page*PAGE_SIZE);
        var entries=new ArrayList<Map<String,Object>>();
        // Sort only small metadata. Sorting long chapter/dossier text can exhaust MySQL's sort buffer in long saves.
        for(var row:named.queryForList("SELECT entry_key,kind,title,location_name,story_time,start_seconds,end_seconds,source_label,learned_tick"+base+" ORDER BY COALESCE(end_seconds,-1) DESC,rank_id DESC,entry_key DESC LIMIT 20 OFFSET :offset",params)){
            var entry=new LinkedHashMap<String,Object>();
            for(String key:List.of("entry_key","kind","title","location_name","story_time","start_seconds","end_seconds","source_label","learned_tick"))entry.put(key,row.get(key));
            String key=text(row.get("entry_key"));long id=Long.parseLong(key.substring(key.indexOf(':')+1));
            String raw=key.startsWith("chapter:")?jdbc.queryForObject("SELECT processed_dialog FROM interaction_log WHERE session_id=? AND player_id=? AND log_id=?",String.class,sid,pid,id):
                key.startsWith("known:")?jdbc.queryForObject("SELECT content FROM role_knowledge WHERE session_id=? AND god_id=? AND knowledge_id=?",String.class,sid,self,id):
                jdbc.queryForObject("SELECT event_description FROM world_book WHERE session_id=? AND entry_id=?",String.class,sid,id);
            if("CHARACTER_DOSSIER".equals(row.get("kind")))entry.put("content",displayDossier(decode(raw)));
            else if("CHARACTER_HISTORY".equals(row.get("kind")))entry.put("content",decode(raw));
            else entry.put("content",text(raw));
            entries.add(entry);
        }
        var result=new LinkedHashMap<String,Object>();result.put("sessionId",sid);result.put("viewMode",omni?"OMNISCIENT":"DREAMER");
        result.put("branchEpoch",number(version.get("branch_epoch")));result.put("revision",number(version.get("revision")));result.put("storySeconds",now);
        result.put("roles",roles);result.put("activeCharacters",active);
        result.put("entries",entries);result.put("total",total);result.put("page",page);result.put("hasMore",((long)page+1)*PAGE_SIZE<total);
        result.put("timeNote","时间按故事秒数查询，覆盖该时刻的整段故事也会列出；旧记录或见闻只有世界刻数时，保留原时标，不猜测秒数，填写秒数范围后不列出这些记录。");
        return result;
    }

    private List<Map<String,Object>> knownRoles(String sid,String pid,String self,boolean omni){
        String known=omni?"":" AND (g.god_id=? OR EXISTS (SELECT 1 FROM role_knowledge k WHERE k.session_id=g.session_id AND k.god_id=? AND ((k.source_type='OBSERVED_STATE' AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(k.content),k.content,'{}'),'$.id'))=g.god_id) OR (k.source_type IN ("+KNOWN_SOURCES+") AND (k.source_god_id=g.god_id OR LOCATE(g.god_name,k.content)>0)))) OR EXISTS (SELECT 1 FROM interaction_log l WHERE l.session_id=g.session_id AND l.player_id=? AND (LOCATE(g.god_id,l.processed_dialog)>0 OR LOCATE(g.god_name,l.processed_dialog)>0)))";
        var rows=omni?jdbc.queryForList("SELECT g.god_id,g.god_name FROM god_profile g WHERE session_id=? ORDER BY god_name",sid):
            jdbc.queryForList("SELECT g.god_id,g.god_name FROM god_profile g WHERE session_id=?"+known+" ORDER BY god_name",sid,self,self,pid);
        return rows.stream().map(r->Map.<String,Object>of("id",r.get("god_id"),"name",r.get("god_name"))).toList();
    }
    /** Extras retain six small fields; no full history, recursive dossier or ornate primary-character panel. */
    private JsonNode displayDossier(JsonNode raw){
        if(!"EXTRA".equals(raw.path("narrativeTier").asText()))return raw;
        var out=JSON.createObjectNode();
        for(String key:List.of("name","narrativeTier","snapshotAtSeconds"))if(raw.has(key))out.set(key,raw.get(key));
        var brief=out.putObject("brief");
        for(String key:List.of("roleInScene","immediateConcern","attitude","willingHelp","knowledgeLimits","boundaries"))if(raw.path("psychology").has(key))brief.set(key,raw.path("psychology").get(key));
        return out;
    }
    private List<Map<String,Object>> cards(String sid,String pid,String self,String sceneId,boolean omni,long now){
        var scene=scenes.findBySessionIdAndSceneId(sid,sceneId);
        if(scene.isEmpty() || (!omni && self.isBlank()))return List.of();
        var observations=new LinkedHashMap<String,Map<String,Object>>();
        if(omni){
            for(String id:scene.get().getPresentGods())if(!id.equals(self))observations.put(id,Map.of("basis","当前场景人物"));
            var starts=jdbc.queryForList("SELECT JSON_EXTRACT(rule_snapshot,'$.storyStartSeconds') FROM interaction_log WHERE session_id=? AND player_id=? ORDER BY log_id DESC LIMIT 1",String.class,sid,pid);
            JsonNode start=starts.isEmpty()?JSON.nullNode():decode(starts.get(0));
            // Include genuinely updated off-scene characters from the latest passage, without inventing a background simulation.
            if(start.isIntegralNumber() && start.asLong()>=0 && start.asLong()<=now)
                for(var row:jdbc.queryForList("SELECT event_description FROM world_book WHERE session_id=? AND event_type='CHARACTER_DOSSIER' AND story_time REGEXP '^故事第[0-9]+秒$' AND CAST(SUBSTRING(story_time,4,CHAR_LENGTH(story_time)-4) AS SIGNED) BETWEEN ? AND ?",sid,start.asLong(),now)){
                    var snapshot=decode(row.get("event_description"));String id=snapshot.path("id").asText();
                    if(!id.isBlank() && !id.equals(self))observations.putIfAbsent(id,Map.of("basis","本段有档案更新 · 所在以档案时刻为准"));
                }
        }
        else for(var seen:projection.bodies(sid,scene.get(),self))observations.put(text(seen.get("id")),seen);
        var result=new ArrayList<Map<String,Object>>();
        var appearances=portraitJournal.publicAppearances(sid,self);
        for(var person:observations.entrySet()){
            var names=jdbc.queryForList("SELECT god_name FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,person.getKey());
            if(names.isEmpty())continue;
            var card=new LinkedHashMap<String,Object>();card.put("id",person.getKey());card.put("name",names.get(0));
            if(appearances.containsKey(person.getKey()))card.put("publicAppearance",appearances.get(person.getKey()));
            card.put("basis",omni?person.getValue().get("basis"):"此地最近所见，并非实时追踪");
            if(omni){
                var dossier=dossiers.previousDossier(sid,person.getKey());
                card.put("tier",dossier.path("narrativeTier").asText("EXTRA"));card.put("dossier",displayDossier(dossier));
                if(dossier.has("snapshotAtSeconds"))card.put("snapshotAtSeconds",dossier.path("snapshotAtSeconds").asLong());
                card.put("snapshotNote","附加表记录本人当时的心理与意愿，不等于已经发生的行动；空白表示尚未记录。");
            }else{
                // Deliberate allowlist: never copy an NPC's profile, goals, stats, emotions, plans or private memories.
                card.put("tier","KNOWN");var facts=new LinkedHashMap<String,Object>();
                for(String key:List.of("awake","bound","position","activity","lastObservedProcess","observedAtSeconds","presence","basis"))if(person.getValue().containsKey(key))facts.put(key,person.getValue().get(key));
                card.put("known",facts);
            }
            result.add(card);
        }
        return result;
    }
}
