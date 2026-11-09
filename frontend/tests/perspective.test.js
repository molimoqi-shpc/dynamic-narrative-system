import {describe,it,expect,vi,beforeEach} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
const transport=vi.hoisted(()=>({get:vi.fn(),post:vi.fn()}))
vi.mock('../src/api',()=>({default:transport}))
import Panel from '../src/components/PerspectivePanel.vue'
import Switch from '../src/components/PerspectiveModeSwitch.vue'
const props={sessionId:'A',playerId:'owner',viewMode:'OMNISCIENT',refreshKey:'1:2:3',branchEpoch:2}
const pending=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
function result(extra={}){return {sessionId:'A',viewMode:'OMNISCIENT',branchEpoch:2,available:true,storySeconds:120,roles:[{id:'npc',name:'知秋'}],character:{id:'npc',name:'知秋',tier:'CORE',location:[{location_name:'渡口'}],sixStatsEstablished:true,baseStats:{wen:30},actualStats:{wen:33},inventory:[],dossier:{psychology:{thought:'PRIVATE_THOUGHT'}}},decisions:[{godId:'npc',godName:'知秋',innerThought:'PRIVATE_INTENTION',isLying:null}],memories:[{id:25,type:'TURN_EVENT',title:'个人经历',tick:2,content:{text:'PRIVATE_MEMORY'}}],hasMoreMemories:true,nextBefore:25,ruleSnapshots:[{logId:1,round:1,snapshot:{score:'PRIVATE_SCORE'}}],playerMemoryContext:'PRIVATE_LAYERS',...extra}}
function panel(options={}){return mount(Panel,{props:{...props,...options},global:{stubs:{StatFormulaDialog:true}}})}
beforeEach(()=>{transport.get.mockReset();transport.post.mockReset()})
describe('perspective presentation and private-data lifetime',()=>{
 it('defaults to readable experiences and keeps state records separately accessible without another request',async()=>{
  transport.get.mockResolvedValue(result({memories:[{id:3,type:'BODY_STATE',title:'身体记录',content:{health:87,awake:true}},{id:2,type:'TURN_EVENT',title:'一次问询',content:{text:'她把从前发生的经过讲清楚了。'}}]}))
  const view=panel();await flushPromises();await view.findAll('button').find(b=>b.text()==='独立记忆').trigger('click')
  try{expect(view.findAll('.observer-memory')).toHaveLength(1);expect(view.find('.memory-passage').text()).toContain('经过讲清楚');expect(view.text()).not.toContain('身体记录');const count=transport.get.mock.calls.length;await view.findAll('.memory-categories button')[1].trigger('click');expect(view.text()).toContain('身体记录');expect(view.text()).toContain('87');expect(transport.get).toHaveBeenCalledTimes(count)}finally{view.unmount()}
 })
 it('presents the stored oath-world profile without encoded JSON or author instructions',async()=>{
  const data=result();data.character.name='奈雅';data.character.dossier={identity:{establishedSelf:[JSON.stringify({name:'奈雅',personalHistory:'十二岁，爱法诺的妹妹，想替母亲凑药钱。',source:'OPENING_AUTHOR'})]},freshnessMeaning:'INTERNAL_SNAPSHOT_INSTRUCTION',templateVersion:'CHARACTER_V2'}
  transport.get.mockResolvedValue(data);const view=panel();await flushPromises()
  try{expect(view.text()).toContain('想替母亲凑药钱');expect(view.text()).not.toMatch(/personalHistory|OPENING_AUTHOR|INTERNAL_SNAPSHOT|CHARACTER_V2|\{"/);expect(transport.post).not.toHaveBeenCalled()}finally{view.unmount()}
 })
 it('shows readable personal memories and excludes cursor records and the model prompt context',async()=>{
  transport.get.mockResolvedValue(result({memories:[{id:26,type:'ROLE_INTEGRATED',title:'历史索引位置',content:{lastKnowledgeId:25}},{id:25,type:'SELF_PROFILE',title:'人物来历',content:JSON.stringify({personalHistory:'她今早偷偷下滩找旧币。'})}],playerMemoryContext:'INTERNAL_SYSTEM_PROMPT'}))
  const view=panel();await flushPromises();await view.findAll('button').find(b=>b.text()==='独立记忆').trigger('click')
  try{expect(view.text()).toContain('偷偷下滩找旧币');expect(view.text()).not.toMatch(/历史索引位置|lastKnowledgeId|SELF_PROFILE|INTERNAL_SYSTEM_PROMPT|personalHistory/);expect(transport.post).not.toHaveBeenCalled()}finally{view.unmount()}
 })
 it('formats structured legacy intentions rather than interpolating objects into text',async()=>{
  transport.get.mockResolvedValue(result({decisions:[{godId:'npc',godName:'知秋',innerThought:{thought:'不愿透露旧债'},actionDirection:JSON.stringify({direction:'先召回滩上的人',rawResponse:'INTERNAL_RAW_PACKET'}),isLying:null}]}))
  const view=panel();await flushPromises();await view.findAll('button').find(b=>b.text()==='意愿与隐情').trigger('click')
  try{expect(view.text()).toContain('不愿透露旧债');expect(view.text()).toContain('先召回滩上的人');expect(view.text()).not.toMatch(/\{"|"thought"|rawResponse|INTERNAL_RAW_PACKET|\[object Object\]/)}finally{view.unmount()}
 })
 it('switches a dossier from the character index through a read request only',async()=>{
  const first=result({roles:[{id:'npc',name:'知秋'},{id:'elder',name:'陈翁'}]});transport.get.mockResolvedValueOnce(first)
  const view=panel();await flushPromises();const next=result();next.character={...next.character,id:'elder',name:'陈翁'};transport.get.mockResolvedValueOnce(next)
  await view.findAll('.observer-index>button').find(b=>b.text().includes('陈翁')).trigger('click');await flushPromises()
  expect(transport.get).toHaveBeenLastCalledWith('/game/perspective',{params:{sessionId:'A',roleId:'elder',beforeMemory:undefined}})
  expect(view.find('.observer-person-heading h3').text()).toContain('陈翁');expect(transport.post).not.toHaveBeenCalled();view.unmount()
 })
 it('never requests backstage data in limited mode',async()=>{const view=panel({viewMode:'DREAMER'});await flushPromises();expect(transport.get).not.toHaveBeenCalled();expect(view.text()).toContain('入梦模式已关闭');view.unmount()})
 it('renders real records and distinguishes absence of a lie judgment from an explicit flag',async()=>{
  transport.get.mockResolvedValue(result());const view=panel();await flushPromises();expect(view.text()).toContain('PRIVATE_THOUGHT');expect(view.text()).toContain('33');await view.findAll('button').find(b=>b.text()==='意愿与隐情').trigger('click');expect(view.text()).toContain('PRIVATE_INTENTION');expect(view.text()).toContain('说谎状态未记录');expect(view.find('.observer-lie').exists()).toBe(false);view.unmount()
 })
 it('highlights an explicit saved lie flag without inventing one',async()=>{transport.get.mockResolvedValue(result({decisions:[{godId:'npc',godName:'知秋',innerThought:'隐瞒去向',isLying:true}]}));const view=panel();await flushPromises();await view.findAll('button').find(b=>b.text()==='意愿与隐情').trigger('click');expect(view.find('.observer-lie').text()).toContain('已记录说谎');view.unmount()})
 it('immediately erases secrets on mode change and ignores a late omniscient response',async()=>{
  transport.get.mockResolvedValueOnce(result());const view=panel();await flushPromises();const delayed=pending();transport.get.mockReturnValueOnce(delayed.promise);await view.setProps({refreshKey:'changed'});await view.setProps({viewMode:'DREAMER'});expect(view.text()).not.toContain('PRIVATE_');delayed.resolve(result());await flushPromises();expect(view.text()).not.toContain('PRIVATE_');view.unmount()
 })
 it('rejects old sessions and old branches',async()=>{transport.get.mockResolvedValue(result({sessionId:'B'}));const view=panel();await flushPromises();expect(view.text()).not.toContain('PRIVATE_');expect(view.text()).toContain('正在同步');transport.get.mockResolvedValue(result({branchEpoch:1}));await view.setProps({refreshKey:'new'});await flushPromises();expect(view.text()).not.toContain('PRIVATE_');view.unmount()})
 it('paginates only the selected character and retains earlier pages',async()=>{
  transport.get.mockResolvedValueOnce(result());const view=panel();await flushPromises();await view.findAll('button').find(b=>b.text()==='独立记忆').trigger('click');transport.get.mockResolvedValueOnce(result({memories:[{id:4,type:'PERSONAL',title:'更早经历',content:'OLDER_MEMORY'}],hasMoreMemories:false}));await view.findAll('button').find(b=>b.text()==='读取更早的记忆').trigger('click');await flushPromises();expect(transport.get).toHaveBeenLastCalledWith('/game/perspective',{params:{sessionId:'A',roleId:'npc',beforeMemory:25}});expect(view.text()).toContain('PRIVATE_MEMORY');expect(view.text()).toContain('OLDER_MEMORY');view.unmount()
 })
 it('does not present placeholder stats as established abilities and escapes stored markup',async()=>{const data=result();data.character.tier='EXTRA';data.character.sixStatsEstablished=false;data.character.dossier={roleInScene:'<img src=x onerror=alert(1)>'};transport.get.mockResolvedValue(data);const view=panel();await flushPromises();expect(view.text()).toContain('现场简表');expect(view.text()).toContain('尚未确立');expect(view.find('.observer-stats').exists()).toBe(false);expect(view.find('img[src=x],img[onerror]').exists()).toBe(false);expect(view.findAll('img').every(img=>img.attributes('src').startsWith('/art/'))).toBe(true);view.unmount()})
 it('reports failures without displaying old private content',async()=>{transport.get.mockRejectedValue(new Error('offline'));const view=panel();await flushPromises();expect(view.text()).toContain('暂时未能读取');expect(view.text()).not.toContain('PRIVATE_');view.unmount()})
})
describe('server-confirmed perspective switching',()=>{
 it('does not change mode before the server confirms, and prevents repeated requests',async()=>{const wait=pending();transport.post.mockReturnValue(wait.promise);const view=mount(Switch,{props:{modelValue:'DREAMER',sessionId:'A'}});await view.findAll('button')[1].trigger('click');expect(view.emitted('change')).toBeUndefined();expect(view.emitted('pending')[0]).toEqual([true]);await view.findAll('button')[1].trigger('click');expect(transport.post).toHaveBeenCalledTimes(1);wait.resolve({viewMode:'OMNISCIENT'});await flushPromises();expect(view.emitted('change')).toEqual([['OMNISCIENT']]);view.unmount()})
 it('retains the previous mode on save failure',async()=>{transport.post.mockRejectedValue(new Error('offline'));const view=mount(Switch,{props:{modelValue:'OMNISCIENT',sessionId:'A'}});await view.findAll('button')[0].trigger('click');await flushPromises();expect(view.emitted('change')).toBeUndefined();expect(view.text()).toContain('仍保留原模式');expect(view.findAll('button')[1].attributes('aria-pressed')).toBe('true');view.unmount()})
 it('ignores a switch response after changing saves',async()=>{const wait=pending();transport.post.mockReturnValue(wait.promise);const view=mount(Switch,{props:{modelValue:'DREAMER',sessionId:'A'}});await view.findAll('button')[1].trigger('click');await view.setProps({sessionId:'B'});wait.resolve({viewMode:'OMNISCIENT'});await flushPromises();expect(view.emitted('change')).toBeUndefined();view.unmount()})
})
