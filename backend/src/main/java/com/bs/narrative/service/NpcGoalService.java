package com.bs.narrative.service;

import com.bs.narrative.domain.NpcGoal;
import com.bs.narrative.repository.NpcGoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * V2 NPC 目标管理服务。
 *
 * 每个 NPC 有自己的秘密目标，驱动主动行为。
 * 目标可以由 LLM 生成（角色出现时）或手动设置。
 */
@Service
public class NpcGoalService {

    public static final String BOOTSTRAP = "【待确立个人目标】依据自身已知处境选择具体目的，说明完成条件和愿承担的代价；不得虚构过去经历，不以玩家为任务中心。";

    private static final Logger log = LoggerFactory.getLogger(NpcGoalService.class);

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;
    private final NpcGoalRepository goalRepo;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public NpcGoalService(NpcGoalRepository goalRepo,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.goalRepo = goalRepo;
    }

    /**
     * 为 NPC 设置目标。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public NpcGoal setGoal(String sessionId, String godId, String description, int priority) {
        NpcGoal goal = new NpcGoal();
        goal.setSessionId(sessionId);
        goal.setGodId(godId);
        goal.setGoalDescription(description);
        goal.setPriority(priority);
        goal.setStatus("ACTIVE");
        return goalRepo.save(goal);
    }

    /** A purpose is a private choice, not proof of past facts or objective completion. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean applyPurposeChoice(String sid,String role,JsonNode update){
        if(update==null || !update.isObject())return false;
        String mode=update.path("mode").asText(),purpose=update.path("purpose").asText();
        if(!java.util.Set.of("REPLACE","ABANDON").contains(mode) || "REPLACE".equals(mode) && (purpose.isBlank() || purpose.length()>1200))return false;
        var previous=getActiveGoals(sid,role);
        if("REPLACE".equals(mode) && previous.size()==1 && purpose.equals(previous.get(0).getGoalDescription()))return true;
        for(var goal:previous){goal.setStatus("ABANDONED");goalRepo.save(goal);}
        if("REPLACE".equals(mode))setGoal(sid,role,purpose,6);
        return true;
    }

    /**
     * 获取 NPC 的所有 ACTIVE 目标。
     */
    @Transactional(readOnly = true)
    public List<NpcGoal> getActiveGoals(String sessionId, String godId) {
        return goalRepo.findBySessionIdAndGodIdAndStatusOrderByPriorityDesc(sessionId, godId, "ACTIVE");
    }

    /**
     * 获取目标描述列表（用于 Prompt 注入）。
     */
    @Transactional(readOnly = true)
    public List<String> getGoalDescriptions(String sessionId, String godId) {
        var result=new ArrayList<String>();
        for(var g:getActiveGoals(sessionId,godId)) {
            var data=GoalChecks.read(g.getGoalDescription());
            result.add("· "+GoalChecks.text(g.getGoalDescription())+"（优先级"+g.getPriority()+"）"
                    +(data==null?"；尚无机器可验收条件，不得自行宣称完成。":"；服务器反馈："+data.path("feedback").asText("尚未满足完成条件")));
        }
        goalRepo.findBySessionIdAndGodIdOrderByGoalIdDesc(sessionId,godId).stream()
                .filter(g->java.util.Set.of("COMPLETED","FAILED").contains(g.getStatus())).limit(2)
                .forEach(g->{var data=GoalChecks.read(g.getGoalDescription());result.add("【过往目的·"+g.getStatus()+"，不再执行】"+GoalChecks.text(g.getGoalDescription())+(data==null?"":"；结局："+data.path("feedback").asText()));});
        return result;
    }

