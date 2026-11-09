package com.bs.narrative.engine;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.engine.model.ResolutionResult;
import com.bs.narrative.engine.model.StatDelta;
import com.bs.narrative.engine.model.StringPair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 ResolutionResult 里的 statDeltaPerGod 施加到 GodProfile 的 base 字段，
 * 把 relationshipDeltaPerPair 施加到有向关系 trustValue；并移除消耗掉的物品。
 * **不调 JPA save**：返回已 set 完新值的 detached 对象集合，业务层再统一 saveAll。
 */
public final class DeltaApplier {

    private DeltaApplier() {}

    // —— 数据对象：apply 之后对“神明集合 + 关系集合”两份快照都已更新 ——
    public record Snapshot(
            Map<String, GodProfile> godsById,
            List<GodRelationship> relationships,
            List<String> consumedItems
    ) {}

    public static Snapshot applyAll(ResolutionResult r,
                                    Map<String, GodProfile> inputGods,
                                    List<GodRelationship> inputRels) {
        // 1) 对 inputGods 做拷贝（避免直接修改调用者传入的 Map）
        Map<String, GodProfile> gods = new LinkedHashMap<>(inputGods == null ? Map.of() : inputGods);

        // 2) 施加每神明 StatDelta → 对应 base* 字段（并最终 clamp baseBen 到 0..100）
        if (r != null && r.statDeltaPerGod() != null) {
            for (Map.Entry<String, StatDelta> e : r.statDeltaPerGod().entrySet()) {
                GodProfile g = gods.get(e.getKey());
                if (g == null) continue;
                StatDelta d = e.getValue();
                if (d == null) continue;
                g.setBaseZhi(addAndClamp(g.getBaseZhi(), d.zhi(), 10000));
                g.setBaseHui(addAndClamp(g.getBaseHui(), d.hui(), 10000));
                g.setBaseDou(addAndClamp(g.getBaseDou(), d.dou(), 10000));
                g.setBaseWen(addAndClamp(g.getBaseWen(), d.wen(), 10000));
                g.setBaseBen(addAndClamp(g.getBaseBen(), d.ben(), StatsCalculator.BEN_MAX)); // 本必须 [0,100]
                g.setBaseGui(addAndClamp(g.getBaseGui(), d.gui(), 10000));
            }
        }

        // 3) 移除 consumed 物品
        List<String> consumed = new ArrayList<>();
        if (r != null && r.consumedItems() != null) consumed.addAll(r.consumedItems());
        if (!consumed.isEmpty()) {
            for (GodProfile g : gods.values()) {
                if (g == null || g.getInventory() == null) continue;
                List<String> inv = new ArrayList<>(g.getInventory());
                boolean changed = inv.removeAll(consumed);
                if (changed) g.setInventory(inv);
            }
        }

        // 4) 关系 delta：施加 trustValue 增量
        List<GodRelationship> rels = new ArrayList<>(inputRels == null ? List.of() : inputRels);
        Map<StringPair, Integer> relDelta = (r == null || r.relationshipDeltaPerPair() == null)
                ? Map.of() : r.relationshipDeltaPerPair();
        Map<String, Integer> pairIndex = new HashMap<>();
        for (int i = 0; i < rels.size(); i++) {
            GodRelationship rel = rels.get(i);
            if (rel == null || rel.getSourceId() == null || rel.getTargetId() == null) continue;
            pairIndex.put(rel.getSourceId() + "->" + rel.getTargetId(), i);
        }
        for (Map.Entry<StringPair, Integer> e : relDelta.entrySet()) {
            String key = e.getKey().from + "->" + e.getKey().to;
            Integer idx = pairIndex.get(key);
            GodRelationship rel;
            if (idx != null) {
                rel = rels.get(idx);
            } else {
                rel = new GodRelationship();
                rel.setSourceId(e.getKey().from);
                rel.setTargetId(e.getKey().to);
                rel.setTrustValue(50);
                rel.setAllied(false);
                rel.setBackstabbed(false);
                rels.add(rel);
            }
            int tv = rel.getTrustValue() != null ? rel.getTrustValue() : 50;
            tv = addAndClamp(tv, e.getValue(), 100); // trustValue ∈ [0,100]
            rel.setTrustValue(tv);
        }

        return new Snapshot(Map.copyOf(gods), List.copyOf(rels), List.copyOf(consumed));
    }

    /** 快速取“变更后某个神明”的实际六维（先应用 delta，再算实际）。 */
    public static Map<String, com.bs.narrative.engine.model.ActualStats> actualsAfter(Snapshot s) {
        Map<String, com.bs.narrative.engine.model.ActualStats> out = new LinkedHashMap<>();
        if (s == null || s.godsById() == null) return out;
        for (Map.Entry<String, GodProfile> e : s.godsById().entrySet()) {
            out.put(e.getKey(), StatsCalculator.calcActualStats(e.getValue()));
        }
        return out;
    }

    private static int addAndClamp(Integer base, int delta, int max) {
        int v = (base == null ? 0 : base) + delta;
        if (v < 0) v = 0;
        if (v > max) v = max;
        return v;
    }

}
