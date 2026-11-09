package com.bs.narrative;

import com.bs.narrative.service.WorldBookBrowserService;
import com.bs.narrative.service.StoryPlotService;
import com.bs.narrative.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class WorldBookBrowserIT {
    @Autowired WorldBookBrowserService browser;
    @Autowired JdbcTemplate jdbc;
    @Autowired StoryPlotService plots;
    @Autowired com.bs.narrative.service.PlayerJournalService portraitJournal;
    @Autowired com.bs.narrative.service.GameActionService game;
    final String sid="book_browser_test",pid="book_browser_user";
    final ObjectMapper json=new ObjectMapper();
    @BeforeEach void seed(){
        for(String id:List.of("book_self","book_core","book_extra","book_hidden"))
            jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,?,'MORTAL','私密情绪','[]')",id,sid,switch(id){case "book_core"->"知秋";case "book_extra"->"船夫";case "book_hidden"->"远方秘客";default->"行者";});
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('book_scene',?,'渡口','[\"book_core\",\"book_extra\"]','暮色中的渡口')",sid);
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id) VALUES(?,'book_self',?,'unused','book_scene',?)",pid,pid,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,player_god_id,current_scene_id,status) VALUES(?,?,'book_self','book_scene','PLAYING')",sid,pid);
        jdbc.update("INSERT INTO game_runtime(session_id,branch_epoch,revision,story_seconds) VALUES(?,3,7,120)",sid);
        known("entered","SCENE_SEEN","{\"atSeconds\":0}","presence:book_scene");
        known("seen","OBSERVED_STATE","{\"id\":\"book_core\",\"name\":\"知秋\",\"awake\":true,\"observedAtSeconds\":40,\"presence\":\"LAST_SEEN\",\"basis\":\"亲见\"}","observed:book_scene:book_core");
        known("问路所得","HEARD","船夫说河水上涨，明早再渡。","report");
        known("私密内部状态","ROLE_DECISION","不可见内部计划","private");
        chapter(0,120,"知秋：河上有雾，我们等天亮再渡。",1);
        world("CHARACTER_DOSSIER","book_core","故事第40秒","{\"name\":\"知秋\",\"narrativeTier\":\"CORE\",\"snapshotAtSeconds\":40,\"psychology\":{\"personality\":{\"stableTraits\":[\"外柔内刚\"]},\"motivations\":{\"hiddenAgenda\":\"私下寻找旧盟友\"}}}");
        world("CHARACTER_DOSSIER","book_extra","故事第50秒","{\"name\":\"船夫\",\"narrativeTier\":\"EXTRA\",\"snapshotAtSeconds\":50,\"psychology\":{\"roleInScene\":\"渡口船夫\",\"attitude\":\"愿意指路\",\"hiddenAgenda\":\"多余复杂设定\"},\"pastInformation\":{\"ownRecords\":[\"不必铺陈的历史\"]}}");
        world("STORY_MILESTONE","book_hidden","很久以前","远方秘客的未获知事件");
        jdbc.update("UPDATE world_book SET is_secret=false WHERE session_id=? AND event_type='STORY_MILESTONE'",sid);
    }
    void known(String title,String type,String content,String ref){jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,'book_self',?,?,?,?, '', 'book_scene','渡口',2)",sid,ref,title,content,type);}
    void chapter(long start,long end,String story,int round){jdbc.update("INSERT INTO interaction_log(session_id,player_id,scene_id,round_no,player_input,true_intent,outcome_type,processed_dialog,rule_snapshot) VALUES(?,?,'book_scene',?,'前往渡口','TALK','NO_OP',?,?)",sid,pid,round,story,"{\"storyStartSeconds\":"+start+",\"storySeconds\":"+end+"}");}
    void world(String type,String role,String time,String content){jdbc.update("INSERT INTO world_book(session_id,round_no,story_time,coord_x,coord_y,location_name,event_type,actor_ids,event_description,importance,is_secret,info_source) VALUES(?,1,?,0,0,'渡口',?,?,?,5,true,'PERSONAL')",sid,time,type,"[\""+role+"\"]",content);}
    Map<String,Object> read(String keyword,String role,Long from,Long to,int page){return browser.browse(sid,pid,keyword,role,from,to,page);}
    String encoded(Object result)throws Exception{return json.writeValueAsString(result);}
    @Test void acceptedPublicAppearanceReachesBothSceneAndLimitedCharacterCards()throws Exception{
        portraitJournal.apply(sid,"book_self","book_scene",json.valueToTree(Map.of("characterAppearances",List.of(Map.of("roleId","book_core","gender","female","ageGroup","YOUNG","divineRevealed",false,"basis","主角已亲见知秋是一位年轻女子。")))),120);
        var scene=json.valueToTree(game.describeScene(pid,sid));
        var role=java.util.stream.StreamSupport.stream(scene.path("presentGods").spliterator(),false).filter(r->"book_core".equals(r.path("godId").asText())).findFirst().orElseThrow();
        assertEquals("YOUNG",role.path("publicAppearance").path("ageGroup").asText());
        var card=json.valueToTree(read("","",null,null,0)).path("activeCharacters").get(0);
        assertEquals("female",card.path("publicAppearance").path("gender").asText());
        assertFalse(card.path("publicAppearance").path("divineRevealed").asBoolean());
        assertFalse(card.has("dossier"));assertFalse(encoded(card).contains("hiddenAgenda"));
    }
    @Test void limitedViewNeverExposesPrivateDossiersOrUnknownWorldEventsEvenOnSearch()throws Exception{
        var result=read("","",null,null,0);String body=encoded(result);
        assertEquals("DREAMER",result.get("viewMode"));assertEquals(2L,result.get("total"));
        assertTrue(body.contains("河上有雾"));assertTrue(body.contains("所见"));
        for(String secret:List.of("hiddenAgenda","私下寻找旧盟友","私密情绪","不可见内部计划","远方秘客","多余复杂设定"))assertFalse(body.contains(secret),secret);
        assertEquals(0L,read("私下寻找旧盟友","",null,null,0).get("total"));
        assertEquals(0L,read("","book_hidden",null,null,0).get("total"));
        assertEquals(0L,read("","not_a_role",null,null,0).get("total"));
    }
    @Test void omniscientCoreIsDetailedButExtraRemainsBrief()throws Exception{
        jdbc.update("UPDATE game_session SET view_mode='OMNISCIENT' WHERE session_id=?",sid);
        String body=encoded(read("","",null,null,0));assertTrue(body.contains("外柔内刚"));assertTrue(body.contains("私下寻找旧盟友"));
        assertTrue(body.contains("愿意指路"));assertFalse(body.contains("多余复杂设定"));assertFalse(body.contains("不必铺陈的历史"));
        assertEquals(1L,read("外柔内刚","book_core",40L,40L,0).get("total"));
    }
    @Test void storyMomentMatchesCoveringChapterAndDoesNotInventTimesForOldRecords(){
        assertEquals(1L,read("","",60L,60L,0).get("total"));assertEquals(0L,read("","",121L,null,0).get("total"));
        assertEquals(1L,read("河水上涨","",null,null,0).get("total"));assertEquals(0L,read("河水上涨","",0L,120L,0).get("total"));
        assertEquals(0L,read("%","",null,null,0).get("total"));
        assertThrows(BusinessException.class,()->read("","",100L,50L,0));
    }
    @Test void paginationAndKnownRoleFilterIncludeOlderChapters(){
        for(int i=0;i<24;i++)chapter(121+i,122+i,"知秋：旧页"+i,2+i);
        var first=read("旧页","book_core",null,null,0);var second=read("旧页","book_core",null,null,1);
        assertEquals(24L,first.get("total"));assertEquals(20,((List<?>)first.get("entries")).size());assertEquals(4,((List<?>)second.get("entries")).size());
        assertEquals(true,first.get("hasMore"));assertEquals(false,second.get("hasMore"));
        assertEquals(25L,read("","book_self",null,null,0).get("total"));
    }
    @Test void ownershipAndReadOnlyBehaviorAreEnforced()throws Exception{
        assertThrows(BusinessException.class,()->browser.browse(sid,"other_account","","",null,null,0));
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Long.class,sid);
        var result=read("","",null,null,0);assertEquals(3L,result.get("branchEpoch"));assertEquals(7L,result.get("revision"));assertEquals(120L,result.get("storySeconds"));
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Long.class,sid));
        jdbc.update("UPDATE game_session SET view_mode='OMNISCIENT' WHERE session_id=?",sid);assertTrue(encoded(read("","",null,null,0)).contains("hiddenAgenda"));
        jdbc.update("UPDATE game_session SET view_mode='DREAMER' WHERE session_id=?",sid);assertFalse(encoded(read("","",null,null,0)).contains("hiddenAgenda"));
    }
    @Test void plotQueriesAndOriginalPassagesRequireTheSameSaveOwner(){
        assertThrows(BusinessException.class,()->browser.browsePlots(sid,"another_player","","","",0,0));
        var plots=browser.browsePlots(sid,pid,"","","",0,0);assertEquals("DREAMER",plots.get("viewMode"));assertEquals(sid,plots.get("sessionId"));
        long log=jdbc.queryForObject("SELECT log_id FROM interaction_log WHERE session_id=? LIMIT 1",Long.class,sid);
        assertTrue(browser.plotPassage(sid,pid,log).get("story").toString().contains("河上有雾"));assertThrows(BusinessException.class,()->browser.plotPassage(sid,"another_player",log));
        assertThrows(BusinessException.class,()->browser.plotPassage("not_this_save",pid,log));
    }
    @Test void authorRecoversTheExactOldSourceWithoutDuplicatingRecentProseOrCuttingLongChapters(){
        long log=jdbc.queryForObject("SELECT log_id FROM interaction_log WHERE session_id=? LIMIT 1",Long.class,sid);
        var updates=json.createObjectNode();updates.putArray("events").addObject().put("key","old").put("title","旧渡口问题");plots.apply(sid,log,0,1,120,updates);
        var facts=json.createObjectNode();facts.set("plotMemory",plots.authorContext(sid));plots.attachPublishedSources(sid,facts);assertEquals("知秋：河上有雾，我们等天亮再渡。",facts.at("/plotSourcePages/0/story").asText());
        facts.putArray("previousChapters").addObject().put("logId",log);plots.attachPublishedSources(sid,facts);assertTrue(facts.path("plotSourcePages").isEmpty());
        facts.remove("previousChapters");jdbc.update("UPDATE interaction_log SET processed_dialog=? WHERE session_id=? AND log_id=?","原文".repeat(4000),sid,log);plots.attachPublishedSources(sid,facts);assertFalse(facts.at("/plotSourcePages/0").has("story"));assertTrue(facts.at("/plotSourcePages/0").has("lookupNote"));
    }
    @Test void legacyNullTimeDoesNotBecomeStorySecondZero(){
        jdbc.update("UPDATE interaction_log SET rule_snapshot='{\"storySeconds\":null}' WHERE session_id=?",sid);
        assertEquals(2L,read("","",null,null,0).get("total"));assertEquals(0L,read("","",0L,0L,0).get("total"));
    }
    @Test void largeStoryPagesDoNotSortEntireProse(){
        long old=jdbc.queryForObject("SELECT @@session.sort_buffer_size",Long.class);
        try{jdbc.execute("SET SESSION sort_buffer_size=32768");
            for(int i=0;i<24;i++)chapter(200+i,201+i,"长篇正文"+"文".repeat(14000),3+i);
            var result=read("长篇正文","",null,null,0);assertEquals(24L,result.get("total"));assertEquals(20,((List<?>)result.get("entries")).size());
        }finally{jdbc.execute("SET SESSION sort_buffer_size="+old);}
    }
    @Test void onlyOmniscientViewIncludesOffSceneCharactersWithActualUpdatesThisPassage()throws Exception{
        jdbc.update("UPDATE interaction_log SET rule_snapshot='{\"storyStartSeconds\":60,\"storySeconds\":120}' WHERE session_id=?",sid);
        world("CHARACTER_DOSSIER","book_hidden","故事第80秒","{\"id\":\"book_hidden\",\"name\":\"远方秘客\",\"narrativeTier\":\"EXTRA\",\"snapshotAtSeconds\":80,\"psychology\":{\"roleInScene\":\"异地人物\"}}");
        assertFalse(encoded(read("","",null,null,0).get("activeCharacters")).contains("远方秘客"));
        jdbc.update("UPDATE game_session SET view_mode='OMNISCIENT' WHERE session_id=?",sid);
        String cards=encoded(read("","",null,null,0).get("activeCharacters"));assertTrue(cards.contains("远方秘客"));assertTrue(cards.contains("本段有档案更新"));
        jdbc.update("UPDATE interaction_log SET rule_snapshot='{\"storyStartSeconds\":90,\"storySeconds\":120}' WHERE session_id=?",sid);
        assertFalse(encoded(read("","",null,null,0).get("activeCharacters")).contains("远方秘客"));
    }
    @Test void cardsFollowActivePlayerLocationAndDoNotExposeThePreviousScene(){
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES('book_new_scene',?,'山口','[]','空地')",sid);
        jdbc.update("UPDATE player_profile SET current_scene_id='book_new_scene' WHERE player_id=?",pid);
        known("entered new scene","SCENE_SEEN","{\"atSeconds\":120}","presence:book_new_scene");
        // This entry is deliberately scoped to the new scene; no live NPC rescan should leak the old roster.
        jdbc.update("UPDATE role_knowledge SET scene_id='book_new_scene' WHERE session_id=? AND source_ref='presence:book_new_scene'",sid);
        assertTrue(((List<?>)read("","",null,null,0).get("activeCharacters")).isEmpty());
    }
}
