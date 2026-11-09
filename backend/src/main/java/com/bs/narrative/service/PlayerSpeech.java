package com.bs.narrative.service;
import com.bs.narrative.llm.*;
import java.util.regex.Pattern;
/** Pass only explicitly uttered substrings; private planning is never the dialogue payload. */
public final class PlayerSpeech {
    private PlayerSpeech(){}
    public static String extract(LLMResponse response,String raw){
        if(response==null || raw==null)return "";
        try{
            var n=ModelOutput.object(response.parsedJson()!=null?response.parsedJson():response.content());
            String text=ModelOutput.text(n,"spokenText","",600).strip();
            if(text.isBlank() || text.length()>300 || !raw.contains(text))return "";
            var quoted=Pattern.compile("(?:说|问|询问|告诉|喊|道|承诺|保证|答应)[^，。；]{0,12}[“\"]([^”\"]+)[”\"]").matcher(raw);
            while(quoted.find())if(quoted.group(1).equals(text)) {
                int start=Math.max(raw.lastIndexOf('，',quoted.start()),Math.max(raw.lastIndexOf('。',quoted.start()),raw.lastIndexOf('；',quoted.start())))+1;
                String before=raw.substring(start,quoted.start());
                if(!before.matches("(?s).*(心想|暗自|内心|心里|未说出口).*"))return text;
            }
            String prefix=raw.substring(0,raw.indexOf(text));
            if((prefix+text).matches("(?s).*(心想|心里|暗自|内心|未说出口|伺机).*"))return "";
            if(!raw.matches("(?s).*(说|问|请教|询问|告知|告诉|喊|交谈|承诺|保证|答应).*"))return "";
            return text;
        }catch(IllegalArgumentException e){return "";}
    }
}
