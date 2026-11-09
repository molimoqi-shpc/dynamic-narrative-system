package com.bs.narrative.engine;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.engine.enums.IntentEnum;
import com.bs.narrative.engine.enums.StatKey;
import com.bs.narrative.engine.model.Pass1Intent;
import com.bs.narrative.engine.model.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 2 合法 + 3 非法场景校验。 */
class IntentValidatorTest {

    static GameScene fengdu() {
        GameScene s = new GameScene(); s.setSceneId("scene_fengdu");
        s.setPresentGods(List.of("rain_god", "museum_god"));
        return s;
    }
    static Map<String, GodProfile> gods() {
        GodProfile pl = new GodProfile(); pl.setGodId("player_01"); pl.setInventory(List.of("骨刃","水囊"));
        GodProfile rn = new GodProfile(); rn.setGodId("rain_god");  rn.setInventory(List.of("止水符"));
        GodProfile ms = new GodProfile(); ms.setGodId("museum_god");ms.setInventory(List.of());
        GodProfile fm = new GodProfile(); fm.setGodId("farm_god");  fm.setInventory(List.of());
        return Map.of("player_01", pl, "rain_god", rn, "museum_god", ms, "farm_god", fm);
    }

    // ——— 合法 2 场景 ———
    @Test void valid_player_provoke_rainGod() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.PROVOKE, "player_01", "rain_god",
                "就扣黑锅", StatKey.WEN, null, 0.85, "{}");
        ValidationResult r = IntentValidator.validate("player_01", p1, fengdu(), gods());
        assertTrue(r.isValid(), () -> "期望通过，失败原因：" + String.join("；", r.reasons()));
        assertEquals(0, r.reasons().size());
    }

    @Test void valid_player_consume_shuinang() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.CONSUME_ITEM, "player_01", "rain_god",
                "取出", null, "水囊", 0.95, "{}");
        ValidationResult r = IntentValidator.validate("player_01", p1, fengdu(), gods());
        assertTrue(r.isValid(), () -> "期望通过，失败原因：" + String.join("；", r.reasons()));
    }

    // ——— 非法 3 场景：target 不在场；attackKey 不匹配；物品没带 ———
    @Test void invalid_farmGod_not_present() {
        // farm_god 不在 presentGods=[rain_god, museum_god]，target 非法
        Pass1Intent p1 = Pass1Intent.ofSimple(IntentEnum.ATTACK, "player_01", "farm_god");
        ValidationResult r = IntentValidator.validate("player_01", p1, fengdu(), gods());
        assertFalse(r.isValid(), "farm_god 不在场，应失败");
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("farm_god")),
                () -> "原因应包含 farm_god 字样：" + r.reasons());
    }

    @Test void invalid_attackKey_not_supported_by_intent() {
        // BRIBE 只支持 GUI/WEN；用 DOU 会报错
        Pass1Intent p1 = new Pass1Intent(IntentEnum.BRIBE, "player_01", "rain_god",
                "送钱", StatKey.DOU, null, 0.9, "{}");
        ValidationResult r = IntentValidator.validate("player_01", p1, fengdu(), gods());
        assertFalse(r.isValid(), "BRIBE DOU 应失败");
        assertTrue(r.firstReason().contains("对抗属性"), () -> r.reasons().toString());
    }

    @Test void invalid_consume_item_not_in_backpack() {
        Pass1Intent p1 = new Pass1Intent(IntentEnum.CONSUME_ITEM, "player_01", "rain_god",
                "用符", null, "不存在的符", 1.0, "{}");
        ValidationResult r = IntentValidator.validate("player_01", p1, fengdu(), gods());
        assertFalse(r.isValid());
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("不存在的符")));
    }

    @Test void invalid_cast_talisman_on_non_talisman_item() {
        // 玩家指定"水囊"作为咒，但水囊不含符/咒/印
        Pass1Intent p1 = new Pass1Intent(IntentEnum.CAST_TALISMAN, "player_01", "rain_god",
                "祭", null, "水囊", 1.0, "{}");
        ValidationResult r = IntentValidator.validate("player_01", p1, fengdu(), gods());
        assertFalse(r.isValid());
        assertTrue(r.reasons().stream().anyMatch(s -> s.contains("不是符类")),
                () -> r.reasons().toString());
    }

    @Test void edge_attackStatKey_null_valid_because_autoFill() {
        // attackStatKey == null 不报错（上层会用 intent.firstAttackKey 补）
        Pass1Intent p1 = new Pass1Intent(IntentEnum.PROVOKE, "player_01", "rain_god",
                "", null, null, 0.5, "{}");
        assertTrue(IntentValidator.validate("player_01", p1, fengdu(), gods()).isValid());
    }
}
