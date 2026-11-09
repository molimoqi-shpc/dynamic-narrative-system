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
 * spec FR-A5 最小补足：玩家账号与状态表。
 */
@Entity
@Table(name = "player_profile")
@Getter
@Setter
public class PlayerProfile {

    @Id
    @Column(name = "player_id", length = 32)
    private String playerId;

    @Column(name = "active_session_id", length = 32)
    private String activeSessionId;

    @Column(name = "god_id", length = 32, nullable = false)
    private String godId;

    @Column(name = "username", length = 50, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", length = 255, nullable = false)
    private String passwordHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inventory", columnDefinition = "JSON")
    private List<String> inventory = new ArrayList<>();

    @Column(name = "current_scene_id", length = 32, nullable = false)
    private String currentSceneId;

    /** DREAMER = 入梦人(限知)；OMNISCIENT = 世界人(全知)。 */
    @Column(name = "view_mode", length = 16)
    private String viewMode = "DREAMER";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
