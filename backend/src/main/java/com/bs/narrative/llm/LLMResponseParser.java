package com.bs.narrative.llm;

import java.util.regex.Pattern;

/**
 * 防坑规则2：LLM 输出解析兜底。
 * 三级降级链：
 *   1) 直接 ObjectMapper readTree 校验 → 通过返回纯 JSON
 *   2) 剥 ```json ... ``` 围栏后 readTree 校验
 *   3) 正则提取第一个 {...} 子串再校验
 *   全部失败返回 null，上层走 FallbackProvider。
 */
public final class LLMResponseParser {

    private static final Pattern JSON_FENCE =
            Pattern.compile("(?is)```(?:json)?\\s*(.*?)\\s*```");

    private LLMResponseParser() {}

    /** 返回清洗后的纯 JSON 字符串；无法解析返回 null。 */
    public static String extractJsonOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;

        // 1) 直接校验
        String ok = validateOrNull(raw);
        if (ok != null) return ok;

        // 2) 剥 markdown 围栏
        var fence = JSON_FENCE.matcher(raw);
        if (fence.find()) {
            String inner = fence.group(1);
            int start=fence.start(),end=fence.end();
            if(fence.find() || raw.substring(0,start).indexOf('{')>=0 || raw.substring(end).indexOf('{')>=0)return null;
            return validateOrNull(inner);
        }

        // 3) 正则提取首个 {...}
        String text=raw.strip();if(text.startsWith("{") || text.startsWith("["))return null;
        int from=raw.indexOf('{');if(from<0 || raw.substring(0,from).stripTrailing().endsWith("["))return null;
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=from;i<raw.length();i++){
            char c=raw.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"')quoted=true;else if(c=='{')depth++;else if(c=='}' && --depth==0){
                if(raw.substring(i+1).indexOf('{')>=0)return null;
                return validateOrNull(raw.substring(from,i+1));
            }
        }
        return null;
    }

    /** 直接 ObjectMapper 校验通过返回原串；失败返回 null。 */
    private static String validateOrNull(String s) {
        try {
            return ModelOutput.object(s).toString();
        } catch (Exception ignore) {}
        return null;
    }

    public static boolean isParsable(String raw) {
        return extractJsonOrNull(raw) != null;
    }
}
