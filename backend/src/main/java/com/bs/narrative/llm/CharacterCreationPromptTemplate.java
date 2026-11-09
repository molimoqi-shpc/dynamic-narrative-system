package com.bs.narrative.llm;

/**
 * V2 角色创建 Prompt 模板。
 *
 * 两个 LLM 调用：
 *   1) generateTalents：三问题答案 → 性格标签 + 天命天赋
 *   2) generateBirthStory：性格标签 + 选中天赋 → 出生到第一次大事件的故事
 */
public final class CharacterCreationPromptTemplate {

    private CharacterCreationPromptTemplate() {}

    // ==================== 固定三问题 ====================

    /** 开局三个问题（固定，不调 LLM 生成）。 */
    public static String[] defaultQuestions() {
        return new String[]{
            "你叫什么名字，性别和年龄如何？来自怎样的家庭或经历？",
            "你心中最重之事为何？亲友、自由、真知，还是其他愿望？",
            "若逢必死之绝境，你当如何？搏命一击、隐忍图存、求援于友、还是遁走避祸？"
        };
    }

    // ==================== 天赋生成 Prompt ====================

    /** System Prompt：天赋生成。 */
    public static String talentSystemPrompt() {
        return """
                你是当前存档世界的角色主创，请你根据以下问题及答案生成性格标签与天命天赋，生成至少三个互不重复的候选天赋，玩家最终只选一个；候选数量与强弱无关。

                【输出 JSON Schema】（只允许输出 JSON，禁止任何解释、Markdown 围栏、前言后缀）
                {
                  "personality_tags": ["性格标签1", "性格标签2"],
                  "talents": [
                    {
                      "talent_name": "天赋名称",
                      "talent_description": "天赋效果描述",
                      "power_level": 3,
                      "stat_key": "",
                      "special_effect": {
                        "trigger": "在什么具体处境下能发挥，需哪些现实条件",
                        "benefit": "区别于加属性的一项适度特长，具体改变观察、方法、适应或交往的何种机会",
                        "limits": "这项作用不能做到什么，仍受哪些能力与环境限制",
                        "tradeoff": "自然的使用代价或干扰条件；没有则空，不为凑字段强造惩罚"
                      }
                    }
                  ]
                }

                【规则】
                - 性格标签2-4个，反映角色性格倾向（如"坚毅""多疑""仁厚""偏执"）
                - 生成至少三个互不重复的候选天赋，玩家最终只选一个；候选数量与强弱无关
                - power_level: 1=窄场景特长，2=较常用特长，3=较突出但有明显条件；任何档位都必须至少三个候选，不代表神级能力
                - 天赋名称与固定世界的时代、文化和能力边界相符，不默认神性或超自然力量
                - 每个候选都必须有special_effect，不能只是增加属性或把加点改写成好听的名字。三个候选要提供不同的行事方式，而不只是换一个属性。每项只保留一项核心特长，效果连同条件、边界、代价合计约80至150字，清楚好懂。
                - 可以从手艺、观察方法、记忆习惯、语言适应、协作、求生或本世界允许的微小特殊能力设计。作用必须与固定世界相容：现代普通人不因天赋获得魔法；允许超自然的世界也只给有限、具体的小能力，不默认神性或超凡身世。
                - 优势只改变有依据的机会或方法，不保证成功、命中、说服或胜利，不提供全知、读心、不死、无限资源、凭空治疗或每轮固定奖励；不能凭空知道未接触的秘密。限制应来自这项能力本身和使用环境，不设后台无法兑现的倒计时、每日次数、必触发奇遇或复杂被动数值系统。
                - stat_key通常为空，允许纯特殊效果。确有匹配理由时可附一项zhi/hui/dou/wen基础增益，由服务端按档位只给1至2点，不把它当作特殊效果的替代。选中的特殊作用会持续写入主创上下文，由主创结合当前处境描写实际发挥，不需要新增一次角色或审稿调用。
                - talent_description用一两句向玩家概述同一特殊效果，不新增special_effect之外的能力；不要把系统、API或格式实现细节写进天赋卡。
                - 天赋应与角色出身和性格呼应

                【严格输出】只输出 JSON 本体，不输出任何其他内容。
                """;
    }

