package com.bs.narrative.service;

import com.bs.narrative.llm.ModelOutput;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Move a complete, misplaced protocol footer into its documented field. */
final class StoryReplyEnvelope {
    private StoryReplyEnvelope() {}
    static ObjectNode normalize(ObjectNode original) {
        String story=original.path("story").asText();
        String marker="\n\ndecision:";
        int split=story.lastIndexOf(marker);
        if(split<0 || !"DECISION".equals(original.path("stop").asText()))return original;
        try {
            var decision=ModelOutput.object(story.substring(split+marker.length()).strip());
            if(!decision.path("question").isTextual() || decision.path("question").asText().isBlank() || !decision.path("type").isTextual())return original;
            if(original.hasNonNull("decision") && !original.path("decision").equals(decision))return original;
            var out=original.deepCopy();out.set("decision",decision.deepCopy());out.put("story",story.substring(0,split).stripTrailing());
            return out;
        }catch(IllegalArgumentException invalid){return original;}
    }
}
