package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * V2 天赋表：开局由 LLM 根据三问题答案生成，玩家选择后生效。
 * 候选至少三个、最终选择一个；强度表示适用范围，不改变候选数量。
 */
@Entity
@Table(name = "talent",
       indexes = {
               @Index(name = "idx_session",     columnList = "session_id"),
               @Index(name = "idx_session_god", columnList = "session_id, god_id")
       })
@Getter
@Setter
public class Talent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "talent_id")
    private Long talentId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    /** 拥有此天赋的角色ID（玩家选择前为空）。 */
    @Column(name = "god_id", length = 32)
    private String godId;

    @Column(name = "talent_name", length = 64, nullable = false)
    private String talentName;

    @Column(name = "talent_description", nullable = false, columnDefinition = "TEXT")
    private String talentDescription;

    /** 1=窄场景特长/2=常用特长/3=较突出但有明显条件；旧版保留原属性增益。 */
    @Column(name = "power_level")
    private Integer powerLevel = 1;

    @Column(name = "is_selected")
    private Boolean isSelected = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
