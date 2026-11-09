package com.bs.narrative.repository;

import com.bs.narrative.domain.PlayerMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 玩家三层记忆 Repository（按 session 隔离 + scope 分层查询）。
 * B2 阶段 Pass2 Prompt 注入 + 异步摘要更新会用到。
 */
public interface PlayerMemoryRepository extends JpaRepository<PlayerMemory, Long> {

    /** 查某玩家某 session 的某层记忆，按 created_at 降序（Pageable 限 TopN）。 */
    List<PlayerMemory> findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(
            String playerId, String sessionId, String scope, Pageable pageable);

    /** 查某玩家某 session 的所有记忆，按重要性降序（用于全局记忆注入）。 */
    List<PlayerMemory> findByPlayerIdAndSessionIdOrderByImportanceDescCreatedAtDesc(
            String playerId, String sessionId);
}
