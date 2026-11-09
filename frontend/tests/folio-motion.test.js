import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {mount} from '@vue/test-utils'
import {folioMotion,folioMotionTiming} from '../src/utils/folioMotion'

let media,animate,animations,views,descriptor
const render=(page='self')=>{const v=mount({props:['page'],directives:{folioMotion},template:'<article v-folio-motion="page"><button>{{page}}</button></article>'},{props:{page}});views.push(v);return v}
beforeEach(()=>{
 views=[];animations=[];media={matches:false,addEventListener:vi.fn(),removeEventListener:vi.fn()}
 vi.mocked(window.matchMedia).mockReturnValue(media)
 descriptor=Object.getOwnPropertyDescriptor(HTMLElement.prototype,'animate')
 animate=vi.fn(()=>{const a={cancel:vi.fn()};animations.push(a);return a})
 Object.defineProperty(HTMLElement.prototype,'animate',{configurable:true,value:animate})
})
afterEach(()=>{views.forEach(v=>v.unmount());if(descriptor)Object.defineProperty(HTMLElement.prototype,'animate',descriptor);else delete HTMLElement.prototype.animate;vi.restoreAllMocks()})

describe('cancellable manuscript content motion',()=>{
 it('animates on first appearance but ignores unrelated updates',async()=>{
  const view=render();expect(animate).toHaveBeenCalledTimes(1)
  expect(animate.mock.calls[0][1]).toEqual({...folioMotionTiming,fill:'none'})
  await view.setProps({page:'self'});expect(animate).toHaveBeenCalledTimes(1)
 })
 it('replaces interrupted motion and immediately displays the latest tab',async()=>{
  const view=render();await view.setProps({page:'items'});await view.setProps({page:'relationships'})
  expect(animations[0].cancel).toHaveBeenCalledTimes(1);expect(animations[1].cancel).toHaveBeenCalledTimes(1)
  expect(view.text()).toBe('relationships');expect(animate).toHaveBeenCalledTimes(3)
 })
 it('honors an existing reduced-motion preference',async()=>{
  media.matches=true;const view=render();await view.setProps({page:'items'})
  expect(animate).not.toHaveBeenCalled();expect(view.text()).toBe('items')
 })
 it('cancels immediately when reduced motion is enabled during an animation',()=>{
  render();media.matches=true;media.addEventListener.mock.calls[0][1]()
  expect(animations[0].cancel).toHaveBeenCalledTimes(1)
 })
 it('stops hidden-tab work and removes both listeners on unmount',()=>{
  const remove=vi.spyOn(document,'removeEventListener'),view=render()
  vi.spyOn(document,'hidden','get').mockReturnValue(true);document.dispatchEvent(new Event('visibilitychange'))
  expect(animations[0].cancel).toHaveBeenCalledTimes(1)
  view.unmount();views=[]
  expect(media.removeEventListener).toHaveBeenCalledWith('change',expect.any(Function))
  expect(remove).toHaveBeenCalledWith('visibilitychange',expect.any(Function))
 })
 it('shows content normally without the Web Animations API',async()=>{
  delete HTMLElement.prototype.animate;const view=render();await view.setProps({page:'items'})
  expect(view.text()).toBe('items');expect(view.attributes('style')).toBeUndefined()
 })
 it('can animate only an identity change while leaving the existing portrait mounted',async()=>{
  const view=mount({props:['page'],directives:{folioMotion},template:'<p v-folio-motion.update="page">{{page}}</p>'},{props:{page:'elf-a'}});views.push(view)
  expect(animate).not.toHaveBeenCalled();await view.setProps({page:'elf-b'});expect(animate).toHaveBeenCalledTimes(1)
 })
})
