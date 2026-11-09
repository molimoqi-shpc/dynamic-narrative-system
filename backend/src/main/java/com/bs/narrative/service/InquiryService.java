package com.bs.narrative.service;

import com.bs.narrative.domain.GodProfile;
import com.bs.narrative.domain.NpcMemory;
import com.bs.narrative.domain.WorldBook;
import com.bs.narrative.repository.GodProfileRepository;
import com.bs.narrative.repository.PlayerProfileRepository;
import com.bs.narrative.exception.BusinessException;
import com.bs.narrative.exception.ErrorCode;
import com.bs.narrative.repository.NpcMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * V2 打听机制：限知博弈的核心实现。
 *
 * 【优化指导 6.3】从纯随机升级为属性驱动的加权采样：
 *   - 闻与慧影响公开候选的信息筛选和权重，不授予秘密访问权限
 *   - 低属性只能捞出 importance≤2 的表面记录
 *   - 采样不再是纯随机，而是 importance 加权（高重要度事件更可能被抽中）
 *   - 这把六维数值引擎与打听机制完美咬合
 *
 * 限知博弈的关键：
 *   - 两个角色在同一地点可能知道不同的事
 *   - NPC A 可能打听到了 NPC B 曾经背刺过某人，但 NPC B 不知道 A 知道
 *   - 玩家打听到的信息写入世界书，成为新的"已知事件"
 */
