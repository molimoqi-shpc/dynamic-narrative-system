package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * §五.5 NPC 群戏决策日志表（论文级可追溯补强）。
 *
 * 每回合每个在场 NPC 对玩家动作的反应独立落库 1 条，对应 NpcAgentService.NpcDecision。
 * 与 interaction_log 的关系：1 个 log_id 可对应多条 npc_decision_log（每 NPC 一条）。
 *
 * 防坑规则3：按 session_id 隔离，避免跨周目/跨玩家串扰。
 *
 * 论文截图用途：
 *   - 可追溯每个 NPC 每回合的"怎么想、怎么说、是否说谎"
 *   - 配合 npc_goal 表可分析"目标 → 行为"的决策合理性
 *   - 配合 interaction_log 可还原完整回合流水
 */
@Entity
@Table(name = "npc_decision_log",
       indexes = {
               @Index(name = "idx_session_created",
                       columnList = "session_id, created_at DESC"),
               @Index(name = "idx_log_god",
                       columnList = "log_id, god_id"),
               @Index(name = "idx_session_god",
                       columnList = "session_id, god_id, created_at DESC")
       })
@Getter
@Setter
public class NpcDecisionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "decision_id")
    private Long decisionId;

    /** 防坑规则3：所属会话/周目ID。 */
    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /** 关联 interaction_log.log_id（玩家本回合的日志ID）。 */
    @Column(name = "log_id")
    private Long logId;

    /** NPC 的 god_id（god_profile.god_id）。 */
    @Column(name = "god_id", length = 32, nullable = false)
    private String godId;

    /** 场景ID（便于按场景回溯群戏）。 */
    @Column(name = "scene_id", length = 32, nullable = false)
    private String sceneId;

    /** 玩家本回合输入（冗余字段，便于单表分析无需 join）。 */
    @Column(name = "player_input", columnDefinition = "TEXT")
    private String playerInput;

    /**
     * NPC 行为决策枚举（对应 NpcAgentPromptTemplate）。
     * RESPOND / ATTACK / SUPPORT / OPPOSE / IGNORE / LEAVE / INQUIRE
     */
    @Column(name = "behavior", length = 16, nullable = false)
    private String behavior;

    /** 公开对白（限知视角，前端回合列表展示）。 */
    @Column(name = "speech", columnDefinition = "TEXT", nullable = false)
    private String speech;

    /** 真实内心活动（全知视角，天眼面板展示）。 */
    @Column(name = "inner_thought", columnDefinition = "TEXT", nullable = false)
    private String innerThought;

    /** 是否说谎：公开言行与真实想法矛盾时为 true。 */
    @Column(name = "is_lying", nullable = false)
    private Boolean isLying = false;

    /** LLM 调用是否走了 Fallback（true=本地兜底，论文分析用）。 */
    @Column(name = "used_fallback")
    private Boolean usedFallback = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
