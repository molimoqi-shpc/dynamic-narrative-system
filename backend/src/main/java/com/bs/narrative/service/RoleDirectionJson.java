package com.bs.narrative.service;

import com.bs.narrative.llm.ModelOutput;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;

/** Preserve a role's complete reply when a finished object array omits its ']'.
 * Only a following object-field key makes this specific syntax unambiguous.
 * No strings, field values, intentions or facts are rewritten or discarded. */
final class RoleDirectionJson {
    private RoleDirectionJson() {}
    static JsonNode parse(String raw) {
        try {return ModelOutput.object(raw);}
        catch(IllegalArgumentException original) {
            if(raw==null)throw original;
            String candidate=trimSurplusRootClosers(quotedProse(raw));
            if(!candidate.equals(raw))try{return ModelOutput.object(candidate);}catch(IllegalArgumentException invalid){/* Continue only with delimiter repairs. */}
            // A complete nested value with only the outer object left open is
            // a missing delimiter, not permission to invent unfinished content.
            if(onlyRootUnclosed(candidate))try{return ModelOutput.object(candidate+"}");}catch(IllegalArgumentException invalid){throw original;}
            for(int attempt=0;attempt<2;attempt++) {
                int boundary=missingArrayEnd(candidate);
                if(boundary<0)throw original;
                candidate=candidate.substring(0,boundary)+"]"+candidate.substring(boundary);
                try {return ModelOutput.object(candidate);}
                catch(IllegalArgumentException stillInvalid) { /* Do not accept until the entire object parses. */ }
            }
            throw original;
        }
    }
    /** A complete root followed only by extra '}' has no missing or competing data.
     * Retain every field in the complete root; a second value is never discarded. */
    private static String trimSurplusRootClosers(String raw){
        var stack=new ArrayDeque<Character>();boolean quoted=false,escaped=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"'){quoted=true;continue;}
            if(c=='{' || c=='[')stack.push(c);
            else if(c=='}' || c==']'){
                if(stack.isEmpty() || stack.pop()!=(c=='}'?'{':'['))return raw;
                if(stack.isEmpty()){
                    String tail=raw.substring(i+1).strip();
                    if(!tail.isEmpty() && tail.chars().allMatch(x->x=='}' || Character.isWhitespace(x)))return raw.substring(0,i+1);
                    return raw;
                }
            }
        }
        return raw;
    }
    /** Escape a bare quote inside a complete prose value when the next character
     * cannot close that JSON string. Decoded words are preserved verbatim. */
    private static String quotedProse(String raw){
        var out=new StringBuilder();boolean quoted=false,escaped=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(quoted){
                if(escaped)escaped=false;
                else if(c=='\\')escaped=true;
                else if(c=='"'){
                    int next=i+1;while(next<raw.length() && Character.isWhitespace(raw.charAt(next)))next++;
                    if(next<raw.length() && Character.UnicodeScript.of(raw.charAt(next))==Character.UnicodeScript.HAN)out.append('\\');else quoted=false;
                }
            }else if(c=='"')quoted=true;
            out.append(c);
        }
        return out.toString();
    }
    private static boolean onlyRootUnclosed(String raw){
        var stack=new ArrayDeque<Character>();boolean quoted=false,escaped=false;int last=-1;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(Character.isWhitespace(c))continue;
            if(stack.isEmpty() && last>=0)return false;
            if(c=='"')quoted=true;
            else if(c=='{' || c=='[')stack.push(c);
            else if(c=='}' || c==']'){if(stack.isEmpty() || stack.pop()!=(c=='}'?'{':'['))return false;}
            last=i;
        }
        return !quoted && stack.size()==1 && stack.peek()=='{' && last>=0 && (raw.charAt(last)=='}' || raw.charAt(last)==']');
    }
    private static int missingArrayEnd(String raw) {
        var containers=new ArrayDeque<Character>();
        boolean quoted=false,escaped=false;
        int previous=-1;
        for(int i=0;i<raw.length();i++) {
            char c=raw.charAt(i);
            if(quoted) {
                if(escaped){escaped=false;continue;}
                if(c=='\\'){escaped=true;continue;}
                if(c=='"') {
                    quoted=false;
                    int next=i+1;while(next<raw.length() && Character.isWhitespace(raw.charAt(next)))next++;
                    if(next<raw.length() && raw.charAt(next)==':' && !containers.isEmpty() && containers.peek()=='[' && previous>=0 && raw.charAt(previous)==',') {
                        int end=previous-1;while(end>=0 && Character.isWhitespace(raw.charAt(end)))end--;
                        if(end>=0 && raw.charAt(end)=='}')return previous;
                    }
                    previous=i;
                }
                continue;
            }
            if(Character.isWhitespace(c))continue;
            if(c=='"'){quoted=true;continue;}
            if(c=='{' || c=='[')containers.push(c);
            else if(c=='}' || c==']') {
                if(containers.isEmpty() || containers.pop()!=(c=='}'?'{':'['))return -1;
            }
            previous=i;
        }
        return -1;
    }
}
