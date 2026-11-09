import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import SelectionRibbon from '../src/components/SelectionRibbon.vue'
import {paperLight} from '../src/utils/paperLight'

let views,media
const tick=async()=>{await nextTick();await vi.advanceTimersByTimeAsync(40);await nextTick()}
beforeEach(()=>{
 views=[];vi.useFakeTimers();vi.stubGlobal('requestAnimationFrame',fn=>setTimeout(fn,16));vi.stubGlobal('cancelAnimationFrame',clearTimeout)
 media={matches:false,addEventListener:vi.fn(),removeEventListener:vi.fn()};vi.mocked(window.matchMedia).mockReturnValue(media)
 vi.spyOn(HTMLElement.prototype,'getBoundingClientRect').mockImplementation(function(){
  if(this.tagName==='NAV')return {left:10,top:100,width:200,height:40}
  if(this.id==='a')return {left:20,top:100,width:70,height:40}
  if(this.id==='b')return {left:100,top:100,width:90,height:40}
  return {left:0,top:0,width:200,height:100}
 })
})
afterEach(()=>{views.forEach(v=>v.unmount());vi.useRealTimers();vi.restoreAllMocks();vi.unstubAllGlobals()})
const nav=()=>{const view=mount({components:{SelectionRibbon},props:['active'],template:'<nav class="selection-track"><SelectionRibbon :value="active"/><button id="a" :aria-pressed="active===\'a\'">甲</button><button id="b" :aria-pressed="active===\'b\'">乙</button></nav>'},{props:{active:'a'}});views.push(view);return view}
const surface=()=>{const view=mount({directives:{paperLight},template:'<section v-paper-light><p>正文保持原位</p></section>'});views.push(view);return view}

it('positions the decorative ribbon on the actual selected button without adding controls',async()=>{
 const view=nav();await tick();const marker=view.get('.selection-ribbon')
 expect(marker.attributes('aria-hidden')).toBe('true');expect(marker.classes()).toContain('has-selection')
 expect(marker.element.style.transform).toBe('translate3d(10px,0px,0)');expect(marker.element.style.width).toBe('70px')
 expect(view.findAll('button')).toHaveLength(2)
 await view.setProps({active:'b'});await tick()
 expect(marker.element.style.transform).toBe('translate3d(90px,0px,0)');expect(marker.element.style.width).toBe('90px')
 expect(view.get('#b').attributes('aria-pressed')).toBe('true')
})
it('hides its ribbon when selection is absent rather than implying an unrelated choice',async()=>{
 const view=nav();await tick();await view.setProps({active:'none'});await tick()
 expect(view.get('.selection-ribbon').classes()).not.toContain('has-selection')
})
it('reschedules for scroll and resize and releases pending animation frames on unmount',async()=>{
 const view=nav();await tick();window.dispatchEvent(new Event('resize'));view.element.dispatchEvent(new Event('scroll'))
 view.unmount();views=[];await tick();expect(vi.getTimerCount()).toBe(0)
})
it('only changes decorative light and shadow variables and restores them on pointer leave',async()=>{
 const view=surface();await view.trigger('pointermove',{pointerType:'mouse',clientX:150,clientY:20});await tick()
 expect(view.element.style.getPropertyValue('--paper-light-x')).toBe('75.0%')
 expect(view.element.style.getPropertyValue('--paper-light-y')).toBe('20.0%')
 expect(view.element.style.transform).toBe('');expect(view.get('p').attributes('style')).toBeUndefined()
 await view.trigger('pointerleave');expect(view.element.style.cssText).toBe('');expect(view.classes()).not.toContain('paper-lit')
})
it('leaves touch interactions and reduced-motion surfaces static',async()=>{
 const view=surface();await view.trigger('pointermove',{pointerType:'touch',clientX:150,clientY:20});await tick();expect(view.element.style.cssText).toBe('')
 media.matches=true;await view.trigger('pointermove',{pointerType:'mouse',clientX:150,clientY:20});await tick();expect(view.element.style.cssText).toBe('')
})
it('cancels a pending sheen when preferences change or its card unmounts',async()=>{
 const view=surface();await view.trigger('pointermove',{pointerType:'mouse',clientX:150,clientY:20})
 media.matches=true;media.addEventListener.mock.calls[0][1]();await tick();expect(view.element.style.cssText).toBe('')
 view.unmount();views=[];expect(media.removeEventListener).toHaveBeenCalledWith('change',expect.any(Function));expect(vi.getTimerCount()).toBe(0)
})
