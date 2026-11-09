package com.bs.narrative.service;

import com.bs.narrative.domain.PlayerMemory;
import com.bs.narrative.repository.PlayerMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * V2 玩家记忆系统（Phase 9）。
 *
 * 三层记忆架构：
 *   ROUND  = 回合短记忆：每回合落库一条，Pass2 注入 Top3（按重要性）
 *   SCENE  = 场景长记忆：每场景聚合摘要（每 10 轮异步更新，本版本暂不实现异步，只接 SCENE 注入）
 *   GLOBAL = 跨场景全局记忆：玩家关键决定/事件（按 importance 降序注入）
 *
 * 设计哲学（对应论文 §五 · 记忆系统）：
 *   - 玩家"记得"什么 ≠ 系统"知道"什么：玩家可能忘记细节、记错事实
 *   - 短记忆精准但有限：Top3 ROUND 记忆覆盖最近回合
 *   - 长记忆模糊但广阔：SCENE/GLOBAL 记忆提供跨场景上下文
 *   - importance 1-10：LLM 生成时打分，注入时按分排序
 */
@Service
public class PlayerMemoryService {

    private static final Logger log = LoggerFactory.getLogger(PlayerMemoryService.class);

    /** ROUND 短记忆注入上限。 */
    private static final int ROUND_TOP_N = 3;
    /** GLOBAL 全局记忆注入上限。 */
    private static final int GLOBAL_TOP_N = 5;

    private final PlayerMemoryRepository memRepo;

    public PlayerMemoryService(PlayerMemoryRepository memRepo) {
        this.memRepo = memRepo;
    }

    // ==================== 落库 ====================

    /**
     * 落库一条 ROUND 短记忆（每回合调用）。
     *
     * @param refId       关联 ID（interaction_log.log_id）
     * @param content     记忆内容（玩家动作 + 结果摘要）
     * @param importance  重要性 1-10
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PlayerMemory recordRoundMemory(String playerId, String sessionId,
                                           String refId, String content, int importance) {
        PlayerMemory m = new PlayerMemory();
        m.setPlayerId(playerId);
        m.setSessionId(sessionId);
        m.setScope("ROUND");
        m.setRefId(refId);
        m.setContent(content);
        m.setImportance(Math.max(1, Math.min(10, importance)));
        m.setCreatedAt(LocalDateTime.now());
        PlayerMemory saved = memRepo.save(m);
        log.debug("[Memory] ROUND 落库 sessionId={} refId={} importance={}",
                sessionId, refId, saved.getImportance());
        return saved;
    }

    /**
     * 落库或更新一条 SCENE 长记忆（场景级聚合）。
     * 若该场景已有 SCENE 记忆，更新 content；否则新建。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PlayerMemory upsertSceneMemory(String playerId, String sessionId,
                                           String sceneId, String content, int importance) {
        List<PlayerMemory> existing = memRepo
                .findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(
                        playerId, sessionId, "SCENE", PageRequest.of(0, 50));
        Optional<PlayerMemory> opt = existing.stream()
                .filter(m -> sceneId.equals(m.getRefId()))
                .findFirst();
        PlayerMemory m;
        if (opt.isPresent()) {
            m = opt.get();
            m.setContent(content);
            m.setImportance(Math.max(m.getImportance() == null ? 1 : m.getImportance(), importance));
            m.setCreatedAt(LocalDateTime.now());
        } else {
            m = new PlayerMemory();
            m.setPlayerId(playerId);
            m.setSessionId(sessionId);
            m.setScope("SCENE");
            m.setRefId(sceneId);
            m.setContent(content);
            m.setImportance(Math.max(1, Math.min(10, importance)));
            m.setCreatedAt(LocalDateTime.now());
        }
        return memRepo.save(m);
    }

    /**
     * 落库一条 GLOBAL 全局记忆（跨场景关键事件）。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PlayerMemory recordGlobalMemory(String playerId, String sessionId,
                                            String refId, String content, int importance) {
        PlayerMemory m = new PlayerMemory();
        m.setPlayerId(playerId);
        m.setSessionId(sessionId);
        m.setScope("GLOBAL");
        m.setRefId(refId);
        m.setContent(content);
        m.setImportance(Math.max(1, Math.min(10, importance)));
        m.setCreatedAt(LocalDateTime.now());
        return memRepo.save(m);
    }

    // ==================== 注入 ====================

    /**
     * 构建 Pass2 Prompt 注入的记忆上下文（按论文 §五 记忆系统设计）。
     *
     * @return 格式化的记忆段落，可直接拼入 Pass2 user prompt
     */
    @Transactional(readOnly = true)
    public String buildContextForPrompt(String playerId, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("【玩家记忆】\n");

        // ROUND Top3 短记忆
        List<PlayerMemory> rounds = memRepo.findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(
                playerId, sessionId, "ROUND", PageRequest.of(0, ROUND_TOP_N));
        if (rounds.isEmpty()) {
            sb.append("近期回合：无\n");
        } else {
            sb.append("近期回合：\n");
            for (PlayerMemory m : rounds) {
                sb.append("  - ").append(truncate(m.getContent(), 100)).append("\n");
            }
        }

        // SCENE 当前场景长记忆
        List<PlayerMemory> scenes = memRepo.findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(
                playerId, sessionId, "SCENE", PageRequest.of(0, 3));
        if (!scenes.isEmpty()) {
            sb.append("场景印象：\n");
            for (PlayerMemory m : scenes) {
                sb.append("  - [").append(m.getRefId()).append("] ")
                        .append(truncate(m.getContent(), 120)).append("\n");
            }
        }

        // GLOBAL Top5 全局记忆
        List<PlayerMemory> globals = memRepo.findByPlayerIdAndSessionIdOrderByImportanceDescCreatedAtDesc(
                playerId, sessionId);
        if (!globals.isEmpty()) {
            sb.append("关键事件：\n");
            globals.stream()
                    .filter(m -> "GLOBAL".equals(m.getScope()))
                    .limit(GLOBAL_TOP_N)
                    .forEach(m -> sb.append("  - ").append(truncate(m.getContent(), 100)).append("\n"));
        }

        return sb.toString();
    }

    /** Deterministic factual summaries: no extra LLM call and no invented facts. */
    @Transactional
    public void consolidate(String playerId,String sessionId,String sceneId,int round,String outcome) {
        if (round % 10 == 0) {
            var recent=memRepo.findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(
                    playerId,sessionId,"ROUND",PageRequest.of(0,10));
            String summary=recent.stream().map(PlayerMemory::getContent).filter(Objects::nonNull)
                    .reduce("",(a,b)->a+"\n"+b);
            upsertSceneMemory(playerId,sessionId,sceneId,truncate(summary,3000),6);
        }
        if (Set.of("EXPLOITATION_RESCUE","THIRD_PARTY_INTERVENE").contains(outcome)) {
            var recent=memRepo.findByPlayerIdAndSessionIdAndScopeOrderByCreatedAtDesc(
                    playerId,sessionId,"ROUND",PageRequest.of(0,1));
            if(!recent.isEmpty()) recordGlobalMemory(playerId,sessionId,recent.get(0).getRefId(),recent.get(0).getContent(),8);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
