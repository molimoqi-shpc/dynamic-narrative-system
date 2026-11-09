package com.bs.narrative.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 防坑规则2 解析器三级降级链验证。 */
class LLMResponseParserTest {

    @Test void pureJson_passesLevel1() {
        String s = "{\"intent\":\"ATTACK\",\"target\":\"rain_god\"}";
        assertEquals(s, LLMResponseParser.extractJsonOrNull(s));
        assertTrue(LLMResponseParser.isParsable(s));
    }

    @Test void markdownFence_passesLevel2() {
        String raw = "好的，下面是结果：\n```json\n{\"intent\":\"UNKNOWN\"}\n```\n以上。";
        String got = LLMResponseParser.extractJsonOrNull(raw);
        assertNotNull(got);
        assertTrue(got.contains("UNKNOWN"));
    }

    @Test void bareFence_noLanguage_passesLevel2() {
        String raw = "```\n{\"k\":1}\n```";
        assertNotNull(LLMResponseParser.extractJsonOrNull(raw));
    }

    @Test void leadingNoise_extractFirstObj_level3() {
        String raw = "啊抱歉加了一些说明文字 {\"actor\":\"player\"} 后面还有尾巴 } 但是只要第一个完整JSON";
        String got = LLMResponseParser.extractJsonOrNull(raw);
        assertNotNull(got, "正则应提取首个 {...}");
        assertTrue(got.contains("player"));
    }

    @Test void halfBrace_returnsNull() {
        assertNull(LLMResponseParser.extractJsonOrNull("纯文字没括号"));
        assertNull(LLMResponseParser.extractJsonOrNull(""));
        assertNull(LLMResponseParser.extractJsonOrNull(null));
    }

    @Test void truncatedJson_returnsNull() {
        // LLM 偶尔吐半截括号，必须返回 null 触发 Fallback
        assertNull(LLMResponseParser.extractJsonOrNull("{\"intent\":\"ATTACK\",\"target\":\"rain_god\""));
    }

    @Test void arrayNotObject_returnsNull() {
        // 我们的 Pass1/Pass2 schema 要求对象，数组应被拒绝
        assertNull(LLMResponseParser.extractJsonOrNull("[1,2,3]"));
    }
    @Test void trailingDocumentsAndDuplicateFieldsCannotBeDiscardedByTheProviderReader(){
        for(String text:new String[]{"{\"a\":1}{\"b\":2}","说明 {\"a\":1} 然后 {\"b\":2}","{\"a\":1,\"a\":2}","```json\n{\"a\":1}\n```\n```json\n{\"b\":2}\n```","[{\"a\":1}]"})assertNull(LLMResponseParser.extractJsonOrNull(text),text);
    }
    @Test void transportRepairsAreIdenticalAtTheProviderAndPersistenceBoundary(){
        String raw="{\"story\":\"保留，正文：原样。\"，\"events\":[],}";
        var parsed=LLMResponseParser.extractJsonOrNull(raw);assertNotNull(parsed);assertEquals(ModelOutput.object(raw),ModelOutput.object(parsed));
    }
}
