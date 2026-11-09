package com.bs.narrative.engine;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.Pass1Intent;
import com.bs.narrative.engine.model.ValidationResult;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass1 输入净化：保证后续规则引擎的 id/键/物品 都合法、都在场。
 * 返回 ValidationResult，reasons 空则通过，否则列中文原因，从不抛异常。
 */
public final class IntentValidator {

    private IntentValidator() {}

    public static ValidationResult validate(String playerId,
                                            Pass1Intent p1,
                                            GameScene scene,
                                            Map<String, GodProfile> godsById) {
        ValidationResult.Builder b = ValidationResult.builder();
        if (p1 == null) { b.add("Pass1 结果为空"); return b.build(); }
        if (scene == null) { b.add("场景为空"); return b.build(); }
        Map<String, GodProfile> gs = (godsById == null) ? Map.of() : godsById;

        // ——— UNKNOWN（玩家废话 / 恶意注入）：跳过 actor/target/物品 校验，直接放行，
        //     由 ConflictResolver 走 NO_OP + 通用环境旁白，避免空指针或 INVALID 阻断流程。
        if (p1.intent() == IntentEnum.UNKNOWN) {
            return ValidationResult.ok();
        }

        boolean needsTarget=!Set.of(IntentEnum.OBSERVE,IntentEnum.EXAMINE,IntentEnum.WAIT,IntentEnum.NARRATE,IntentEnum.OTHER,
                IntentEnum.DEFEND,IntentEnum.QUESTION,IntentEnum.TALK).contains(p1.intent());
        // 1) actor/target 非空 & 不同
        if (p1.actorGodId().isBlank()) b.add("缺少行动方 actorGodId");
        if (needsTarget && p1.targetGodId().isBlank()) b.add("缺少目标方 targetGodId");
        if (!p1.actorGodId().isBlank() && p1.actorGodId().equals(p1.targetGodId())) {
            b.add("行动方与目标方不能为同一神明：" + p1.actorGodId());
        }

        // 2) actor ∈ {playerId} ∪ presentGods；target ∈ presentGods
        Set<String> present = new HashSet<>(scene.getPresentGods() == null ? List.of() : scene.getPresentGods());
        Set<String> allowActors = new HashSet<>(present);
        if (playerId != null) allowActors.add(playerId);
        if (!p1.actorGodId().isBlank() && !allowActors.contains(p1.actorGodId())) {
            b.add("行动方[" + p1.actorGodId() + "]不在场景或不是玩家");
        }
        if (!p1.targetGodId().isBlank() && !present.contains(p1.targetGodId())) {
            b.add("目标[" + p1.targetGodId() + "]不在当前场景在场神明列表");
        }

        // 3) 基础档案存在
        if (!p1.actorGodId().isBlank()  && !gs.containsKey(p1.actorGodId()))  b.add("找不到行动方基础档案：" + p1.actorGodId());
        if (!p1.targetGodId().isBlank() && !gs.containsKey(p1.targetGodId())) b.add("找不到目标方基础档案：" + p1.targetGodId());

        // 4) attackStatKey 必须在 intent.preferredAttackStatKeys 白名单中（null 则由上层自动补齐，不判错）
        IntentEnum intent = p1.intent() == null ? IntentEnum.OTHER : p1.intent();
        StatKey k = p1.attackStatKey();
        if (k != null && !intent.supportsKey(k.name())) {
            b.add("意图[" + intent.zhLabel + "]不支持对抗属性[" + k.zhLabel + "]，推荐：" + intent.preferredAttackStatKeys);
        }

        // 5) confidence ∈ [0,1]
        if (p1.confidence() < 0 || p1.confidence() > 1) {
            b.add("confidence 必须在[0,1]，实际：" + p1.confidence());
        }

        // 6) CONSUME_ITEM / CAST_TALISMAN 必须引用 actor 背包中确实存在的物品
        if (intent == IntentEnum.CONSUME_ITEM || intent == IntentEnum.CAST_TALISMAN || intent == IntentEnum.GIVE || intent == IntentEnum.TRADE) {
            String cand = p1.consumedItemOrTalisman();
            if (cand == null || cand.isBlank()) {
                b.add("意图[" + intent.zhLabel + "]必须指定 consumedItemOrTalisman");
            } else {
                GodProfile actor = gs.get(p1.actorGodId());
                if (actor != null) {
                    String actual = InventoryMatcher.resolveActualItem(actor, cand);
                    if (actual == null) {
                        b.add("行动方背包中不存在指定物品[" + cand + "]");
                    } else if (intent == IntentEnum.CAST_TALISMAN
                            && InventoryMatcher.TALISMAN_KEYWORDS.stream().noneMatch(actual::contains)) {
                        b.add("CAST_TALISMAN 指定的物品[" + actual + "]不含符/咒/印/阵/幡/令关键字，不是符类");
                    }
                }
            }
        }

        // 7) OTHER 意图但 confidence ≥ 0.99 给出 warning（不判失败，但要留痕）
        if (intent == IntentEnum.OTHER && p1.confidence() > 0.99) {
            b.add("意图被识别为 OTHER 但 confidence 过高，Pass1 可能没按枚举约束收敛");
        }

        // 8) 积木式场景引擎：非对抗类意图（MOVE/EXAMINE/PICK_UP/TALK）走特殊校验
        //    - MOVE/PICK_UP 需要 itemId（目标场景ID / 物品名），否则报错
        //    - EXAMINE/TALK 不需要 target，actor 可空（可由玩家发起）
        //    - 这类意图无需对抗属性，跳过 attackStatKey 校验
        if (intent == IntentEnum.MOVE || intent == IntentEnum.PICK_UP) {
            String itemId = p1.consumedItemOrTalisman();
            if (itemId == null || itemId.isBlank()) {
                b.add("意图[" + intent.zhLabel + "]必须指定 itemId（"
                        + (intent == IntentEnum.MOVE ? "目标场景ID/出口名" : "物品名") + "）");
            }
        }
        // MOVE/TALK/EXAMINE/PICK_UP 无需校验 target 在场，统一放行
        // （MOVE 的 target 是"目标场景"，不是神明；EXAMINE/TALK 可以无 target）

        return b.build();
    }
}
