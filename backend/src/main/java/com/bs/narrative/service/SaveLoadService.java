package com.bs.narrative.service;
import com.bs.narrative.repository.*;
import com.bs.narrative.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Full-state checkpoints replace incomplete log truncation. */
@Service
public class SaveLoadService {
    private final SessionStateService state;
    private final GameSessionRepository sessions;
    private final GodProfileRepository gods;
    public SaveLoadService(SessionStateService state,GameSessionRepository sessions,GodProfileRepository gods) {
        this.state=state;this.sessions=sessions;this.gods=gods;
    }
    @Transactional
    public List<Map<String,Object>> listSavePoints(String sid) {
        state.ensureCheckpoint(sid); return state.list(sid);
    }
    @Transactional(readOnly=true)
    public int getCurrentRound(String sid) {
        return sessions.findById(sid).map(s->s.getCurrentRound()==null?0:s.getCurrentRound()).orElse(0);
    }
    @Transactional
    public Map<String,Object> loadToRound(String sid,int round) { return state.restore(sid,round); }
    @Transactional(readOnly=true)
    public Map<String,Object> getSnapshotAfterRewind(String sid,String pid) {
        var session=sessions.findById(sid).orElseThrow(()->new BusinessException(ErrorCode.PARAM_INVALID,"会话不存在"));
        if(!pid.equals(session.getPlayerId())) throw new BusinessException(ErrorCode.PARAM_INVALID,"会话归属不匹配");
        var god=gods.findById(session.getPlayerGodId()).orElseThrow();
        return Map.of("sessionId",sid,"currentRound",session.getCurrentRound(),"player",
                Map.of("playerId",pid,"godId",god.getGodId(),"currentSceneId",session.getCurrentSceneId(),
                        "inventory",god.getInventory(),"stats",Map.of("zhi",god.getBaseZhi(),"hui",god.getBaseHui(),
                                "dou",god.getBaseDou(),"wen",god.getBaseWen(),"ben",god.getBaseBen(),"gui",god.getBaseGui())));
    }
}
