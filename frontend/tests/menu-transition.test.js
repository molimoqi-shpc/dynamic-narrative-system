import {describe,it,expect,vi,afterEach} from 'vitest'
import {createMenuTransition,menuTimings,menuRevealTimings} from '../src/utils/menuTransition'
import {menuTransition} from '../src/utils/menuTransition'
import {mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import MenuTransition from '../src/components/MenuTransition.vue'

afterEach(()=>{vi.useRealTimers();vi.restoreAllMocks()})
describe('menu transition navigation lifecycle',()=>{
 for(const kind of ['angel','gears','tide','hand'])it(`${kind}: covers before acting once, and unlocks after revealing`,async()=>{
  vi.useFakeTimers();const flow=createMenuTransition(),action=vi.fn(()=>expect(flow.state.phase).toBe('covered'))
  const result=flow.run(kind,action)
  expect(await flow.run('angel',action)).toBe(false)
  await vi.advanceTimersByTimeAsync(menuTimings[kind]-1);expect(action).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(35);expect(action).toHaveBeenCalledTimes(1);expect(flow.state.phase).toBe('leave')
  await vi.advanceTimersByTimeAsync(menuRevealTimings[kind]);expect(await result).toBe(true);expect(flow.state.active).toBe(false);expect(flow.state.busy).toBe(false)
 })
 it('releases its lock and curtain when the target navigation rejects',async()=>{
  vi.useFakeTimers();const flow=createMenuTransition()
  const result=flow.run('tide',()=>{throw Error('route failed')});const checked=expect(result).rejects.toThrow('route failed')
  await vi.advanceTimersByTimeAsync(menuTimings.tide+34);await checked
  expect(flow.state).toMatchObject({active:false,busy:false,phase:'idle'})
 })
 it('cancelled entrances never invoke their target and leave no timers',async()=>{
  vi.useFakeTimers();const flow=createMenuTransition(),action=vi.fn(),result=flow.run('hand',action)
  flow.cancel();await vi.runAllTimersAsync();expect(await result).toBe(false);expect(action).not.toHaveBeenCalled();expect(vi.getTimerCount()).toBe(0)
 })
 it('uses the short accessible fade on reduced motion or an unavailable image',async()=>{
  vi.useFakeTimers();const flow=createMenuTransition(),action=vi.fn(),result=flow.run('angel',action,{simplified:true})
  expect(flow.state.reduced).toBe(true);await vi.advanceTimersByTimeAsync(89);expect(action).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(125);expect(await result).toBe(true);expect(action).toHaveBeenCalledTimes(1)
 })
 it('the navigation watchdog releases a target that never settles',async()=>{
  vi.useFakeTimers();const flow=createMenuTransition(),result=flow.run('gears',()=>new Promise(()=>{}))
  const checked=expect(result).rejects.toThrow('页面暂未响应')
  await vi.advanceTimersByTimeAsync(menuTimings.gears+34+12000);await checked;expect(flow.state.active).toBe(false);expect(flow.state.busy).toBe(false)
 })
 it('skip shortens the artwork but still invokes the original action only once',async()=>{
  vi.useFakeTimers();const flow=createMenuTransition(),action=vi.fn(),result=flow.run('angel',action)
  flow.skip();await vi.advanceTimersByTimeAsync(34);expect(action).toHaveBeenCalledTimes(1)
  flow.skip();await vi.runAllTimersAsync();expect(await result).toBe(true);expect(action).toHaveBeenCalledTimes(1)
 })
})
it('the persistent host traps Tab, skips with Escape and restores focus and input',async()=>{
 vi.useFakeTimers();const root=document.createElement('div');root.id='app';const button=document.createElement('button');root.append(button);document.body.append(root);button.focus()
 const host=mount(MenuTransition,{attachTo:document.body,global:{stubs:{teleport:true}}})
 try{
  const action=vi.fn(),result=menuTransition.run('gears',action);await nextTick();await nextTick()
  expect(root.inert).toBe(true)
  window.dispatchEvent(new KeyboardEvent('keydown',{key:'Tab',cancelable:true}));expect(document.activeElement).toBe(host.get('button').element)
  window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',cancelable:true}));await vi.advanceTimersByTimeAsync(34)
  expect(action).toHaveBeenCalledTimes(1);await vi.advanceTimersByTimeAsync(menuRevealTimings.gears);await result;await nextTick()
  expect(root.inert).toBe(false);expect(document.activeElement).toBe(button);expect(host.find('.menu-transition').exists()).toBe(false)
 }finally{host.unmount();menuTransition.cancel();root.remove()}
})
