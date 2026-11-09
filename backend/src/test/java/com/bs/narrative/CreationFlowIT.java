package com.bs.narrative;
import com.bs.narrative.service.*;
import com.bs.narrative.llm.*;
import com.bs.narrative.repository.*;
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
class CreationFlowIT {
    @Autowired CharacterCreationService creation;
    @Autowired CharacterCreationRepository creations;
    @Autowired GameActionService actions;
    @Autowired GameSessionService lifecycle;
    @Autowired SessionStateService state;
    @Autowired TalentRepository talents;
    @Autowired PlayerProfileRepository players;
    @Autowired GameSessionRepository sessions;
    @Autowired GameSceneRepository scenes;
    @Autowired JdbcTemplate jdbc;
    @Autowired CharacterPanelService characterPanels;
    @MockBean LLMProvider models;
    private static LLMResponse response(String json) {String raw=CreationWorldFixture.candidatesJson(json);return new LLMResponse(raw,raw,0,0,0,0);}
    private Map<String,Object> legacyStart(String pid,String mode){
        var start=creation.startGame(pid,mode);
        var cc=creations.findBySessionIdAndPlayerId(start.get("sessionId").toString(),pid).orElseThrow();
        cc.setStatus("QUESTIONS");creations.save(cc);
        return start;
    }
    private void stub() {
        when(models.chatForJson(anyList())).thenReturn(
            response("{\"personality_tags\":[\"坚韧\"],\"talents\":[{\"talent_name\":\"骨刃\",\"talent_description\":\"传承\",\"power_level\":1}]}"),
            response("{\"birth_story\":\"凡人入世\",\"initial_location\":{\"location_name\":\"水岸\",\"coord_x\":0,\"coord_y\":0,\"scene_description\":\"雾气弥漫\",\"present_npcs\":[\"rain_god\",\"museum_god\"]}}"),
            response("{\"objectives\":[{\"objective_id\":\"goal\",\"title\":\"探寻真相\",\"current_step\":\"初始\"}]}"));
    }
    @Test void originalAnswersAndOpeningResourcesSurviveCreation() {
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        when(models.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);
            return switch(calls.getAndIncrement()){
              case 0 -> response("{\"talents\":[{\"talent_name\":\"匠心\",\"power_level\":1,\"stat_key\":\"hui\"}]}");
              case 1 -> {
                assertTrue(messages.get(1).get("content").contains("普通木匠林舟，没有特殊身世"));
                assertTrue(messages.get(1).get("content").contains("兑现承诺"));
                assertTrue(messages.get(1).get("content").contains("先观察退路"));
                yield response("""
                  {"player_name":"林舟","birth_story":"林舟是普通木匠。","initial_location":{"location_name":"木棚","scene_description":"你握着刻刀。陈师傅举着铜镜。","present_npcs":["陈师傅"]},"initial_inventory":[{"owner":"PLAYER","items":[{"name":"刻刀","basis":"你握着刻刀。"}]},{"owner":"陈师傅","items":[{"name":"铜镜","basis":"陈师傅举着铜镜。"}]}],"player_journal":{"relationships":[{"name":"陈师傅","kind":"ACQUAINTANCE","label":"同棚匠人","basis":"实际当场见面"}]}}
                  """);
              }
              default -> response("{\"objectives\":[]}");
            };
        });
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");
        creation.submitAnswers(sid,"p_demo","普通木匠林舟，没有特殊身世","兑现承诺","先观察退路");
        var born=creation.selectTalent(sid,"p_demo",talents.findBySessionId(sid).get(0).getTalentId());
        assertEquals("[\"刻刀\"]",jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,born.get("playerGodId")));
        assertEquals("林舟",jdbc.queryForObject("SELECT god_name FROM god_profile WHERE god_id=?",String.class,born.get("playerGodId")));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='PLAYER_RELATION'",Integer.class,sid));
        assertEquals("[\"铜镜\"]",jdbc.queryForObject("SELECT inventory FROM god_profile WHERE session_id=? AND god_name='陈师傅'",String.class,sid));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM role_knowledge WHERE session_id=? AND source_type='ITEM_STATE' AND content LIKE '%GENESIS%'",Integer.class,sid));
    }
    @Test void specialTalentKeepsItsEffectAcrossReloadBirthAndRepeatedSelection(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(models.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);
            return switch(calls.getAndIncrement()){
                case 0->response("""
                  {"talents":[{"talent_name":"听木知隙","power_level":2,"stat_key":"","special_effect":{"trigger":"近距离检查熟悉的木制器物时","benefit":"更容易从轻敲的声音察觉空腔或松动","limits":"需要靠近并实际检查，不能隔墙探知内部，也不能直接知道机关用途","tradeoff":"嘈杂环境会干扰判断，精细检查需要时间"}}]}
                  """);
                case 1->{
                    assertTrue(messages.get(1).get("content").contains("轻敲的声音"));
                    assertFalse(messages.get(0).get("content").contains("天赋只有描述中标明的基础属性增益"));
                    yield response("{\"player_name\":\"林舟\",\"birth_story\":\"林舟跟随父亲学习木工，如今在木棚检查松动的门框。\",\"initial_location\":{\"location_name\":\"旧木棚\",\"scene_description\":\"林舟站在旧木棚里，手边没有其他人。\",\"present_npcs\":[]},\"initial_inventory\":[{\"owner\":\"PLAYER\",\"items\":[]}]}");
                }
                default->response("{\"objectives\":[]}");
            };
        });
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");
        var generated=creation.submitAnswers(sid,"p_demo","普通木匠林舟，没有超自然能力","认真手艺","观察再行动");
        var chosen=talents.findBySessionId(sid).get(0);String description=chosen.getTalentDescription();
        assertTrue(description.contains("轻敲的声音"),"Generated special effects must not be replaced by a fixed stat bonus");assertTrue(description.contains("不能隔墙"));
        creation.creationState(sid,"p_demo");assertEquals(description,talents.findById(chosen.getTalentId()).orElseThrow().getTalentDescription());
        assertEquals(generated,creation.submitAnswers(sid,"p_demo","普通木匠林舟，没有超自然能力","认真手艺","观察再行动"));assertEquals(1,calls.get());
        var born=creation.selectTalent(sid,"p_demo",chosen.getTalentId());String role=born.get("playerGodId").toString();
        assertEquals(9,jdbc.queryForObject("SELECT base_hui FROM god_profile WHERE god_id=?",Integer.class,role),"A purely situational talent does not silently add stats");
        var panel=(List<Map<String,Object>>)characterPanels.describe(sid,"p_demo").get("talents");assertEquals("NARRATIVE",panel.get(0).get("effectKind"));assertEquals(description,panel.get(0).get("description"));
        int completedCalls=calls.get();creation.selectTalent(sid,"p_demo",chosen.getTalentId());creation.creationState(sid,"p_demo");assertEquals(completedCalls,calls.get());assertEquals(description,talents.findById(chosen.getTalentId()).orElseThrow().getTalentDescription());
    }
    @Test void fullCreationProducesOwnedCharactersAndInitialCheckpoint() {
        stub();
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");
        creation.submitAnswers(sid,"p_demo","一","二","三");
        var talent=talents.findBySessionId(sid).get(0);
        var result=creation.selectTalent(sid,"p_demo",talent.getTalentId());
        assertEquals("PLAYING",sessions.findById(sid).orElseThrow().getStatus());
        String sceneId=(String)result.get("sceneId"),role=(String)result.get("playerGodId");
        assertTrue(sceneId.length()<=32);assertTrue(role.length()<=32);
        assertEquals(sid,players.findById("p_demo").orElseThrow().getActiveSessionId());
        assertEquals(2,scenes.findById(sceneId).orElseThrow().getPresentGods().size());
        var location=(Map<?,?>)result.get("initialLocation");var characters=(List<Map<String,String>>)location.get("presentCharacters");
        assertEquals(2,characters.size());assertTrue(characters.stream().allMatch(c->!c.get("name").startsWith("npc_") && !c.get("introduction").isBlank()));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));
        assertEquals(1,state.list(sid).size());
        assertEquals(0,((Number)state.list(sid).get(0).get("roundNo")).intValue());
    }
    @Test void restartReusesItsCreationSessionAndKeepsPreviousSessionPlayable() {
        var previous=sessions.findById("sess_demo_001").orElseThrow().getStatus();
        var restarted=lifecycle.restart("p_demo","DREAMER",true,"sess_demo_001");
        String sid=(String)restarted.get("newSessionId");
        assertEquals(sid,creation.creationState(sid,"p_demo").get("sessionId"));
        assertEquals(previous,sessions.findById("sess_demo_001").orElseThrow().getStatus());
        assertEquals("sess_demo_001",sessions.findById(sid).orElseThrow().getPrevSessionId());
    }

    @Test void openingCastEvidenceMaterializesEvenIfLegacyNameArrayIsEmpty(){
        when(models.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"清辉\",\"stat_key\":\"hui\",\"power_level\":1}]}"),
            response("{\"birth_story\":\"陈道人站在坑边，黑衣女修在另一侧看守。\",\"initial_location\":{\"location_name\":\"陨石坑\",\"scene_description\":\"陈道人站在坑边，黑衣女修在另一侧看守。\",\"present_npcs\":[],\"scene_cast\":[{\"name\":\"陈道人\",\"basis\":\"陈道人站在坑边\"},{\"name\":\"黑衣女修\",\"basis\":\"黑衣女修在另一侧看守\"}]}}"),response("{\"objectives\":[]}"));
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");creation.submitAnswers(sid,"p_demo","一","二","三");
        var result=creation.selectTalent(sid,"p_demo",talents.findBySessionId(sid).get(0).getTalentId());
        assertEquals(2,scenes.findById((String)result.get("sceneId")).orElseThrow().getPresentGods().size());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM session_npc_alias WHERE session_id=?",Integer.class,sid));
    }

    @Test void legacyEmptyCastIsRecoveredInExistingIntentRequestBeforeNpcActs(){
        when(models.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"清辉\",\"stat_key\":\"hui\",\"power_level\":1}]}"),
            response("{\"birth_story\":\"陈道人站在坑边，黑衣女修在另一侧看守。\",\"initial_location\":{\"location_name\":\"旧坑\",\"scene_description\":\"陈道人站在坑边，黑衣女修在另一侧看守。\",\"present_npcs\":[]}}"),response("{\"objectives\":[]}"));
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");creation.submitAnswers(sid,"p_demo","一","二","三");
        var born=creation.selectTalent(sid,"p_demo",talents.findBySessionId(sid).get(0).getTalentId());String sceneId=(String)born.get("sceneId");assertTrue(scenes.findById(sceneId).orElseThrow().getPresentGods().isEmpty());
        when(models.chatForJson(anyList())).thenReturn(response("{\"intent\":\"WAIT\",\"waiting\":{\"condition\":\"CHANGE\"},\"scene_cast\":[{\"name\":\"陈道人\",\"basis\":\"陈道人站在坑边\"},{\"name\":\"黑衣女修\",\"basis\":\"黑衣女修在另一侧看守\"}]}"),response("{\"visible\":{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"等候期间，两人开口谈起自己的事情。\"}]}}"));
        when(models.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"RESPOND\",\"speech\":\"此地尚需查明，我先问清其中来由。\"}"));
        var result=actions.processActionWithDb(new com.bs.narrative.dto.ActionRequest("p_demo",sceneId,"等待直到有变化",null),sid);
        assertEquals(2,scenes.findById(sceneId).orElseThrow().getPresentGods().size());assertEquals("READY",((Map<?,?>)result.ruleSnapshot().get("waiting")).get("status"));
        verify(models,times(2)).chatSubForJson(anyList());
        state.restore(sid,0);assertTrue(scenes.findById(sceneId).orElseThrow().getPresentGods().isEmpty());
    }

    @Test void firstDialogueAlreadyHasAnExplicitOpeningTargetBeforeIntentParsing(){
        when(models.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"清辉\",\"stat_key\":\"hui\",\"power_level\":1}]}"),
            response("{\"birth_story\":\"两人在坑边。\",\"initial_location\":{\"location_name\":\"旧坑\",\"scene_description\":\"陈道人、黑衣女修各据一方。\",\"present_npcs\":[]}}"),response("{\"objectives\":[]}"));
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");creation.submitAnswers(sid,"p_demo","一","二","三");var born=creation.selectTalent(sid,"p_demo",talents.findBySessionId(sid).get(0).getTalentId());String sceneId=(String)born.get("sceneId");
        when(models.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> m=call.getArgument(0);if(m.get(0).get("content").contains("意图解析器")){
          String target=jdbc.queryForObject("SELECT god_id FROM god_profile WHERE session_id=? AND god_name='陈道人'",String.class,sid);assertTrue(m.get(1).get("content").contains(target));
          return response("{\"intent\":\"TALK\",\"targetGodId\":\""+target+"\",\"spokenText\":\"愿意一起核对来历吗\",\"confidence\":0.99}");
        }return response("{\"visible\":{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"你向陈道人提出核对的询问。\"}]}}");});
        when(models.chatSubForJson(anyList())).thenReturn(response("{\"behavior\":\"RESPOND\",\"speech\":\"我先听听来由。\",\"decision_point\":\"NONE\"}"));
        var r=actions.processActionWithDb(new com.bs.narrative.dto.ActionRequest("p_demo",sceneId,"我对陈道人说：愿意一起核对来历吗",null),sid);
        assertEquals("NO_OP",r.outcomeType());assertTrue(r.lines().stream().anyMatch(l->l.speech().contains("先听听来由")));verify(models,times(2)).chatSubForJson(anyList());
    }

    @Test void identicalAnswersReplayTheOriginalTalentsWithoutCallingTheModelAgain(){
        stub();String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");var first=creation.submitAnswers(sid,"p_demo","甲","乙","丙");
        clearInvocations(models);var second=creation.submitAnswers(sid,"p_demo","甲","乙","丙");assertEquals(first,second);verifyNoInteractions(models);
    }
    @RepeatedTest(3) void playerAliasesCannotCreateASelfNpcOrLoseAnInheritedKeepsake(){
        when(models.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"修屋巧手\",\"stat_key\":\"hui\",\"power_level\":1}]}"),response("""
          {"player_name":"阿德里安·维斯","birth_story":"阿德里安从父亲手中继承旧银徽，今日与米拉同在驿舍。","initial_location":{"location_name":"雨后驿舍","scene_description":"阿德里安站在门前。米拉正在擦桌。","present_npcs":["阿德里安","米拉"],"scene_cast":[{"name":"阿德里安·维斯","basis":"阿德里安·维斯站在门前。"}]},"initial_inventory":[{"owner":"PLAYER","items":[]},{"owner":"阿德里安","items":[{"name":"旧银徽","basis":"父亲所赠，目前仍随身"}]},{"owner":"米拉","items":[]}],"player_journal":{"items":[{"name":"旧银徽","kind":"KEEPSAKE","description":"父亲留下的身份信物","origin":"父亲赠予"}]}}
          """),response("{\"objectives\":[]}"));
        String sid=(String)legacyStart("p_demo","OMNISCIENT").get("sessionId");creation.submitAnswers(sid,"p_demo","主角叫阿德里安，人类男性。","父亲所赠银徽仍随身。","与米拉经营驿舍。");var born=creation.selectTalent(sid,"p_demo",talents.findBySessionId(sid).get(0).getTalentId());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=?",Integer.class,sid));assertEquals("[\"旧银徽\"]",jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,born.get("playerGodId")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM god_profile WHERE session_id=? AND god_id<>? AND god_name IN ('阿德里安','阿德里安·维斯')",Integer.class,sid,born.get("playerGodId")));
        var panel=characterPanels.describe(sid,"p_demo");assertTrue(panel.get("importantItems").toString().contains("旧银徽"));verify(models,times(3)).chatForJson(anyList());
    }
    @Test void changedAnswersCannotDeleteOrRerollAcceptedTalents(){
        stub();String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");creation.submitAnswers(sid,"p_demo","甲","乙","丙");var ids=talents.findBySessionId(sid).stream().map(t->t.getTalentId()).toList();clearInvocations(models);
        assertThrows(com.bs.narrative.exception.BusinessException.class,()->creation.submitAnswers(sid,"p_demo","改了","乙","丙"));assertEquals(ids,talents.findBySessionId(sid).stream().map(t->t.getTalentId()).toList());verifyNoInteractions(models);
    }
    @Test void rapidStartsHaveDistinctSchemaSizedSessionIdentities(){
        String first=(String)legacyStart("p_demo","DREAMER").get("sessionId"),second=(String)legacyStart("p_demo","DREAMER").get("sessionId");assertNotEquals(first,second);assertEquals(32,first.length());assertEquals(32,second.length());
    }

    @Test void incompleteInventoryRepairsOnlyTheFactTableAndPreservesTheOpeningStory(){
        when(models.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"细察\",\"stat_key\":\"hui\",\"power_level\":1}]}"),
                response("{\"birth_story\":\"林舟仍是原来的普通木匠。\",\"initial_location\":{\"location_name\":\"旧木棚\",\"scene_description\":\"你握着刻刀。陈师傅空手站在门边。\",\"present_npcs\":[\"陈师傅\"]},\"initial_inventory\":[{\"owner\":\"PLAYER\",\"items\":[{\"name\":\"刻刀\",\"basis\":\"你握着刻刀。\"}]}]}"),
                response("{\"initial_inventory\":[{\"owner\":\"PLAYER\",\"items\":[{\"name\":\"刻刀\",\"basis\":\"你握着刻刀。\"}]},{\"owner\":\"陈师傅\",\"items\":[]}]}"),response("{\"objectives\":[]}"));
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");creation.submitAnswers(sid,"p_demo","普通木匠","求真","谨慎");
        var born=creation.selectTalent(sid,"p_demo",talents.findBySessionId(sid).get(0).getTalentId());
        assertEquals("林舟仍是原来的普通木匠。",born.get("birthStory"));
        assertEquals("[\"刻刀\"]",jdbc.queryForObject("SELECT inventory FROM god_profile WHERE god_id=?",String.class,born.get("playerGodId")));
        assertEquals("[]",jdbc.queryForObject("SELECT inventory FROM god_profile WHERE session_id=? AND god_name='陈师傅'",String.class,sid));
        verify(models,times(4)).chatForJson(anyList());
    }

    @Test void malformedInventoryRepairCannotCommitAnUnknownOwner(){
        when(models.chatForJson(anyList())).thenReturn(response("{\"talents\":[{\"talent_name\":\"细察\",\"power_level\":1}]}"),
                response("{\"birth_story\":\"普通木匠来到木棚。\",\"initial_location\":{\"location_name\":\"木棚\",\"scene_description\":\"你与陈师傅空手站在门边。\",\"present_npcs\":[\"陈师傅\"]},\"initial_inventory\":[]}"),
                response("{\"initial_inventory\":[{\"owner\":\"未知编号\",\"items\":[]},{\"owner\":\"陈师傅\",\"items\":[]}]}"));
        String sid=(String)legacyStart("p_demo","DREAMER").get("sessionId");creation.submitAnswers(sid,"p_demo","普通木匠","求真","谨慎");
        assertThrows(com.bs.narrative.exception.BusinessException.class,()->creation.selectTalent(sid,"p_demo",talents.findBySessionId(sid).get(0).getTalentId()));
        verify(models,times(3)).chatForJson(anyList());
    }

}
