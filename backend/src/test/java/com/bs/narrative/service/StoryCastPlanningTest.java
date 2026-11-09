package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryCastPlanningTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void participantsComeFromStructuredAuthorReferencesInsteadOfProseOrSceneNames()throws Exception{
        var proposal=json.readTree("""
            {"story":"未列参与者的路人，不据正文抽人","sceneId":"place-not-a-role",
             "roleExperiences":[{"roleId":"old"}],"sceneOutcomes":[{"sceneId":"far","roleIds":["old","returning"]}],
             "decision":{"counterpartId":"negotiator"},"mechanics":[{"actorId":"player","targetId":"opponent"}],
             "events":[{"actorId":"giver","effects":[{"fromId":"giver","toId":"receiver","itemRef":"item-not-a-role"}]}],
             "roleRequests":[{"roleId":"lookup"}]}
            """);
        assertEquals(Set.of("old","returning","negotiator","player","opponent","giver","receiver","lookup"),StoryCastService.referencedRoles(proposal));
    }
    @Test void legacyUncommittedProseIsReplacedWithOnlyProposedIdentities(){
        var f=json.createObjectNode();f.putObject("uncommittedStoryProposal").put("story","CANDIDATE_DIALOGUE_NOT_HISTORY");
        var rs=f.putObject("currentWorld").putObject("resources");rs.putObject("new").put("name","店主").put("provisional",true);rs.putObject("old").put("name","同行者");
        var own=f.putObject("privateRoleContexts").putObject("new");own.putArray("establishedSelf").add("店主谨慎，不轻诺");own.putObject("previousPortrayal").put("narrativeTier","EXTRA");
        assertTrue(StoryCastService.refreshPlanning(f));assertFalse(f.toString().contains("CANDIDATE_DIALOGUE_NOT_HISTORY"));assertEquals(1,f.path("castPlanning").path("candidates").size());assertEquals("new",f.at("/castPlanning/candidates/0/roleId").asText());assertEquals("UNCOMMITTED",f.at("/castPlanning/status").asText());assertFalse(StoryCastService.refreshPlanning(f));
    }
    @Test void storyFirstRepairNeverReintroducesOldArrivalOrClockInstructions()throws Exception{
        var provider=mock(LLMProvider.class);var f=json.createObjectNode().put("storyFirst",true);var draft=json.createObjectNode().put("story","候选尚未发生");
        when(provider.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String repair=messages.stream().filter(m->m.get("content").contains("时间只填整段耗时")).findFirst().orElseThrow().get("content");assertFalse(repair.contains("先CONTINUE"));assertFalse(repair.contains("routeDestinations矛盾"));return new LLMResponse("{}","{}",0,0,0,0);});
        new SceneStoryService(provider).write(f,draft,"保存字段格式",System.nanoTime()+60_000_000_000L);verify(provider,times(1)).chatForJson(anyList());
    }
    @Test void frozenContinuationRefreshesStalePlanningBeforeEveryMainRequest()throws Exception{
        var provider=mock(LLMProvider.class);var f=json.createObjectNode().put("storyFirst",true);
        f.putArray("alreadyHappenedThisChapter").add("已抵达铜匠铺并看过断铜片，铜匠转身取出旧账。");
        f.putObject("generationScope").put("mode","NEW_PASSAGE");
        f.putObject("castPlanning").put("instruction","完整创作玩家本轮方向的起因、行程、交谈和结果");
        f.putObject("currentWorld").putObject("resources").putObject("new").put("name","账房").put("provisional",true);
        f.putObject("privateRoleContexts").putObject("new").putArray("establishedSelf").add("账房只愿核对旧账");
        when(provider.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> m=call.getArgument(0);var sent=json.readTree(m.get(1).get("content"));
            assertEquals("CONTINUE_AFTER_COMMITTED",sent.at("/generationScope/mode").asText());assertEquals(1,sent.at("/generationScope/committedPassageCount").asInt());
            assertTrue(sent.at("/castPlanning/instruction").asText().contains("不重写本轮开头"));
            assertEquals(f.path("alreadyHappenedThisChapter"),sent.path("alreadyHappenedThisChapter"));
            String last=m.get(m.size()-1).get("content");var guide=json.readTree(last.substring(last.indexOf('{')));
            assertTrue(sent.at(guide.at("/episodeFocus/scopeRef").asText()).path("instruction").asText().contains("绝不重写已发布行程"));
            return new LLMResponse("{}","{}",0,0,0,0);
        });
        var service=new SceneStoryService(provider);service.write(f,null,"",System.nanoTime()+60_000_000_000L);
        service.write(f,json.createObjectNode().put("story","未提交的账本候选"),"字段修复",System.nanoTime()+60_000_000_000L);
        verify(provider,times(2)).chatForJson(anyList());assertEquals("NEW_PASSAGE",f.at("/generationScope/mode").asText(),"transport refresh must not mutate stored facts");
    }
}
