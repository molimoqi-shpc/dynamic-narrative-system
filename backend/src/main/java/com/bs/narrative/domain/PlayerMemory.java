package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 玩家三层记忆存储（B2 用，先建表预留）。
 *
 * scope 取值：
 *   ROUND  = 回合短记忆（ref_id = interaction_log.log_id）
 *   SCENE  = 场景长记忆（ref_id = scene_id，每 10 轮异步摘要更新）
 *   GLOBAL = 跨场景全局记忆（ref_id = objective_id 或事件 id）
 *
 * importance 1-10：用于 Pass2 Prompt 注入时的优先级排序。
 */
@Entity
@Table(name = "player_memory",
       indexes = {
               @Index(name = "idx_player_session_scope",
                       columnList = "player_id, session_id, scope, created_at"),
               @Index(name = "idx_session", columnList = "session_id")
       }
)
@Getter
@Setter
public class PlayerMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "memory_id")
    private Long memoryId;

    @Column(name = "player_id", length = 32, nullable = false)
    private String playerId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /** ROUND / SCENE / GLOBAL */
    @Column(name = "scope", length = 16, nullable = false)
    private String scope;

    /** ROUND→log_id, SCENE→scene_id, GLOBAL→objective_id */
    @Column(name = "ref_id", length = 64)
    private String refId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** LLM 提炼的精炼摘要（B2 异步生成，先预留）。 */
    @Column(name = "llm_summary", columnDefinition = "TEXT")
    private String llmSummary;

    /** 1-10，用于 Prompt 注入优先级。 */
    @Column(name = "importance")
    private Integer importance = 5;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
