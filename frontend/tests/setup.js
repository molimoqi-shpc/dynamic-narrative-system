import {vi} from 'vitest'
window.matchMedia=vi.fn(()=>({matches:false,addEventListener:vi.fn(),removeEventListener:vi.fn()}))
global.ResizeObserver=class {observe(){} unobserve(){} disconnect(){}}
// jsdom has no scrolling implementation; mounted chapter tests still exercise real Vue updates.
HTMLElement.prototype.scrollTo=vi.fn()
// Unexpected browser transport is a test failure, never a game-model request.
global.fetch=vi.fn(()=>{throw new Error('Real network is forbidden in joint offline tests')})
