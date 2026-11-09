package com.bs.narrative.llm;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ModelOutputTest {
    @Test void nonObjectResponsesAreRejected() {
        for(String json:List.of("null","[]","42","bad")) assertThrows(IllegalArgumentException.class,()->ModelOutput.object(json));
        assertThrows(IllegalArgumentException.class,()->ModelOutput.object(null));
    }
    @Test void namesAreTextOnlyUniqueAndBounded() {
        var object=ModelOutput.object("{\"names\":[\"aa\",\"aa\",3,null,\" \",\"bbbb\",\"cc\"]}");
        assertEquals(List.of("aa","bbb"),ModelOutput.names(object.path("names"),2,3));
    }
    @Test void truncationDoesNotSplitSupplementaryUnicodeCharacters() {
        String symbol=new String(Character.toChars(0x1F600));
        assertEquals("a",ModelOutput.cap("a"+symbol+"b",2));
        assertEquals("a"+symbol,ModelOutput.cap("a"+symbol+"b",3));
    }
    @Test void aPrematureRootCloseCanRetainLaterFieldsWithoutChangingQuotedStory() {
        var result=ModelOutput.object("{\"story\":\"原文：}，并非结构。\",\"timeProgress\":{\"elapsedSeconds\":16}},\"roleIds\":[\"father\"],\"stop\":\"RESOLVED\"}");
        assertEquals("原文：}，并非结构。",result.path("story").asText());assertEquals(16,result.at("/timeProgress/elapsedSeconds").asInt());assertEquals("father",result.at("/roleIds/0").asText());
    }
    @Test void aRootContinuationMustNotJoinDocumentsOrOverwriteEarlierFields() {
        for(String raw:List.of("{\"a\":1} {\"b\":2}","{\"a\":1}, {\"b\":2}","{\"a\":1},\"a\":2}","{\"a\":1},\"b\":", "{\"a\":1},\"b\":2} {}"))
            assertThrows(IllegalArgumentException.class,()->ModelOutput.object(raw),raw);
    }
}
