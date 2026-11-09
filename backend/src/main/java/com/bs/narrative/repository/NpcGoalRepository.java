package com.bs.narrative.repository;

import com.bs.narrative.domain.NpcGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2 NPC 目标 Repository。
 */
public interface NpcGoalRepository extends JpaRepository<NpcGoal, Long> {

    /** 查某 NPC 在某 session 的所有 ACTIVE 目标。 */
    List<NpcGoal> findBySessionIdAndGodIdAndStatusOrderByPriorityDesc(
            String sessionId, String godId, String status);

    List<NpcGoal> findBySessionIdAndGodIdOrderByGoalIdDesc(String sessionId, String godId);

    /** 查某 session 所有 NPC 的 ACTIVE 目标。 */
    List<NpcGoal> findBySessionIdAndStatusOrderByPriorityDesc(String sessionId, String status);
}
