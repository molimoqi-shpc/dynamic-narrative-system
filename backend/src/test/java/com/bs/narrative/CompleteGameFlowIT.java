package com.bs.narrative;

import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
import com.bs.narrative.service.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
class CompleteGameFlowIT {
    @Autowired AuthService auth;
    @Autowired CharacterCreationService creation;
    @Autowired ConversationOperationService operations;
    @Autowired SessionStateService checkpoints;
    @Autowired GameSessionService lifecycle;
    @Autowired PlayerProfileRepository players;
    @Autowired TalentRepository talents;
    @Autowired GameActionService game;
    @Autowired PlayerObjectiveService objectives;
    @Autowired ObjectiveRuleService goalRules;
    @Autowired LegacyObjectiveRepairService legacyGoals;
    @Autowired KnowledgeService knowledge;
    @Autowired GameSceneRepository scenes;
    @Autowired JdbcTemplate jdbc;
    @MockBean LLMProvider model;
    final ObjectMapper json=new ObjectMapper();
    String pid,sid,scene,role,anchor;
    LLMResponse response(Object value)throws Exception{String raw=CreationWorldFixture.candidatesJson(json.writeValueAsString(value));return new LLMResponse(raw,raw,0,0,0,0);}
    @BeforeEach void born()throws Exception{
        String username="flow_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        auth.register(username,"flow-password-2026");pid=players.findByUsername(username).orElseThrow().getPlayerId();
        assertNotNull(auth.login(username,"flow-password-2026").get("token"));
        anchor=jdbc.queryForObject("SELECT session_id FROM god_profile WHERE god_id=(SELECT god_id FROM player_profile WHERE player_id=?)",String.class,pid);
        when(model.chatForJson(anyList())).thenReturn(response(Map.of("talents",List.of(Map.of("talent_name","细察","stat_key","hui","power_level",1)))),
                response(Map.of("birth_story","你是普通旅人，来到河畔。","initial_location",Map.of("location_name","河畔","scene_description","河畔无人，东边的小径通往树林。","present_npcs",List.of()))),
                response(Map.of("objectives",List.of())));
        sid=creation.startGame(pid,"DREAMER").get("sessionId").toString();
        CreationWorldFixture.ready(creation,model,sid,pid);
        creation.submitAnswers(sid,pid,"普通旅人","谨慎观察","愿意探访未知");
        var born=creation.selectTalent(sid,pid,talents.findBySessionId(sid).get(0).getTalentId());
        scene=born.get("sceneId").toString();role=born.get("playerGodId").toString();
        when(model.chatSubForJson(anyList())).thenAnswer(c->response(Map.of("inner_thought","","speech","","target_god_id","","action_text","","speech_timing","BEFORE_ACTION")));
    }
    Map<String,Object> act(String id,String text)throws Exception{
        var current=game.describeScene(pid,sid);
        return operations.create(new ConversationOperationService.Request(id,sid,pid,current.get("sceneId").toString(),text,null,
                ((Number)current.getOrDefault("branchEpoch",0)).longValue(),((Number)current.getOrDefault("revision",0)).longValue()));
    }
    void world()throws Exception{
        when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);String system=messages.get(0).get("content");var input=json.readTree(messages.get(1).get("content"));
            if(system.contains("玩家输入授权解析")){
                String text=input.path("input").asText();
                var step=json.createObjectNode().put("type","ACTION").put("text",text);
                if(text.contains("向东"))step.putObject("exploration").put("direction","EAST");
                return response(Map.of("steps",List.of(step)));
            }
            boolean explore=input.has("worldGenerationPermission");
            var effect=explore?Map.of("kind","EXPLORE","direction","EAST","exitName","东行小径","returnRoute",true,"scene",Map.of("locationName","林边","opening","林边无人，有一条返回河畔的小径。","characters",List.of()))
                    :Map.of("kind","OBSERVE","scope","VISIBLE_SCENE");
            String process=explore?"你沿东边的小径来到林边。":"你仔细观察了眼前的河畔与小径。";
            return response(Map.of("process",process,"observations",List.of(Map.of("channel","VISUAL","text",process)),"durationSeconds",2,"visibility","PUBLIC","effects",List.of(effect),"status","COMPLETED"));
        });
    }
    @Test void observationExplorationVictoryReloadRewindAndRestartFormACompleteGame()throws Exception{
        assertEquals(0L,game.describeScene(pid,sid).get("branchEpoch"));
        assertEquals(0L,game.describeScene(pid,sid).get("revision"));
        world();var first=act("observe","我观察眼前的场景。");assertEquals("COMPLETED",first.get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND status='COMPLETED'",Integer.class,sid));
        var victory=act("explore","我向东探索。");assertEquals("COMPLETED",victory.get("status"));
        assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND scope='ROUND'",Integer.class,sid));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND state_changes LIKE 'operation-log:%'",Integer.class,sid));
        assertEquals("PLAYING",checkpoints.resume(sid,pid).get("status"));
        assertEquals("COMPLETED",act("after-milestone","我观察眼前的场景。").get("status"));
        checkpoints.restore(sid,1);assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND scope='ROUND'",Integer.class,sid));
        assertEquals("COMPLETED",act("another-branch","我向东探索。").get("status"));
        assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
        var restarted=lifecycle.restart(pid,"DREAMER",true,sid);assertNotEquals(sid,restarted.get("newSessionId"));
        assertEquals("WORLD_PENDING",creation.creationState(restarted.get("newSessionId").toString(),pid).get("status"));
    }
    @Test void acceptedBirthCanBeRetriedAndRecoveredWithoutAnotherModelCall(){
        clearInvocations(model);var talent=talents.findBySessionId(sid).stream().filter(t->Boolean.TRUE.equals(t.getIsSelected())).findFirst().orElseThrow();
        var retried=creation.selectTalent(sid,pid,talent.getTalentId());assertEquals(role,retried.get("playerGodId"));
        var recovered=creation.creationState(sid,pid);assertEquals("COMPLETED",recovered.get("status"));
        assertEquals(List.of("普通旅人","谨慎观察","愿意探访未知"),recovered.get("answers"));verifyNoInteractions(model);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));
    }
    @Test void repeatedDefeatsCausePhysicalDeathAndRewindRestoresLife()throws Exception{
        String opponent="npc_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
        jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_dou,base_gui,inventory) VALUES(?,?,'守桥人','MORTAL','警惕',900,75,'[]')",opponent,sid);
        jdbc.update("UPDATE game_scene SET present_gods=JSON_ARRAY(?) WHERE scene_id=?",opponent,scene);
        when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);
            if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","我与守桥人交手。","contest",Map.of("kind","ATTACK","targetId",opponent,"statKey","DOU")))));
            var facts=json.readTree(messages.get(1).get("content"));var contest=facts.path("serverContest");
            return response(Map.of("process","你在交手中受挫。","durationSeconds",1,"visibility","PUBLIC","observations",List.of(Map.of("channel","VISUAL","text","你在交手中受挫。")),"effects",List.of(Map.of("kind","CONTEST","targetId",opponent,"outcome",contest.path("outcome").asText(),"fingerprint",contest.path("fingerprint").asText())),"status","COMPLETED"));
        });
        for(int i=0;i<4;i++)assertEquals("COMPLETED",act("combat-"+i,"我与守桥人交手。").get("status"));
        assertEquals("DEAD",game.describeScene(pid,sid).get("sessionStatus"));assertEquals(0,game.describeScene(pid,sid).get("playerHealth"));
        assertTrue(lifecycle.checkDeath(sid,pid));assertNotNull(lifecycle.processDeath(sid,pid).get("deathSummary"));
        checkpoints.restore(sid,2);assertEquals(50,game.describeScene(pid,sid).get("playerHealth"));assertFalse(lifecycle.checkDeath(sid,pid));
    }
    @Test void tenRoundsConsolidateMemoryWithoutDuplicatingHistory()throws Exception{
        world();for(int i=0;i<10;i++)assertEquals("COMPLETED",act("round-"+i,"我观察眼前的场景。").get("status"));
        assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND scope='ROUND'",Integer.class,sid));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND scope='SCENE'",Integer.class,sid));
        assertEquals(10,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND state_changes LIKE 'operation-log:%'",Integer.class,sid));
        assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
    }
    @Test void privateExperienceIsRememberedWithoutBecomingPublicWorldHistory()throws Exception{
        String text="我默默调整自己的呼吸。";
        when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);
            if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text",text))));
            return response(Map.of("process","只有你自己感到呼吸渐渐平稳。","durationSeconds",1,"visibility","PRIVATE","effects",List.of(),"status","COMPLETED"));
        });
        var result=act("private-process",text);assertEquals("COMPLETED",result.get("status"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND scope='ROUND' AND content LIKE '%呼吸%'",Integer.class,sid));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_book WHERE session_id=? AND state_changes LIKE 'operation-log:%'",Integer.class,sid));
    }
    @Test void cognitiveFacilityGoalsFallBackToActuallyExecutableConditions()throws Exception{
        var fake=json.createObjectNode().put("type","FACILITY_STATE").put("sceneId",scene).put("objectName","河畔").put("state","秘密已理解");
        when(model.chatForJson(anyList())).thenReturn(response(Map.of("objectives",List.of(Map.of("objective_id","fake1","title","理解秘密","rule",fake),Map.of("objective_id","fake2","title","探明退路","rule",fake)))));
        var goals=objectives.initObjectives(sid,pid,"普通旅人来到河畔","细察");
        assertEquals(Set.of("main_investigate","side_survive"),new HashSet<>(goals.stream().map(g->g.getObjectiveId()).toList()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_goal_rule WHERE session_id=? AND CAST(rule_json AS CHAR) LIKE '%FACILITY_STATE%'",Integer.class,sid));
        world();assertEquals("COMPLETED",act("look-again","我观察眼前的场景。").get("status"));
        assertEquals("COMPLETED",act("new-place","我向东探索。").get("status"));assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
    }
    @Test void hearingGoalRequiresDeliveredSpeechFromTheSpecifiedCharacter()throws Exception{
        String speaker="npc_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
        String other="npc_"+UUID.randomUUID().toString().replace("-","").substring(0,24);
        for(String id:List.of(speaker,other))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,inventory) VALUES(?,?,'旅人','MORTAL','平静','[]')",id,sid);
        jdbc.update("UPDATE game_scene SET present_gods=JSON_ARRAY(?,?) WHERE scene_id=?",speaker,other,scene);
        var condition=json.createObjectNode().put("type","HEARD_FROM").put("godId",speaker);
        goalRules.define(sid,pid,"hear_specific_person",condition,false);
        assertFalse(goalRules.matches(sid,pid,condition));
        var place=scenes.findById(scene).orElseThrow();
        knowledge.hear(sid,other,role,"东边有路。",place,1,1);
        assertFalse(goalRules.matches(sid,pid,condition));
        knowledge.hear(sid,speaker,other,"这句没有说给玩家。",place,2,1);
        assertFalse(goalRules.matches(sid,pid,condition));
        knowledge.hear(sid,speaker,role,"河畔的小径可以通行。",place,3,1);
        assertTrue(goalRules.matches(sid,pid,condition));
    }

    void legacyGoals(){
        jdbc.update("DELETE FROM player_objective WHERE session_id=?",sid);
        jdbc.update("DELETE FROM game_goal_rule WHERE session_id=?",sid);
        var ending=json.createObjectNode().put("type","AND");var children=ending.putArray("children");
        var states=List.of("退路已探明","刻痕已辨","四方意图已明");
        for(int i=0;i<3;i++){
            String id="old_"+i;
            jdbc.update("INSERT INTO player_objective(session_id,player_id,objective_id,title,status,progress,current_step) VALUES(?,?,?,?,'ACTIVE',50,'旧步骤')",sid,pid,id,"旧认知目标"+i);
            var rule=json.createObjectNode().put("type","AND");var conditions=rule.putArray("children");
            conditions.addObject().put("type","AT_SCENE").put("sceneId",scene);
            conditions.addObject().put("type","FACILITY_STATE").put("sceneId",scene).put("objectName","河畔").put("state",states.get(i));
            goalRules.define(sid,pid,id,rule,false);children.addObject().put("type","OBJECTIVE_COMPLETED").put("objectiveId",id);
        }
        goalRules.define(sid,pid,"initial_journey_ending",ending,true);
        checkpoints.refreshCurrent(sid,pid);clearInvocations(model);
    }
    void applyRepair(Map<String,Object> p){legacyGoals.apply(sid,pid,((Number)p.get("branchEpoch")).longValue(),((Number)p.get("revision")).longValue(),p.get("previewToken").toString());}
    @Test void legacyPreviewDoesNotInventCognitionOrChangeTheSave(){
        legacyGoals();var preview=legacyGoals.preview(sid,pid);
        assertEquals(true,preview.get("available"));assertEquals(false,preview.get("mayFinish"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND status='ACTIVE' AND progress=50",Integer.class,sid));
        assertEquals(0,jdbc.queryForObject("SELECT current_round FROM game_session WHERE session_id=?",Integer.class,sid));
        assertFalse(goalRules.terminalReached(sid,pid));verifyNoInteractions(model);
    }
    @Test void acceptedLegacyReplacementCanBePlayedToVictoryAndRewound()throws Exception{
        legacyGoals();applyRepair(legacyGoals.preview(sid,pid));verifyNoInteractions(model);
        assertEquals(0L,game.describeScene(pid,sid).get("storySeconds"));assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
        var archived=json.readTree(jdbc.queryForObject("SELECT rule_json FROM game_goal_rule WHERE session_id=? AND goal_id='initial_journey_ending'",String.class,sid));
        assertEquals(3,archived.path("legacyRepair").path("objectives").size());assertEquals(4,archived.path("legacyRepair").path("rules").size());
        world();act("repaired-look","我观察眼前的场景。");act("repaired-exit","我向东探索。");assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
        checkpoints.restore(sid,0);assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
        assertEquals(true,legacyGoals.preview(sid,pid).get("available"));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND progress=50",Integer.class,sid));
    }
    @Test void alreadyProvenReplacementWarnsBeforeEndingAndNeverFinishesOnRead()throws Exception{
        legacyGoals();world();
        act("old-look","我观察眼前的场景。");act("old-exit","我向东探索。");
        clearInvocations(model);var proposal=legacyGoals.preview(sid,pid);
        assertEquals(false,proposal.get("mayFinish"));assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));
        applyRepair(proposal);assertEquals("PLAYING",game.describeScene(pid,sid).get("sessionStatus"));verifyNoInteractions(model);
    }
    @Test void staleOrForgedRepairCannotRewriteGoals(){
        legacyGoals();var p=legacyGoals.preview(sid,pid);
        assertThrows(RuntimeException.class,()->legacyGoals.apply(sid,pid,0,0,"forged"));
        jdbc.update("UPDATE game_runtime SET revision=revision+1 WHERE session_id=?",sid);
        assertThrows(RuntimeException.class,()->applyRepair(p));
        jdbc.update("UPDATE game_runtime SET revision=0,branch_epoch=1 WHERE session_id=?",sid);
        assertThrows(RuntimeException.class,()->applyRepair(p));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=?",Integer.class,sid));verifyNoInteractions(model);
    }
    @Test void repairRejectsForeignOwnerPendingOperationsAndRoadReferences(){
        legacyGoals();assertThrows(RuntimeException.class,()->legacyGoals.preview(sid,"someone_else"));
        jdbc.update("UPDATE game_runtime SET active_operation='unfinished' WHERE session_id=?",sid);
        assertEquals(false,legacyGoals.preview(sid,pid).get("available"));
        jdbc.update("UPDATE game_runtime SET active_operation=NULL WHERE session_id=?",sid);
        jdbc.update("INSERT INTO scene_connection(session_id,from_scene_id,to_scene_id,direction,exit_name,required_objective) VALUES(?,?,?,'EAST','旧目标门槛','old_0')",sid,scene,scene);
        assertEquals(false,legacyGoals.preview(sid,pid).get("available"));verifyNoInteractions(model);
    }
    @Test void realFacilityConditionsAndExtraTerminalRulesAreNeverReplaced(){
        legacyGoals();var original=jdbc.queryForObject("SELECT rule_json FROM game_goal_rule WHERE session_id=? AND goal_id='old_0'",String.class,sid);
        jdbc.update("UPDATE game_goal_rule SET rule_json=? WHERE session_id=? AND goal_id='old_0'",original.replace("退路已探明","门已打开"),sid);
        assertEquals(false,legacyGoals.preview(sid,pid).get("available"));
        jdbc.update("UPDATE game_goal_rule SET rule_json=? WHERE session_id=? AND goal_id='old_0'",original,sid);
        goalRules.define(sid,pid,"other_ending",json.createObjectNode().put("type","AT_SCENE").put("sceneId",scene),true);
        assertEquals(false,legacyGoals.preview(sid,pid).get("available"));verifyNoInteractions(model);
    }
    void goal(String id,JsonNode condition){
        jdbc.update("INSERT INTO player_objective(session_id,player_id,objective_id,title,status,progress) VALUES(?,?,?,?,'ACTIVE',0)",sid,pid,id,id);
        goalRules.define(sid,pid,id,condition,false);
    }
    JsonNode completed(String id){return json.createObjectNode().put("type","OBJECTIVE_COMPLETED").put("objectiveId",id);}
    @Test void goalDependenciesCompleteInTheSameTurnRegardlessOfIdOrder(){
        goal("z_prerequisite",json.createObjectNode().put("type","AT_SCENE").put("sceneId",scene));
        goal("m_middle",completed("z_prerequisite"));goal("a_last",completed("m_middle"));
        goalRules.refresh(sid,pid);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND objective_id IN ('a_last','m_middle','z_prerequisite') AND status='COMPLETED'",Integer.class,sid));
    }
    @Test void negatedPrerequisiteCannotCompleteBeforeItsPositiveEvidence(){
        goal("z_prerequisite",json.createObjectNode().put("type","AT_SCENE").put("sceneId",scene));
        var negative=json.createObjectNode().put("type","NOT");negative.set("child",completed("z_prerequisite"));goal("a_negative",negative);
        goalRules.refresh(sid,pid);
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM player_objective WHERE session_id=? AND objective_id='a_negative'",String.class,sid));
    }
    @Test void circularGoalsStayActiveWithoutInventingCompletion(){
        goal("cycle_a",json.createObjectNode().put("type","AT_SCENE").put("sceneId",scene));goal("cycle_b",completed("cycle_a"));
        goalRules.define(sid,pid,"cycle_a",completed("cycle_b"),false);goalRules.refresh(sid,pid);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM player_objective WHERE session_id=? AND objective_id IN ('cycle_a','cycle_b') AND status='ACTIVE'",Integer.class,sid));
    }
    @Test void authorizedExplorationRepairsAnInventedMoveWithoutCommittingIt()throws Exception{
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        String invented="scene_invented_"+UUID.randomUUID().toString().substring(0,8);
        when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);
            if(messages.get(0).get("content").contains("玩家输入授权解析"))return response(Map.of("steps",List.of(Map.of("type","ACTION","text","我沿前方山道探索。","exploration",Map.of("direction","OTHER")))));
            var input=json.readTree(messages.get(1).get("content"));
            assertEquals("OTHER",input.path("worldGenerationPermission").path("direction").asText());
            assertEquals("EXPLORE",input.path("worldGenerationPermission").path("requiredArrivalEffect").asText());
            boolean first=calls.getAndIncrement()==0;
            Object effect;
            if(first)effect=Map.of("kind","MOVE","actorId",role,"fromSceneId",scene,"toSceneId",invented);
            else{
                assertEquals("no connected route",input.path("rejectedResultReason").asText());
                assertTrue(input.path("rejectedResultGuidance").asText().contains("不是现场道路封闭"));
                effect=Map.of("kind","EXPLORE","direction","OTHER","exitName","前方山道","returnRoute",true,"scene",Map.of("locationName","山间平台","opening","山间平台无人，后方山道通往河畔。","characters",List.of()));
            }
            return response(Map.of("process","你沿山道抵达平台。","durationSeconds",2,"visibility","PUBLIC","effects",List.of(effect),"status","COMPLETED"));
        });
        var result=act("repair-route","我沿前方山道探索。");
        assertEquals("COMPLETED",result.get("status"));assertEquals(2,calls.get());
        assertEquals(2L,result.get("storySeconds"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE scene_id=?",Integer.class,invented));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_scene WHERE session_id=?",Integer.class,sid));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM player_memory WHERE session_id=? AND scope='ROUND'",Integer.class,sid));
    }
    @Test void inactiveSessionSceneReadFailsInsteadOfUsingAnotherSessionsPlayerState(){
        String other=creation.startGame(pid,"DREAMER").get("sessionId").toString();
        jdbc.update("UPDATE player_profile SET active_session_id=? WHERE player_id=?",other,pid);
        var error=assertThrows(com.bs.narrative.exception.BusinessException.class,()->game.describeScene(pid,sid));
        assertEquals(com.bs.narrative.exception.ErrorCode.DATA_CONFLICT,error.getErrorCode());
        checkpoints.resume(sid,pid);assertEquals(scene,game.describeScene(pid,sid).get("sceneId"));
    }
    @AfterEach void cleanup(){
        if(pid==null)return;
        jdbc.update("DELETE FROM auth_token WHERE player_id=?",pid);
        var sessions=jdbc.queryForList("SELECT session_id FROM game_session WHERE player_id=?",String.class,pid);
        jdbc.update("DELETE FROM player_profile WHERE player_id=?",pid);
        for(String id:sessions){
            jdbc.update("DELETE FROM talent WHERE session_id=?",id);jdbc.update("DELETE FROM character_creation WHERE session_id=?",id);
            for(String table:List.of("llm_operation_usage","session_checkpoint_archive","game_goal_rule","game_operation","game_runtime","session_checkpoint","npc_decision_log","npc_memory","npc_goal","player_memory","player_objective","story_summary","interaction_log","world_book","god_relationship","scene_connection","session_npc_alias","npc_plan","world_clock","role_knowledge"))jdbc.update("DELETE FROM `"+table+"` WHERE session_id=?",id);
            jdbc.update("DELETE FROM game_session WHERE session_id=?",id);jdbc.update("DELETE FROM game_scene WHERE session_id=?",id);jdbc.update("DELETE FROM god_profile WHERE session_id=?",id);
        }
        if(anchor!=null){jdbc.update("DELETE FROM game_scene WHERE session_id=?",anchor);jdbc.update("DELETE FROM god_profile WHERE session_id=?",anchor);}
    }
}
