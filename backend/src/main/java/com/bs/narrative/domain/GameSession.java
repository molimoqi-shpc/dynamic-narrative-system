package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * V2 游戏会话生命周期表：管理一次完整游戏的生命周期。
 * 状态流转：CREATING → PLAYING → DEAD → SUMMARIZED
 */
@Entity
@Table(name = "game_session",
       indexes = {
               @Index(name = "idx_player", columnList = "player_id"),
               @Index(name = "idx_status", columnList = "status")
       })
@Getter
@Setter
public class GameSession {

    @Id
    @Column(name = "session_id", length = 32)
    private String sessionId;

    @Column(name = "player_id", length = 32, nullable = false)
    private String playerId;

    @Column(name = "player_god_id", length = 32)
    private String playerGodId;
    @Column(name = "current_scene_id", length = 32)
    private String currentSceneId;
    @Column(name = "current_round")
    private Integer currentRound = 0;

    /** DREAMER=入梦(限知)/OMNISCIENT=知梦(全知)。 */
    @Column(name = "view_mode", length = 16, nullable = false)
    private String viewMode = "DREAMER";

    /** CREATING/PLAYING/DEAD/SUMMARIZED。 */
    @Column(name = "status", length = 16, nullable = false)
    private String status = "CREATING";

    /** 主角死亡后的总结叙事。 */
    @Column(name = "death_summary", columnDefinition = "TEXT")
    private String deathSummary;

    /** 重启时是否继承上一周目世界书。 */
    @Column(name = "inherit_world")
    private Boolean inheritWorld = false;

    /** 继承的上一周目ID。 */
    @Column(name = "prev_session_id", length = 32)
    private String prevSessionId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {createdAt=LocalDateTime.now();updatedAt=createdAt;}

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
