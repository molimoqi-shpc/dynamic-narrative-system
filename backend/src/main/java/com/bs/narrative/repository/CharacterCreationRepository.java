package com.bs.narrative.repository;

import com.bs.narrative.domain.CharacterCreation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * V2 角色创建流程 Repository。
 */
public interface CharacterCreationRepository extends JpaRepository<CharacterCreation, Long> {

    /** 查某 session + 玩家的角色创建记录。 */
    Optional<CharacterCreation> findBySessionIdAndPlayerId(String sessionId, String playerId);
}
