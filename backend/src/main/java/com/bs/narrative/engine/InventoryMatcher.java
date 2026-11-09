package com.bs.narrative.engine;

import com.bs.narrative.domain.GodProfile;

import java.util.Collection;
import java.util.List;

/**
 * 物品命中：完全匹配 / 关键词子串 / 符类分类（符/咒/印/阵/幡/令）。
 * 纯函数；inventory 为 null 时安全返回 false，不 NPE。
 */
public final class InventoryMatcher {

    private InventoryMatcher() {}

    public static final List<String> TALISMAN_KEYWORDS = List.of("符", "咒", "印", "阵", "幡", "令");

    public static boolean hasExact(GodProfile p, String itemName) {
        if (p == null || p.getInventory() == null || itemName == null) return false;
        String name = itemName.trim();
        if (name.isEmpty()) return false;
        for (String it : p.getInventory()) { if (name.equals(it)) return true; }
        return false;
    }

    public static boolean containsSubstring(GodProfile p, String keyword) {
        if (p == null || p.getInventory() == null || keyword == null) return false;
        String k = keyword.trim();
        if (k.isEmpty()) return false;
        for (String it : p.getInventory()) { if (it != null && it.contains(k)) return true; }
        return false;
    }

    /** 任一物品含“符/咒/印/阵/幡/令” → 视为符类，可触发 TALISMAN_NEUTRALIZE。 */
    public static boolean hasCategoryTalisman(GodProfile p) {
        if (p == null || p.getInventory() == null) return false;
        for (String item : p.getInventory()) {
            if (item == null) continue;
            for (String kw : TALISMAN_KEYWORDS) { if (item.contains(kw)) return true; }
        }
        return false;
    }

    /** 返回实际存在的物品：完全匹配优先，否则首个子串匹配。 */
    public static String resolveActualItem(GodProfile p, String candidate) {
        if (p == null || p.getInventory() == null || candidate == null) return null;
        String key = candidate.trim();
        if (key.isEmpty()) return null;
        String sub = null;
        for (String it : p.getInventory()) {
            if (it == null) continue;
            if (key.equals(it)) return it;
            if (sub == null && it.contains(key)) sub = it;
        }
        return sub;
    }

    public static long countHits(GodProfile p, Collection<String> names) {
        if (p == null || names == null) return 0L;
        return names.stream().filter(n -> hasExact(p, n)).count();
    }
}
