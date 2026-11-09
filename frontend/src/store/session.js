import { defineStore } from 'pinia'

/**
 * 会话级状态仓（Phase 7 Step 1）。
 * 生命周期与一次游戏对齐——浏览器关闭即失效，符合"毕设演示防串扰"原则。
 * 标签页仅记住所选存档ID；刷新后从服务器恢复，不缓存正文、库存或时间。
 */
export const useSessionStore = defineStore('session', {
  state: () => ({
    // === 当前活跃会话 ===
    playerId: null,        // 玩家 ID（LoginView 占位登录拿 'p_demo' 或后续真实登录）
    sessionId: null,       // 当前会话 ID
    sceneId: null,         // 当前场景 ID
    playerGodId: null,     // 玩家作为神明的 godId（天眼面板区分玩家 vs NPC）
    status: null,          // 'CREATING' | 'PLAYING' | 'DEAD' | 'SUMMARIZED'
    archiveReady: null,    // false while a new world has not yet been authored
    inheritWorld: false,   // 重开时是否继承世诰
    prevSessionId: null,   // 上一周目的 sessionId（重开跨周目引用）
    branchEpoch: null,
    revision: 0,
    pendingOperation: null,
    sessionGeneration: 0,

    // === Phase 8 玩家目标系统 ===
    objectives: [],        // 当前所有目标列表

    // === Phase 9 记忆系统 ===
    memoryContext: '',     // Pass2 注入用的格式化记忆段

    // === Phase 10 NPC 群戏 ===
    lastNpcDecisions: [],    // 上回合 NPC 决策列表（前端徽章用）
    npcDecisionHistory: []  // 全部 NPC 决策追溯（论文截图用）
  }),
  getters: {
    canPlay: s => !!s.sessionId && ['PLAYING','DEAD','RETIRED','VICTORY'].includes(s.status),
    /** Phase 8：活跃目标数量。 */
    activeObjectiveCount: s => s.objectives.filter(o => o.status === 'ACTIVE').length,
    /** Phase 8：已完成目标数量。 */
    completedObjectiveCount: s => s.objectives.filter(o => o.status === 'COMPLETED').length,
    /** Phase 10：会话累计 NPC 说谎次数（论文分析用）。 */
    npcLyingCount: s => s.npcDecisionHistory.filter(d => d.isLying).length
  },
  actions: {
    /** 浅合并写入会话字段。 */
    setActiveSession(payload) {
      if(Object.hasOwn(payload,'sessionId') && payload.sessionId!==this.sessionId)this.archiveReady=null
      const changesIdentity=['playerId','sessionId'].some(key=>Object.hasOwn(payload,key) && payload[key]!==this[key])
      if(changesIdentity){this.sessionGeneration++;this.pendingOperation=null}
      Object.assign(this.$state, payload)
      if(this.playerId && this.sessionId && this.archiveReady!==false) {
        try {sessionStorage.setItem(`bs-selected-session:${this.playerId}`,this.sessionId)} catch { /* Storage denial does not prevent play. */ }
      }
    },
    rememberedSessionId() {
      if(!this.playerId)return null
      try{return sessionStorage.getItem(`bs-selected-session:${this.playerId}`)}catch{return null}
    },
    forgetSelectedSession() {
      if(this.playerId)try {sessionStorage.removeItem(`bs-selected-session:${this.playerId}`)}catch { /* No persistent selection available. */ }
    },
    setPendingOperation(value) {
      this.pendingOperation = value
      const key = `bs-operation:${this.playerId}:${this.sessionId}`
      try {
        if (value) sessionStorage.setItem(key, JSON.stringify(value))
        else sessionStorage.removeItem(key)
      } catch { /* The durable server operation and in-memory identity remain usable. */ }
    },
    restorePendingOperation() {
      try { this.pendingOperation = JSON.parse(sessionStorage.getItem(`bs-operation:${this.playerId}:${this.sessionId}`) || 'null') }
      catch { this.pendingOperation = null }
    },
    /** Phase 8：设置目标列表。 */
    setObjectives(list) {
      this.objectives = Array.isArray(list) ? list : []
    },
    /** Phase 9：设置记忆上下文。 */
    setMemoryContext(ctx) {
      this.memoryContext = typeof ctx === 'string' ? ctx : ''
    },
    /** Phase 10：设置上回合 NPC 决策列表。 */
    setLastNpcDecisions(list) {
      this.lastNpcDecisions = Array.isArray(list) ? list : []
    },
    /** Phase 10：设置 NPC 决策追溯历史。 */
    setNpcDecisionHistory(list) {
      this.npcDecisionHistory = Array.isArray(list) ? list : []
    },
    /** 清空当前会话（含 Phase 8-10 字段，防止跨周目串扰）。 */
    clearSession() {
      this.sessionGeneration++
      this.forgetSelectedSession()
      this.setPendingOperation(null)
      this.branchEpoch = null
      this.revision = 0
      this.playerId = null
      this.sessionId = null
      this.sceneId = null
      this.playerGodId = null
      this.status = null
      this.archiveReady = null
      this.inheritWorld = false
      this.prevSessionId = null
      this.objectives = []
      this.memoryContext = ''
      this.lastNpcDecisions = []
      this.npcDecisionHistory = []
    }
  }
})
