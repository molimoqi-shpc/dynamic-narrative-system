import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {installMotionPreferences,motionPreferences,loadMotion,setMotionMode,currentMotion,MOTION_KEY,normalizeMotion} from '../src/utils/motionPreferences'
import {createMenuTransition,menuTimings,menuRevealTimings} from '../src/utils/menuTransition'
let stop,media,storage,values
beforeEach(()=>{
 values=new Map();storage={getItem:k=>values.get(k),setItem:(k,v)=>values.set(k,v)}
 media={matches:false,addEventListener:vi.fn(),removeEventListener:vi.fn()}
 stop=installMotionPreferences({storage,media,root:document.body,events:window})
})
afterEach(()=>{stop();vi.useRealTimers();vi.restoreAllMocks()})
it('normalizes damaged preferences and restores a saved mode',()=>{
 expect(normalizeMotion('unknown')).toBe('FULL');expect(loadMotion({getItem:()=>'{broken'})).toBe('FULL')
 setMotionMode('LIGHT');expect(JSON.parse(values.get(MOTION_KEY))).toEqual({mode:'LIGHT'})
 expect(currentMotion()).toBe('LIGHT');expect(document.body.dataset.motion).toBe('LIGHT')
 stop();stop=installMotionPreferences({storage,media});expect(motionPreferences.mode).toBe('LIGHT')
})
it('system reduced motion overrides a complete presentation without discarding the user preference',()=>{
 setMotionMode('FULL');media.matches=true;media.addEventListener.mock.calls.at(-1)[1]()
 expect(currentMotion()).toBe('STATIC');expect(motionPreferences.mode).toBe('FULL');expect(document.body.dataset.motion).toBe('STATIC')
 media.matches=false;media.addEventListener.mock.calls.at(-1)[1]();expect(currentMotion()).toBe('FULL')
})
it('applies settings even when local persistence is denied',()=>{
 stop();stop=installMotionPreferences({storage:{getItem:()=>{throw Error('denied')},setItem:()=>{throw Error('denied')}},media})
 expect(()=>setMotionMode('STATIC')).not.toThrow();expect(currentMotion()).toBe('STATIC')
})
it('receives another tab preference change and releases the listener on cleanup',()=>{
 values.set(MOTION_KEY,JSON.stringify({mode:'LIGHT'}));window.dispatchEvent(new StorageEvent('storage',{key:MOTION_KEY}))
 expect(currentMotion()).toBe('LIGHT');stop();expect(media.removeEventListener).toHaveBeenCalledWith('change',expect.any(Function))
})
it('keeps the complete gear ceremony on repeated archive openings in full motion',async()=>{
 vi.useFakeTimers();const flow=createMenuTransition(),action=vi.fn()
 const first=flow.run('gears',action);expect(flow.state.reduced).toBe(false)
 await vi.advanceTimersByTimeAsync(menuTimings.gears+34+menuRevealTimings.gears);expect(await first).toBe(true)
 const repeated=flow.run('gears',action);expect(flow.state.reduced).toBe(false)
 await vi.advanceTimersByTimeAsync(menuTimings.gears-1);expect(action).toHaveBeenCalledTimes(1);expect(flow.state.phase).toBe('enter')
 await vi.advanceTimersByTimeAsync(35);expect(action).toHaveBeenCalledTimes(2);expect(flow.state.phase).toBe('leave');expect(flow.state.reduced).toBe(false)
 await vi.advanceTimersByTimeAsync(menuRevealTimings.gears-1);expect(flow.state.active).toBe(true)
 await vi.advanceTimersByTimeAsync(1);expect(await repeated).toBe(true);expect(flow.state.active).toBe(false)
})
it('a static preference change interrupts a ceremony and still performs its original navigation once',async()=>{
 vi.useFakeTimers();const flow=createMenuTransition(),action=vi.fn(),result=flow.run('angel',action)
 await vi.advanceTimersByTimeAsync(100);setMotionMode('STATIC')
 await vi.advanceTimersByTimeAsync(40);expect(await result).toBe(true);expect(action).toHaveBeenCalledTimes(1);expect(flow.state.active).toBe(false)
})
