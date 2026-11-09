import {describe,it,expect,vi,afterEach} from 'vitest'
import {mount} from '@vue/test-utils'
import {defineComponent,nextTick} from 'vue'
import {manuscriptGeometry} from '../src/utils/manuscriptGeometry'
import ManuscriptPaper from '../src/components/ManuscriptPaper.vue'

const desktop={width:660.875,height:869,top:72,formLeft:35,formTop:728,formWidth:794,formHeight:56}
afterEach(()=>{vi.restoreAllMocks();vi.unstubAllGlobals()})
describe('manuscript composition attached to the writing controls',()=>{
 it('keeps a small paper margin beyond the button at both desktop widths',()=>{
  for(const box of [desktop,{width:506.875,height:648,top:72,formLeft:24,formTop:537,formWidth:612,formHeight:44}]){
   const geometry=manuscriptGeometry(box),gap=geometry.tip.x-box.formLeft-box.formWidth
   expect(gap).toBeGreaterThanOrEqual(40);expect(gap).toBeLessThanOrEqual(65)
   expect(geometry.tip.y).toBeGreaterThan(box.formTop+box.formHeight)
   expect(geometry.tip.y).toBeLessThan(box.height)
   expect(geometry.start.x).toBeLessThan(geometry.tip.x)
   expect(geometry.end.x).toBeLessThan(geometry.tip.x)
   expect(geometry.page).not.toMatch(/NaN|Infinity/)
  }
 })
 it('follows resized writing controls instead of leaving a detached fixed arrow',()=>{
  const before=manuscriptGeometry(desktop),wider=manuscriptGeometry({...desktop,formWidth:desktop.formWidth+100}),higher=manuscriptGeometry({...desktop,formTop:desktop.formTop-80})
  expect(wider.tip.x-before.tip.x).toBe(100)
  expect(higher.tip.y-before.tip.y).toBe(-80)
  expect(higher.start.y-before.start.y).toBe(-80)
  expect(manuscriptGeometry({...desktop,width:0})).toBeNull()
 })
 it('remeasures after a composer expansion without touching text or submitting an action',async()=>{
  let offset=0
  const resizeCallbacks=[]
  vi.stubGlobal('ResizeObserver',class{constructor(callback){resizeCallbacks.push(callback)}observe(){}disconnect(){}})
  vi.spyOn(window,'requestAnimationFrame').mockImplementation(callback=>{callback();return 1})
  vi.spyOn(Element.prototype,'getBoundingClientRect').mockImplementation(function(){return this.matches('.composer-form')?{left:205,top:800+offset,width:794,height:56}:{left:170,top:72,width:660.875,height:869}})
  const Fixture=defineComponent({components:{ManuscriptPaper},template:'<section><ManuscriptPaper/><div class="game-composer"><form class="composer-form" @submit.prevent><textarea aria-label="行动草稿">保留我的行动</textarea><button>推进</button></form></div></section>'})
  const view=mount(Fixture)
  await nextTick()
  const input=view.get('textarea'),before=view.get('.manuscript-paper-page').attributes('d'),calls=globalThis.fetch.mock.calls.length
  offset=-90;resizeCallbacks.forEach(callback=>callback());await nextTick()
  expect(view.get('.manuscript-paper-page').attributes('d')).not.toBe(before)
  expect(input.element.value).toBe('保留我的行动')
  expect(globalThis.fetch.mock.calls.length).toBe(calls)
  expect(view.get('svg').attributes('aria-hidden')).toBe('true')
  view.unmount();vi.unstubAllGlobals()
 })
})
