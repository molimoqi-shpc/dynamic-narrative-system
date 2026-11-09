package com.bs.narrative.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Validate model data before any database writes. */
public final class ModelOutput {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ModelOutput() {}
    public static JsonNode object(String json) {
        try {
            JsonNode root;
            try { root=read(json); }
            catch(com.fasterxml.jackson.core.JsonProcessingException original){
                String compatible=structuralPunctuation(quotedProse(json));
                try{root=MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_TRAILING_COMMA).readTree(compatible);}
                catch(com.fasterxml.jackson.core.JsonProcessingException malformed){
                    String continued=objectContinuation(compatible);
                    if(continued==null)throw malformed;
                    root=MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(continued);
                }
            }
            if (root == null || !root.isObject()) throw new IllegalArgumentException("模型输出必须是对象");
            return root;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("模型输出不是合法 JSON", e);
        }
    }
    private static JsonNode read(String raw)throws com.fasterxml.jackson.core.JsonProcessingException{
        return raw==null?null:MAPPER.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
    }
    /** Escape unquoted CJK quotations inside strings. This changes transport
     * escaping only; decoded words, including their quotation marks, stay intact. */
    private static String quotedProse(String raw){
        if(raw==null)return null;
        var out=new StringBuilder();boolean quoted=false,escaped=false,embedded=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(quoted){
                if(escaped)escaped=false;
                else if(c=='\\')escaped=true;
                else if(c=='"'){
                    int next=i+1;while(next<raw.length() && Character.isWhitespace(raw.charAt(next)))next++;
                    boolean internal=next<raw.length() && Character.UnicodeScript.of(raw.charAt(next))==Character.UnicodeScript.HAN;
                    if(!internal && embedded && next<raw.length() && raw.charAt(next)=='"'){
                        int after=next+1;while(after<raw.length() && Character.isWhitespace(raw.charAt(after)))after++;
                        internal=after==raw.length() || ",]}".indexOf(raw.charAt(after))>=0;
                    }
                    if(internal){out.append('\\');embedded=true;}else quoted=false;
                }
            }else if(c=='"'){quoted=true;embedded=false;}
            out.append(c);
        }
        return out.toString();
    }
    /** One complete object followed by comma-key fields is a prematurely closed
     * root, not a second document. Join only this unambiguous transport shape;
     * the complete result still rejects duplicates and trailing documents. */
    private static String objectContinuation(String raw){
        if(raw==null)return null;
        try(var parser=MAPPER.createParser(raw)){
            JsonNode first=MAPPER.readTree(parser);long offset=parser.currentLocation().getCharOffset();
            if(first==null || !first.isObject() || offset<1 || offset>raw.length() || raw.charAt((int)offset-1)!='}')return null;
            String tail=raw.substring((int)offset).stripLeading();
            if(!tail.startsWith(",") || !tail.substring(1).stripLeading().startsWith("\"") || !tail.stripTrailing().endsWith("}"))return null;
            return raw.substring(0,(int)offset-1)+raw.substring((int)offset);
        }catch(java.io.IOException invalid){return null;}
    }
    /** Accept common full-width separators only outside strings; never alter prose or complete partial JSON. */
    private static String structuralPunctuation(String raw){
        if(raw==null)return null;
        var out=new StringBuilder(raw.length());boolean quoted=false,escaped=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(quoted){
                if(escaped)escaped=false;
                else if(c=='\\')escaped=true;
                else if(c=='"')quoted=false;
            }else if(c=='"')quoted=true;
            else if(c=='，')c=',';
            else if(c=='：')c=':';
            out.append(c);
        }
        return out.toString();
    }
    public static String text(JsonNode root, String key, String fallback, int max) {
        JsonNode value = root.path(key);
        String text = value.isTextual() ? value.asText().trim() : fallback;
        if (text.isBlank()) text = fallback;
        return cap(text, max);
    }
    public static String cap(String text, int max) {
        if (text.length() <= max) return text;
        int end = Character.isHighSurrogate(text.charAt(max-1)) ? max-1 : max;
        return text.substring(0,end);
    }
    public static List<String> names(JsonNode array, int maxCount, int maxLength) {
        if (!array.isArray()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode node : array) {
            if (!node.isTextual() || node.asText().isBlank()) continue;
            names.add(cap(node.asText().trim(),maxLength));
            if (names.size() >= maxCount) break;
        }
        return new ArrayList<>(names);
    }
}
