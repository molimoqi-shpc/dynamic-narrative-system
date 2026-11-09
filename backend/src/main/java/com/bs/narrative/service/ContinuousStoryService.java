package com.bs.narrative.service;
import com.bs.narrative.llm.LLMProvider;
import org.springframework.stereotype.Service;
import java.util.*;
/** Render committed player-visible API prose. Presentation cannot author a second world. */
@Service
public class ContinuousStoryService {
    // Kept as constructor dependency for source compatibility. Rendering performs no model call.
    public ContinuousStoryService(LLMProvider model) {}
    public record Story(String text,boolean polished) {}
    public Story write(String location,List<Map<String,Object>> publicEvents){
        var paragraphs=new ArrayList<String>();
        for(var event:publicEvents){
            Object text=event.get("text");
            if(!(text instanceof String value))throw new IllegalArgumentException("committed event has no display text");
            if(!value.isBlank())paragraphs.add(value);
        }
        // Input order is the already committed causal order, including same-tick speech.
        return new Story(String.join("\n\n",paragraphs),false);
    }
}
