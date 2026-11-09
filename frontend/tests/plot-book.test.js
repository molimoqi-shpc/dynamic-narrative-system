import {describe,it,expect,vi,beforeEach} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
const transport=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../src/api',()=>({default:transport}))
import Panel from '../src/components/PlotBookPanel.vue'
const props={sessionId:'plot-A',playerId:'user',viewMode:'DREAMER',refreshKey:'1:0:0'}
const event={eventId:'evt_known',title:'旧册缺页',category:'INVESTIGATION',status:'OPEN',summary:'主角已查看旧册，原因未明。',source:{logId:12,round:2},foreshadows:[{threadId:'fo_known',title:'撕痕',summary:'缺页去向不明',status:'OPEN'}]}
function result(extra={}){return {sessionId:'plot-A',viewMode:'DREAMER',events:[event],total:1,page:0,threadPage:0,hasMore:false,...extra}}
function pending(){let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
beforeEach(()=>transport.get.mockReset())
describe('important plot book',()=>{
 it('queries by category and event reference and opens the original published chapter without a game action',async()=>{
  transport.get.mockResolvedValue(result());const panel=mount(Panel,{props});await flushPromises()
  const inputs=panel.findAll('input');await inputs[0].setValue('旧册');await inputs[1].setValue('evt_known');await panel.find('select').setValue('INVESTIGATION');await panel.find('form').trigger('submit');await flushPromises()
  expect(transport.get).toHaveBeenLastCalledWith('/game/world-book/plots',{params:{sessionId:'plot-A',keyword:'旧册',eventId:'evt_known',category:'INVESTIGATION',page:0,threadPage:0}})
  expect(panel.text()).toContain('缺页去向不明');transport.get.mockResolvedValueOnce({sessionId:'plot-A',logId:12,story:'narrator：原始铺垫正文'})
  await panel.findAll('button').find(b=>b.text().includes('查阅原章节')).trigger('click');await flushPromises();expect(panel.text()).toContain('原始铺垫正文')
  expect(transport.get).toHaveBeenLastCalledWith('/game/world-book/plots/passage',{params:{sessionId:'plot-A',logId:12}});panel.unmount()
 })
 it('shows the player-known natural-language status instead of losing it to a generic label',async()=>{
  transport.get.mockResolvedValue(result({events:[{...event,status:'已解释来路，善后未定'}]}));const panel=mount(Panel,{props});await flushPromises();expect(panel.text()).toContain('已解释来路，善后未定');panel.unmount()
 })
 it('clears private event details and pending original text synchronously after perspective changes',async()=>{
  transport.get.mockResolvedValueOnce(result({viewMode:'OMNISCIENT',events:[{...event,payoff:'unused',outcome:'SECRET_OUTCOME',foreshadows:[{threadId:'secret',title:'隐秘伏笔',payoff:'SECRET_PAYOFF'}]}]}))
  const panel=mount(Panel,{props:{...props,viewMode:'OMNISCIENT'}});await flushPromises();expect(panel.text()).toContain('SECRET_PAYOFF')
  const source=pending();transport.get.mockReturnValueOnce(source.promise);await panel.findAll('button').find(b=>b.text().includes('查阅原章节')).trigger('click')
  transport.get.mockResolvedValueOnce(result());await panel.setProps({viewMode:'DREAMER'});await flushPromises();expect(panel.text()).not.toContain('SECRET')
  source.resolve({sessionId:'plot-A',story:'STALE_SOURCE'});await flushPromises();expect(panel.text()).not.toContain('STALE_SOURCE');panel.unmount()
 })
 it('rejects an old branch response and escapes model-authored titles and prose',async()=>{
  const old=pending();transport.get.mockReturnValueOnce(old.promise);const panel=mount(Panel,{props})
  transport.get.mockResolvedValueOnce(result({events:[{...event,title:'<img src=x onerror=alert(1)>',summary:'<script>bad()</script>'}]}));await panel.setProps({refreshKey:'2:1:0'});await flushPromises()
  old.resolve(result({events:[{...event,summary:'OLD_BRANCH'}]}));await flushPromises();expect(panel.text()).not.toContain('OLD_BRANCH');expect(panel.find('img').exists()).toBe(false);expect(panel.find('script').exists()).toBe(false);panel.unmount()
 })
 it('pages large plot and thread collections and handles an unavailable source',async()=>{
  transport.get.mockResolvedValue(result({total:13,hasMore:true,events:[{...event,threadHasMore:true}]}));const panel=mount(Panel,{props});await flushPromises()
  await panel.findAll('button').find(b=>b.text()==='更多线索').trigger('click');await flushPromises();expect(transport.get.mock.lastCall[1].params).toMatchObject({eventId:'evt_known',page:0,threadPage:1})
  await panel.findAll('button').find(b=>b.text()==='下一页剧情').trigger('click');await flushPromises();expect(transport.get.mock.lastCall[1].params).toMatchObject({page:1,threadPage:0})
  transport.get.mockRejectedValueOnce(new Error('unavailable'));await panel.findAll('button').find(b=>b.text().includes('查阅原章节')).trigger('click');await flushPromises();expect(panel.text()).toContain('原章节暂时无法读取');panel.unmount()
 })
})
