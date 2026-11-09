package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 玩家目标进度表（按 session 隔离，防坑规则3）。
 * 主线/支线目标推进，配合场景连接图的 required_objective 做门禁。
 */
@Entity
@Table(name = "player_objective",
       uniqueConstraints = @UniqueConstraint(name = "uk_player_session_obj",
               columnNames = {"player_id", "session_id", "objective_id"}),
       indexes = @Index(name = "idx_session", columnList = "session_id")
)
@Getter
@Setter
public class PlayerObjective {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "objective_row_id")
    private Long objectiveRowId;

    @Column(name = "objective_id", length = 64, nullable = false)
    private String objectiveId;

    @Column(name = "player_id", length = 32, nullable = false)
    private String playerId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    @Column(name = "title", length = 100, nullable = false)
    private String title;

    /** ACTIVE / COMPLETED / FAILED */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "ACTIVE";

    /** 0-100 进度。 */
    @Column(name = "progress")
    private Integer progress = 0;

    @Column(name = "current_step", length = 255)
    private String currentStep;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
