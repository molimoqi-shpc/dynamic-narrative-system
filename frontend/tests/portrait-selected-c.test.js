import {beforeEach,describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import {characterPortrait,portraitCatalog} from '../src/utils/characterPortrait'
import CharacterStage from '../src/components/CharacterStage.vue'
const west={culturalStyle:'WESTERN'}
const person=(id,extra={})=>({id,name:id,publicAppearance:{gender:'female',ageGroup:'ADULT',species:'human',culturalStyle:'WESTERN',...extra}})
const art=(p,scope='DREAMER',world=west)=>characterPortrait(p,scope,world)
function closest(result,gender='female'){
 expect(result.src).toBeTruthy();expect(result.matchKind).toBe('closest')
 const asset=portraitCatalog.find(item=>item.key===result.key)
 expect(asset.gender).toBe(gender);expect(asset.cultures||['WESTERN']).toContain('WESTERN')
 return asset
}
const cases=[
 ['01',{archetype:'THIEF',basis:'高马尾的女盗贼穿深青皮革长风衣，掌中托着冰蓝宝石。'}],
 ['02',{archetype:'双剑女骑士',basis:'她穿深蓝钢板甲，手持一黑一银的双剑。'}],
 ['03',{gender:'male',archetype:'双剑男骑士',basis:'他穿炭灰暗红板甲，两柄长剑交叉。'}],
 ['04',{archetype:'KNIGHT',basis:'她穿珍珠白淡紫板甲，身旁站着白色独角兽，牵着缰绳。'}],
 ['05',{gender:'male',archetype:'KNIGHT',basis:'他穿蓝白战袍，身旁站着灰色苹果斑独角兽。'}],
 ['06',{archetype:'MAGE',basis:'她戴深紫宽檐巫帽，胸前漂浮水晶球，身旁环绕塔罗牌。'}],
 ['07',{gender:'male',archetype:'MAGE',basis:'他银发束成低马尾，身穿石板灰长外套，掌中凝聚白蓝电光。'}],
 ['08',{archetype:'吟游诗人',basis:'她穿酒红天鹅绒裙，抱着木质鲁特琴弹唱。'}]
]
beforeEach(()=>localStorage.clear())
describe('C batch approved portrait integration',()=>{
 it.each(cases)('matches C%s from the character public traits',(id,info)=>{
  const portrait=art(person('C'+id,info))
  expect(portrait.key).toBe('P261027C-'+id)
  expect(portrait.src).toContain('/portraits-v6/')
  expect(portrait.src).toContain('-transparent.png')
  expect(portrait.cinematic).toBe(true)
 })
 it('keeps all eight assets tagged and the two compositions separate',()=>{
  const assets=portraitCatalog.filter(p=>p.key.startsWith('P261027C-'))
  expect(assets).toHaveLength(8)
  expect(assets.filter(a=>a.composition==='character-and-mount').map(a=>a.key)).toEqual(['P261027C-04','P261027C-05'])
  for(const a of assets){expect(a.keywords.length).toBeGreaterThanOrEqual(30);expect(a.cultures).toEqual(['WESTERN'])}
 })
 it('does not give ordinary knights mounts based on clothing, profession or unicorn insignia',()=>{
  for(const info of [
   {archetype:'KNIGHT',basis:'她穿珍珠白淡紫板甲。'},
   {archetype:'UNICORN_CAVALIER'},
   {archetype:'KNIGHT',basis:'她的头盔上刻着独角兽徽记。'},
   {archetype:'KNIGHT',basis:'她身旁的壁画上有白色独角兽。'},
   {archetype:'KNIGHT',basis:'她看着另一位骑士身旁的白色独角兽。'},
   {archetype:'KNIGHT',basis:'从前有白色独角兽站在她身旁。'},
   {archetype:'KNIGHT',basis:'她身旁没有独角兽。'}
  ])expect(['P261027C-04','P261027C-05']).not.toContain(art(person('ordinary',info)).key)
 })
 it('supports explicit public mounts but refuses absent, wrong species or incompatible coats',()=>{
  expect(art(person('f',{archetype:'KNIGHT',visibleMount:{species:'unicorn',present:true,coat:'pure-white'},clothing:'珍珠白淡紫板甲'})).key).toBe('P261027C-04')
  expect(art(person('m',{gender:'male',archetype:'KNIGHT',visibleMount:{species:'unicorn',present:true,coat:'dapple-gray'},clothing:'蓝白战袍'})).key).toBe('P261027C-05')
  for(const mount of [
   {species:'unicorn',present:false,coat:'pure-white'},
   {species:'horse',present:true,coat:'pure-white'},
   {species:'unicorn',present:true,coat:'black'},
   {species:'unicorn',present:true,coat:'dapple-gray'}
  ])expect(art(person('none',{...cases[3][1],visibleMount:mount})).key).not.toBe('P261027C-04')
 })
 it('keeps mounts out of limited perspective when they appear only in private appearance',()=>{
  const p={...person('hidden',{archetype:'KNIGHT'}),appearance:cases[3][1],dossier:{mount:{species:'unicorn',present:true,coat:'pure-white'}}}
  expect(art(p).key).not.toBe('P261027C-04')
  expect(art(p,'OMNISCIENT').key).toBe('P261027C-04')
 })
 it('preserves specific matches and treats a nearest outfit picture as presentation rather than new abilities',()=>{
  expect(art(person('one',{archetype:'KNIGHT',clothing:'深蓝钢板甲',visibleEquipment:'一把长剑'})).key).not.toBe('P261027C-02')
  expect(art(person('noble',{archetype:'NOBLE',clothing:'酒红天鹅绒裙'})).key).not.toBe('P261027C-08')
  const mage=person('mage',{gender:'male',archetype:'MAGE',clothing:'石板灰长外套'}),before=JSON.stringify(mage)
  closest(art(mage),'male');expect(JSON.stringify(mage)).toBe(before)
 })
 it('keeps worlds, races and visible ages compatible for the new catalog',()=>{
  for(const [id,info] of cases){
   const p=person('C'+id,info)
   expect(art(p,'DREAMER',{culturalStyle:'EASTERN'}).symbolic).toBe(true)
   expect(art(p,'DREAMER',{culturalStyle:'MODERN'}).symbolic).toBe(true)
   const dwarf={...p,publicAppearance:{...p.publicAppearance,species:'dwarf'}},before=JSON.stringify(dwarf)
   closest(art(dwarf),info.gender||'female');expect(JSON.stringify(dwarf)).toBe(before)
   expect(art({...p,publicAppearance:{...p.publicAppearance,ageGroup:'CHILD'}}).symbolic).toBe(true)
  }
 })
 it('shares matching art, preserves look across reloads and drops a composite after the mount departs',()=>{
  const p=person('owner',cases[3][1])
  expect(art(p).src).toBe(art({...p,id:'second-owner'}).src)
  const first=art(p).src
  expect(art({...p,publicAppearance:{...p.publicAppearance,basis:p.publicAppearance.basis+'她静静等候。'}}).src).toBe(first)
  expect(art({...p,publicAppearance:{...p.publicAppearance,visibleMount:false}}).key).not.toBe('P261027C-04')
  expect(art(p).src).toBe(first)
 })
 it('renders the C catalog in the real stage and uses the same resource for silhouette and thumbnails',()=>{
  const cast=cases.map(([id,info])=>person('C'+id,info))
  const wrapper=mount(CharacterStage,{props:{scene:{worldSetting:west,presentGods:cast.map(p=>({...p,godId:p.id,godName:p.name}))},atmosphere:{key:'default',label:'旅途'},viewMode:'DREAMER',identity:'C:0:DREAMER'}})
  expect(wrapper.find('.paper-person').attributes('src')).toContain('01-shadow-silk')
  expect(wrapper.find('.portrait-silhouette').attributes('src')).toBe(wrapper.find('.paper-person').attributes('src'))
  expect(wrapper.findAll('.cast-portrait-crop img').map(img=>img.attributes('src'))).toEqual(cast.map(p=>art(p).src))
  wrapper.unmount()
 })
})
