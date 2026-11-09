package com.bs.narrative.repository;

import com.bs.narrative.domain.NpcMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * V2 NPC 个人记忆 Repository。
 */
public interface NpcMemoryRepository extends JpaRepository<NpcMemory, Long> {
    boolean existsBySessionIdAndGodIdAndWorldEntryIdAndMemoryType(String sessionId,String godId,Long worldEntryId,String memoryType);


    /** 查某 NPC 在某 session 的所有记忆（按时间倒序）。 */
    List<NpcMemory> findBySessionIdAndGodIdOrderByCreatedAtDesc(
            String sessionId, String godId, Pageable pageable);

    /** 查某 NPC 某类型的记忆。 */
    List<NpcMemory> findBySessionIdAndGodIdAndMemoryTypeOrderByCreatedAtDesc(
            String sessionId, String godId, String memoryType);

    /** 查某 NPC 某类型的记忆（带分页限制）。 */
    List<NpcMemory> findBySessionIdAndGodIdAndMemoryTypeOrderByCreatedAtDesc(
            String sessionId, String godId, String memoryType, Pageable pageable);
}
