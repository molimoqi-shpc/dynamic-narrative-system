package com.bs.narrative.service;
import com.bs.narrative.domain.GodProfile;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Versioned public seed templates, independent of mutable game sessions. */
@Component
public class NpcTemplateCatalog {
    private final ObjectMapper mapper=new ObjectMapper();
    private final List<JsonNode> profiles=new ArrayList<>(),relationships=new ArrayList<>();
    public NpcTemplateCatalog() {
        try(var stream=NpcTemplateCatalog.class.getResourceAsStream("/npc-templates.json")) {
            if(stream==null) throw new IllegalStateException("NPC模板缺失");
            JsonNode root=mapper.readTree(stream);
            root.path("profiles").forEach(profiles::add);
            root.path("relationships").forEach(relationships::add);
        } catch(java.io.IOException e) {throw new IllegalStateException("NPC模板不可读",e);}
    }
    public GodProfile find(String alias) {
        for(JsonNode profile:profiles)
            if(alias.equals(profile.path("godId").asText()) || alias.equals(profile.path("godName").asText())) {
                try {return mapper.treeToValue(profile,GodProfile.class);}
                catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException(e);}
            }
        return null;
    }
    public Optional<JsonNode> relationship(Object source,Object target) {
        return relationships.stream().filter(r -> r.path("source").asText().equals(source)
                && r.path("target").asText().equals(target)).findFirst();
    }
}
