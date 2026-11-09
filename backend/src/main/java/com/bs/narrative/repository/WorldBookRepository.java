package com.bs.narrative.repository;

import com.bs.narrative.domain.WorldBook;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * V2 世界书 Repository。
 *
 * 查询场景：
 *   1) 注入 Prompt：查某 session 最近 N 条（按 round_no 降序）
 *   2) 按地点查询：打听机制用，查某 session 某坐标的所有事件
 *   3) 按角色查询：查某 session 某角色参与的所有事件
 *   4) 按重要度查询：#9 压缩用，查低重要度事件
 *   5) 6.4 事件溯源：按 round_no 删除（读档回溯用）
 */
public interface WorldBookRepository extends JpaRepository<WorldBook, Long> {

    /** 注入 Prompt 用：查某 session 最近 N 条（按 round_no 降序）。 */
    @Query("SELECT w FROM WorldBook w WHERE w.sessionId=:sid AND w.eventType NOT IN ('PLOT_EVENT','PLOT_THREAD') ORDER BY w.roundNo DESC")
    List<WorldBook> findBySessionIdOrderByRoundNoDesc(@Param("sid") String sessionId, Pageable pageable);

    /** 打听机制用：查某 session 某坐标的所有事件（按 round_no 升序）。 */
    @Query("SELECT w FROM WorldBook w WHERE w.sessionId=:sid AND w.coordX=:x AND w.coordY=:y AND w.eventType NOT IN ('PLOT_EVENT','PLOT_THREAD') ORDER BY w.roundNo ASC")
    List<WorldBook> findBySessionIdAndCoordXAndCoordYOrderByRoundNoAsc(
            @Param("sid") String sessionId, @Param("x") Integer coordX, @Param("y") Integer coordY);

    /** 按角色查询：查某 session 某角色参与的所有事件。 */
    @Query(value="SELECT w.* FROM world_book w WHERE w.session_id = :sid "
            + "AND JSON_CONTAINS(w.actor_ids, JSON_QUOTE(:godId)) = 1 "
            + "ORDER BY w.round_no DESC, w.entry_id DESC", nativeQuery=true)
    List<WorldBook> findBySessionIdAndActorContainingOrderByRoundNoDesc(
            @Param("sid") String sessionId, @Param("godId") String godId, Pageable pageable);

    /** 按事件类型查询。 */
    List<WorldBook> findBySessionIdAndEventTypeOrderByRoundNoDesc(
            String sessionId, String eventType);

    /** 查某 session 最大 round_no。 */
    @Query("SELECT MAX(w.roundNo) FROM WorldBook w WHERE w.sessionId = :sid")
    Integer findMaxRoundNo(@Param("sid") String sessionId);

    /**
     * 【优化指导 6.4】删除某 session 中 round_no > 指定回合的所有世界书记录。
     * 读档到第 N 回合时调用，实现轻量级时空回溯。
     */
    @Modifying
    @Query("DELETE FROM WorldBook w WHERE w.sessionId = :sid AND w.roundNo > :round")
    int deleteBySessionIdAndRoundNoGreaterThan(@Param("sid") String sessionId, @Param("round") Integer roundNo);
}
