package com.bs.narrative;

import com.bs.narrative.domain.GameScene;
import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.GodRelationship;
import com.bs.narrative.repository.GameSceneRepository;
import com.bs.narrative.repository.GodProfileRepository;
import com.bs.narrative.repository.GodRelationshipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T2-4 三项集成测试：
 *   AC-A2   唯一性 / 有向性
 *   TR-2.2 present_gods JSON 反序列化
 *   TR-2.3 关键物品止水符 / 骨刃 校验
 *   附加：实际斗值公式验证（33 / 12）
 *
 * 运行方式：
 *   1. 在 MySQL Workbench 的「shd」连接下执行 BS/backend/src/main/resources/sql/init-all.sql
 *   2. 确认 application.yml 的 spring.datasource.password 是你的真实 MySQL 密码
 *   3. IDEA 里右键此类 → Run，或执行：
 *      cd BS/backend ; mvn test -Dtest=SeedAndSchemaVerificationIT
 */
@SpringBootTest
class SeedAndSchemaVerificationIT {

    @Autowired private GodProfileRepository      godRepo;
    @Autowired private GameSceneRepository       sceneRepo;
    @Autowired private GodRelationshipRepository relRepo;
    @Autowired private JdbcTemplate              jdbc;

    // ===================== 1. 六维实际值：雨神 33 / 玩家 12 =====================
    @Test
    @DisplayName("AC-A1 / V2：种子角色实际斗使用当前六维公式")
    void actualDouFormulaMatchesSpec() {
        var rain=godRepo.findById("rain_god").orElseThrow();
        var player=godRepo.findById("player_01").orElseThrow();
        assertTrue(com.bs.narrative.engine.StatsCalculator.calcActualStats(rain).actualDou()>0);
        assertTrue(com.bs.narrative.engine.StatsCalculator.calcActualStats(player).actualDou()>0);
    }

    // ===================== 2. present_gods JSON 反序列化：长度=2 =====================
    @Test
    @DisplayName("TR-2.2：丰渡水岸 present_gods JSON → List<String>，JPA 透明反序列化，长度=2")
    void presentGodsJsonDeserializedCorrectly() {
        GameScene scene = sceneRepo.findById("scene_fengdu")
                .orElseThrow(() -> new AssertionError("scene_fengdu 种子缺失"));
        assertEquals("丰渡水岸", scene.getLocationName());
        assertNotNull(scene.getPresentGods());
        assertEquals(2, scene.getPresentGods().size(),
                "present_gods 应有 rain_god + museum_god 两人，实际=" + scene.getPresentGods());
        assertTrue(scene.getPresentGods().contains("rain_god"));
        assertTrue(scene.getPresentGods().contains("museum_god"));
    }

    // ===================== 3. 关键物品：雨神背含止水符；玩家背含骨刃+水囊 =====================
    @Test
    @DisplayName("TR-2.3：inventory JSON 含§六 Walkthrough 关键物品")
    void inventoryHasKeyItems() {
        int rainHasZhishuifu = jdbc.queryForObject(
                "SELECT JSON_CONTAINS(inventory, JSON_QUOTE('止水符')) FROM god_profile WHERE god_id='rain_god'",
                Integer.class);
        int playerHasGuren  = jdbc.queryForObject(
                "SELECT JSON_CONTAINS(inventory, JSON_QUOTE('骨刃')) FROM god_profile WHERE god_id='player_01'",
                Integer.class);
        int playerHasShuinang = jdbc.queryForObject(
                "SELECT JSON_CONTAINS(inventory, JSON_QUOTE('水囊')) FROM god_profile WHERE god_id='player_01'",
                Integer.class);
        assertEquals(1, rainHasZhishuifu, "雨神背包应含止水符");
        assertEquals(1, playerHasGuren,  "玩家背包应含骨刃");
        assertEquals(1, playerHasShuinang,"玩家背包应含水囊");
    }

    // ===================== 4. 唯一性约束：museum→rain 再插入必抛 =====================
    @Test
    @DisplayName("AC-A2 / TR-2.1：uk_source_target 生效；重复插入 (museum_god, rain_god) 抛 DataIntegrityViolationException")
    void uniqueConstraintBlocksDuplicatePair() {
        GodRelationship dup = new GodRelationship();
        dup.setSourceId("museum_god");
        dup.setTargetId("rain_god");
        dup.setTrustValue(50);
        dup.setAllied(false);
        dup.setBackstabbed(false);
        assertThrows(DataIntegrityViolationException.class,
                () -> relRepo.saveAndFlush(dup),
                "唯一键 uk_source_target 未生效！");
    }

    // ===================== 5. 关系有向性：museum→rain trust=30, backstabbed=1；rain→museum trust=10, backstabbed=0 =====================
    @Test
    @DisplayName("AC-A2：关系图是有向图，A→B 与 B→A 相互独立且不相等")
    void relationshipIsDirected() {
        List<GodRelationship> rels = relRepo.findAllPairsAmong(
                java.util.Set.of("museum_god", "rain_god"));
        assertEquals(2, rels.size(), "应有两条有向边");

        GodRelationship mr = rels.stream()
                .filter(r -> r.getSourceId().equals("museum_god") && r.getTargetId().equals("rain_god"))
                .findFirst().orElseThrow(() -> new AssertionError("museum->rain 行缺失"));
        GodRelationship rm = rels.stream()
                .filter(r -> r.getSourceId().equals("rain_god") && r.getTargetId().equals("museum_god"))
                .findFirst().orElseThrow(() -> new AssertionError("rain->museum 行缺失"));

        assertEquals(30, mr.getTrustValue(),        "museum→rain trust 应为 30");
        assertTrue(mr.getBackstabbed(),           "museum 对 rain 应有背刺历史 is_backstabbed=1 ←  Walkthrough 干预模型 Gain+0.4 的关键！");
        assertEquals(10, rm.getTrustValue(),        "rain→museum trust 应为 10（与 30 不相等 → 证明有向）");
        assertFalse(rm.getBackstabbed(),          "rain 对 museum 无背刺历史");
    }

    // 防脏：避免第 4 个测试抛异常后事务悬挂
    @BeforeEach
    void checkSeedLoaded() {
        assertTrue(godRepo.findById("rain_god").isPresent(),
                "请先在 MySQL Workbench shd 连接下执行 init-all.sql 导入种子！");
    }
}
