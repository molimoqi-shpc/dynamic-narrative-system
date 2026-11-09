import {beforeEach,describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import {characterPortrait,portraitCatalog} from '../src/utils/characterPortrait'
import CharacterStage from '../src/components/CharacterStage.vue'
const world={culturalStyle:'WESTERN'}
const person=(extra={})=>({id:'b-person',name:'人物',publicAppearance:{gender:'female',ageGroup:'ADULT',culturalStyle:'WESTERN',...extra}})
const art=(p,scope='DREAMER',setting=world)=>characterPortrait(p,scope,setting)
function closest(result,gender='female'){
 expect(result.src).toBeTruthy();expect(result.matchKind).toBe('closest')
 const asset=portraitCatalog.find(item=>item.key===result.key)
 expect(asset.gender).toBe(gender);expect(asset.cultures||['WESTERN']).toContain('WESTERN')
 return asset
}
const dragon=()=>person({species:'龙裔',archetype:'龙裔',basis:'她长着深红龙角和赤红龙翼，红色鳞尾垂在身后，身穿红黑提花长裙。'})
const knight=()=>person({species:'human',archetype:'修女骑士',basis:'她将浅金发盘成低发髻，银白轻板甲覆在黑白修女袍上，手持银色长柄战锤。'})
beforeEach(()=>localStorage.clear())
describe('B batch approved portraits',()=>{
 it('loads only B03 and B07 with distinct race and equipment tags',()=>{
  const added=portraitCatalog.filter(p=>p.key.startsWith('P261027B-'))
  expect(added.map(p=>p.key).sort()).toEqual(['P261027B-03','P261027B-07'])
  expect(added.find(p=>p.key==='P261027B-03').species).toBe('dragonborn')
  expect(added.find(p=>p.key==='P261027B-07').roles).not.toContain('NUN')
  for(const p of added){expect(p.keywords.length).toBeGreaterThanOrEqual(30);expect(p.src).toContain('-transparent.png')}
 })
 it('matches Chinese and English dragonborn and supports a public legacy other packet',()=>{
  expect(art(dragon()).key).toBe('P261027B-03')
  expect(art(person({species:'DRAGONBORN',archetype:'TRAVELER'})).key).toBe('P261027B-03')
  expect(art(person({species:'other',archetype:'TRAVELER',basis:'主角亲见她是一位龙裔，收拢赤红的龙翼。'})).key).toBe('P261027B-03')
  expect(art(person({archetype:'DRAGONBORN'})).key).toBe('P261027B-03')
 })
 it('does not equate dragon riders, humans, elves or unknown private ancestry with dragonborn',()=>{
  const privateRole={...person({species:'other'}),name:'红龙公主',appearance:dragon().publicAppearance,dossier:{bloodline:'龙裔'},psychology:{secret:'赤龙'}}
  expect(closest(art(privateRole)).species).toBe('human')
  expect(art(privateRole).key).toBe(art(person({species:'other'})).key)
  expect(art(privateRole,'OMNISCIENT').key).toBe('P261027B-03')
  expect(art(person({species:'human',archetype:'NOBLE',clothing:'红黑提花长裙'})).key).not.toBe('P261027B-03')
  expect(art(person({species:'elf',archetype:'NOBLE'})).key).not.toBe('P261027B-03')
  expect(art(person({species:'human',gender:'male',archetype:'DRAGON_RIDER'})).key).toBe('western-dragon-rider-male')
 })
 it('allows a nearest color or species approximation without crossing gender, childhood or private ancestry',()=>{
  const blue=person({species:'dragonborn',basis:'银白龙角，脸颊覆盖冰蓝鳞片。'}),before=JSON.stringify(blue)
  expect(closest(art(blue)).species).toBe('dragonborn');expect(JSON.stringify(blue)).toBe(before)
  expect(closest(art(person({species:'dragonborn',scaleColor:'silver-blue'}))).species).toBe('dragonborn')
  closest(art(person({species:'dragonborn',gender:'male'})),'male')
  expect(art(person({species:'dragonborn',ageGroup:'CHILD'})).symbolic).toBe(true)
  expect(closest(art(person({species:'other',basis:'据说她是龙裔。'}))).species).toBe('human')
  expect(closest(art(person({species:'other',basis:'她并不是龙裔。'}))).species).toBe('human')
 })
 it('matches armored nuns from public words without turning a plain nun or mage into a paladin',()=>{
  expect(art(knight()).key).toBe('P261027B-07')
  expect(art(person({species:'human',archetype:'NUN_KNIGHT'})).key).toBe('P261027B-07')
  const nun=art(person({species:'human',archetype:'NUN'}));closest(nun);expect(nun.key).not.toBe('P261027B-07')
  expect(art(person({species:'human',archetype:'MAGE',hairColor:'silver-white'})).key).toBe('P261026-02')
  expect(art(person({species:'human',gender:'male',archetype:'PALADIN'})).key).not.toBe('P261027B-07')
 })
 it('keeps perspective, cultural style, old readbacks and same-type reuse separate',()=>{
  for(const p of [dragon(),knight()]){
   expect(art(p,'DREAMER',{culturalStyle:'EASTERN'}).symbolic).toBe(true)
   expect(art(p,'DREAMER',{culturalStyle:'MODERN'}).symbolic).toBe(true)
   expect(art(p).src).toBe(art({...p,id:'another-id'}).src)
  }
  const first=art(knight()).src
  art(dragon(),'OMNISCIENT')
  expect(art(knight()).src).toBe(first)
 })
 it('uses both approved resources in the real stage, cast thumbnails and silhouette',()=>{
  const cast=[{...dragon(),id:'b-dragon'},{...knight(),id:'b-knight'}]
  const wrapper=mount(CharacterStage,{props:{scene:{worldSetting:world,presentGods:cast.map(p=>({...p,godId:p.id,godName:p.name}))},atmosphere:{key:'default',label:'旅途'},viewMode:'DREAMER',identity:'B:0:DREAMER'}})
  expect(wrapper.find('.paper-person').attributes('src')).toContain('03-ember-dragonborn')
  expect(wrapper.find('.portrait-silhouette').attributes('src')).toBe(wrapper.find('.paper-person').attributes('src'))
  expect(wrapper.findAll('.cast-portrait-crop img').map(img=>img.attributes('src'))).toEqual(cast.map(p=>art(p).src))
  wrapper.unmount()
 })
})
