package com.bs.narrative.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2 角色创建流程表：三问题→天赋→故事开头。
 *
 * status 流转：QUESTIONS → TALENT_GENERATED → STORY_GENERATED → COMPLETED
 */
@Entity
@Table(name = "character_creation",
       indexes = {
               @Index(name = "uk_session_player", columnList = "session_id, player_id", unique = true)
       })
@Getter
@Setter
public class CharacterCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "creation_id")
    private Long creationId;

    @Column(name = "session_id", length = 32, nullable = false)
    private String sessionId;

    @Column(name = "player_id", length = 32, nullable = false)
    private String playerId;

    /** DREAMER=入梦(限知)/OMNISCIENT=知梦(全知)。 */
    @Column(name = "view_mode", length = 16, nullable = false)
    private String viewMode = "DREAMER";

    @Column(name = "question_1", nullable = false, columnDefinition = "TEXT")
    private String question1;

    @Column(name = "answer_1", columnDefinition = "TEXT")
    private String answer1;

    @Column(name = "question_2", nullable = false, columnDefinition = "TEXT")
    private String question2;

    @Column(name = "answer_2", columnDefinition = "TEXT")
    private String answer2;

    @Column(name = "question_3", nullable = false, columnDefinition = "TEXT")
    private String question3;

    @Column(name = "answer_3", columnDefinition = "TEXT")
    private String answer3;

    /** LLM生成的性格标签数组。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "personality_tags", columnDefinition = "JSON")
    private List<String> personalityTags;

    /** 玩家选中的天赋ID。 */
    @Column(name = "selected_talent_id")
    private Long selectedTalentId;

    /** LLM编写的从出生到第一次大事件的故事。 */
    @Column(name = "birth_story", columnDefinition = "TEXT")
    private String birthStory;

    /** QUESTIONS/TALENT_GENERATED/STORY_GENERATED/COMPLETED。 */
    @Column(name = "status", length = 24, nullable = false)
    private String status = "QUESTIONS";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
