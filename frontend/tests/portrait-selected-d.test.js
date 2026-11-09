import {beforeEach,describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import {characterPortrait,portraitCatalog} from '../src/utils/characterPortrait'
import CharacterStage from '../src/components/CharacterStage.vue'
const west={culturalStyle:'WESTERN'}
const person=(id,extra={})=>({id,name:id,publicAppearance:{gender:'female',ageGroup:'ADULT',species:'human',culturalStyle:'WESTERN',...extra}})
const art=(p,scope='DREAMER',world=west)=>characterPortrait(p,scope,world)
const cases=[
 ['01',{archetype:'炼金术士',basis:'她穿深棕皮工作长外套，右手举圆底玻璃烧瓶，左手托炼成笔记。'}],
 ['02',{gender:'male',archetype:'炼金师',basis:'他穿深酒红天鹅绒长外套，掌上悬浮赤红宝石与两个黄铜炼成环。'}],
 ['03',{archetype:'机械师',basis:'她穿深橄榄绿帆布工装，左前臂为黄铜钢机械义肢，右手托蓝金机械核心。'}],
 ['04',{gender:'male',archetype:'铁匠',basis:'他穿深棕牛皮锻造围裙，左肘以下为黄铜黑钢机械义臂，右手握长柄大铁锤。'}],
 ['05',{archetype:'暗黑骑士',basis:'她黑色长发，身穿哑黑钢板甲，双手扶黑色巨剑，剑身有局部黑焰。'}],
 ['06',{gender:'male',archetype:'黑暗骑士',basis:'他白色短发，穿黑曜石厚重板甲，右手握尖刺长柄战锤，左掌黑焰球。'}],
 ['07',{archetype:'天启战士',basis:'她纯白长发，头顶细薄金火环，穿黑暗金轻战甲，手托黄铜红沙沙漏。'}],
 ['08',{gender:'male',archetype:'天启战士',basis:'他灰白长发，头顶残破薄火环，穿黑暗金厚重板甲，肩扛黑双手巨剑，腰挂牛角号。'}]
]
beforeEach(()=>localStorage.clear())
describe('D batch in the real portrait renderer',()=>{
 it.each(cases)('matches approved D%s using public appearance',(id,info)=>{
  const result=art(person('D'+id,info))
  expect(result.key).toBe('P261027D-'+id)
  expect(result.src).toContain('/portraits-v7/')
  expect(result.cinematic).toBe(true)
 })
 it('registers all eight with detailed tags and uses the corrected female dark knight',()=>{
  const assets=portraitCatalog.filter(a=>a.key.startsWith('P261027D-'))
  expect(assets).toHaveLength(8)
  for(const a of assets){expect(a.keywords.length).toBeGreaterThanOrEqual(30);expect(a.background).toBe('transparent')}
  expect(assets.find(a=>a.key==='P261027D-05').src).toContain('-transparent-v2.png')
 })
 it.each(['human','dwarf','矮人'])('allows the stocky smith for public species %s without altering it',species=>{
  const p=person('smith',{...cases[3][1],species})
  expect(art(p).key).toBe('P261027D-04')
  expect(p.publicAppearance.species).toBe(species)
 })
 it.each([
  {archetype:'MECHANIC'},
  {archetype:'MECHANIC',basis:'左手托蓝金机械核心。'},
  {archetype:'MECHANIC',basis:'右前臂为黄铜机械义肢。'},
  {archetype:'MECHANIC',basis:'她曾经有左机械义臂。'},
  {archetype:'MECHANIC',basis:'她没有左机械义臂。'},
  {archetype:'MECHANIC',basis:'据说她有左机械义臂。'},
  {archetype:'MECHANIC',basis:'另一位工匠有左机械义臂。'},
  {archetype:'MECHANIC',anatomy:{leftForearm:'natural',rightForearm:'mechanical prosthesis'}},
  {...cases[2][1],anatomy:{leftForearm:'natural'}},
  {...cases[2][1],basis:cases[2][1].basis+'双手都是天然血肉。'},
  {...cases[2][1],basis:cases[2][1].basis+'右前臂也是机械义肢。'}
 ])('does not infer a left prosthesis from tools, occupation, rumors or conflicting facts: %j',info=>{
  expect(art(person('mechanic',info)).key).not.toBe('P261027D-03')
 })
 it('supports structured public limb facts and keeps secret anatomy out of limited perspective',()=>{
  const p=person('limb',{archetype:'ENGINEER',visibleAnatomy:{leftForearm:'brass-steel mechanical prosthesis',rightHand:'natural'}})
  expect(art(p).key).toBe('P261027D-03')
  const hidden={...person('hidden',{archetype:'MECHANIC'}),appearance:cases[2][1],dossier:{anatomy:{leftForearm:'mechanical prosthesis'}}}
  expect(art(hidden).key).not.toBe('P261027D-03')
  expect(art(hidden,'OMNISCIENT').key).toBe('P261027D-03')
 })
 it('keeps specific ordinary matches while allowing closest-tag illustrations without changing abilities',()=>{
  const ordinary=[
   {archetype:'SCHOLAR',clothing:'深棕皮工作长外套'},
   {gender:'male',archetype:'SCHOLAR',clothing:'深酒红天鹅绒长外套'},
   {archetype:'KNIGHT',clothing:'哑黑钢板甲'},
   {gender:'male',archetype:'KNIGHT',clothing:'黑曜石厚重板甲'},
   {archetype:'WARRIOR',clothing:'黑暗金轻战甲',hairColor:'纯白'},
   {gender:'male',archetype:'JUDGE',clothing:'黑暗金厚重板甲'}
  ]
  for(const info of ordinary){
   const p=person('ordinary',info),before=JSON.stringify(p),result=art(p)
   expect(result.src).toBeTruthy()
   const asset=portraitCatalog.find(item=>item.key===result.key)
   expect(asset.gender).toBe(info.gender||'female');expect(asset.cultures||['WESTERN']).toContain('WESTERN')
   if(result.key.startsWith('P261027D-'))expect(result.matchKind).toBe('closest')
   expect(JSON.stringify(p)).toBe(before)
  }
  expect(art(person('visible',{archetype:'KNIGHT',clothing:'哑黑钢板甲',visibleEquipment:'剑身燃起黑焰'})).key).toBe('P261027D-05')
 })
 it('preserves world, sex, species, visible age and shared-art behavior',()=>{
  for(const [id,info] of cases){
   const p=person('D'+id,info),key='P261027D-'+id
   expect(art(p).src).toBe(art({...p,id:'other-'+id}).src)
   expect(art(p,'DREAMER',{culturalStyle:'EASTERN'}).symbolic).toBe(true)
   expect(art(p,'DREAMER',{culturalStyle:'MODERN'}).symbolic).toBe(true)
   expect(art(person('child',{...info,ageGroup:'CHILD'})).symbolic).toBe(true)
   expect(art(person('elf',{...info,species:'elf'})).key).not.toBe(key)
   expect(art(person('sex',{...info,gender:info.gender==='male'?'female':'male'})).key).not.toBe(key)
  }
 })
 it('uses identical art for the real stage, silhouette and cast thumbnails',()=>{
  const cast=cases.map(([id,info])=>person('D'+id,info))
  const wrapper=mount(CharacterStage,{props:{scene:{worldSetting:west,presentGods:cast.map(p=>({...p,godId:p.id,godName:p.name}))},atmosphere:{key:'default',label:'旅途'},viewMode:'DREAMER',identity:'D:0:DREAMER'}})
  expect(wrapper.find('.paper-person').attributes('src')).toBe(art(cast[0]).src)
  expect(wrapper.find('.portrait-silhouette').attributes('src')).toBe(art(cast[0]).src)
  expect(wrapper.findAll('.cast-portrait-crop img').map(img=>img.attributes('src'))).toEqual(cast.map(p=>art(p).src))
  wrapper.unmount()
 })
})
