package com.bs.narrative.service;

import com.bs.narrative.llm.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoryAuthorContextTest {
    @Test void fixedWorldSurvivesActualAuthorPreparationAndRoleMessages()throws Exception{
        var mapper=new ObjectMapper();var facts=mapper.createObjectNode().put("storyFirst",true);
        var world=facts.putObject("currentWorld").putObject("worldSetting")
                .put("title","灰港").put("culturalStyle","WESTERN")
                .put("summary","早期工业城邦，精灵与普通人共存，不默认神明真实存在。");
        var model=mock(LLMProvider.class);
        when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);
            assertEquals(world,mapper.readTree(messages.get(1).get("content")).at("/currentWorld/worldSetting"));
            assertEquals(world,mapper.readTree(messages.get(messages.size()-1).get("content")).path("fixedWorldSetting"));
            return new LLMResponse("{}","{}",0,0,0,0);
        });
        new SceneStoryService(model).write(facts,null,"",System.nanoTime()+60_000_000_000L);
        verify(model,times(1)).chatForJson(anyList());
        var own=mapper.createObjectNode();own.set("worldSetting",world.deepCopy());
        assertTrue(StoryAuthorContext.roleMessages(own).stream().anyMatch(m->m.get("content").contains(world.path("summary").asText())));
    }
    @Test void authorGetsAnExplicitPlayerJournalReminderWithoutAnAdditionalModelCall(){
        var facts=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();facts.putObject("playerJournal").putArray("records");
        var brief=StoryAuthorContext.brief(facts);assertTrue(brief.path("honorExistingPlayerChoice").asText().contains("相同问题"));assertTrue(brief.path("playerJournalNeedsInitialSnapshot").asBoolean());assertTrue(brief.path("playerJournalRecording").asText().contains("首次"));
        facts.withObject("playerJournal").withArray("records").addObject().put("source_type","PLAYER_RELATION");assertFalse(StoryAuthorContext.brief(facts).path("playerJournalNeedsInitialSnapshot").asBoolean());
    }
    final ObjectMapper json=new ObjectMapper();
    @Test void roleFocusKeepsItsOwnPastRelationshipsAndHoldingsAlongsideAnotherActorsRequest()throws Exception{
        var own=json.createObjectNode().put("id","mila").put("name","米拉").put("narrativeActorId","adrian").put("narrativeRequest","我把父亲的银徽借给米拉");
        own.putArray("items").addObject().put("name","账本");own.putArray("establishedSelf").add("米拉是掌事人与记账员");
        own.putArray("openingSharedHistory").addObject().put("withRoleId","adrian").put("kind","FRIEND").put("summary","自小认识，共同接下驿舍");
        var original=own.deepCopy();var latest=json.readTree(StoryAuthorContext.roleMessages(own).get(2).get("content"));
        assertEquals("mila",latest.at("/ownIdentity/yourRoleId").asText());assertEquals(own.path("items"),latest.path("items"));
        assertEquals(own.path("openingSharedHistory"),latest.path("openingSharedHistory"));assertEquals(own.path("establishedSelf"),latest.path("establishedSelf"));assertEquals(original,own);
    }
    @Test void latestPublishedFactsAndCurrentIntentionsRemainDistinctFromHistoricalPlans()throws Exception{
        var facts=json.createObjectNode().put("storyFirst",true).put("playerDirection","沿已确认的新依据查清来历");
        facts.putArray("previousChapters").addObject().put("story","先前在旧窑坡问过老妇");
        String published="你与阿绣已在桥西尽头罗师傅铺前看过铜片，退到檐下。";
        facts.putArray("alreadyHappenedThisChapter").add(published);
        facts.putObject("sceneDraft").put("story","UNCOMMITTED_NEW_LOCATION");
        var person=facts.putObject("characterDossiers").putObject("copper").put("name","罗师傅");
        person.putObject("identity").putArray("establishedSelf").add("蛇桥集桥西尽头补铜匠");
        person.putObject("currentState").put("storySituation","在桥西尽头矮铺，不认送铜片人").put("recordedLocation","蛇桥集·桥西第三间草铺");
        person.putObject("intentions").put("boundaries","不指认送件人，不带路");
        person.putObject("pastInformation").putArray("ownRecords").addObject().put("source_type","ROLE_PROFILE").put("content","旧计划：或许去旧窑坡");
        var original=facts.deepCopy();StoryCastService.refreshPlanning(facts);var brief=StoryAuthorContext.brief(facts);
        assertEquals("/alreadyHappenedThisChapter/0",brief.at("/factSources/latestPublishedPassageRef").asText());assertEquals("/characterDossiers/copper",brief.at("/characterUpdates/0/dossierRef").asText());
        assertFalse(brief.toString().contains("旧计划：或许去旧窑坡"));assertFalse(brief.toString().contains("UNCOMMITTED_NEW_LOCATION"));assertEquals(original.path("characterDossiers"),facts.path("characterDossiers"));
        var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> m=call.getArgument(0);var complete=json.readTree(m.get(1).get("content"));String last=m.get(m.size()-1).get("content");
            assertTrue(complete.at("/characterDossiers/copper/pastInformation/historicalSubjectiveRecords").toString().contains("旧计划：或许去旧窑坡"),"Unarchived history remains available in its subjective layer");
            assertTrue(complete.at("/characterDossiers/copper/intentions/boundaries").asText().contains("不指认送件人，不带路"));assertEquals(published,complete.at(json.readTree(last).at("/factSources/latestPublishedPassageRef").asText()).asText());assertFalse(last.contains("UNCOMMITTED_NEW_LOCATION"));return new LLMResponse("{}","{}",0,0,0,0);
        });
        new SceneStoryService(model).write(facts,null,"",System.nanoTime()+60_000_000_000L);
    }
    @Test void allSixDifferencesReachTheActualAuthorRequestAndUnknownPlaceholdersDoNotSkewThem()throws Exception{
        var facts=json.createObjectNode().put("storyFirst",true).put("playerDirection","调查亭内公开痕迹，按各人的能力自然推进");
        var dossiers=facts.putObject("characterDossiers");
        var low=dossiers.putObject("low").put("name","甲");low.putObject("identity").put("sixStatsEstablished",true).set("sixStats",json.readTree("{\"zhi\":8,\"hui\":8,\"dou\":12,\"wen\":8,\"ben\":0,\"gui\":15}"));
        var high=dossiers.putObject("high").put("name","乙");high.putObject("identity").put("sixStatsEstablished",true).set("sixStats",json.readTree("{\"zhi\":123,\"hui\":123,\"dou\":118,\"wen\":123,\"ben\":87,\"gui\":87}"));
        var unknown=dossiers.putObject("unknown");unknown.putObject("identity").put("sixStatsEstablished",false).set("sixStats",json.readTree("{\"zhi\":999,\"hui\":999,\"dou\":999,\"wen\":999,\"ben\":999,\"gui\":999}"));
        var original=facts.deepCopy();var model=mock(LLMProvider.class);
        when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);String latest=messages.get(messages.size()-1).get("content");
            var brief=json.readTree(latest.substring(latest.indexOf('{')));var ranges=brief.path("sixStatRanges");
            assertEquals(6,ranges.size());assertEquals(115,ranges.at("/zhi/gap").asInt());assertEquals(115,ranges.at("/hui/gap").asInt());assertEquals(106,ranges.at("/dou/gap").asInt());assertEquals(115,ranges.at("/wen/gap").asInt());assertEquals(87,ranges.at("/ben/gap").asInt());assertEquals(72,ranges.at("/gui/gap").asInt());
            assertEquals(123,ranges.at("/wen/maxActual").asInt());assertEquals(8,brief.at("/characterUpdates/0/actualSixStats/wen").asInt());assertEquals(123,brief.at("/characterUpdates/1/actualSixStats/wen").asInt());
            return new LLMResponse("{}","{}",0,0,0,0);
        });
        var service=new SceneStoryService(model);service.write(facts,null,"",System.nanoTime()+60_000_000_000L);assertEquals(original,facts);
        service.write(facts,json.createObjectNode().put("story","尚未提交的候选"),"候选保存问题",System.nanoTime()+60_000_000_000L);assertEquals(original,facts);verify(model,times(2)).chatForJson(anyList());
    }
    @Test void roleAbilityReminderContainsOnlyOwnNumbersAndASecondKnownPersonIsNeededForComparisons()throws Exception{
        var own=json.createObjectNode().put("id","a").put("sixStatsEstablished",true);own.putObject("sixStats").put("wen",123).put("hui",36);
        var input=own.deepCopy();var messages=StoryAuthorContext.roleMessages(own);var latest=json.readTree(messages.get(messages.size()-1).get("content"));
        assertEquals(own.path("sixStats"),latest.path("actualSixStats"));assertFalse(latest.has("sixStatRanges"));assertEquals(input,own);
        var facts=json.createObjectNode();facts.putObject("characterDossiers").putObject("a").putObject("identity").set("sixStats",own.path("sixStats"));
        assertFalse(StoryAuthorContext.brief(facts).has("sixStatRanges"));
    }
    @Test void theLatestRoleRequestSeparatesNpcIdentityFromThePlayersFirstPerson()throws Exception{
        var own=json.createObjectNode().put("id","npc").put("name","陆衡").put("narrativeActorId","player").put("narrativeRequest","我将自己持有的印交给知秋");own.putArray("items");
        var original=own.deepCopy();var messages=StoryAuthorContext.roleMessages(own);var latest=json.readTree(messages.get(messages.size()-1).get("content"));
        assertEquals("npc",latest.path("yourRoleId").asText());assertEquals("陆衡",latest.path("yourName").asText());assertEquals("player",latest.path("requestActorId").asText());assertTrue(latest.path("requestMeaning").asText().contains("不是你"));assertEquals(original,own);
        assertTrue(messages.get(0).get("content").contains("\"yourName\":\"陆衡\""));assertTrue(messages.get(0).get("content").contains("\"playerActorId\":\"player\""));
    }
    @Test void accumulatedContinuityReachesTheLastAuthorMessageAlongsideTheActualPublishedEnding()throws Exception{
        var facts=json.createObjectNode().put("storyFirst",true);facts.putArray("storyMemory").add("旧委托已解决，信物仍由知秋代管；未答复的邀请尚未成立。");facts.putArray("previousChapters").addObject().put("story","两人已走出北亭。");facts.putArray("alreadyHappenedThisChapter").add("刚才已完成登记核对，发现新的年份线索。");
        var model=mock(LLMProvider.class);when(model.chatForJson(anyList())).thenAnswer(call->{List<Map<String,String>> messages=call.getArgument(0);String latest=messages.get(messages.size()-1).get("content");var guide=json.readTree(latest);var input=json.readTree(messages.get(1).get("content"));assertTrue(input.at(guide.at("/factSources/accumulatedContinuityRef").asText()).toString().contains("旧委托已解决"));assertEquals("刚才已完成登记核对，发现新的年份线索。",input.at(guide.at("/factSources/latestPublishedPassageRef").asText()).asText());assertFalse(latest.contains("旧委托已解决"));return new LLMResponse("{}","{}",0,0,0,0);});new SceneStoryService(model).write(facts,null,"",System.nanoTime()+60_000_000_000L);
    }
    @Test void aNewRoleReceivesPlayerIdentityAndContinuationPhaseWithoutOtherPeoplesExperiences()throws Exception{
        var operation=json.createObjectNode().put("playerRole","player").put("playerInput","我把自己的信物交给守册人");operation.putArray("scenePassages").add("OTHER_PERSON_PRIVATE_EVENT");var own=json.createObjectNode().put("id","new_npc").put("name","守册人").put("storySituation","本段拟初次出场");StoryAuthorContext.bindRoleRequest(own,operation);var messages=StoryAuthorContext.roleMessages(own);var latest=json.readTree(messages.get(messages.size()-1).get("content"));assertEquals("player",latest.path("requestActorId").asText());assertEquals("CONTINUE_AFTER_COMMITTED",latest.at("/narrativeScope/mode").asText());assertFalse(messages.toString().contains("OTHER_PERSON_PRIVATE_EVENT"));assertFalse(operation.has("narrativeActorId"));
    }
    @Test void newRoundUsesLastPublishedChapterAndDoesNotInventPresentCast(){
        var facts=json.createObjectNode();facts.putArray("previousChapters").addObject().put("story","更早的前文");facts.withArray("previousChapters").addObject().put("story","你们已核实寄包仍由老妇保存");
        facts.putArray("alreadyHappenedThisChapter");var brief=StoryAuthorContext.brief(facts);
        assertEquals("你们已核实寄包仍由老妇保存",facts.at(brief.at("/factSources/latestPublishedPassageRef").asText()).asText());assertTrue(brief.path("characterUpdates").isEmpty());
    }
    @Test void roleRequestEndsWithNewProposalAndLatestCompletedExperienceInsteadOfAnOldPlan()throws Exception{
        var own=json.createObjectNode().put("id","axiu").put("narrativeRequest","玩家提议自愿结伴，请给出本人回应与条件").put("storySituation","铜片已经看完，退到沟口");
        var records=own.putArray("ownRecords");records.addObject().put("source_type","TURN_EVENT").putObject("content").put("text","已经看完铜片，未交铃");
        records.addObject().put("source_type","ROLE_PROFILE").putObject("content").put("executionStatus","PROPOSED").put("intention","准备请人取出铜片来看");
        records.addObject().put("source_type","TURN_EVENT").putObject("content").put("text","更早时仍在集上问路");
        var original=own.deepCopy();var messages=StoryAuthorContext.roleMessages(own);var chronological=json.readTree(messages.get(1).get("content"));var now=json.readTree(messages.get(2).get("content"));
        assertEquals("更早时仍在集上问路",chronological.at("/ownRecords/0/content/text").asText());assertEquals("已经看完铜片，未交铃",chronological.at("/ownRecords/2/content/text").asText());
        assertEquals("已经看完铜片，未交铃",now.at("/latestActualExperience/text").asText());assertEquals(RoleRequestContext.OWN_DIRECTION,now.path("narrativeRequest").asText());assertFalse(now.toString().contains("准备请人取出铜片来看"));assertFalse(now.has("establishedStoryFacts"));assertEquals(original,own);
        var repeated=StoryAuthorContext.roleMessages((ObjectNode)chronological);assertEquals(chronological,json.readTree(repeated.get(1).get("content")),"Reusing already chronological input must not reverse it again");
    }
}
