package com.bs.narrative.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;
import java.util.Map;

/** Teaching material belongs to the isolated walkthrough, never to a saved game. */
public record EngineDemoResponse(@JsonUnwrapped ActionResponse result, Story story, Map<String,Object> analysis) {
    public record Story(String title, String premise, List<CastMember> cast,
                        List<Beat> beats, String ending) {}

    public record CastMember(String id, String name, String identity,
                             String publicGoal, String hiddenMotive) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Beat(String id, String stage, String speakerId, String speakerName,
                       String action, String speech, String innerThought,
                       String explanation, String lieReason) {}
}