    /** User Prompt：拼接三问题答案。 */
    public static String talentUserPrompt(String answer1, String answer2, String answer3) {
        return """
                【玩家三问回答】
                问题1：你生于何等人家？
                回答：%s

                问题2：你心中最重之事为何？
                回答：%s

                问题3：若逢必死之绝境，你当如何？
                回答：%s

                请根据以上回答，生成性格标签与天命天赋。按 System Prompt 约定的 JSON Schema 输出。
                """.formatted(
                answer1 == null ? "（未回答）" : answer1,
                answer2 == null ? "（未回答）" : answer2,
                answer3 == null ? "（未回答）" : answer3);
    }

    // ==================== 出生故事 Prompt ====================

    /** System Prompt：出生故事生成。 */
    public static String birthStorySystemPrompt() {
        return com.bs.narrative.service.PublicAppearanceSchema.PROMPT+"""
                你是当前存档世界的小说主创。请根据以下角色信息，编写从出生到第一次大事件的完整故事。

                【输出 JSON Schema】（只允许输出 JSON，禁止任何解释、Markdown 围栏、前言后缀）
                portraitStyle is optional presentation data: use CRIMSON_GOWN only when an adult or young adult human woman is publicly wearing a burgundy embroidered court gown. Otherwise omit it; never change the story or occupation to fit artwork.
                {
                  "birth_story": "从出生到第一次大事件的完整故事（与固定世界观一致的叙事，不少于500字）",
                  "player_name": "主角真实姓名，保留玩家原三问姓名，不能填你或玩家",
                  "initial_location": {
                    "location_name": "初始地点名（原创，不能使用常见地名）",
                    "coord_x": 0,
                    "coord_y": 0,
                    "scene_description": "初始场景描述（符合固定世界设定的环境描写）",
                    "present_npcs": [],
                    "scene_cast": [{"name":"当刻在场人物名","basis":"scene_description中该人物在场的原文句子","self_history":"仅这个NPC本人知道的身份、营生、实际过去及与主角的既有关联；不得把主角身世移给他，未知可空"}]
                  },
                  "story_time": "剧情开始时间（如'子时初刻'）",
                  "initial_inventory": [{"owner":"PLAYER或present_npcs中的原名","items":[{"name":"当前持有物品名称","basis":"依据开局正文说明当前实际持有人与来历，可概述"}]}],
                  "player_journal": {"characterAppearances":[{"name":"主角及在场人物实际名字","gender":"female/male/unknown","ageGroup":"CHILD/YOUNG/ADULT/ELDER/UNKNOWN","culturalStyle":"EASTERN/WESTERN/MODERN/SCIFI/MIXED","archetype":"WARRIOR/KNIGHT/SWORDSMAN/MAGE/NECROMANCER/ALCHEMIST/MECHANIC/PIRATE_CAPTAIN/VAMPIRE/NOBLE/CLERIC/DRAGON_RIDER/ELF/ROSE_ELF/TRAVELER/UNKNOWN","species":"human/elf/dwarf/dragonborn/vampire/other/unknown","portraitStyle":"可省略，仅公开酒红礼服形象用CRIMSON_GOWN","basis":"实际公开外貌与衣着依据"}],"items":[{"name":"初始确已获得的重要物品","kind":"ARTIFACT/EQUIPMENT/KEY_ITEM/CONSUMABLE/KEEPSAKE/OTHER","description":"玩家已知的外形与特征","knownEffects":"已显露的作用","limitations":"已知限制","significance":"已有的故事意义","origin":"实际获得来历"}],"relationships":[{"name":"玩家实际认识的在场人物名字","kind":"ALLY/FRIEND/ENEMY/RIVAL/FAMILY/ACQUAINTANCE/ESTRANGED","label":"具体关系","summary":"实际过去与当前关系","basis":"故事里玩家已知的关系依据"}]},
                  "first_event_description": "第一次大事件的一句话描述"
                }

                【规则】
                - 故事遵循已固定世界观，不少于500字；不默认主角或其他人物是神明
                - 故事从角色出生写起，到第一次大事件为止
                - 玩家三问原始回答是身世与能力的依据，必须保留其明确出身、姓名和限制。选中天赋卡面明确写出的有限特长可以自然体现，按发挥条件、边界和代价描写；纯情境天赋不凭空增加六维。旧版卡面仅写开局加点时仍只有这项增益，名称不赋予预知、通灵、读心等额外能力，不为展示天赋改变玩家身份。
                - player_name使用已确立主角姓名，没有给名字时可为其取一个合适姓名并自然写入出生故事。player_journal只整理玩家已知、确已持有的神器、装备、剧情信物等重要物品与实际人际关系；普通工具、麻绳、饮食、杂物不用填珍藏详情，但实际所持仍保留在initial_inventory供正文使用。重要性来自功能和故事，不依物品名字关键词判断，不凭空附加未显露力量或装备数值。父母、旧友、认识的同场人物要记录具体关系；初次见面不等于盟友，未公开的敌意不写成已知仇敌。
                - present_npcs与scene_cast只列NPC，不包含player_name所指主角；主角已有唯一编号PLAYER，不能把主角真名或简称再当同场NPC建立一份。两份NPC名字集合相同。initial_inventory的owner集合为PLAYER加这些NPC原名，每人一行，无物写items:[]。主角通过身世取得、当下仍持有的重要信物必须放在PLAYER行，不因scene_description没有再次描写它就丢失；玩家珍藏详情与同一物品表对应。环境设施和饮食杂物不逐件登记。basis据出生经历与当前现场概述，不以打算或别人的所有物建立资源，不为填表凭空添加装备。
                - 故事结尾给出群像正在发生的事件。玩家只是参与者；其他人物有独立处境、诉求和矛盾，不围绕玩家转。不要替玩家决定行动。
                - 初始地点名要原创，不能使用"丰渡水岸"等已有地名
                - 初始坐标默认(0,0)
                - 开局聚焦主角和眼前真正有关的人，通常只需零至两位可互动人物，确有剧情需要再增加；不要为了铺满人物列表或展示美术素材批量造人。立绘类型和数量不决定角色人数、种族、性别或职业，同类型人物可以共用立绘。不要一次介绍未来才会遇到的全部重要角色。
                - present_npcs列出结尾当前现场所有能互动的人物名字（最多8个），并给scene_cast在场原文依据。birth_story末尾与scene_description都要让读者读懂每个人如何在场：为每人至少写一句公众身份线索、所在位置或正在做的事，以及原本就在这里还是此刻到来。不能仅塞入名字或用群体遮掉已单列的人；同场角色不需要都刚好突然出现。
                - 人物初见介绍按玩家当下能观察到的内容写，不先揭穿隐藏身份、内心、阴谋或未获知的往事。可表现衣着、所持已有物、与人公开交谈或被谁称呼；关系需要实际公众依据。每个scene_cast.basis须是scene_description包含该人物名字及其在场行为的完整原句，不能仅写名字。
                - 没有人时两列表可为空，但结尾不能凭空写一群只存在于文字、无法行动的人。群体可用一个明确群体称呼代表，不能同时生成重复的同一群体。
                - 不预写后续结果；人物接下来做什么由角色API决定，不指定等玩家回应才能继续。
                - 为重要NPC在同一份scene_cast.self_history留下本人已有经历与已知关系，供其角色API接续；初始场景与birth_story末尾是同一个时点，不能正文尚在等待救助，场景却已跳过救助到别处，或把同一落水者分成两个人。
                - story_time用本世界适合的计时方式，并给出清楚的日内时段；不默认东方时辰

                【严格输出】只输出 JSON 本体，不输出任何其他内容。
                """;
    }

    /** User Prompt：拼接角色信息。 */
    public static String birthStoryUserPrompt(String personalityTags, String talentName,
                                               String talentDescription, String viewMode) {
        return """
                【角色信息】
                性格标签：%s
                天命天赋：%s（%s）
                视角模式：%s

                请根据以上角色信息，编写从出生到第一次大事件的完整故事。
                按 System Prompt 约定的 JSON Schema 输出。
                """.formatted(
                personalityTags == null ? "（未生成）" : personalityTags,
                talentName == null ? "（未选择）" : talentName,
                talentDescription == null ? "" : talentDescription,
                viewMode == null ? "DREAMER" : viewMode);
    }

}
