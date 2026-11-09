package com.bs.narrative.service;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class TemporalStoryFactsTest {
    Map<String,Object> row(long id,long tick,String scene,String state){return Map.of("knowledge_id",id,"learned_tick",tick,"scene_id",scene,"location_name",scene,"content",state);}
    @Test void factsKeepTheLatestStatePerObjectAndLocationWithoutSortingLargeJsonInSql(){
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString(),eq("s"),eq("player"))).thenReturn(List.of(
            row(3,5,"old","{\"objectName\":\"寄包\",\"state\":\"已验看后仍在老妇处\"}"),
            row(9,1,"old","{\"objectName\":\"寄包\",\"state\":\"未打开\"}"),
            row(10,6,"far","{\"objectName\":\"寄包\",\"state\":\"掌柜只是听过寄包说法\"}"),
            row(11,7,"far","not-json"),row(12,8,"far","null"),row(13,9,"far","{}")));
        var facts=new TemporalContextService(jdbc).establishedStoryFacts("s","player");assertEquals(2,facts.size());assertEquals("已验看后仍在老妇处",facts.get(0).get("fact"));assertEquals("掌柜只是听过寄包说法",facts.get(1).get("fact"));
        verify(jdbc).queryForList(argThat(sql->!sql.contains("ORDER BY")),eq("s"),eq("player"));
    }
}
