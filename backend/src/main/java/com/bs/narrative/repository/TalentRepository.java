package com.bs.narrative.repository;

import com.bs.narrative.domain.Talent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2 天赋 Repository。
 */
public interface TalentRepository extends JpaRepository<Talent, Long> {
    java.util.List<Talent> findBySessionId(String sessionId);

    /** 查某 session 的所有天赋（供玩家选择）。 */
    List<Talent> findBySessionIdOrderByPowerLevelDesc(String sessionId);

    /** 查某 session 某角色选中的天赋。 */
    List<Talent> findBySessionIdAndGodId(String sessionId, String godId);
}
