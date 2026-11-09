import {it,expect,afterEach,vi} from 'vitest'
import {mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import MenuTransition from '../src/components/MenuTransition.vue'
import {menuTransition,prepareMenuScene,menuSceneAssets} from '../src/utils/menuTransition'
import {setMotionMode} from '../src/utils/motionPreferences'
let host
afterEach(()=>{host?.unmount();host=null;menuTransition.cancel();vi.unstubAllGlobals();vi.restoreAllMocks();setMotionMode('FULL')})
async function scene(kind,reduced=false){
 host=mount(MenuTransition,{global:{stubs:{teleport:true}}})
 Object.assign(menuTransition.state,{active:true,busy:true,kind,phase:'enter',reduced});await nextTick();return host
}
it('stages three independent wing pairs with increasing delay around one body',async()=>{
 const view=await scene('angel'),pairs=view.findAll('.seraph-wing-pair')
 expect(pairs).toHaveLength(3);expect(view.findAll('.seraph-wing')).toHaveLength(6);expect(view.findAll('.seraph-core')).toHaveLength(1)
 const delays=pairs.map(pair=>parseInt(pair.element.style.getPropertyValue('--pair-delay')))
 expect(delays[0]).toBeLessThan(delays[1]);expect(delays[1]).toBeLessThan(delays[2])
})
it('keeps each moving chain and its gear half in the same departing rig',async()=>{
 const view=await scene('gears')
 for(const side of ['left','right']){const rig=view.find('.rig-'+side);expect(rig.find('.gear-'+side).exists()).toBe(true);expect(rig.find('.tether-'+side).exists()).toBe(true)}
 menuTransition.state.phase='leave';await nextTick();expect(view.findAll('.gear-rig')).toHaveLength(2)
})
it('carries the complete rainy scene, rain trails, folded corner and hand as one leaf',async()=>{
 const view=await scene('hand'),rig=view.find('.rain-drag-rig')
 for(const selector of ['.rain-mural','.rain-curtain','.rain-water-trails','.rain-fold','.pull-hand'])expect(rig.find(selector).exists()).toBe(true)
 expect(view.find('.sheet-title').exists()).toBe(false)
 menuTransition.state.phase='leave';await nextTick();expect(view.find('.rain-drag-rig .rain-curtain').exists()).toBe(true)
})
it('places distinct drifting apparitions underneath the same rising water surface',async()=>{
 const view=await scene('tide'),lake=view.find('.tide-body .lake-phantoms')
 expect(lake.findAll('.lake-spirit')).toHaveLength(3)
 expect(new Set(lake.findAll('img').map(img=>img.attributes('src'))).size).toBe(2)
})
it('does not mount heavy artwork for a simplified transition',async()=>{
 const view=await scene('angel',true);expect(view.find('.seraph-stage').exists()).toBe(false);expect(view.find('.transition-short-label').exists()).toBe(true)
 menuTransition.state.kind='hand';await nextTick();expect(view.find('.rain-drag-rig').exists()).toBe(false)
 menuTransition.state.kind='tide';await nextTick();expect(view.find('.lake-depths').exists()).toBe(false)
})
it('falls back when any scene asset fails, then retries only the failed cached image',async()=>{
 setMotionMode('FULL');const failed=new Set([menuSceneAssets.tide[1]]),seen=[]
 vi.stubGlobal('Image',class{set src(value){this._src=value;seen.push(value);queueMicrotask(()=>failed.has(value)?this.onerror?.():this.onload?.())}decode(){return Promise.resolve()}get complete(){return false}})
 expect(await prepareMenuScene('tide')).toBe(false);failed.clear();expect(await prepareMenuScene('tide')).toBe(true)
 expect(seen.filter(src=>src===menuSceneAssets.tide[0])).toHaveLength(1);expect(seen.filter(src=>src===menuSceneAssets.tide[1])).toHaveLength(2)
})
