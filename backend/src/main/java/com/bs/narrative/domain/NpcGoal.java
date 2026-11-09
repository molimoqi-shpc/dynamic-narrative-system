package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * V2 NPC 独立目标表：每个 NPC 有自己的秘密目标，驱动主动行为。
 */
@Entity
@Table(name = "npc_goal",
       indexes = {
               @Index(name = "idx_session_god",    columnList = "session_id, god_id"),
               @Index(name = "idx_session_status", columnList = "session_id, status")
       })
@Getter
@Setter
public class NpcGoal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "goal_id")
    private Long goalId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    @Column(name = "god_id", length = 32, nullable = false)
    private String godId;

    @Column(name = "goal_description", nullable = false, columnDefinition = "TEXT")
    private String goalDescription;

    /** 1-10，越高越优先。 */
    @Column(name = "priority")
    private Integer priority = 5;

    /** ACTIVE/COMPLETED/FAILED/ABANDONED。 */
    @Column(name = "status", length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
