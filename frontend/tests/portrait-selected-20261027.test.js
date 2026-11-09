import {beforeEach,describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import {characterPortrait,portraitCatalog} from '../src/utils/characterPortrait'
import CharacterStage from '../src/components/CharacterStage.vue'
const world={culturalStyle:'WESTERN'}
const appearance=(extra={})=>({gender:'female',ageGroup:'ADULT',species:'human',culturalStyle:'WESTERN',...extra})
const role=(id,extra={})=>({id,name:id,publicAppearance:appearance(extra)})
const art=(p,scope='DREAMER',setting=world)=>characterPortrait(p,scope,setting)
beforeEach(()=>localStorage.clear())
const elf=()=>role('elf',{species:'elf',archetype:'ELF',basis:'银白长发的精灵身穿月白长裙和淡青纱袍，手持白木法杖。'})
const thief=()=>role('thief',{gender:'male',archetype:'盗贼',basis:'栗色头发束在脑后，穿深绿天鹅绒外套和米白丝衬衫，手中转着金色钥匙。'})
const knight=()=>role('knight',{gender:'male',archetype:'KNIGHT',basis:'金褐短发的骑士身穿白银精工板甲和羽翼肩甲，手持圆盾与符文长剑。'})
const priestess=()=>role('priestess',{archetype:'女祭司',basis:'乌黑长发，靛蓝长裙外披黑纱，银色月相刺绣清晰，双手捧着圣典。'})
describe('four selected western portraits',()=>{
 it.each([[elf,'01'],[thief,'06'],[knight,'07'],[priestess,'08']])('selects the approved image from public appearance: %s', (factory,id)=>{
  const portrait=art(factory())
  expect(portrait.key).toBe('P261027-'+id)
  expect(portrait.src).toContain('/portraits-v4/')
  expect(portrait.src).toContain('-transparent.png')
  expect(portrait.cinematic).toBe(true)
 })
 it('uses detailed staff and armor instead of swapping similar old elf and knight portraits',()=>{
  expect(art(role('old-elf',{species:'elf',archetype:'ENVOY',clothing:'月白丝织长袍'})).key).toBe('P261026-08')
  expect(art(role('flag',{gender:'male',archetype:'KNIGHT',clothing:'蓝白战旗'})).key).toBe('P261026-06')
  expect(art(role('greatsword',{gender:'male',archetype:'PALADIN',clothing:'象牙白重甲',visibleEquipment:'巨剑'})).key).toBe('P261026-10')
 })
 it('does not force an elf face onto a human or use the priestess for an elderly cleric',()=>{
  expect(art({...elf(),publicAppearance:{...elf().publicAppearance,species:'human'}}).key).not.toBe('P261027-01')
  expect(art({...priestess(),publicAppearance:{...priestess().publicAppearance,gender:'male',ageGroup:'ELDER',archetype:'CLERIC'}}).key).toBe('western-cleric-male')
  expect(art({...thief(),publicAppearance:{...thief().publicAppearance,ageGroup:'CHILD'}}).symbolic).toBe(true)
 })
 it('does not infer a thief from the name, hidden biography or private intent',()=>{
  const p={...role('public-noble',{gender:'male',archetype:'NOBLE'}),name:'雷纳德·金指',appearance:thief().publicAppearance,dossier:{secret:'盗贼'}}
  const before=JSON.stringify(p),fallback=art(p),asset=portraitCatalog.find(item=>item.key===fallback.key)
  expect(fallback.src).toBeTruthy();expect(fallback.matchKind).toBe('closest')
  expect(asset.gender).toBe('male');expect(asset.cultures||['WESTERN']).toContain('WESTERN')
  expect(fallback.key).not.toBe('P261027-06');expect(JSON.stringify(p)).toBe(before)
  expect(art(p,'OMNISCIENT').key).toBe('P261027-06')
  expect(art(thief(),'DREAMER',{culturalStyle:'MODERN'}).symbolic).toBe(true)
  expect(art(elf(),'DREAMER',{culturalStyle:'EASTERN'}).symbolic).toBe(true)
 })
 it('keeps both same-type sharing and stable artwork after unrelated public description changes',()=>{
  const a=thief(),b={...thief(),id:'another-thief',name:'另一位人物'}
  expect(art(a).src).toBe(art(b).src)
  expect(art({...a,publicAppearance:{...a.publicAppearance,basis:a.publicAppearance.basis+'他在门外等候。'}}).src).toBe(art(a).src)
 })
 it('renders the selected art in the real stage, silhouette and cast thumbnails',()=>{
  const cast=[elf(),thief(),knight(),priestess()]
  const wrapper=mount(CharacterStage,{props:{scene:{worldSetting:world,presentGods:cast.map(p=>({...p,godId:p.id,godName:p.name}))},atmosphere:{key:'default',label:'旅途'},viewMode:'DREAMER',identity:'selected-four:DREAMER'}})
  expect(wrapper.find('.paper-person').attributes('src')).toContain('01-moonwatcher-elf')
  expect(wrapper.find('.portrait-silhouette').attributes('src')).toBe(wrapper.find('.paper-person').attributes('src'))
  expect(wrapper.findAll('.cast-portrait-crop img').map(img=>img.attributes('src'))).toEqual(cast.map(p=>art(p).src))
  expect(wrapper.findAll('.cast-portrait-crop img')).toHaveLength(4)
  wrapper.unmount()
 })
 it('tags only the approved selection and avoids invented story roles for clothing matches',()=>{
  const items=portraitCatalog.filter(p=>p.key.startsWith('P261027-'))
  expect(items.map(p=>p.key).sort()).toEqual(['P261027-01','P261027-06','P261027-07','P261027-08'])
  for(const item of items){
   expect(item.keywords.length).toBeGreaterThanOrEqual(30)
   expect(item.equipment.length).toBeGreaterThanOrEqual(3)
   expect(item.cultures).toEqual(['WESTERN'])
  }
  expect(items.find(p=>p.key==='P261027-06').roles).not.toContain('DIPLOMAT')
  expect(items.find(p=>p.key==='P261027-08').roles).not.toContain('MAGE')
 })
})
