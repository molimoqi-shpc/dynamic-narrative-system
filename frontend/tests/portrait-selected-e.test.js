import {beforeEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
const transport=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../src/api',()=>({default:transport}))
import {characterPortrait,portraitCatalog} from '../src/utils/characterPortrait'
import CharacterStage from '../src/components/CharacterStage.vue'
import WorldBookPanel from '../src/components/WorldBookPanel.vue'
const west={culturalStyle:'WESTERN'}
const person=(id,extra={})=>({id,name:id,tier:'CORE',publicAppearance:{gender:'female',ageGroup:'ADULT',species:'human',culturalStyle:'WESTERN',...extra}})
const art=(p,scope='DREAMER',world=west)=>characterPortrait(p,scope,world)
function closest(result,gender='female',culture='WESTERN'){
 expect(result.src).toBeTruthy();expect(result.matchKind).toBe('closest')
 const asset=portraitCatalog.find(item=>item.key===result.key)
 expect(asset.gender).toBe(gender);expect(asset.cultures||['WESTERN']).toContain(culture)
 return asset
}
const cases=[
 ['01',{archetype:'死灵法师',basis:'她银白长发，穿黑丝绒长袍，右手持骨首长杖，左掌青绿魂火。'}],
 ['02',{gender:'male',archetype:'亡灵法师',basis:'他灰白长须，穿深炭黑高领长袍，左手托锁链黑皮典籍，右掌冰蓝魂焰。'}],
 ['03',{species:'吸血鬼',archetype:'NOBLE',basis:'她黑发高髻，穿酒红黑哥特长礼裙，右手托水晶高脚杯，左手握半开黑蕾丝折扇。'}],
 ['04',{gender:'male',species:'血族',archetype:'NOBLE',basis:'他银灰低马尾，黑天鹅绒长大衣，右手握银蝙蝠翼手杖，左掌暗红雾。'}],
 ['05',{archetype:'海盗船长',basis:'她红色长卷发，戴金边黑三角帽，穿深红呢绒长外套，手握弯刀和指南针。'}],
 ['06',{gender:'male',archetype:'海盗',basis:'他左眼戴黑眼罩，深海蓝呢绒外套，戴黑三角帽，手持燧发枪与黄铜望远镜。'}]
]
beforeEach(()=>{localStorage.clear();transport.get.mockReset()})
describe('E batch approved portraits and framing',()=>{
 it.each(cases)('matches E%s from visible character facts',(id,info)=>{
  const result=art(person('E'+id,info))
  expect(result.key).toBe('P261027E-'+id)
  expect(result.src).toContain('/portraits-v8/')
  expect(result.cinematic).toBe(true)
  expect(result.framing.stage['--portrait-inset']).toBeTruthy()
 })
 it('enables the eight approved portraits and retains detailed public tags',()=>{
  const selected=portraitCatalog.filter(a=>a.key.startsWith('P261027E-'))
  expect(selected.map(a=>a.key)).toEqual([...cases.map(([id])=>'P261027E-'+id),'P261027E-07','P261027E-08'])
  for(const a of selected)expect(a.keywords.length).toBeGreaterThanOrEqual(30)
 })
 it('matches the two eastern swordsmen and keeps old ordinary and child portraits',()=>{
  const east={culturalStyle:'EASTERN'}
  for(const [gender,id,clothing]of [['female','07','素白轻纱广袖长衫'],['male','08','墨绿宽袖长袍']]){
   const p=person('sword'+id,{gender,culturalStyle:'EASTERN',archetype:'剑客',clothing})
   expect(art(p,'DREAMER',east).key).toBe('P261027E-'+id)
   expect(art(p,'DREAMER',west).symbolic).toBe(true)
  }
  expect(art(person('ordinary',{culturalStyle:'EASTERN'}),'DREAMER',east).key).toBe('woman')
  expect(art(person('child',{culturalStyle:'EASTERN',archetype:'SWORDSMAN',ageGroup:'CHILD'}),'DREAMER',east).key).toBe('child-girl')
  const elf=person('elf',{culturalStyle:'EASTERN',species:'elf'}),before=JSON.stringify(elf)
  closest(art(elf,'DREAMER',east),'female','EASTERN');expect(JSON.stringify(elf)).toBe(before)
 })
 it('uses visible age for centuries-old vampires and honors explicit elderly or child appearances',()=>{
  const p=person('old',{species:'vampire',age:600,ageGroup:undefined})
  expect(art(p).key).toBe('P261027E-03')
  expect(art(person('child',{species:'vampire',apparentAge:10})).symbolic).toBe(true)
  expect(closest(art(person('elder',{species:'vampire',apparentAgeGroup:'ELDER'}))).species).toBe('vampire')
 })
 it('never reveals hidden vampire ancestry or necromancy from private records',()=>{
  const p={...person('secret',{archetype:'NOBLE'}),appearance:{species:'vampire',archetype:'VAMPIRE'},dossier:{identity:'吸血鬼'},name:'吸血鬼伯爵'}
  expect(art(p).key).toBe('western-noble-female')
  expect(art(p,'OMNISCIENT').key).toBe('P261027E-03')
  expect(art(person('public',{species:'unknown',archetype:'吸血鬼'})).key).toBe('P261027E-03')
  expect(art(person('rumor',{species:'unknown',archetype:'NOBLE',basis:'据说她是吸血鬼。'})).key).not.toBe('P261027E-03')
  expect(art({...person('mage',{archetype:'MAGE'}),appearance:cases[0][1]}).key).not.toBe('P261027E-01')
 })
 it('keeps specific mage matches and offers a nearest captain picture without inventing pirate identity',()=>{
  expect(art(person('mage',{archetype:'MAGE',clothing:'黑丝绒长袍'})).key).not.toBe('P261027E-01')
  const captain=person('captain',{gender:'male',archetype:'船长',clothing:'深海蓝呢绒外套'}),before=JSON.stringify(captain)
  closest(art(captain),'male');expect(JSON.stringify(captain)).toBe(before)
  expect(art(person('soul',{archetype:'MAGE',clothing:'黑丝绒长袍',visibleEquipment:'骨首长杖与魂火'})).key).toBe('P261027E-01')
 })
 it('respects world, species and gender while allowing shared artwork',()=>{
  for(const [id,info]of cases){
   const p=person(id,info)
   expect(art(p).src).toBe(art({...p,id:'another'}).src)
   expect(art(p,'DREAMER',{culturalStyle:'EASTERN'}).symbolic).toBe(true)
   expect(art(p,'DREAMER',{culturalStyle:'MODERN'}).symbolic).toBe(true)
   expect(art(person('elf',{...info,species:'elf'})).key).not.toBe('P261027E-'+id)
   expect(art(person('sex',{...info,gender:info.gender==='male'?'female':'male'})).key).not.toBe('P261027E-'+id)
  }
 })
 it('frames stage and shadow together and gives the list its own head crop',async()=>{
  const cast=cases.map(([id,info])=>person('E'+id,info))
  const wrapper=mount(CharacterStage,{props:{scene:{worldSetting:west,presentGods:cast.map(p=>({...p,godId:p.id,godName:p.name}))},atmosphere:{key:'default',label:'旅途'},viewMode:'DREAMER',identity:'E:0:DREAMER'}})
  expect(wrapper.find('.refraction-actor').classes()).toContain('portrait-framed')
  expect(wrapper.find('.refraction-actor').attributes('style')).toContain('--portrait-inset:')
  expect(wrapper.find('.portrait-silhouette').attributes('src')).toBe(wrapper.find('.paper-person').attributes('src'))
  const thumbs=wrapper.findAll('.cast-portrait-crop img')
  expect(thumbs).toHaveLength(6)
  for(let i=0;i<thumbs.length;i++){expect(thumbs[i].attributes('src')).toBe(art(cast[i]).src);expect(thumbs[i].attributes('style')).toContain('width:')}
  wrapper.unmount()
 })
 it('uses the same selected artwork with a separate book-card crop',async()=>{
  transport.get.mockResolvedValue({sessionId:'E',viewMode:'DREAMER',storySeconds:0,entries:[],activeCharacters:cases.map(([id,info])=>person('E'+id,info))})
  const panel=mount(WorldBookPanel,{props:{sessionId:'E',playerId:'owner',viewMode:'DREAMER',refreshKey:'0:0:0',worldSetting:west}})
  await flushPromises()
  expect(panel.findAll('.book-card-portrait')).toHaveLength(6)
  expect(panel.find('.book-card-portrait').attributes('style')).toContain('width: 250%')
  expect(panel.find('.book-card-portrait').attributes('src')).toContain('/portraits-v8/')
  panel.unmount()
 })
})
