package com.bs.narrative.llm;

/**
 * V2 动态场景生成 Prompt 模板。
 *
 * 【优化指导 6.2】从 (x,y) 坐标偏移改为方向枚举（拓扑图边）：
 *   - 玩家说"我往北走" → LLM 输出 direction="NORTH"
 *   - 系统查 scene_connection 是否有 NORTH 方向的边 → 有则跳转，无则生成新场景+建立双向边
 *   - 论文表述："基于动态图遍历的场景探索机制"
 *
 * 玩家没去过的地方，玩家不知道地名，只有 LLM 生成后才知道。
 */
public final class SceneGenerationPromptTemplate {

    private SceneGenerationPromptTemplate() {}

    /** System Prompt：场景生成（拓扑图版）。 */
    public static String systemPrompt() {
        return """
                你是当前存档固定世界的场景主创，负责根据玩家的移动意图生成新的场景信息。

                【输出 JSON Schema】（只允许输出 JSON，禁止任何解释、Markdown 围栏、前言后缀）
                {
                  "direction": "NORTH|SOUTH|EAST|WEST|UP|DOWN|OTHER",
                  "location_name": "新地点名（原创，符合本世界时代文化的风格）",
                  "scene_description": "场景描述（符合本世界的环境描写，不少于50字）",
                  "present_npcs": [],
                  "exit_name": "出口名称（例如'北去彼岸'，OTHER 时填原始描述）"
                }

                【direction 枚举说明】
                - NORTH：北、上方、向前走（默认）
                - SOUTH：南、回头、向后
                - EAST：东、右
                - WEST：西、左
                - UP：上楼、登顶、飞升
                - DOWN：下楼、入地、下沉
                - OTHER：非标准方向（如"穿过芦苇荡"、"沿河走"），exit_name 存原始描述

                【规则】
                - 地点名必须原创，不能与已记录的地点重复（已记录地点见下方世界书上下文）
                - 如果玩家描述没有明确方向，根据语境合理推断（默认 NORTH 向前走）
                - 场景描述为符合本世界时代文化的风格，体现地点特色
                - present_npcs列出scene_description中实际在场、可互动的人物名字；人迹、传闻、远方声音不算在场。最多8个，不复制已知人物到两个位置。
                - 可额外给scene_cast:[{"name":"在场名字","basis":"scene_description明确其当前在场的原文句子"}]。如果描述有人，列表必须同步落地，不能只写人而返回空数组。
                - 空旷或独处场景可为空；不靠每次强制出场人物制造热闹。人物数量只由当前剧情与真实在场情况决定，不随立绘库规模增加，不必每一种立绘都安排一个人；同类型人物可共用形象，之后需要时再自然引入新人。
                - exit_name 承接本世界时代文化，符合方向

                【严格输出】只输出 JSON 本体，不输出任何其他内容。
                """;
    }

    /** User Prompt：拼接玩家移动意图 + 当前位置 + 世界书上下文。 */
    public static String userPrompt(String playerText,
                                     String currentLocationName, String worldBookContext) {
        return """
                【当前状态】
                当前位置：%s

                【世界书记录】（已访问地点，不可重复取名）
                %s

                【玩家移动意图】
                %s

                请根据玩家意图生成新场景信息。按 System Prompt 约定的 JSON Schema 输出。
                """.formatted(
                currentLocationName == null ? "未知地点" : currentLocationName,
                worldBookContext == null ? "（暂无）" : worldBookContext,
                playerText == null ? "向前走" : playerText);
    }
}
