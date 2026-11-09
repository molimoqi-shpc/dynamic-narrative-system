package com.bs.narrative.service;
import com.bs.narrative.dto.ActionResponse.NarrativeLine;
import com.bs.narrative.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnsembleNarrativeTest {
    Pass2Service.Pass2Result narrator(Pass2Service.Status status) {
        return new Pass2Service.Pass2Result(List.of(new NarrativeLine("narrator","渡者留下，交易尚未完成。","凭空的心理"),
                new NarrativeLine("a","叙事模型改写成无条件同意","杜撰动机")),Map.of("a",false),
                new LLMResponse("{}","{}",10,20,30,0.1),status,"test",null);
    }
    NpcAgentService.NpcDecision actor(String id,String speech,boolean fallback) {
        return new NpcAgentService.NpcDecision(id,"RESPOND",speech,"宁可等价交换，不愿白送。",true,fallback);
    }
    @Test void exactActorWordsReplaceNarratorImpersonationAndPreserveFlagsAndUsage() {
        var original=narrator(Pass2Service.Status.SUCCESS);
        var r=EnsembleNarrative.merge(original,List.of(actor("a","我不会白送通行印。",false)),List.of("a"));
        assertEquals(2,r.narrativeLines().size());assertEquals("",r.narrativeLines().get(0).innerThought());
        assertEquals("我不会白送通行印。",r.narrativeLines().get(1).speech());
        assertTrue(r.lieFlags().get("a"));assertSame(original.llmResponse(),r.llmResponse());
    }
    @Test void fallbackAndForeignActorsDoNotInjectDialogue() {
        var r=EnsembleNarrative.merge(narrator(Pass2Service.Status.FALLBACK),
                List.of(actor("foreign","泄露其他会话",false),actor("a","假的完成消息",true)),List.of("a"));
        assertEquals(1,r.narrativeLines().size());assertTrue(r.lieFlags().isEmpty());assertEquals(Pass2Service.Status.FALLBACK,r.status());
    }
    @Test void allPhasesKeepTheirActualOrderAndSilenceDoesNotEraseEarlierSpeech() {
        var r=EnsembleNarrative.merge(narrator(Pass2Service.Status.SUCCESS),
                List.of(actor("a","第一刻的答话",false),actor("a","最新的答话",false),actor("a","",false)),List.of("a"));
        assertEquals(3,r.narrativeLines().size());assertEquals("第一刻的答话",r.narrativeLines().get(1).speech());assertEquals("最新的答话",r.narrativeLines().get(2).speech());
    }
    @Test void narratorRequestUsesOneCallAndRejectsGeneratedNpcLines() {
        var model=mock(LLMProvider.class);
        when(model.chatForJson(anyList())).thenAnswer(call->{
            List<Map<String,String>> messages=call.getArgument(0);
            assertTrue(messages.get(1).get("content").contains("visible.lines只输出godId=narrator的一条正文"));
            String j="{\"lines\":[{\"godId\":\"narrator\",\"speech\":\"交换尚未完成。\"},{\"godId\":\"a\",\"speech\":\"我已经送你了\"}]}";
            return new LLMResponse(j,j,10,20,30,0.1);
        });
        var service=new Pass2Service(model,new LLMConfig(),new StorySummaryValidator());
        var r=service.generateEnsembleNarrativeWithMemory("NO_OP","交涉","提议交换",List.of("a"),List.of("尚未转移物品"),"渡口","");
        assertEquals(1,r.narrativeLines().size());assertEquals("narrator",r.narrativeLines().get(0).godId());
        verify(model,times(1)).chatForJson(anyList());
    }

    @Test void actorQuotesAreWovenBetweenActionsAndNotTakenFromPrivateThoughts(){
        String body=EnsembleNarrative.weave("道人上前问：[[QUOTE:0]]\n\n猎户指向村口：[[QUOTE:1]]\n\n道人转头看向火光。",List.of(actor("a","此物从何而来？",false),actor("b","村中着火，家人还在那里。",false)),List.of("a","b"),Map.of("a","道人","b","猎户"));
        assertTrue(body.indexOf("此物从何而来")<body.indexOf("猎户指向"));assertTrue(body.indexOf("村中着火")<body.indexOf("道人转头"));assertFalse(body.contains("QUOTE:"));assertFalse(body.contains("宁可等价交换"));
    }
    @Test void missingModelQuotesStillAppearInMainStory(){
        String body=EnsembleNarrative.weave("道人问完便停下。",List.of(actor("a","此物从何而来？",false)),List.of("a"),Map.of("a","道人"));assertTrue(body.contains("道人说：“此物从何而来？”"));
    }
    @Test void reversedQuoteReferencesCannotPlaceAnAnswerBeforeItsQuestion(){
        String body=EnsembleNarrative.weave("[[QUOTE:1]][[QUOTE:0]]",List.of(actor("a","你为何在这里？",false),actor("b","我在等家人。",false)),List.of("a","b"),Map.of("a","道人","b","猎户"));assertTrue(body.indexOf("你为何")<body.indexOf("我在等"));assertFalse(body.contains("QUOTE:"));
    }

    @Test void arrivalWithoutAnExistingMentionCannotSplitTheOpeningSentence(){
        String body=EnsembleNarrative.introduceArrival("你仍伏在岸边。","黑衣旅人从山路来到此地。",List.of("黑衣旅人"));assertEquals("这段时间里，黑衣旅人从山路来到此地。\n\n你仍伏在岸边。",body);
    }
    @Test void arrivalIsBeforeFirstMentionAndDoesNotRepeatIfAlreadyWritten(){
        String arrival="黑衣旅人从山路来到此地。";String body=EnsembleNarrative.introduceArrival("木匠继续检查。\n\n黑衣旅人走近闸门。",arrival,List.of("黑衣旅人"));assertTrue(body.indexOf("木匠")<body.indexOf(arrival));assertTrue(body.indexOf(arrival)<body.indexOf("黑衣旅人走近"));assertEquals(body,EnsembleNarrative.introduceArrival(body,arrival,List.of("黑衣旅人")));
    }

    @Test void simultaneousActorsCanBeOrganizedAroundThePlayersQuestionWithoutEmptyColons(){
        String body=EnsembleNarrative.weave("看守回应。[[QUOTE:1]]\n\n木匠接着说明。[[QUOTE:0]]",List.of(actor("a","木槽已经清理。",false),actor("b","我会守住绞盘。",false)),List.of("a","b"),Map.of("a","木匠","b","看守"),List.of(1,1),List.of());assertTrue(body.contains("看守说：“我会守住绞盘。”"));assertTrue(body.contains("木匠说：“木槽已经清理。”"));assertFalse(body.contains("QUOTE:"));
    }
    @Test void crossingTimeReferencesUseActualEventsAndSpeechInsteadOfBrokenProse(){
        String body=EnsembleNarrative.weave("他应道：[[QUOTE:1]]随后问：[[QUOTE:0]]",List.of(actor("a","你为何在这里？",false),actor("b","我在等家人。",false)),List.of("a","b"),Map.of("a","道人","b","猎户"),List.of(1,2),List.of(Map.of("phase",2,"kind","SCENE_ACTION","text","猎户把身后的孩子护到石壁旁。")));assertTrue(body.indexOf("你为何")<body.indexOf("我在等"));assertTrue(body.contains("护到石壁旁"));assertFalse(body.contains("他应道：随后问："));
    }

    @Test void anExistingSpeechIntroductionDoesNotGetADuplicateSpeakerPrefix(){
        String body=EnsembleNarrative.weave("岑衡向阮青说：[[QUOTE:0]]",List.of(actor("a","先稳住绞盘。",false)),List.of("a"),Map.of("a","木匠岑衡"));assertEquals("岑衡向阮青说：“先稳住绞盘。”",body);
    }

    @Test void omittedEarlierQuestionCannotBeAppendedAfterReferencedAnswer(){
        String body=EnsembleNarrative.weave("猎户答道：[[QUOTE:1]]",List.of(actor("a","你为何在这里？",false),actor("b","我在等家人。",false)),List.of("a","b"),Map.of("a","道人","b","猎户"),List.of(1,2),List.of());
        assertTrue(body.indexOf("你为何")<body.indexOf("我在等"));assertFalse(body.contains("QUOTE:"));
    }


 @Test void brokenNarratorCannotEraseTheExecutedPlayerAction(){
  String body=EnsembleNarrative.weave("[[QUOTE:99]]",List.of(),List.of(),Map.of(),List.of(),List.of(Map.of("phase",1,"kind","ACTION","text","你完成了观察，确认绳结已解开。")));
  assertTrue(body.contains("确认绳结已解开"));assertFalse(body.contains("QUOTE:"));
 }


 @Test void naturalSpeechLeadInDoesNotProduceHeSaidActorSaid(){
  String body=EnsembleNarrative.weave("木匠先说了句[[QUOTE:0]]",List.of(actor("a","先探一探。",false)),List.of("a"),Map.of("a","木匠岑衡"));
  assertEquals("木匠先说了句“先探一探。”",body);
 }
 @Test void anOldOrCosmeticBlockerCannotInterruptWithoutNewProgress(){
  assertFalse(OpenSceneService.concreteBlocker(List.of("木匠仍面对卡死的门。"),false,"可以靠近"));
  assertFalse(OpenSceneService.concreteBlocker(List.of("木匠只是抬手，场面未变。"),true,"可以靠近"));
 }

 @org.junit.jupiter.api.Test void executedTerminalBlockerIsDifferentFromPossibleOrUnchangedObstacle(){
  assertTrue(OpenSceneService.concreteBlocker(List.of("门扇被撬起半寸，未能继续抬起。"),false,""));
  assertFalse(OpenSceneService.concreteBlocker(List.of("他准备抬起门扇，可能无法继续。"),false,""));
  assertFalse(OpenSceneService.concreteBlocker(List.of("木匠仍面对卡死的门。"),false,""));
 }
}
