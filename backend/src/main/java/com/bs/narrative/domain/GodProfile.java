package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * §五.1 神明基础与属性表（含玩家实体 player_01）。
 * 字段与 init-all.sql #1 god_profile 一一对应，绝不新增/遗漏。
 */
@Entity
@Table(name = "god_profile")
@Getter
@Setter
public class GodProfile {

    @Id
    @Column(name = "god_id", length = 32)
    private String godId;

    @Column(name = "session_id", length = 32)
    private String sessionId;

    @Column(name = "god_name", length = 50, nullable = false)
    private String godName;

    @Column(name = "god_status", length = 50, nullable = false)
    private String godStatus;

    @Column(name = "god_emotion", length = 32, nullable = false)
    private String godEmotion;

    @Column(name = "base_zhi")
    private Integer baseZhi = 0;

    @Column(name = "base_hui")
    private Integer baseHui = 0;

    @Column(name = "base_dou")
    private Integer baseDou = 0;

    @Column(name = "base_wen")
    private Integer baseWen = 0;

    @Column(name = "base_ben")
    private Integer baseBen = 0;   // CHECK 0..100

    @Column(name = "base_gui")
    private Integer baseGui = 0;

    /** MVP 最小补足：该神明/玩家的背包物品数组 JSON。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inventory", columnDefinition = "JSON")
    private List<String> inventory = new ArrayList<>();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
