package com.bs.narrative.repository;

import com.bs.narrative.domain.PlayerObjective;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 玩家目标进度 Repository（按 session 隔离，防坑规则3）。
 */
public interface PlayerObjectiveRepository extends JpaRepository<PlayerObjective, Long> {

    /** 查某玩家在某 session 下的所有目标（按状态/进度排序）。 */
    List<PlayerObjective> findByPlayerIdAndSessionId(String playerId, String sessionId);

    /** 查特定目标（用于场景连接门禁判定：required_objective 是否已 COMPLETED）。 */
    Optional<PlayerObjective> findByPlayerIdAndSessionIdAndObjectiveId(
            String playerId, String sessionId, String objectiveId);
}
