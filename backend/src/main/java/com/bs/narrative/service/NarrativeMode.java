package com.bs.narrative.service;

/** Authoring preference only; it never gates publication or changes story outcomes. */
public final class NarrativeMode {
    public static final String NORMAL="NORMAL",LONG="LONG";
    private NarrativeMode() {}
    public static String normalize(String value){return LONG.equalsIgnoreCase(value)?LONG:NORMAL;}
    public static String guidance(String mode){
        String length=LONG.equals(normalize(mode))
                ?"【长篇沉浸】整次完整推进的正文通常1500至3500字；重大、丰富的情节可以更长。充分展开关键场景、人物交锋和情感变化，仍把普通手续概述带过，不为长而长。"
                :"【正常篇幅】按事情轻重有详有略。整次完整推进的正文至少500字，普通事件通常500至1200字；情节丰富、有多次实质变化时可以写1500至3000字乃至更多。不要每轮都写几千字，也不要把轻微动作拆成空泛短段。";
        return length+"500字下限针对同一次完整推进的合并正文，不是每个分场都要500字。多场景时按重要程度分配篇幅，已写场景不重复；末场合并前段后达到下限即可。写完本次方向的实际变化与阶段结果，重大决策处自然停。输出前在同次写作内确认篇幅；不足时补足真实回应、行动经过及后果，不加新任务、不重复环境或总结凑字。";
    }
}
