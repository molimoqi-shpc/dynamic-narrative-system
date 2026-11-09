package com.bs.narrative.repository;

import com.bs.narrative.domain.GameSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * V2 游戏会话生命周期 Repository。
 */
public interface GameSessionRepository extends JpaRepository<GameSession, String> {

    /** 查某玩家的所有会话（按创建时间倒序）。 */
    List<GameSession> findByPlayerIdOrderByCreatedAtDesc(String playerId);

    /** 查某玩家的活跃会话。 */
    Optional<GameSession> findByPlayerIdAndStatus(String playerId, String status);
}
