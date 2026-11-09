package com.bs.narrative;

import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.dto.ActionRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","spring.jpa.show-sql=false"})
@Transactional
class InvestigationSocialIT {
    @Autowired GameActionService actions; @Autowired SessionStateService state; @Autowired KnowledgeService knowledge;
    @Autowired JdbcTemplate jdbc; @Autowired EntityManager em;
    @MockBean LLMProvider model;
    String sid="social_it",pid="social_user",role="social_player",receiver="social_receiver",bystander="social_bystander",scene="social_scene";
    static LLMResponse response(String j){return new LLMResponse(j,j,0,0,0,0);}
    @BeforeEach void seed(){
        for(String id:List.of(role,receiver,bystander))jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_gui,inventory) VALUES(?,?,?,'MORTAL','平静',75,?)",id,sid,id.equals(role)?"你":id.equals(receiver)?"渡者":"旁观者",id.equals(role)?"[\"水囊\",\"骨刃\"]":id.equals(receiver)?"[\"通行印\"]":"[]");
        jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary,coord_x,coord_y) VALUES(?,?,'雨后渡口',?,'渡口石阶留有湿痕，渡者守着旧船。',0,0)",scene,sid,"[\""+receiver+"\",\""+bystander+"\"]");
        jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,active_session_id,inventory) VALUES(?,?,'social_test','unused',?,?,'[\"水囊\",\"骨刃\"]')",pid,role,scene,sid);
        jdbc.update("INSERT INTO game_session(session_id,player_id,status,view_mode) VALUES(?,?,'PLAYING','DREAMER')",sid,pid);state.initialize(sid,pid);
        when(model.chatForJson(anyList())).thenReturn(response("{\"visible\":{\"lines\":[]}}"));
        when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"IGNORE\"}"));
    }
    com.bs.narrative.dto.ActionResponse act(String intent,String raw,String target,String item,String extra){
        String json="{\"intent\":\""+intent+"\",\"targetGodId\":\""+target+"\",\"itemId\":"+(item==null?"null":"\""+item+"\"")+","+extra+"\"visible\":{\"lines\":[]}}";
        when(model.chatForJson(anyList())).thenReturn(response(json));return actions.processActionWithDb(new ActionRequest(pid,scene,raw,null),sid);
    }
    void reply(Boolean accepted){when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);boolean recipient=m.get(1).get("content").contains("姓名：渡者");return response("{\"behavior\":\"RESPOND\",\"accept_offer\":"+(recipient && accepted!=null?accepted:"null")+"}");});}
    String inventory(String id){return jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,id);}
    long discover(){act("EXAMINE","我细查渡口，记下可核验的见闻。","",null,"");return ((Number)knowledge.list(sid,role).get(0).get("knowledgeId")).longValue();}
    @Test void investigationRecordsOnlyPublicSameLocationInformationAndNeverCreatesLoot(){
        jdbc.update("INSERT INTO world_book(session_id,round_no,coord_x,coord_y,location_name,event_type,event_description,is_secret) VALUES(?,0,0,0,'雨后渡口','NPC_PLAN','巡守沿旧桥离开。',false),(?,0,0,0,'雨后渡口','NPC_PLAN','绝不能公开的秘密甲',true),(?,0,0,0,'另一地点','NPC_PLAN','远方消息乙',false)",sid,sid,sid);
        discover();String facts=knowledge.privateContext(sid,role);assertTrue(facts.contains("巡守沿旧桥离开"));assertFalse(facts.contains("秘密甲"));assertFalse(facts.contains("远方消息乙"));assertTrue(inventory(role).contains("水囊"));assertFalse(inventory(role).contains("神器"));
        assertEquals(2,knowledge.list(sid,role).size());assertTrue(knowledge.list(sid,role).stream().anyMatch(k->"INVESTIGATED".equals(k.get("sourceType")) && "调查所得".equals(k.get("sourceLabel"))));
        discover();assertEquals(2,knowledge.list(sid,role).size());assertEquals(2,jdbc.queryForObject("SELECT current_round FROM game_session WHERE session_id=?",Integer.class,sid));
    }
    @Test void selectedKnowledgeGoesOnlyToRecipientAndSurvivesCheckpoint(){
        long id=discover();String title=(String)knowledge.list(sid,role).get(0).get("title");List<String> prompts=Collections.synchronizedList(new ArrayList<>());
        when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);prompts.add(m.get(1).get("content"));return response("{\"behavior\":\"IGNORE\"}");});
        act("SHARE_INFO","我向渡者告知「"+title+"」这条消息。",receiver,null,"\"knowledgeId\":"+id+",");
        assertEquals(1,knowledge.list(sid,receiver).size());assertTrue(knowledge.list(sid,bystander).isEmpty());
        for(String p:prompts)if(p.contains("姓名：旁观者"))assertFalse(p.contains("【他人转述"));
        assertEquals("TOLD",knowledge.list(sid,receiver).get(0).get("sourceType"));state.restore(sid,1);assertTrue(knowledge.list(sid,receiver).isEmpty());assertEquals(1,knowledge.list(sid,role).size());
    }
    @Test void sharingCannotUseAnotherRolesKnowledgeOrUnrequestedMessage(){
        long id=discover();act("SHARE_INFO","我没有决定要告诉渡者什么。",receiver,null,"\"knowledgeId\":"+id+",");assertTrue(knowledge.list(sid,receiver).isEmpty());
        act("SHARE_INFO","我告知渡者一个编造的秘密。",receiver,null,"\"knowledgeId\":99999999,");assertTrue(knowledge.list(sid,receiver).isEmpty());
    }
    @Test void acceptanceTransfersRealGiftAndSynchronizesMovementInventory(){
        reply(true);var result=act("GIVE","我提议把水囊赠予渡者。",receiver,"水囊","");
        assertEquals("COMPLETED",((Map<?,?>)result.ruleSnapshot().get("offer")).get("status"));assertFalse(inventory(role).contains("水囊"));assertTrue(inventory(receiver).contains("水囊"));
        assertFalse(jdbc.queryForObject("SELECT inventory FROM player_profile WHERE player_id=?",String.class,pid).contains("水囊"));
        state.restore(sid,0);assertTrue(inventory(role).contains("水囊"));assertFalse(inventory(receiver).contains("水囊"));
    }
    @Test void declinedGiftKeepsBothInventoriesAndTargetChoiceIsRespected(){reply(false);var r=act("GIVE","我想把水囊赠予渡者。",receiver,"水囊","");assertEquals("DECLINED",((Map<?,?>)r.ruleSnapshot().get("offer")).get("status"));assertTrue(inventory(role).contains("水囊"));assertFalse(inventory(receiver).contains("水囊"));}
    @Test void bystanderCannotAcceptForRecipientAndDoesNotSeePrivateOffer(){
        List<String> prompts=Collections.synchronizedList(new ArrayList<>());when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String p=m.get(1).get("content");prompts.add(p);return response("{\"behavior\":\"IGNORE\",\"accept_offer\":"+(p.contains("姓名：旁观者")?"true":"null")+"}");});
        var r=act("GIVE","我提议把水囊赠予渡者。",receiver,"水囊","");assertEquals("NO_RESPONSE",((Map<?,?>)r.ruleSnapshot().get("offer")).get("status"));assertTrue(inventory(role).contains("水囊"));
        for(String p:prompts)if(p.contains("姓名：旁观者"))assertFalse(p.contains("只向你提出的物品往来"));
    }
    @Test void tradeCommitsBothSidesTogetherAndRewindsBoth(){
        reply(true);var r=act("TRADE","我提议用水囊交换渡者的通行印。",receiver,"水囊","\"exchange\":{\"requestedItem\":\"通行印\"},");
        assertEquals("COMPLETED",((Map<?,?>)r.ruleSnapshot().get("offer")).get("status"));assertTrue(inventory(role).contains("通行印"));assertFalse(inventory(receiver).contains("通行印"));assertTrue(inventory(receiver).contains("水囊"));
        state.restore(sid,0);assertFalse(inventory(role).contains("通行印"));assertTrue(inventory(receiver).contains("通行印"));
    }
    @Test void missingTradeResourceCannotBecomeGiftOrFabricatedItem(){reply(true);act("TRADE","我提议用水囊交换渡者的神器。",receiver,"水囊","\"exchange\":{\"requestedItem\":\"神器\"},");assertTrue(inventory(role).contains("水囊"));assertFalse(inventory(role).contains("神器"));assertFalse(inventory(receiver).contains("水囊"));}
    @Test void negatedGiftCannotTransferEvenIfModelSaysAccept(){reply(true);act("GIVE","不要把水囊给渡者。",receiver,"水囊","");assertTrue(inventory(role).contains("水囊"));assertFalse(inventory(receiver).contains("水囊"));}
    @Test void recipientDeparturePreventsConsentFromTransferringItems(){when(model.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"LEAVE\",\"accept_offer\":true}"));var r=act("GIVE","我提议把水囊赠予渡者。",receiver,"水囊","");assertEquals("BLOCKED",((Map<?,?>)r.ruleSnapshot().get("offer")).get("status"));assertTrue(inventory(role).contains("水囊"));}
    @Test void modelFailureCannotAcceptOrConsumeAnOffer(){when(model.chatSubForJson(anyList())).thenThrow(new LLMApiException("offline",true));var r=act("GIVE","我提议把水囊赠予渡者。",receiver,"水囊","");assertEquals("NO_RESPONSE",((Map<?,?>)r.ruleSnapshot().get("offer")).get("status"));assertTrue(inventory(role).contains("水囊"));}
    @Test void inventedGiftIsRejectedBeforeItCanAdvanceTheWorld(){reply(true);act("GIVE","我赠予渡者无穷宝物。",receiver,"无穷宝物","");assertFalse(inventory(receiver).contains("无穷宝物"));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM world_clock WHERE session_id=?",Integer.class,sid));}
    @Test void spokenQuestionReachesOnlyTargetAndDialogueIsNotWorldTruth(){
        List<String> prompts=Collections.synchronizedList(new ArrayList<>());when(model.chatSubForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);String p=m.get(1).get("content");prompts.add(p);return response(p.contains("姓名：渡者")?"{\"behavior\":\"RESPOND\",\"speech\":\"我不打算离开渡口。\"}":"{\"behavior\":\"IGNORE\"}");});
        act("TALK","我问渡者为什么还守着旧船，心里盘算稍后绕路。",receiver,null,"\"spokenText\":\"为什么还守着旧船\",");
        assertTrue(prompts.stream().anyMatch(p->p.contains("玩家确实向你说出的话") && p.contains("为什么还守着旧船")));
        assertTrue(prompts.stream().noneMatch(p->p.contains("盘算稍后绕路")));
        for(String p:prompts)if(p.contains("姓名：旁观者"))assertFalse(p.contains("玩家确实向你说出的话"));
        assertEquals("HEARD",knowledge.list(sid,receiver).get(0).get("sourceType"));assertEquals("HEARD",knowledge.list(sid,role).get(0).get("sourceType"));
        assertFalse(jdbc.queryForObject("SELECT event_description FROM world_book WHERE session_id=? AND event_type='ACTION' ORDER BY entry_id DESC LIMIT 1",String.class,sid).contains("不打算离开"));
    }
    @Test void postponedOrDirectlyNegatedGiftCannotExecutePrematurely(){reply(true);act("GIVE","我不把水囊给渡者。",receiver,"水囊","");assertTrue(inventory(role).contains("水囊"));act("GIVE","等旁观者离开后，我再把水囊给渡者。",receiver,"水囊","");assertTrue(inventory(role).contains("水囊"));assertFalse(inventory(receiver).contains("水囊"));}
    @Test void oldSaveWithoutNotebookRestoresNoFutureKnowledge(){jdbc.update("UPDATE session_checkpoint SET payload=JSON_REMOVE(payload,'$.role_knowledge') WHERE session_id=? AND round_no=0",sid);discover();state.restore(sid,0);assertTrue(knowledge.list(sid,role).isEmpty());}
    long told(String text, boolean secret, String eventSid, String place) {
        jdbc.update("INSERT INTO world_book(session_id,round_no,coord_x,coord_y,location_name,event_type,event_description,is_secret) VALUES(?,0,0,0,?,'STORY_MILESTONE','旧桥恢复通行。',?)",eventSid,place,secret);
        Long event=jdbc.queryForObject("SELECT MAX(entry_id) FROM world_book WHERE session_id=?",Long.class,eventSid);
        jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'旧桥的消息',?,'TOLD',?,?,'雨后渡口',0)",sid,role,"event:"+event,text,receiver,scene);
        return jdbc.queryForObject("SELECT knowledge_id FROM role_knowledge WHERE session_id=? AND god_id=? AND source_ref=?",Long.class,sid,role,"event:"+event);
    }
    @Test void investigationChecksOwnedReportAgainstPublicOriginalAndDoesNotRepeatFindings(){
        long id=told("旧桥恢复通行。",false,sid,"雨后渡口");discover();
        var report=knowledge.list(sid,role).stream().filter(k->((Number)k.get("knowledgeId")).longValue()==id).findFirst().orElseThrow();
        assertEquals("SOURCE_MATCH",report.get("verificationStatus"));assertTrue(report.get("verificationNote").toString().contains("同一来源"));
        assertFalse(knowledge.privateContext(sid,receiver).contains("原始记录一致"));
        long before=jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type NOT IN ('TURN_EVENT','ROLE_DECISION')",Long.class,sid);discover();
        assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type NOT IN ('TURN_EVENT','ROLE_DECISION')",Long.class,sid));
    }
    @Test void inconsistentReportDoesNotProveLyingAndEditedClaimInvalidatesOldProof(){
        long id=told("旧桥已经断裂。",false,sid,"雨后渡口");discover();
        var record=knowledge.list(sid,role).stream().filter(k->((Number)k.get("knowledgeId")).longValue()==id).findFirst().orElseThrow();
        assertEquals("SOURCE_MISMATCH",record.get("verificationStatus"));assertTrue(record.get("verificationNote").toString().contains("原因待核实"));
        jdbc.update("UPDATE role_knowledge SET content='新的未知说法' WHERE knowledge_id=?",id);
        assertFalse(knowledge.list(sid,role).stream().filter(k->((Number)k.get("knowledgeId")).longValue()==id).findFirst().orElseThrow().containsKey("verificationStatus"));
    }
    @Test void secretForeignAndRemoteOriginalsCannotBeReadForVerification(){
        told("已知的转述甲",true,sid,"雨后渡口");told("已知的转述乙",false,"social_other","雨后渡口");told("已知的转述丙",false,sid,"远方地点");discover();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='CHECK'",Integer.class,sid));
    }
    @Test void realTradeCreatesPrivateHistoryWithoutBuyingTrustAndRewindRemovesIt(){
        reply(true);act("TRADE","我用水囊交换渡者的通行印，由他决定。",receiver,"水囊","\"exchange\":{\"requestedItem\":\"通行印\"},");
        assertTrue(knowledge.privateContext(sid,receiver).contains("本次交换履行"));assertTrue(knowledge.privateContext(sid,role).contains("本次交换履行"));
        assertFalse(knowledge.privateContext(sid,bystander).contains("本次交换履行"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_relationship WHERE session_id=? AND is_allied=true",Integer.class,sid));
        state.restore(sid,0);assertFalse(knowledge.privateContext(sid,receiver).contains("本次交换履行"));
    }
    @Test void refusalIsNotBetrayalAndFailedCallCreatesNoInteractionHistory(){
        reply(false);act("GIVE","我把水囊送给渡者，由他决定。",receiver,"水囊","");
        assertTrue(knowledge.privateContext(sid,receiver).contains("拒绝不等于欺骗或背叛"));state.restore(sid,0);
        doThrow(new LLMApiException("test failure",true)).when(model).chatSubForJson(anyList());
        act("GIVE","我把水囊送给渡者，由他决定。",receiver,"水囊","");
        assertFalse(knowledge.privateContext(sid,receiver).contains("实际交往"));
    }
    @Test void socialHistoryCannotCrowdOutOldEvidenceAndVerificationRewinds(){
        long id=told("旧桥恢复通行。",false,sid,"雨后渡口");
        for(int i=0;i<65;i++)jdbc.update("INSERT INTO role_knowledge(session_id,god_id,source_ref,title,content,source_type,source_god_id,scene_id,location_name,learned_tick) VALUES(?,?,?,'交往','实际交换一次','SOCIAL',?,?,'雨后渡口',0)",sid,role,"social:test:"+i,receiver,scene);
        assertTrue(knowledge.privateContext(sid,role).contains("旧桥恢复通行"));assertTrue(knowledge.list(sid,role).stream().anyMatch(k->k.get("title").equals("旧桥的消息")));discover();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='CHECK'",Integer.class,sid));
        state.restore(sid,0);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=?",Integer.class,sid));
    }

}

