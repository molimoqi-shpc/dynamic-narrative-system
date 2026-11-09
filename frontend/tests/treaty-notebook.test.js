import {describe,it,expect} from 'vitest'
import {shallowMount} from '@vue/test-utils'
import Rail from '../src/components/GameSceneRail.vue'
const entry={knowledgeId:1,title:'与渡者的盟约',content:'进度：待回应 · 尚未结盟',sourceType:'TREATY',sourceLabel:'盟约记录 · 双方表态',treaty:{status:'PROPOSED',isProposer:false,counterpartId:'npc'}}
function mount(present=true,busy=false){return shallowMount(Rail,{props:{atmosphere:{position:'50% 50%'},busy,scene:{knowledge:[entry],presentGods:present?[{godId:'npc',godName:'渡者'}]:[]}}})}
describe('treaty notebook consent',()=>{
 it('offers an explicit acceptance draft without sharing the treaty or acting automatically',async()=>{
  const page=mount();expect(page.emitted('treaty')).toBeUndefined();expect(page.text()).not.toContain('向此人分享');expect(page.text()).not.toContain('向此人求证');await page.findAll('button').find(b=>b.text()==='接受提议').trigger('click');expect(page.emitted('treaty')).toEqual([[{targetId:'npc',targetName:'渡者',text:'我同意与你结盟。'}]]);page.unmount()
 })
 it('lets a remote response be drafted without pretending that it was delivered',async()=>{const page=mount(false);const accept=page.findAll('button').find(b=>b.text()==='接受提议');expect(accept.attributes('disabled')).toBeUndefined();expect(page.text()).toContain('实际联系经过');await accept.trigger('click');expect(page.emitted('treaty')).toHaveLength(1);page.unmount()})
 it('still disables response drafts while the current request is running',()=>{const page=mount(true,true);expect(page.findAll('button').find(b=>b.text()==='接受提议').attributes('disabled')).toBeDefined();page.unmount()})
})
