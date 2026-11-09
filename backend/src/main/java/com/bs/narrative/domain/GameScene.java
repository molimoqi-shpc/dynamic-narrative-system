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
 * §五.3 场景状态与上下文摘要表。
 * present_gods 以 JSON 字符串数组存 VARCHAR(255)；JPA 侧自动反序列化为 List<String>。
 */
@Entity
@Table(name = "game_scene",
       indexes = @Index(name = "idx_session", columnList = "session_id"))
@Getter
@Setter
public class GameScene {

    @Id
    @Column(name = "scene_id", length = 32)
    private String sceneId;

    /** 防坑规则3：所属会话/周目ID，多人独立存档隔离。 */
    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    @Column(name = "location_name", length = 50, nullable = false)
    private String locationName;

    /** 地点坐标 X（玩家回去时定位用，避免 LLM 重复取名）。 */
    @Column(name = "coord_x")
    private Integer coordX = 0;

    /** 地点坐标 Y（玩家回去时定位用，避免 LLM 重复取名）。 */
    @Column(name = "coord_y")
    private Integer coordY = 0;

    /** 对应 DB VARCHAR(255) JSON 字符串数组 → 透明地 List<String> 往返。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "present_gods", length = 255, nullable = false)
    private List<String> presentGods = new ArrayList<>();

    @Column(name = "scene_summary", columnDefinition = "TEXT")
    private String sceneSummary;

    @Column(name = "log_count")
    private Integer logCount = 0;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
