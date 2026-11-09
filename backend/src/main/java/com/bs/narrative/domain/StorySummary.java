package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 剧情总结元数据表（隐藏的剧情锚点 + 坐标定位 + Prompt 注入约束）。
 *
 * 核心机制：
 *   每次 LLM 生成故事时，要求 LLM 同时输出 visible + hidden_summary 双块。
 *   hidden_summary 写入本表，玩家不可见；下次调 Pass1/Pass2 时自动注入 Prompt 约束 LLM。
 *   坐标 coord_x/coord_y 用于玩家"回去"时定位已存在场景，避免 LLM 重复取名。
 */
@Entity
@Table(name = "story_summary",
       indexes = {
               @Index(name = "idx_session_round",  columnList = "session_id, round_no"),
               @Index(name = "idx_session_coord",  columnList = "session_id, coord_x, coord_y"),
               @Index(name = "idx_session_recent", columnList = "session_id, created_at")
       }
)
@Getter
@Setter
public class StorySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    private Long summaryId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /** 关联场景ID（过场剧情可空）。 */
    @Column(name = "scene_id", length = 32)
    private String sceneId;

    /** 回合序号（本 session 内递增）。 */
    @Column(name = "round_no")
    private Integer roundNo = 0;

    /** 地点坐标 X（玩家回去时定位用）。 */
    @Column(name = "coord_x")
    private Integer coordX = 0;

    /** 地点坐标 Y（玩家回去时定位用）。 */
    @Column(name = "coord_y")
    private Integer coordY = 0;

    @Column(name = "location_name", length = 64, nullable = false)
    private String locationName;

    /** 剧情内时间（如"戌时三刻"）。 */
    @Column(name = "story_time", length = 32)
    private String storyTime;

    /** 本回合剧情概括（一句话说清楚）。 */
    @Column(name = "plot_summary", nullable = false, columnDefinition = "TEXT")
    private String plotSummary;

    /** 在场角色ID数组。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "present_gods", columnDefinition = "JSON")
    private List<String> presentGods;

    /** 关键事件数组（如"玩家挑衅雨神"）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_events", columnDefinition = "JSON")
    private List<String> keyEvents;

    /** 状态变化摘要（如"雨神trust-15, 博物叟trust+10"）。 */
    @Column(name = "state_changes", columnDefinition = "TEXT")
    private String stateChanges;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
