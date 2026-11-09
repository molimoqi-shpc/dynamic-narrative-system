import {describe,it,expect,vi,beforeEach} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
const transport=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../src/api',()=>({default:transport}))
import Panel from '../src/components/WorldBookPanel.vue'
const props={sessionId:'A',playerId:'user',viewMode:'DREAMER',refreshKey:'1:0:0',storySeconds:120}
function result(extra={}){return {sessionId:'A',viewMode:'DREAMER',storySeconds:120,roles:[{id:'npc',name:'知秋'}],activeCharacters:[{id:'npc',name:'知秋',tier:'KNOWN',basis:'最近所见',known:{awake:true,observedAtSeconds:40}}],entries:[],total:0,hasMore:false,...extra}}
function pending(){let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
beforeEach(()=>transport.get.mockReset())
describe('world book player browser',()=>{
 it('formats a JSON-encoded historical dossier instead of displaying it as a story paragraph',async()=>{
  transport.get.mockResolvedValue(result({viewMode:'OMNISCIENT',entries:[{entry_key:'profile',kind:'CHARACTER_DOSSIER',content:JSON.stringify({personalHistory:'米拉在盐坞做验誓伙计。',source:'OPENING_AUTHOR',rawResponse:'INTERNAL_PACKET'})}]}))
  const panel=mount(Panel,{props:{...props,viewMode:'OMNISCIENT'}});await flushPromises()
  try{expect(panel.text()).toContain('米拉在盐坞做验誓伙计');expect(panel.text()).not.toMatch(/personalHistory|OPENING_AUTHOR|INTERNAL_PACKET|\{"/);expect(panel.find('.book-entry>.book-prose').exists()).toBe(false)}finally{panel.unmount()}
 })
 it('receives an inventory lookup once, keeps it while visiting the public world leaf, and shows the new title',async()=>{
  transport.get.mockResolvedValue(result());const panel=mount(Panel,{props:{...props,initialQuery:{keyword:'月魄镜'},worldSetting:{title:'长夜渡星',summary:'普通人与精灵共同生活。'}}});await flushPromises()
  expect(transport.get).toHaveBeenCalledTimes(1);expect(transport.get.mock.lastCall[1].params.keyword).toBe('月魄镜')
  await panel.findAll('.book-directory button')[0].trigger('click');expect(panel.text()).toContain('普通人与精灵共同生活。')
  await panel.findAll('.book-directory button')[1].trigger('click');expect(panel.find('input').element.value).toBe('月魄镜');expect(transport.get).toHaveBeenCalledTimes(1)
  expect(panel.find('h2').text()).toContain('世诰');panel.unmount()
 })
 it('links a known character to their records by role id without turning the story',async()=>{
  transport.get.mockResolvedValue(result());const panel=mount(Panel,{props});await flushPromises();await panel.find('.book-person-link').trigger('click');await flushPromises()
  expect(transport.get.mock.lastCall[1].params.roleId).toBe('npc');expect(panel.find('select').element.value).toBe('npc');panel.unmount()
 })
 it('searches with a literal keyword, role and seconds range without submitting an action',async()=>{
  transport.get.mockResolvedValue(result());const panel=mount(Panel,{props});await flushPromises()
  const inputs=panel.findAll('input');await inputs[0].setValue('河上有雾');await panel.find('select').setValue('npc');await inputs[1].setValue(40);await inputs[2].setValue(80)
  await panel.find('form').trigger('submit');await flushPromises()
  expect(transport.get).toHaveBeenLastCalledWith('/game/world-book/browse',{params:{sessionId:'A',keyword:'河上有雾',roleId:'npc',fromSeconds:40,toSeconds:80,page:0}})
  expect(panel.text()).toContain('所见时刻：1分钟20秒前');expect(panel.text()).not.toContain('心理附加表');panel.unmount()
 })
 it('clears omniscient data immediately and rejects an old private response after switching to limited view',async()=>{
  const delayed=pending();transport.get.mockResolvedValueOnce(result({viewMode:'OMNISCIENT',entries:[{entry_key:'private',kind:'CHARACTER_DOSSIER',content:{psychology:{thought:'NPC_SECRET'}}}]}))
  const panel=mount(Panel,{props:{...props,viewMode:'OMNISCIENT'}});await flushPromises();expect(panel.html()).toContain('NPC_SECRET')
  transport.get.mockReturnValueOnce(delayed.promise);await panel.setProps({refreshKey:'1:0:1'})
  transport.get.mockResolvedValueOnce(result());await panel.setProps({viewMode:'DREAMER'});await flushPromises()
  expect(panel.html()).not.toContain('NPC_SECRET');delayed.resolve(result({viewMode:'OMNISCIENT',entries:[{entry_key:'late',content:'LATE_SECRET'}]}));await flushPromises()
  expect(panel.html()).not.toContain('LATE_SECRET');expect(panel.text()).toContain('入梦');panel.unmount()
 })
 it('rejects responses for a previous session or branch and resets another save’s filters',async()=>{
  const delayed=pending();transport.get.mockReturnValueOnce(delayed.promise);const panel=mount(Panel,{props})
  transport.get.mockResolvedValueOnce(result({sessionId:'B'}));await panel.setProps({sessionId:'B',refreshKey:'2:1:0'});await flushPromises()
  delayed.resolve(result({entries:[{entry_key:'old',content:'OLD_BRANCH'}]}));await flushPromises();expect(panel.text()).not.toContain('OLD_BRANCH');panel.unmount()
 })
 it('keeps extra cards compact, displays major sections, and escapes stored HTML',async()=>{
  transport.get.mockResolvedValue(result({viewMode:'OMNISCIENT',activeCharacters:[
   {id:'extra',name:'船夫',tier:'EXTRA',dossier:{brief:{roleInScene:'渡口船夫',attitude:'愿意指路'}}},
   {id:'core',name:'知秋',tier:'CORE',dossier:{psychology:{personality:{stableTraits:['外柔内刚']},motivations:{hiddenAgenda:'寻找旧盟友'}}}},
  ],entries:[{entry_key:'html',kind:'CHAPTER',title:'旧事',content:'<img src=x onerror="alert(1)">'}]}))
  const panel=mount(Panel,{props:{...props,viewMode:'OMNISCIENT'}});await flushPromises();const cards=panel.findAll('.book-card')
  expect(cards[0].find('details').exists()).toBe(false);expect(cards[0].text()).toContain('愿意指路');expect(cards[1].find('details').exists()).toBe(true)
  expect(cards[1].text()).toContain('外柔内刚');expect(cards[1].text()).toContain('未公开的目的');expect(panel.find('img[src=x],img[onerror]').exists()).toBe(false);expect(panel.findAll('img').every(img=>img.attributes('src').startsWith('/art/'))).toBe(true);panel.unmount()
 })
 it('paginates, handles errors, and validates time bounds without network calls',async()=>{
  transport.get.mockResolvedValue(result({hasMore:true,total:21}));const panel=mount(Panel,{props});await flushPromises()
  await panel.findAll('button').find(b=>b.text()==='下一页').trigger('click');await flushPromises();expect(transport.get.mock.lastCall[1].params.page).toBe(1)
  const inputs=panel.findAll('input');await inputs[1].setValue(100);await inputs[2].setValue(40);const calls=transport.get.mock.calls.length;await panel.find('form').trigger('submit');await flushPromises()
  expect(transport.get).toHaveBeenCalledTimes(calls);expect(panel.text()).toContain('有效的世界时间范围')
  transport.get.mockRejectedValueOnce(new Error('offline'));await panel.findAll('button').find(b=>b.text()==='清空').trigger('click');await flushPromises();expect(panel.text()).toContain('暂时未能读取');panel.unmount()
 })
})
