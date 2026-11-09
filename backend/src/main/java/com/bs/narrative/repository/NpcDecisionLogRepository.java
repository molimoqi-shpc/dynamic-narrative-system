package com.bs.narrative.repository;

import com.bs.narrative.domain.NpcDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * NPC 群戏决策日志 Repository（按 session 隔离，论文级追溯用）。
 */
public interface NpcDecisionLogRepository extends JpaRepository<NpcDecisionLog, Long> {

    /** 按会话查全部 NPC 决策（按时间倒序，论文截图用）。 */
    List<NpcDecisionLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    /** 按会话+回合 log_id 查本回合所有 NPC 决策（前端天眼面板"本回合群戏"用）。 */
    List<NpcDecisionLog> findBySessionIdAndLogIdOrderByGodIdAsc(String sessionId, Long logId);

    /** 按会话+NPC 查该 NPC 的全部决策轨迹（角色行为分析用）。 */
    List<NpcDecisionLog> findBySessionIdAndGodIdOrderByCreatedAtDesc(String sessionId, String godId);

    /** 按会话统计 NPC 说谎次数（论文 §五.5 数据分析用）。 */
    long countBySessionIdAndIsLyingTrue(String sessionId);

    /**
     * 【优化指导 6.4】删除某 session 中 log_id > 指定 log_id 的所有 NPC 决策。
     * 读档到第 N 回合时调用（N 回合对应的 log_id 之后的全删）。
     */
    @Modifying
    @Query("DELETE FROM NpcDecisionLog n WHERE n.sessionId = :sid AND n.logId > :logId")
    int deleteBySessionIdAndLogIdGreaterThan(@Param("sid") String sessionId, @Param("logId") Long logId);
}
