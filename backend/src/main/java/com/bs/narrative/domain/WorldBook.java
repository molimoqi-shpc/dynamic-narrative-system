package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2 世界书：全局事件记录中枢。
 *
 * 核心机制：
 *   每回合事件自动写入世界书，按时间顺序排列。
 *   所有 API 共享世界书但各自的知识范围不同（限知博弈）。
 *   importance 为 #9 压缩/遗忘预留：高重要度事件保留原文，低重要度后期压缩。
 *   info_source 为 #8 NPC 个人记忆预留：PERSONAL/INQUIRED/TOLD 三级。
 */
@Entity
@Table(name = "world_book",
       indexes = {
               @Index(name = "idx_session_round",      columnList = "session_id, round_no"),
               @Index(name = "idx_session_location",     columnList = "session_id, coord_x, coord_y"),
               @Index(name = "idx_session_recent",      columnList = "session_id, created_at"),
               @Index(name = "idx_session_importance",  columnList = "session_id, importance")
       }
)
@Getter
@Setter
public class WorldBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /** 回合序号（本 session 内递增）。 */
    @Column(name = "round_no")
    private Integer roundNo = 0;

    /** 剧情内时间（如"戌时三刻"，不可倒退）。 */
    @Column(name = "story_time", length = 32)
    private String storyTime;

    @Column(name = "coord_x")
    private Integer coordX = 0;

    @Column(name = "coord_y")
    private Integer coordY = 0;

    @Column(name = "location_name", length = 64, nullable = false)
    private String locationName;

    /** 事件类型：MOVE/COMBAT/DIALOGUE/INQUIRY/ITEM_GAIN/ITEM_LOSS/NPC_DEATH/NPC_SPAWN/STORY_MILESTONE/DEATH。 */
    @Column(name = "event_type", length = 32, nullable = false)
    private String eventType;

    /** 参与角色ID数组。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "actor_ids", columnDefinition = "JSON")
    private List<String> actorIds;

    /** 一句话概括发生了什么。 */
    @Column(name = "event_description", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String eventDescription;

    /** 状态变化摘要（如"雨神trust-15, 博物叟trust+10"）。 */
    @Column(name = "state_changes", columnDefinition = "TEXT")
    private String stateChanges;

    /** 1-10，用于压缩/遗忘优先级（10=关键转折，1=琐碎移动）。 */
    @Column(name = "importance")
    private Integer importance = 5;

    /**
     * 【优化指导 6.3】是否为秘密事件。
     * true=隐藏历史（高属性"闻"+"慧"才能打听到）
     * false=表面记录（低属性也能捞出）
     */
    @Column(name = "is_secret", nullable = false)
    private Boolean isSecret = false;

    /** 信息来源：PERSONAL=亲历/INQUIRED=打听/TOLD=被告知。 */
    @Column(name = "info_source", length = 16)
    private String infoSource = "PERSONAL";

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
