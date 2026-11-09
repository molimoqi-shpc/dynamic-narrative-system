import { defineStore } from 'pinia'

/** 视图模式：入梦人(限知) / 世界人(全知天眼)。spec FR-E 与 AC-E1/E2。 */
export const useViewModeStore = defineStore('viewMode', {
  state: () => ({
    /** 'DREAMER' | 'OMNISCIENT' */
    mode: ['DREAMER','OMNISCIENT'].includes(localStorage.getItem('bs_viewmode')) ? localStorage.getItem('bs_viewmode') : 'DREAMER'
  }),
  getters: {
    isOmniscient: s => s.mode === 'OMNISCIENT'
  },
  actions: {
    setMode(m) {
      if (!["DREAMER","OMNISCIENT"].includes(m)) return
      this.mode = m
      localStorage.setItem('bs_viewmode', m)
    },
    toggle() {
      this.setMode(this.isOmniscient ? 'DREAMER' : 'OMNISCIENT')
    }
  }
})
