package com.bs.narrative.llm;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.util.*;
class DeepSeekEmptyResponseTest {
    LLMConfig cfg; MockRestServiceServer server; DeepSeekLLMProvider provider;
    @BeforeEach void setup(){
        cfg=new LLMConfig();cfg.setMaxRetries(1);cfg.getMain().setApiKey("test-key");cfg.getMain().setModel("test-model");
        var builder=RestClient.builder().baseUrl("https://example.test");server=MockRestServiceServer.bindTo(builder).build();provider=new DeepSeekLLMProvider(cfg,builder.build());LLMUsage.begin();
    }
    String body(String content){return "{\"choices\":[{\"message\":{\"content\":"+content+"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";}
    void expect(String content){server.expect(requestTo("https://example.test/chat/completions")).andRespond(withSuccess(body(content),MediaType.APPLICATION_JSON));}
    @Test void emptyJsonRetriesWithinLimitAndRecordsBothBillableCalls(){
        expect("\"\"");expect("\"{\\\"intent\\\":\\\"OBSERVE\\\"}\"");
        var result=provider.chatForJson(List.of(Map.of("role","user","content","输出JSON")));
        assertTrue(result.content().contains("OBSERVE"));assertEquals(2,LLMUsage.totals().calls());assertEquals(20,LLMUsage.totals().input());server.verify();
    }
    @Test void repeatedEmptyJsonFailsBoundedlyWithoutInventingOutput(){
        expect("\"\"");expect("null");assertThrows(LLMApiException.class,()->provider.chatForJson(List.of()));assertEquals(2,LLMUsage.totals().calls());server.verify();
    }
    @Test void plainTextModeRetainsExistingBehaviorWithoutJsonRetries(){expect("\"\"");assertEquals("",provider.chat(List.of()).content());assertEquals(1,LLMUsage.totals().calls());server.verify();}
    @Test void unparseableStoryReplyIsReturnedVerbatimWithoutPaidAutomaticRetry(){expect("\"{story format broken\"");var result=LLMRequestPolicy.STORY_AUTHOR.call(()->provider.chatForJson(List.of()));assertEquals("{story format broken",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();}
    @Test void emptyStoryReplyAlsoReturnsOnceForOperationLevelRecovery(){expect("\"\"");var result=LLMRequestPolicy.STORY_AUTHOR.call(()->provider.chatForJson(List.of()));assertEquals("",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();}
    @Test void malformedFinalPublicationIsReturnedVerbatimWithoutAutomaticPaidRetry(){
        expect("\"{broken publication with a paid story\"");
        var result=LLMRequestPolicy.STORY_ARCHIVE.call(()->provider.chatForJson(List.of()));
        assertEquals("{broken publication with a paid story",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();
    }
    @Test void emptyFinalPublicationAlsoBelongsToDurableOperationRecovery(){
        expect("\"\"");var result=LLMRequestPolicy.STORY_ARCHIVE.call(()->provider.chatForJson(List.of()));
        assertEquals("",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();
    }
    @Test void malformedSourceTimeReplyIsReturnedForDurableRecoveryWithoutAutomaticRetry(){
        expect("\"{broken source time\"");var result=LLMRequestPolicy.STORY_TIME.call(()->provider.chatForJson(List.of()));
        assertEquals("{broken source time",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();
    }
    @Test void malformedManuscriptReplyIsReturnedForDurableRecoveryWithoutRedrawingTheAuthor(){
        expect("\"{broken final manuscript\"");var result=LLMRequestPolicy.STORY_PUBLICATION.call(()->provider.chatForJson(List.of()));
        assertEquals("{broken final manuscript",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();
    }
    @Test void malformedCharacterAnswerIsReturnedForDurableRecoveryWithoutAutomaticRedraw(){
        expect("\"{broken character template\"");
        var result=LLMRequestPolicy.CHARACTER.call(()->provider.chatForJson(List.of()));
        assertEquals("{broken character template",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();
    }
    @Test void malformedFocusedReviewIsKeptWithoutAnAutomaticChargedRedraw(){
        expect("\"{broken focused review\"");var result=LLMRequestPolicy.STORY_REVIEW.call(()->provider.chatForJson(List.of()));
        assertEquals("{broken focused review",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();
    }
    @Test void anEmptyCharacterAnswerStillUsesTheExistingBoundedRetry(){
        expect("\"\"");expect("\"{\\\"intention\\\":\\\"继续调查\\\"}\"");
        var result=LLMRequestPolicy.CHARACTER.call(()->provider.chatForJson(List.of()));
        assertNotNull(result.parsedJson());assertEquals(2,LLMUsage.totals().calls());server.verify();
    }
    @Test void anAlreadyPaidCreationCandidateIsReturnedForDurableRepairWithoutAutomaticRedraw(){
        expect("\"{broken world\"");var result=provider.chatMainForJson(ModelProtocol.messages(ModelProtocol.Stage.WORLD,List.of()));
        assertEquals("{broken world",result.content());assertNull(result.parsedJson());assertEquals(1,LLMUsage.totals().calls());server.verify();
    }
}
