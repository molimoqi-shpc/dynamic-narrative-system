import {describe,it,expect,afterEach} from 'vitest'
import {mount} from '@vue/test-utils'
import {readdirSync,existsSync} from 'node:fs'
import {resolve} from 'node:path'
import Library from '../src/components/PortraitLibrary.vue'
import {portraitLibrary,filterPortraitLibrary} from '../src/utils/portraitLibrary'
import approved from '../src/utils/approvedPortraits.json'
const art=resolve('public/art')+'/'
const views=[]
const render=()=>{const view=mount(Library,{props:{open:true},global:{stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot name="header"/><slot/></section>'},ElImage:{name:'ElImage',props:['src','alt','fit','previewSrcList'],template:'<img :src="src" :alt="alt"/>'}}}});views.push(view);return view}
afterEach(()=>{views.forEach(view=>view.unmount());views.length=0})
describe('complete stored portrait library',()=>{
 it('includes every stored portrait and all approved metadata without including backdrops',()=>{
  const files=['paper-theatre-v1','refraction-v1','refraction-v2','portraits-v3','portraits-v4','portraits-v5','portraits-v6','portraits-v7','portraits-v8','portraits-common-v1'].flatMap(dir=>readdirSync(art+dir).filter(name=>name.endsWith('.png') && !name.startsWith('stage-')).map(name=>`/art/${dir}/${name}`))
  expect(new Set(portraitLibrary.map(p=>p.src))).toEqual(new Set(files));expect(portraitLibrary).toHaveLength(files.length)
  for(const asset of portraitLibrary)expect(existsSync(art+'../'+asset.src.slice(1))).toBe(true)
  for(const asset of portraitLibrary)expect(asset.groups.every(group=>group.values.every(value=>typeof value==='string'))).toBe(true)
  for(const asset of approved){const entry=portraitLibrary.find(p=>p.key===asset.key);expect(entry.groups.find(g=>g.label==='特征与关键词').values).toEqual([...new Set(asset.keywords)])}
 })
 it('combines translated tags, original keywords, world and gender filters',()=>{
  expect(filterPortraitLibrary({query:'P261027E-07'}).map(p=>p.label)).toEqual(['寒梅白衣女侠'])
  const results=filterPortraitLibrary({query:'海盗 男性',culture:'WESTERN',gender:'male'})
  expect(results.length).toBeGreaterThan(0);expect(results.every(p=>p.gender==='male' && p.cultures.includes('WESTERN'))).toBe(true)
  expect(filterPortraitLibrary({query:'不存在的藏品'})).toEqual([])
 })
 it('shows complete artwork and every tag, switches entries and supports the empty/reset states',async()=>{
  const view=render();expect(view.findAll('.atlas-card')).toHaveLength(portraitLibrary.length)
  await view.get('input').setValue('P261027E-07');expect(view.findAll('.atlas-card')).toHaveLength(1)
  expect(view.get('.atlas-notes').text()).toContain('寒梅白衣女侠');expect(view.get('.atlas-notes').text()).toContain('完整浅灰绣鞋')
  const full=view.findComponent({name:'ElImage'});expect(full.props('fit')).toBe('contain');expect(full.props('previewSrcList')).toEqual([portraitLibrary.find(p=>p.key==='P261027E-07').src])
  await view.get('input').setValue('不存在的藏品');expect(view.find('.atlas-empty').exists()).toBe(true)
  await view.get('.atlas-empty button').trigger('click');expect(view.findAll('.atlas-card')).toHaveLength(portraitLibrary.length)
  await view.get('[aria-label="下一张立绘"]').trigger('click');expect(view.get('.atlas-notes h3').text()).toBe(portraitLibrary[1].label)
  await view.get('.atlas-close').trigger('click');expect(view.emitted('update:open').at(-1)).toEqual([false])
 })
})
