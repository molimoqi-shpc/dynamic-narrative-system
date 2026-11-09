package com.bs.narrative.engine;

import com.bs.narrative.domain.GodProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** C4 种子物品匹配验证：rain ["止水符"]、player ["骨刃","水囊"] */
class InventoryMatcherTest {

    static GodProfile rain() {
        GodProfile p = new GodProfile(); p.setGodId("rain_god");
        p.setInventory(List.of("止水符")); return p;
    }
    static GodProfile player() {
        GodProfile p = new GodProfile(); p.setGodId("player_01");
        p.setInventory(List.of("骨刃", "水囊")); return p;
    }
    static GodProfile emptyInv() {
        GodProfile p = new GodProfile(); p.setInventory(null); return p;
    }

    @Test void rain_zhishifu_exactAndTalismanCheck() {
        assertTrue(InventoryMatcher.hasExact(rain(), "止水符"));
        assertFalse(InventoryMatcher.hasExact(rain(), "骨刃"));
        // 子串匹配："止水符"确实包含连续子串"水符"（第2、3字）；"止水"也命中；"xyz"不命中
        assertTrue(InventoryMatcher.containsSubstring(rain(), "水符"));
        assertTrue(InventoryMatcher.containsSubstring(rain(), "止水"));
        assertFalse(InventoryMatcher.containsSubstring(rain(), "xyz"));
        // 符类检查："止水符"含"符"
        assertTrue(InventoryMatcher.hasCategoryTalisman(rain()), "止水符应判定为符类");
        // resolveActualItem 完全匹配优先
        assertEquals("止水符", InventoryMatcher.resolveActualItem(rain(), "止水符"));
        assertEquals("止水符", InventoryMatcher.resolveActualItem(rain(), "止水")); // 子串回退
        assertNull(InventoryMatcher.resolveActualItem(rain(), "不存在"));
    }

    @Test void player_items_guren_shuinang_match() {
        assertTrue(InventoryMatcher.hasExact(player(), "骨刃"));
        assertTrue(InventoryMatcher.hasExact(player(), "水囊"));
        // 子串："水" 命中 "水囊"
        assertTrue(InventoryMatcher.containsSubstring(player(), "水"));
        // 玩家没有符类
        assertFalse(InventoryMatcher.hasCategoryTalisman(player()), "玩家骨刃/水囊不含符/咒/印... 不应判定为符类");
        // countHits：只给["骨刃"] → 1；["骨刃","水囊","不存在"] → 2
        assertEquals(1, InventoryMatcher.countHits(player(), List.of("骨刃")));
        assertEquals(2, InventoryMatcher.countHits(player(), List.of("骨刃", "水囊", "不存在")));
    }

    @Test void nullInventory_isSafe() {
        GodProfile n = emptyInv();
        assertFalse(InventoryMatcher.hasExact(n, "x"));
        assertFalse(InventoryMatcher.containsSubstring(n, "x"));
        assertFalse(InventoryMatcher.hasCategoryTalisman(n));
        assertNull(InventoryMatcher.resolveActualItem(n, "x"));
        assertEquals(0, InventoryMatcher.countHits(n, List.of("a","b")));
    }
}