    /**
     * 初始化预设 NPC 目标（从旧种子数据迁移）。
     * 当 NPC 首次出现在新 session 中时调用。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void initPresetGoals(String sessionId, String godId, String godName) {
        // 避免重复初始化
        List<NpcGoal> existing = goalRepo.findBySessionIdAndGodIdAndStatusOrderByPriorityDesc(
                sessionId, godId, "ACTIVE");
        if (!existing.isEmpty()) {
            return;
        }

        // No predetermined villain/quest assignment: establish a grounded goal in the first decision.
        setGoal(sessionId, godId, BOOTSTRAP, 5);
        log.info("[NpcGoal] 等待角色依据已知处境确立目标 sessionId={} god={}", sessionId, godId);
    }
    /** Initial ambitions are proposals, not new historical facts or completed quests. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean adoptGroundedGoal(String sid, String godId, String goal, String basis,
                                     String success, String tradeoff, String knownContext) {
        var active = getActiveGoals(sid, godId);
        if (active.isEmpty() || active.stream().anyMatch(g -> !isPlaceholder(g.getGoalDescription()))) return false;
        if (!validProposal(goal, basis, success, tradeoff, knownContext)) return false;
        for (var old : active) { old.setStatus("ABANDONED"); goalRepo.save(old); }
        setGoal(sid, godId, goal + "\n已知依据：" + basis + "\n完成条件：" + success + "\n取舍：" + tradeoff, 6);
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean adoptGroundedGoal(String sid,String id,String goal,String basis,String success,String tradeoff,
                                     String context,JsonNode checks) {
        boolean typed=checks!=null && !checks.isNull();
        if(typed && !GoalChecks.valid(jdbc,sid,id,checks,success,context))return false;
        if(!adoptGroundedGoal(sid,id,goal,basis,success,tradeoff,context))return false;
        if(typed) {
            var g=getActiveGoals(sid,id).get(0);var data=GoalChecks.JSON.createObjectNode();
            data.set("checks",checks.deepCopy());data.put("createdTick",GoalChecks.tick(jdbc,sid));
            data.put("feedback","执行中：尚未满足全部完成条件。");
            g.setGoalDescription(GoalChecks.store(g.getGoalDescription(),data));goalRepo.save(g);
        }
        return true;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void refreshSession(String sid) {
        for(String id:goalRepo.findBySessionIdAndStatusOrderByPriorityDesc(sid,"ACTIVE").stream()
                .map(NpcGoal::getGodId).distinct().toList())refreshProgress(sid,id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void refreshProgress(String sid,String id) {
        if(em!=null)em.flush();
        var actor=jdbc.queryForList("SELECT god_id FROM god_profile WHERE session_id=? AND god_id=?",String.class,sid,id);
        if(actor.isEmpty())return;
        for(var g:getActiveGoals(sid,id)) {
            if(isPlaceholder(g.getGoalDescription()))continue;
            var data=GoalChecks.read(g.getGoalDescription());
            if(data==null || GoalChecks.canonical(data.path("checks")).isBlank())continue;
            boolean done=true;var missing=new ArrayList<String>();
            for(var c:data.path("checks"))if(!GoalChecks.matches(jdbc,sid,id,c)) {
                done=false;missing.add(GoalChecks.canonical(GoalChecks.JSON.createArrayNode().add(c)));
            }
            String feedback;
            if(done) {g.setStatus("COMPLETED");feedback="已完成：当前实际状态满足全部验收条件。";}
            else {
                feedback="执行中，未满足："+String.join("、",missing)+"。";
                var plans=jdbc.queryForList("SELECT status,result FROM npc_plan WHERE session_id=? AND god_id=? AND due_tick>=? ORDER BY plan_id DESC LIMIT 1",sid,id,data.path("createdTick").asLong());
                if(!plans.isEmpty() && java.util.Set.of("BLOCKED","CANCELLED").contains(plans.get(0).get("status")))
                    feedback="上一步受阻："+plans.get(0).get("result")+"；"+feedback+"请核对条件并换可执行策略，不重复无效路线，也不重置未到期计划。";
            }
            data.put("feedback",feedback);data.put("checkedTick",GoalChecks.tick(jdbc,sid));
            g.setGoalDescription(GoalChecks.store(g.getGoalDescription(),data));goalRepo.save(g);
        }
    }

    static boolean isPlaceholder(String text) {
        return BOOTSTRAP.equals(text) || "观察局势，寻找机会".equals(text);
    }

    static boolean validProposal(String goal, String basis, String success, String tradeoff, String context) {
        if (context == null || goal == null || basis == null || success == null || tradeoff == null) return false;
        if (goal.length() < 8 || goal.length() > 120 || basis.length() < 4 || basis.length() > 100
                || success.length() < 6 || success.length() > 100 || tradeoff.length() < 6 || tradeoff.length() > 100) return false;
        if (!context.contains(basis)) return false;
        for(String value:List.of(goal,basis,success,tradeoff))if(value.contains("【服务器验收数据】"))return false;
        return !java.util.Set.of("观察局势，寻找机会", "变得更强，掌握权力", "帮助玩家完成所有任务").contains(goal)
                && !success.equals(goal) && !tradeoff.equals(goal);
    }

}
