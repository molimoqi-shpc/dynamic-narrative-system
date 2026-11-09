package com.bs.narrative.service;

import com.bs.narrative.domain.*;
import com.bs.narrative.repository.*;
import com.bs.narrative.engine.StatsCalculator;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Explicit exploration permission creates a consistent location packet atomically, not a rescue route. */
@Service
public class OperationWorldExpansionService {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> DIRECTIONS=Set.of("NORTH","SOUTH","EAST","WEST","UP","DOWN","OTHER");
    private final JdbcTemplate jdbc;private final GameSceneRepository scenes;private final GodProfileRepository actors;
    @org.springframework.beans.factory.annotation.Autowired private ItemStateService items;
    @org.springframework.beans.factory.annotation.Autowired private StoryCastService storyCast;
    public OperationWorldExpansionService(JdbcTemplate jdbc,GameSceneRepository scenes,GodProfileRepository actors){this.jdbc=jdbc;this.scenes=scenes;this.actors=actors;}
    public void validatePermission(JsonNode permission){if(!permission.isObject() || !DIRECTIONS.contains(permission.path("direction").asText()))throw new IllegalArgumentException("exploration direction unresolved");}
    private int[] coordinates(String sid,GameScene from,String direction){
        int x=from.getCoordX()==null?0:from.getCoordX(),y=from.getCoordY()==null?0:from.getCoordY();
        switch(direction){case "NORTH"->y++;case "SOUTH"->y--;case "EAST"->x++;case "WEST"->x--;default->x=jdbc.queryForObject("SELECT COALESCE(MAX(coord_x),0)+1 FROM game_scene WHERE session_id=?",Integer.class,sid);}
        return new int[]{x,y};
    }
    public Map<String,Object> permission(String sid,GameScene from,JsonNode requested){
        validatePermission(requested);String direction=requested.path("direction").asText();var edges=jdbc.queryForList("SELECT to_scene_id,exit_name,required_item,required_objective FROM scene_connection WHERE session_id=? AND from_scene_id=? AND direction=?",sid,from.getSceneId(),direction);
        var out=new LinkedHashMap<String,Object>();out.put("direction",direction);out.put("maxNewLocations",1);out.put("maxNewCharacters",4);out.put("coordinateMeaning","只用于旧地图节点索引，不是物理距离或视线证据");
        if(!edges.isEmpty()){out.put("canCreate",false);out.put("existingRoutes",edges);return out;}
        var point=coordinates(sid,from,direction);var existing=scenes.findBySessionId(sid).stream().filter(s->Objects.equals(s.getCoordX(),point[0]) && Objects.equals(s.getCoordY(),point[1])).toList();
        out.put("canCreate",existing.isEmpty());
        if(existing.isEmpty()){
            out.put("requiredArrivalEffect","EXPLORE");
            out.put("arrivalContract","此方向尚未登记地图节点，允许根据原探索行动建立真实新地点；不是道路不可通行。到达新地点时在effects返回EXPLORE，direction原样引用本对象，附exitName、returnRoute、scene:{locationName,opening,characters:[]}；若有人物再提供完整人物事实。不要自造MOVE目的地ID，也不要将地图未登记编成山路封死。");
        }
        if(!existing.isEmpty()){if(existing.size()!=1)throw new IllegalArgumentException("destination coordinates ambiguous");var s=existing.get(0);out.put("existingDestination",Map.of("sceneId",s.getSceneId(),"locationName",s.getLocationName(),"opening",Objects.toString(s.getSceneSummary(),"")));
            out.put("requiredArrivalEffect","EXPLORE");out.put("arrivalContract","目的地已经存在，但此方向尚无登记路线。实际抵达时用EXPLORE，direction引用本对象，existingSceneId引用existingDestination.sceneId，附exitName及布尔returnRoute，不含scene，不重造人物或资源。不要仅返回MOVE；仅知道目的地ID不等于路线已经登记。");}
        return out;
    }
    /** Bind an explicitly authorized arrival to an existing node without regenerating its world. */
    public static void normalizeExistingArrival(JsonNode candidate,JsonNode permission,String role,String from){
        String destination=permission.path("existingDestination").path("sceneId").asText();
        if(!permission.path("canCreate").isBoolean() || permission.path("canCreate").asBoolean() || destination.isBlank() || !permission.path("existingRoutes").isEmpty() || !DIRECTIONS.contains(permission.path("direction").asText()))return;
        for(var effect:candidate.path("effects"))if(effect instanceof com.fasterxml.jackson.databind.node.ObjectNode object && "MOVE".equals(effect.path("kind").asText())
                && role.equals(effect.path("actorId").asText()) && from.equals(effect.path("fromSceneId").asText()) && destination.equals(effect.path("toSceneId").asText())){
            object.put("kind","EXPLORE");object.remove(List.of("actorId","fromSceneId","toSceneId"));
            object.put("direction",permission.path("direction").asText());object.put("existingSceneId",destination);
            String name="通往"+permission.path("existingDestination").path("locationName").asText();object.put("exitName",name.substring(0,Math.min(50,name.length())));
            // The arrival proves this direction only; do not infer an unestablished reverse path.
            object.put("returnRoute",false);
        }
    }
    public String accept(String sid,GameScene from,JsonNode e){
        return accept(sid,from,e,false);
    }
    public String accept(String sid,GameScene from,JsonNode e,boolean storyFirst){
        if(storyFirst)return acceptAuthored(sid,from,e);
        if(!e.path("returnRoute").isBoolean())throw new IllegalArgumentException("exploration returnRoute must be boolean");
        String direction=e.path("direction").asText();validatePermission(JSON.createObjectNode().put("direction",direction));
        if(!storyFirst && jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=? AND from_scene_id=? AND direction=?",Integer.class,sid,from.getSceneId(),direction)>0)throw new IllegalArgumentException("known route cannot be bypassed by world expansion");
        var point=coordinates(sid,from,storyFirst?"OTHER":direction);var existing=storyFirst?scenes.findBySessionId(sid).stream().filter(s->s.getSceneId().equals(e.path("existingSceneId").asText())).toList():scenes.findBySessionId(sid).stream().filter(s->Objects.equals(s.getCoordX(),point[0]) && Objects.equals(s.getCoordY(),point[1])).toList();
        if(storyFirst && e.hasNonNull("existingSceneId") && existing.isEmpty())throw new IllegalArgumentException("引用的已有地点不存在");
        String destination;
        if(!existing.isEmpty()){
            if(existing.size()!=1 || !existing.get(0).getSceneId().equals(e.path("existingSceneId").asText()) || e.has("scene"))throw new IllegalArgumentException("existing place must be reused, not regenerated");destination=existing.get(0).getSceneId();
        }else{
            var packet=e.path("scene");String name=packet.path("locationName").asText(),opening=packet.path("opening").asText(),key=e.path("constructionId").asText();
            if(!key.matches("exp_[a-f0-9]{24}") || name.isBlank() || name.length()>50 || opening.isBlank() || opening.length()>6000 || !packet.path("characters").isArray() || packet.path("characters").size()>4)throw new IllegalArgumentException("new location fact packet incomplete");
            destination=key;var people=new ArrayList<String>();int index=0;
            for(var n:packet.path("characters")){
                String role="npc_"+key.substring(4,24)+"_"+index++;String publicName=n.path("name").asText();
                if(publicName.isBlank() || publicName.length()>50 || !storyFirst && !opening.contains(publicName) || !n.path("stats").isObject() || !n.path("inventory").isArray() || n.path("inventory").size()>12)throw new IllegalArgumentException("opening character facts incomplete");
                var actor=new GodProfile();actor.setGodId(role);actor.setSessionId(sid);actor.setGodName(publicName);actor.setGodStatus("PARTICIPANT");actor.setGodEmotion("尚未作出新的选择");
                var values=new ArrayList<Integer>();for(String stat:List.of("zhi","hui","dou","wen","ben","gui")){
                    var value=n.path("stats").path(stat);if(!value.isIntegralNumber() || value.asLong()<0 || value.asLong()>(stat.equals("ben")?StatsCalculator.BEN_MAX:StatsCalculator.BASE_MAX))throw new IllegalArgumentException("new character stats missing or out of range");values.add(value.asInt());
                }
                actor.setBaseZhi(values.get(0));actor.setBaseHui(values.get(1));actor.setBaseDou(values.get(2));actor.setBaseWen(values.get(3));actor.setBaseBen(values.get(4));actor.setBaseGui(values.get(5));
                var inventory=new ArrayList<String>();for(var item:n.path("inventory")){if(!item.isTextual() || item.asText().isBlank() || item.asText().length()>64)throw new IllegalArgumentException("new held resource undefined");inventory.add(item.asText());}actor.setInventory(inventory);actors.saveAndFlush(actor);items.establishInitialInventory(sid,actor);people.add(role);
                var body=n.path("body");if(!body.path("awake").isBoolean() || !body.path("bound").isBoolean() || !storyFirst && !body.path("position").isTextual())throw new IllegalArgumentException("new body facts missing");
                store(sid,role,"body:v2","BODY_STATE",destination,Map.of("awake",body.path("awake").asBoolean(),"bound",body.path("bound").asBoolean(),"position",body.path("position").asText(),"activity",body.path("activity").asText(),"activityHistorical",false));
                String background=n.path("privateBackground").asText();if(background.length()>4000)throw new IllegalArgumentException("character background too long");if(!background.isBlank())store(sid,role,"genesis:"+role,"SELF_PROFILE",destination,Map.of("background",background,"kind","ESTABLISHED_BACKGROUND"));
            }
            var scene=new GameScene();scene.setSceneId(destination);scene.setSessionId(sid);scene.setLocationName(name);scene.setSceneSummary(opening);scene.setPresentGods(people);scene.setCoordX(point[0]);scene.setCoordY(point[1]);scenes.saveAndFlush(scene);actors.flush();
        }
        String exit=e.path("exitName").asText();if(exit.isBlank() || exit.length()>50)throw new IllegalArgumentException("new route name missing");
        jdbc.update((storyFirst?"INSERT IGNORE":"INSERT")+" INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,?,?)",sid,from.getSceneId(),destination,direction,exit);
        if(e.path("returnRoute").asBoolean()){
            String opposite=switch(direction){case "NORTH"->"SOUTH";case "SOUTH"->"NORTH";case "EAST"->"WEST";case "WEST"->"EAST";case "UP"->"DOWN";case "DOWN"->"UP";default->"OTHER";};
            if(jdbc.queryForObject("SELECT COUNT(*) FROM scene_connection WHERE session_id=? AND from_scene_id=? AND direction=?",Integer.class,sid,destination,opposite)==0)jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,?,?)",sid,destination,from.getSceneId(),opposite,"来时的路");
        }
        return destination;
    }
    /** Stores an authored place, without direction permission, grid occupancy, character-count or route gates. */
    private String acceptAuthored(String sid,GameScene from,JsonNode e){
        String destination=e.path("existingSceneId").asText();
        if(!destination.isBlank())scenes.findBySessionIdAndSceneId(sid,destination).orElseThrow(()->new IllegalArgumentException("已有场景编号不属于当前存档"));
        else{
            var packet=e.path("scene");String name=packet.path("locationName").asText(),opening=packet.path("opening").asText();destination=e.path("constructionId").asText();
            if(destination.isBlank() || name.isBlank() || name.length()>50 || opening.isBlank() || opening.length()>6000)throw new IllegalArgumentException("新场景资料不符合存储字段格式");
            var point=coordinates(sid,from,"OTHER");var place=new GameScene();place.setSceneId(destination);place.setSessionId(sid);place.setLocationName(name);place.setSceneSummary(opening);place.setPresentGods(new ArrayList<>());place.setCoordX(point[0]);place.setCoordY(point[1]);scenes.saveAndFlush(place);
            // Legacy character packets are accepted as brief authored profiles, without mandatory six stats or poses.
            var cast=JSON.createArrayNode();for(var person:packet.path("characters")){
                var entry=cast.addObject().put("name",person.path("name").asText()).put("profile",person.path("profile").asText(person.path("privateBackground").asText("主API在场景中已确立的身份与所见："+opening)));
                if(entry.path("profile").asText().isBlank())entry.put("profile","主API在场景中已确立的身份与所见："+opening);
            }
            // These are explicitly declared residents of this newly authored scene,
            // unlike a cross-scene chapter's list of introduced identities.
            storyCast.remember(sid,"place:"+destination,0,place,cast,true);
            for(var person:packet.path("characters")){
                var ids=jdbc.queryForList("SELECT god_id FROM god_profile WHERE session_id=? AND god_name=?",String.class,sid,person.path("name").asText());if(ids.isEmpty())continue;
                var actor=actors.findById(ids.get(0)).orElseThrow();var inventory=new ArrayList<>(actor.getInventory());for(var item:person.path("inventory"))if(item.isTextual() && !item.asText().isBlank())inventory.add(item.asText());actor.setInventory(inventory);actors.saveAndFlush(actor);items.owned(sid,actor);
                var body=person.path("body");if(body.path("awake").isBoolean() || body.path("bound").isBoolean())store(sid,actor.getGodId(),"body:v2","BODY_STATE",destination,Map.of("awake",body.path("awake").asBoolean(true),"bound",body.path("bound").asBoolean(false),"position","","activity","","activityHistorical",true));
            }
        }
        String direction=e.path("direction").asText("OTHER");if(!DIRECTIONS.contains(direction))direction="OTHER";
        String exit=e.path("exitName").asText();if(exit.isBlank())exit=scenes.findBySessionIdAndSceneId(sid,destination).orElseThrow().getLocationName();if(exit.length()>50)exit=exit.substring(0,50);
        jdbc.update("INSERT IGNORE INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,?,?)",sid,from.getSceneId(),destination,direction,exit);
        if(e.path("returnRoute").asBoolean(false)){
            String opposite=switch(direction){case "NORTH"->"SOUTH";case "SOUTH"->"NORTH";case "EAST"->"WEST";case "WEST"->"EAST";case "UP"->"DOWN";case "DOWN"->"UP";default->"OTHER";};
            jdbc.update("INSERT IGNORE INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name) VALUES(?,?,?,?,?)",sid,destination,from.getSceneId(),opposite,"来时的路");
        }
        return destination;
    }
    private void store(String sid,String role,String ref,String type,String scene,Object data){
        try{jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'已建立的角色事实',?,?,'',?,'',0)",sid,role,ref,JSON.writeValueAsString(data),type,scene);}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
}
