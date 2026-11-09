package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * V2 NPC 个人记忆表：信息不对称的实现基础。
 *
 * 三级记忆：
 *   PERSONAL  = 亲历（accuracy=100，完整记录）
 *   INQUIRED  = 打听（accuracy=60-80，可能不完整）
 *   TOLD      = 被告知（accuracy=40-60，可能有误）
 */
@Entity
@Table(name = "npc_memory",
       indexes = {
               @Index(name = "idx_session_god",      columnList = "session_id, god_id, created_at"),
               @Index(name = "idx_session_god_type",  columnList = "session_id, god_id, memory_type")
       })
@Getter
@Setter
public class NpcMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "memory_id")
    private Long memoryId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    @Column(name = "god_id", length = 32, nullable = false)
    private String godId;

    /** 关联的世界书记录ID（可为空，表示NPC独立记忆）。 */
    @Column(name = "world_entry_id")
    private Long worldEntryId;

    /** PERSONAL=亲历/INQUIRED=打听/TOLD=被告知。 */
    @Column(name = "memory_type", length = 16, nullable = false)
    private String memoryType;

    /** NPC记住的内容（可能不完整或有误）。 */
    @Column(name = "memory_content", nullable = false, columnDefinition = "TEXT")
    private String memoryContent;

    /** 0-100，记忆准确度。 */
    @Column(name = "accuracy")
    private Integer accuracy = 100;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
