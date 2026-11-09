package com.bs.narrative.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Stores the author's declared opening holdings; prose wording is not a resource gate. */
public final class GenesisInventory {
    private GenesisInventory() {}
    /** PLAYER and explicit author/player name fields designate the same actor.
     * This is identifier binding, never an inference from ownership prose. */
    public static JsonNode bindPlayer(JsonNode packet,Set<String> aliases){
        if(!packet.isArray())return packet;
        var json=new ObjectMapper();var out=json.createArrayNode();var own=json.createArrayNode();var names=new HashSet<String>();boolean found=false;
        for(var row:packet){String owner=row.path("owner").asText();
            if(!"PLAYER".equals(owner) && !aliases.contains(owner)){out.add(row.deepCopy());continue;}
            if(!row.path("items").isArray())return packet;
            found=true;for(var item:row.path("items"))if(names.add(item.path("name").asText()))own.add(item.deepCopy());
        }
        if(found)out.addObject().put("owner","PLAYER").set("items",own);
        return out;
    }
    public static Map<String,List<String>> parse(JsonNode packet,String scene,List<String> cast) {
        if(packet.isMissingNode())return Map.of(); // Older persisted creation fixtures.
        if(!packet.isArray() || packet.size()>9)throw new IllegalArgumentException("invalid genesis inventory");
        var allowed=new HashSet<>(cast);allowed.add("PLAYER");
        var result=new LinkedHashMap<String,List<String>>();
        for(var owner:packet){
            String id=owner.path("owner").asText();var entries=owner.path("items");
            if(!allowed.contains(id) || result.containsKey(id) || !entries.isArray() || entries.size()>16)
                throw new IllegalArgumentException("invalid genesis owner or items");
            var names=new ArrayList<String>();
            for(var item:entries){String name=item.path("name").asText().strip();
                if(name.isBlank() || name.length()>64)
                    throw new IllegalArgumentException("invalid genesis item name");
                names.add(name);
            }
            result.put(id,List.copyOf(names));
        }
        if(!result.keySet().equals(allowed))throw new IllegalArgumentException("genesis inventory must cover every actor");
        return Map.copyOf(result);
    }
}
