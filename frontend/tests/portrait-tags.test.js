import {describe,it,expect,beforeEach,vi} from 'vitest'
import {existsSync,readFileSync} from 'node:fs'
import {fileURLToPath} from 'node:url'
import {characterPortrait,portraitCatalog} from '../src/utils/characterPortrait'
const west={culturalStyle:'WESTERN'}
const person=(extra={},id='tags')=>({id,publicAppearance:{gender:'female',ageGroup:'ADULT',species:'human',culturalStyle:'WESTERN',...extra}})
const draw=(extra={},id)=>characterPortrait(person(extra,id),'DREAMER',west)
function closest(result,gender='female',culture='WESTERN'){
 expect(result.src).toBeTruthy();expect(result.matchKind).toBe('closest')
 const asset=portraitCatalog.find(item=>item.key===result.key)
 expect(asset.gender).toBe(gender);expect(asset.cultures||['WESTERN']).toContain(culture)
 return asset
}
beforeEach(()=>localStorage.clear())
describe('approved portrait tags in the real renderer',()=>{
 it('ships only approved new assets, each with visible categories and real PNG data',()=>{
  const approved=portraitCatalog.filter(p=>p.approved)
  expect(approved).toHaveLength(40)
  expect(approved.filter(p=>p.key.startsWith('P261027-')).map(p=>p.key).sort()).toEqual(['P261027-01','P261027-06','P261027-07','P261027-08'])
  expect(approved.find(p=>p.key==='P261026-06').src).toContain('-v2.png')
  expect(approved.find(p=>p.key==='P261026-07').src).toContain('-v2.png')
  expect(approved.find(p=>p.key==='P261026-10').src).toContain('-v3.png')
  for(const p of approved){
   expect(p.keywords.length).toBeGreaterThanOrEqual(12)
   const file=fileURLToPath(new URL('../public'+p.src,import.meta.url))
   expect(existsSync(file)).toBe(true)
   const bytes=readFileSync(file)
   expect([...bytes.subarray(0,8)]).toEqual([137,80,78,71,13,10,26,10])
   expect(bytes.readUInt32BE(16)).toBe(1024);expect(bytes.readUInt32BE(20)).toBe(1536)
   expect(bytes[25]).toBe(6) // RGBA, including the generated alpha.
  }
 })
 it('distinguishes the two academy characters using known hair and clothing',()=>{
  expect(draw({archetype:'MAGE',hairColor:'silver-white',hairLength:'long',clothing:'深灰学院法袍'}).key).toBe('P261026-02')
  expect(draw({archetype:'SCHOLAR',hairColor:'栗棕',hairLength:'长卷发',clothing:'墨蓝学院外袍'}).key).toBe('P261026-04')
  expect(draw({archetype:'SCHOLAR',basis:'她有栗棕长卷发，身穿墨蓝学院外袍。'}).key).toBe('P261026-04')
 })
 it('uses actual dress and occupation for nobles, attendants and female knights',()=>{
  expect(draw({archetype:'NOBLE',clothing:'黑缎'}).key).toBe('P261026-01')
  expect(draw({archetype:'HOUSEKEEPER',clothing:'高领白蕾丝'}).key).toBe('P261026-05')
  expect(draw({archetype:'KNIGHT',clothing:'银钢板甲'}).key).toBe('P261026-03')
  expect(draw({gender:'male',archetype:'KNIGHT',clothing:'银钢板甲'}).key).toBe('P261026-06')
 })
 it('distinguishes the three approved male knights by their public armor and equipment',()=>{
  expect(draw({gender:'male',archetype:'KNIGHT',basis:'他身穿银白铠甲，蓝白战旗覆在一侧肩膀。'}).key).toBe('P261026-06')
  expect(draw({gender:'male',archetype:'KNIGHT',basis:'他身穿深黑钢甲和暗红披风，甲面有战损。'}).key).toBe('P261026-07')
  expect(draw({gender:'male',archetype:'PALADIN',basis:'他身材壮硕，象牙白重甲下双手横握巨剑。'}).key).toBe('P261026-10')
  expect(draw({gender:'female',archetype:'KNIGHT',basis:'她身穿银钢板甲。'}).key).toBe('P261026-03')
  expect(draw({gender:'male',species:'elf',archetype:'RANGER',basis:'他身穿深黑钢甲。'}).key).toBe('P261026-09')
 })
 it('matches male and female moonwhite elves without treating centuries or silver hair as old',()=>{
  expect(draw({species:'elf',age:400,ageGroup:undefined,archetype:'ENVOY',hairColor:'银白',clothing:'月白丝织长袍'}).key).toBe('P261026-08')
  expect(draw({gender:'male',species:'elf',age:500,ageGroup:undefined,archetype:'RANGER',hairColor:'银白',clothing:'月白短披风'}).key).toBe('P261026-09')
  expect(closest(draw({species:'elf',ageGroup:'ELDER',archetype:'ENVOY'})).species).toBe('elf')
  expect(draw({species:'elf',ageGroup:'ELDER',apparentAgeGroup:'ADULT',archetype:'ENVOY'}).key).toBe('P261026-08')
 })
 it('does not obtain a face from secret appearance, psychology or a suggestive name',()=>{
  const p={...person({archetype:'NOBLE'}),name:'银发女法师',appearance:{archetype:'MAGE',hairColor:'silver-white'},psychology:{clothing:'黑缎'}}
  expect(characterPortrait(p,'DREAMER',west).key).toBe('western-noble-female')
  expect(characterPortrait(p,'OMNISCIENT',west).key).toBe('P261026-02')
  const rumor=draw({archetype:undefined,basis:'据说她是银白长发的法师。'})
  closest(rumor);expect(rumor.key).toBe(draw({archetype:undefined}).key)
 })
 it('keeps worlds, species and children compatible, including cached choices',()=>{
  closest(draw({archetype:'KNIGHT',species:'dwarf'}))
  expect(draw({archetype:'MAGE',ageGroup:'CHILD'}).symbolic).toBe(true)
  expect(characterPortrait(person({archetype:'MAGE'}),'DREAMER',{culturalStyle:'EASTERN'}).symbolic).toBe(true)
  const modern={culturalStyle:'MODERN'}
  closest(characterPortrait(person({culturalStyle:'MODERN',archetype:'NOBLE'}),'DREAMER',modern),'female','MODERN')
  expect(characterPortrait(person({culturalStyle:'MODERN',archetype:'NOBLE',clothing:'黑色晚礼服'}),'DREAMER',modern).key).toBe('P261026-01')
  closest(characterPortrait(person({culturalStyle:'MODERN',archetype:'KNIGHT',clothing:'银钢板甲'}),'DREAMER',modern),'female','MODERN')
 })
 it('allows shared portraits and keeps the same visible evidence stable across reloads',()=>{
  const extra={archetype:'MAGE',hairColor:'silver-white'}
  const a=draw(extra,'first'),b=draw(extra,'second')
  expect(a.key).toBe(b.key)
  expect(Object.values(localStorage).some(value=>value.includes('portraitAssetId'))).toBe(true)
  expect(draw({...extra,basis:'仍在门前，与昨日相同。'},'first').key).toBe(a.key)
  draw({archetype:'HOUSEKEEPER'},'first')
  expect(draw(extra,'first').key).toBe(a.key)
 })
 it('still draws without storage access and keeps old special portraits',()=>{
  const spy=vi.spyOn(Storage.prototype,'getItem').mockImplementation(()=>{throw new Error('denied')})
  expect(draw({archetype:'KNIGHT'}).key).toBe('P261026-03')
  spy.mockRestore()
  expect(draw({archetype:'WARRIOR',portraitStyle:'CRIMSON_GOWN'}).key).toBe('western-crimson-gown-female')
  expect(draw({species:'elf',archetype:'ROSE_ELF',hairColor:'银白'}).key).toBe('western-rose-elf-female')
 })
})
