package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * §五.4 交互日志表（每玩家每回合 1 条）。
 */
@Entity
@Table(name = "interaction_log",
       indexes = {
           @Index(name = "idx_session_created", columnList = "session_id, created_at DESC"),
           @Index(name = "idx_player_created", columnList = "player_id, created_at DESC"),
           @Index(name = "idx_scene_created",  columnList = "scene_id,  created_at DESC")
       })
@Getter
@Setter
public class InteractionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    /** 防坑规则3：所属会话/周目ID，多人独立存档隔离。 */
    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /**
     * 【优化指导 6.4】回合序号（事件溯源用）。
     * 读档到第 N 回合时，删除 session_id=xxx 且 round_no > N 的记录即可。
     */
    @Column(name = "round_no")
    private Integer roundNo = 0;

    @Column(name = "player_id", length = 32, nullable = false)
    private String playerId;

    @Column(name = "scene_id", length = 32, nullable = false)
    private String sceneId;

    @Column(name = "player_input", columnDefinition = "TEXT", nullable = false)
    private String playerInput;

    /** 本地规则引擎判定的硬性剧情分支类型，如 EXPLOITATION_RESCUE。 */
    @Column(name = "outcome_type", length = 50, nullable = false)
    private String outcomeType;

    @Column(name = "intervener_id", length = 32)
    private String intervenerId;

    /** NPC 真实内心活动 JSON（全知视角）。 */
    @Column(name = "true_intent", columnDefinition = "TEXT", nullable = false)
    private String trueIntent;

    /** 古风渲染对白（限知视角）。 */
    @Column(name = "processed_dialog", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String processedDialog;

    /** 按用户 profile 要求，token 四项：input/output/total + cost_cny 必须同时显示。 */
    @Column(name = "token_input")
    private Integer tokenInput = 0;

    @Column(name = "token_output")
    private Integer tokenOutput = 0;

    @Column(name = "token_total")
    private Integer tokenTotal = 0;

    @Column(name = "cost_cny", precision = 12, scale = 6)
    private BigDecimal costCny = BigDecimal.ZERO;

    /** 规则引擎中间分快照（Cap/Will/Gain/反噬候选等）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_snapshot", columnDefinition = "JSON")
    private Map<String, Object> ruleSnapshot;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
