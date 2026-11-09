package com.bs.narrative.service;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.repository.GodProfileRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Each session owns mutable NPC profiles; templates are copied, never edited. */
@Service
public class SessionNpcService {
    private final GodProfileRepository gods;
    private final JdbcTemplate jdbc;
    private final NpcTemplateCatalog catalog;
    public SessionNpcService(GodProfileRepository gods,JdbcTemplate jdbc,NpcTemplateCatalog catalog) {this.gods=gods;this.jdbc=jdbc;this.catalog=catalog;}

    public List<String> resolve(String sid,List<String> names) {
        List<String> resolved=new ArrayList<>();
        for(String name:names) {
            if(name==null || name.isBlank()) continue;
            if(name.length()>50) name=name.substring(0,50);
            var aliases=jdbc.queryForList("SELECT god_id FROM session_npc_alias WHERE session_id=? AND alias=?",String.class,sid,name);
            if(!aliases.isEmpty()) {resolved.add(aliases.get(0));continue;}
            var template=catalog.find(name);
            if(template==null) template=gods.findById(name).orElse(null);
            if(template!=null && template.getSessionId()!=null && !sid.equals(template.getSessionId())) continue;
            if(template!=null && sid.equals(template.getSessionId())) {resolved.add(template.getGodId());continue;}
            if(template==null) {
                var ids=jdbc.queryForList("SELECT god_id FROM god_profile WHERE god_name=? AND session_id IS NULL ORDER BY god_id LIMIT 1",String.class,name);
                if(!ids.isEmpty()) template=gods.findById(ids.get(0)).orElse(null);
            }
            String templateId=template==null?null:template.getGodId();
            GodProfile npc=new GodProfile();
            npc.setGodId("npc_"+UUID.randomUUID().toString().replace("-","").substring(0,28));
            npc.setSessionId(sid); npc.setGodName(template==null?name:template.getGodName());
            npc.setGodStatus(template==null?"DEITY":template.getGodStatus());
            npc.setGodEmotion(template==null?"平静":template.getGodEmotion());
            npc.setBaseZhi(template==null?10:template.getBaseZhi());npc.setBaseHui(template==null?10:template.getBaseHui());
            npc.setBaseDou(template==null?10:template.getBaseDou());npc.setBaseWen(template==null?10:template.getBaseWen());
            npc.setBaseBen(template==null?20:template.getBaseBen());npc.setBaseGui(template==null?75:template.getBaseGui());
            npc.setInventory(template==null?new ArrayList<>():new ArrayList<>(template.getInventory()==null?List.of():template.getInventory()));
            gods.saveAndFlush(npc);
            jdbc.update("INSERT INTO session_npc_alias(session_id,alias,god_id,template_id) VALUES(?,?,?,?)",sid,name,npc.getGodId(),templateId);
            resolved.add(npc.getGodId());
        }
        var aliases=jdbc.queryForList("SELECT DISTINCT god_id,template_id FROM session_npc_alias WHERE session_id=?",sid);
        for(var from:aliases) for(var to:aliases) {
            if(from.get("god_id").equals(to.get("god_id"))) continue;
            var rel=catalog.relationship(from.get("template_id"),to.get("template_id"));
            if(rel.isEmpty()) continue;
            var r=rel.get();
            jdbc.update("INSERT INTO god_relationship(session_id,source_id,target_id,trust_value,is_allied,is_backstabbed,note) "
                    +"VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE relation_id=relation_id",sid,from.get("god_id"),to.get("god_id"),
                    r.path("trust").asInt(),r.path("allied").asInt(),r.path("backstabbed").asInt(),r.path("note").asText());
        }
        return resolved.stream().distinct().toList();
    }
    public String publicIndex(String sid,List<String> ids) {
        return gods.findAllById(ids).stream().filter(g->sid.equals(g.getSessionId()))
            .map(g->g.getGodId()+"="+g.getGodName()).collect(java.util.stream.Collectors.joining("，"));
    }
}
