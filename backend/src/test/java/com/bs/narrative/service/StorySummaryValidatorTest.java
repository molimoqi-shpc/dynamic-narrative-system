package com.bs.narrative.service;

import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.llm.Pass2Service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StorySummaryValidator 单测（P1：LLM 输出 hidden_summary 的 schema 校验）。
 *
 * 覆盖：
 *   1) 合法 hidden_summary → 通过
 *   2) locationName 缺失 → PARAM_MISSING
 *   3) plotSummary 缺失 → PARAM_MISSING
 *   4) coordX 非数字 → PARAM_INVALID
 *   5) coordX 越界 → PARAM_RANGE_INVALID
 *   6) presentGods 非数组 → PARAM_INVALID
 *   7) 缺省字段（coord/storyTime/stateChanges）→ 合理默认值
 */
class StorySummaryValidatorTest {

    private StorySummaryValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        validator = new StorySummaryValidator();
    }

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void validSummary_passes() throws Exception {
        String json = """
                {
                  "locationName": "丰渡水岸",
                  "coordX": 0,
                  "coordY": 0,
                  "storyTime": "戌时三刻",
                  "plotSummary": "玩家挑衅雨神，博物叟背刺出手",
                  "presentGods": ["player_01", "rain_god"],
                  "keyEvents": ["玩家挑衅", "博物叟背刺"],
                  "stateChanges": "雨神dou+1, 博物叟wen+2"
                }
                """;
        Pass2Service.HiddenSummary hs = validator.validate(parse(json));
        assertEquals("丰渡水岸", hs.locationName());
        assertEquals(0, hs.coordX());
        assertEquals(0, hs.coordY());
        assertEquals("戌时三刻", hs.storyTime());
        assertEquals("玩家挑衅雨神，博物叟背刺出手", hs.plotSummary());
        assertEquals(List.of("player_01", "rain_god"), hs.presentGods());
        assertEquals(List.of("玩家挑衅", "博物叟背刺"), hs.keyEvents());
        assertEquals("雨神dou+1, 博物叟wen+2", hs.stateChanges());
    }

    @Test
    void missingLocationName_throwsParamMissing() throws Exception {
        String json = """
                {"coordX":0,"coordY":0,"plotSummary":"剧情"}
                """;
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(parse(json)));
        assertEquals(ErrorCode.PARAM_MISSING, e.getErrorCode());
    }

    @Test
    void missingPlotSummary_throwsParamMissing() throws Exception {
        String json = """
                {"locationName":"丰渡水岸","coordX":0,"coordY":0}
                """;
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(parse(json)));
        assertEquals(ErrorCode.PARAM_MISSING, e.getErrorCode());
    }

    @Test
    void coordXNotNumber_throwsParamInvalid() throws Exception {
        String json = """
                {"locationName":"丰渡水岸","coordX":"abc","coordY":0,"plotSummary":"剧情"}
                """;
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(parse(json)));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void coordXOutOfRange_throwsParamRangeInvalid() throws Exception {
        String json = """
                {"locationName":"丰渡水岸","coordX":99999,"coordY":0,"plotSummary":"剧情"}
                """;
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(parse(json)));
        assertEquals(ErrorCode.PARAM_RANGE_INVALID, e.getErrorCode());
    }

    @Test
    void presentGodsNotArray_throwsParamInvalid() throws Exception {
        String json = """
                {"locationName":"丰渡水岸","coordX":0,"coordY":0,"plotSummary":"剧情","presentGods":"player"}
                """;
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(parse(json)));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    @Test
    void missingOptionalFields_usesDefaults() throws Exception {
        // coord/storyTime/stateChanges 都缺
        String json = """
                {"locationName":"丰渡水岸","plotSummary":"剧情"}
                """;
        Pass2Service.HiddenSummary hs = validator.validate(parse(json));
        assertEquals(0, hs.coordX());
        assertEquals(0, hs.coordY());
        assertNull(hs.storyTime());
        assertTrue(hs.presentGods().isEmpty());
        assertTrue(hs.keyEvents().isEmpty());
        assertEquals("无", hs.stateChanges());
    }

    @Test
    void locationNameTooLong_throwsParamRangeInvalid() throws Exception {
        String longName = "a".repeat(65);
        String json = """
                {"locationName":"%s","coordX":0,"coordY":0,"plotSummary":"剧情"}
                """.formatted(longName);
        BusinessException e = assertThrows(BusinessException.class,
                () -> validator.validate(parse(json)));
        assertEquals(ErrorCode.PARAM_RANGE_INVALID, e.getErrorCode());
    }
}
