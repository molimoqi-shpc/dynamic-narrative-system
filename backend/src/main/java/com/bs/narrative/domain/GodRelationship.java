package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * §五.2 神明动态关系网表。
 * 注意：有向关系 (source_id, target_id) 联合唯一，source→target 与 target→source 是两条独立行。
 */
@Entity
@Table(name = "god_relationship",
       uniqueConstraints = @UniqueConstraint(name = "uk_session_source_target",
                                             columnNames = {"session_id", "source_id", "target_id"}),
       indexes = @Index(name = "idx_session", columnList = "session_id")
)
@Getter
@Setter
public class GodRelationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "relation_id")
    private Long relationId;

    /** 防坑规则3：所属会话/周目ID，多人独立关系网隔离。 */
    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    @Column(name = "source_id", length = 32, nullable = false)
    private String sourceId;

    @Column(name = "target_id", length = 32, nullable = false)
    private String targetId;

    /** 0..100 */
    @Column(name = "trust_value")
    private Integer trustValue = 50;

    @Column(name = "is_allied")
    private Boolean allied = false;

    @Column(name = "is_backstabbed")
    private Boolean backstabbed = false;

    @Column(name = "note", length = 255)
    private String note;
}