@Service
public class InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryService.class);

    private final WorldBookService worldBookService;
    private final NpcMemoryRepository memoryRepo;
    private final GodProfileRepository godRepo;
    private final PlayerProfileRepository playerRepo;
    private final SessionStateService stateService;
    private final KnowledgeService knowledge;
    private final WorldTurnService worldTurns;

    public InquiryService(WorldBookService worldBookService,
                          NpcMemoryRepository memoryRepo,
                          GodProfileRepository godRepo,
                          PlayerProfileRepository playerRepo, SessionStateService stateService, KnowledgeService knowledge, WorldTurnService worldTurns) {
        this.worldTurns=worldTurns;
        this.knowledge=knowledge;
        this.worldBookService = worldBookService;
        this.memoryRepo = memoryRepo;
        this.godRepo = godRepo;
        this.playerRepo = playerRepo;
        this.stateService=stateService;
    }

    /** Resolve the HTTP account ID before querying role attributes and memories. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String processPlayerInquiry(String sessionId, String playerId, int coordX, int coordY) {
        stateService.begin(sessionId,playerId);
        var player = playerRepo.findById(playerId).orElseThrow(() ->
                new BusinessException(ErrorCode.PLAYER_NOT_FOUND, "玩家不存在：" + playerId));
        var turn=worldTurns.advance(sessionId,stateService.nextRound(sessionId),player.getCurrentSceneId(),player.getGodId());
        String result=processInquiry(sessionId, player.getGodId(), coordX, coordY);
        if(!turn.events().isEmpty())result+="\n眼前的变化：\n"+turn.events().stream().map(WorldTurnService.VisibleEvent::text).collect(java.util.stream.Collectors.joining("\n"));
        stateService.complete(sessionId,"打听");
        return result;
    }

    /**
     * 角色在当前地点打听 → 属性驱动的加权采样获取世界书信息。
     *
     * @param sessionId  会话ID
     * @param godId      打听者角色ID
     * @param coordX     当前坐标X
     * @param coordY     当前坐标Y
     * @return 打听到的信息文本（用于注入角色 API 上下文）
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String processInquiry(String sessionId, String godId, int coordX, int coordY) {
        // 0) 读取打听者的"闻"+"慧"属性（6.3 属性驱动）
        int wenStat = 0;
        int huiStat = 0;
        int zhiStat = 0;
        GodProfile god = godRepo.findById(godId).orElse(null);
        if (god != null) {
            var actual=com.bs.narrative.engine.StatsCalculator.calcActualStats(god);
            wenStat=actual.actualWen();huiStat=actual.actualHui();zhiStat=actual.actualZhi();
        } else {
            log.warn("[Inquiry] 找不到角色 god={}，按 0 属性处理", godId);
        }

        // 1) 属性驱动的加权采样（6.3 主路径）
        List<WorldBook> revealed = worldBookService.queryForInquiryWeighted(
                sessionId, coordX, coordY, wenStat, huiStat).stream()
                // Asking again is not new intelligence about one's own previous inquiry.
                // Other people's inquiries remain observable social events; sampling formulas are unchanged.
                .filter(w->!("INQUIRY".equals(w.getEventType()) && w.getActorIds()!=null && w.getActorIds().contains(godId))).toList();
        if (revealed.isEmpty()) {
            log.info("[Inquiry] 无可打听信息 sessionId={} god={} coord=({},{}) wen={} hui={}",
                    sessionId, godId, coordX, coordY, wenStat, huiStat);
            return "你四处打听，但此地似乎没有值得注意的传闻。";
        }

        // 2) 写入 npc_memory（每个打听到的事件一条记录）
        int newlyLearned=0;
        for (WorldBook w : revealed) {
            knowledge.rememberInquiry(sessionId,godId,w);
            if(memoryRepo.existsBySessionIdAndGodIdAndWorldEntryIdAndMemoryType(sessionId,godId,w.getEntryId(),"INQUIRED"))continue;
            newlyLearned++;
            NpcMemory mem = new NpcMemory();
            mem.setSessionId(sessionId);
            mem.setGodId(godId);
            mem.setWorldEntryId(w.getEntryId());
            mem.setMemoryType("INQUIRED");
            mem.setMemoryContent(formatEvent(w));
            // 打听来的信息准确度受"慧"影响：慧高则准确度高
            mem.setAccuracy(com.bs.narrative.engine.StatsCalculator.inquiryAccuracy(zhiStat,huiStat));
            memoryRepo.save(mem);
        }

        // 3) 写世界书 INQUIRY 事件
        int roundNo = worldBookService.nextRoundNo(sessionId);
        worldBookService.addEvent(
                sessionId, roundNo, null,
                coordX, coordY, revealed.get(0).getLocationName(),
                "INQUIRY",
                List.of(godId),
                (god!=null && god.getGodName()!=null?god.getGodName():"某位行者") + "在" + revealed.get(0).getLocationName() + "打听消息",
                "核对了"+revealed.size()+"条信息，新增"+newlyLearned+"条记忆（闻=" + wenStat + "慧=" + huiStat + "）",
                4);

        // 4) 格式化为可读文本
        StringBuilder sb = new StringBuilder();
        sb.append("你在此地打听到的消息：\n");
        for (WorldBook w : revealed) {
            sb.append(formatEvent(w)).append("\n");
        }

        log.info("[Inquiry] 打听完成 sessionId={} god={} coord=({},{}) revealed={} wen={} hui={}",
                sessionId, godId, coordX, coordY, revealed.size(), wenStat, huiStat);
        return sb.toString();
    }

    /**
     * 获取角色已知的所有记忆（亲历 + 打听 + 被告知）。
     * 用于注入角色 API 上下文。
     */
    @Transactional(readOnly = true)
    public String getKnownInfo(String sessionId, String godId) {
        // 先查 npc_memory（个人记忆）
        List<NpcMemory> memories = memoryRepo.findBySessionIdAndGodIdAndMemoryTypeOrderByCreatedAtDesc(
                sessionId, godId, "INQUIRED",
                org.springframework.data.domain.PageRequest.of(0, 10));

        // 再查 world_book 中角色亲历的事件
        List<WorldBook> personal = worldBookService.queryByCharacter(sessionId, godId);

        StringBuilder sb = new StringBuilder();
        if (!personal.isEmpty()) {
            sb.append("【你亲历的事件】\n");
            for (WorldBook w : personal) {
                if ("ACTION".equals(w.getEventType())) continue; // Whole-turn aggregates are not individual hearing evidence.
                sb.append(formatEvent(w)).append("\n");
            }
        }
        if (!memories.isEmpty()) {
            sb.append("【你打听到的消息】\n");
            for (NpcMemory m : memories) {
                sb.append(m.getMemoryContent()).append("\n");
            }
        }
        return sb.toString();
    }

    /** 格式化世界书事件为可读文本。 */
    private String formatEvent(WorldBook w) {
        StringBuilder sb = new StringBuilder();
        sb.append("【回合").append(w.getRoundNo()).append("】");
        if (w.getStoryTime() != null && !w.getStoryTime().isBlank()) {
            sb.append(w.getStoryTime()).append(" ");
        }
        sb.append(w.getLocationName()).append("：");
        sb.append(w.getEventDescription());
        // 6.3 标记秘密事件
        if (Boolean.TRUE.equals(w.getIsSecret())) {
            sb.append("（秘闻）");
        }
        return sb.toString();
    }
}
