package com.bs.narrative.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Persisted inside existing goal descriptions so checkpoints and old schemas remain compatible. */
final class GoalChecks {
    static final String MARKER="\n【服务器验收数据】";
    static final ObjectMapper JSON=new ObjectMapper();
    static ObjectNode read(String description) {
        int at=description.indexOf(MARKER);if(at<0)return null;
        try {var n=JSON.readTree(description.substring(at+MARKER.length()));return n.isObject()?(ObjectNode)n:null;}
        catch(Exception e){return null;}
    }
    static String text(String description) {int at=description.indexOf(MARKER);return at<0?description:description.substring(0,at);}
    static String store(String description,ObjectNode data) {return text(description)+MARKER+data;}
    static long tick(JdbcTemplate jdbc,String sid) {
        var rows=jdbc.queryForList("SELECT tick FROM world_clock WHERE session_id=?",Long.class,sid);
        return rows.isEmpty()?0:rows.get(0);
    }
    static String canonical(JsonNode checks) {
        var parts=new ArrayList<String>();
        if(checks==null || !checks.isArray() || checks.isEmpty() || checks.size()>3)return "";
        var unique=new HashSet<String>();
        for(var c:checks) {
            String type=c.path("type").asText(),value=c.path("value").asText();
            if(value.isBlank() || value.length()>64 || !unique.add(type+":"+value))return "";
            if("HAS_ITEM".equals(type))parts.add("持有「"+value+"」");
            else if("AT_LOCATION".equals(type))parts.add("位于「"+value+"」");else return "";
        }
        return String.join("且",parts);
    }
    static boolean matches(JdbcTemplate jdbc,String sid,String id,JsonNode c) {
        String value=c.path("value").asText();
        if("HAS_ITEM".equals(c.path("type").asText())) {
            var inv=jdbc.queryForList("SELECT inventory FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,id);
            try {return !inv.isEmpty() && JSON.readTree(inv.get(0)).isArray() && contains(JSON.readTree(inv.get(0)),id,value,false);}
            catch(Exception e){return false;}
        }
        if("AT_LOCATION".equals(c.path("type").asText())) {
            var scenes=jdbc.queryForList("SELECT present_gods FROM game_scene WHERE session_id=? AND location_name=?",String.class,sid,value);
            for(var occupants:scenes)try {if(contains(JSON.readTree(occupants),id,value,true))return true;}catch(Exception ignored){}
        }
        return false;
    }
    private static boolean contains(JsonNode values,String id,String item,boolean occupants) {
        if(values==null || !values.isArray())return false;
        for(var v:values)if(v.isTextual() && (occupants?id:item).equals(v.asText()))return true;
        return false;
    }
    static boolean valid(JdbcTemplate jdbc,String sid,String id,JsonNode checks,String success,String context) {
        String expected=canonical(checks);if(context==null || expected.isBlank() || !expected.equals(success))return false;
        boolean already=true;
        for(var c:checks) {
            String value=c.path("value").asText();if(!context.contains(value))return false;
            if("AT_LOCATION".equals(c.path("type").asText()) && jdbc.queryForObject(
                "SELECT COUNT(*) FROM game_scene WHERE session_id=? AND location_name=?",Integer.class,sid,value)!=1)return false;
            if("HAS_ITEM".equals(c.path("type").asText())) {
                boolean exists=false;
                for(var inv:jdbc.queryForList("SELECT inventory FROM god_profile WHERE session_id=?",String.class,sid))
                    try{if(contains(JSON.readTree(inv),id,value,false)){exists=true;break;}}catch(Exception ignored){}
                if(!exists)return false;
            }
            already &= matches(jdbc,sid,id,c);
        }
        return !already;
    }
}
