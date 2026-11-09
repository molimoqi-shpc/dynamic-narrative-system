import {describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import {nextTick} from 'vue'
import StoryHistory from '../src/components/StoryHistory.vue'
import ReadingSettings from '../src/components/ReadingSettings.vue'
import {readingDefaults,normalizeReading,loadReading,saveReading,readingVariables,READING_KEY} from '../src/utils/readingPreferences'
import {characterPortrait} from '../src/utils/characterPortrait'

describe('opening history and reading preferences',()=>{
 it('lets a floating reader control change size without trapping outside input, and closes with Escape',async()=>{
  const outside=document.createElement('textarea');document.body.append(outside)
  const view=mount(ReadingSettings,{attachTo:document.body,props:{open:true,modelValue:{...readingDefaults}},global:{stubs:{teleport:true}}})
  await nextTick();outside.focus();expect(document.activeElement).toBe(outside)
  await view.get('input[aria-label="字号"]').setValue(21);expect(view.emitted('update:modelValue').at(-1)[0].size).toBe(21)
  window.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));expect(view.emitted('update:open').at(-1)).toEqual([false]);view.unmount();outside.remove()
 })
 it('opens the saved initial story with zero rounds and escapes prose safely',()=>{
  const view=mount(StoryHistory,{props:{opening:{title:'序章 · 来路',text:'从钟表铺长大。\n\n<img src=x>'},turns:[],storyText:t=>t.text}})
  expect(view.find('.archive-passage').text()).toContain('从钟表铺长大');expect(view.findAll('.story-prose p')).toHaveLength(2);expect(view.find('img').exists()).toBe(false);view.unmount()
 })
 it('keeps opening once when older turns arrive and preserves the selected chapter',async()=>{
  const view=mount(StoryHistory,{props:{opening:{text:'原始来路'},hasMore:true,turns:[{id:'saved-4',round:4,text:'第四段'}],storyText:t=>t.text}})
  await view.findAll('nav button').at(-1).trigger('click');expect(view.find('.archive-passage').text()).toContain('第四段')
  await view.setProps({turns:[{id:'saved-1',round:1,text:'第一段'},{id:'saved-4',round:4,text:'第四段'}]})
  expect(view.findAll('nav button').filter(b=>b.text().includes('序章'))).toHaveLength(1);expect(view.find('.archive-passage').text()).toContain('第四段');view.unmount()
 })
 it('explains legacy missing openings and offers a retry for failed reads',async()=>{
  const view=mount(StoryHistory,{props:{turns:[],error:true,storyText:t=>t.text}})
  await view.get('button').trigger('click');expect(view.emitted('retry')).toHaveLength(1)
  await view.setProps({error:false});expect(view.text()).toContain('暂未留存故事');view.unmount()
 })
 it('recovers damaged stored settings and prevents nonnumeric or invalid font styles',()=>{
  expect(loadReading({getItem:()=>'{broken'})).toEqual(readingDefaults)
  expect(normalizeReading(null)).toEqual(readingDefaults)
  expect(normalizeReading({size:Infinity,spacing:'2px',font:'url(https://invalid)'})).toEqual(readingDefaults)
  expect(normalizeReading({size:999,leading:-2,density:9})).toMatchObject({size:24,leading:1.5,density:1.6})
 })
 it('persists reading preferences and derives the same line ruler measures',()=>{
  const storage=new Map(),adapter={getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v)}
  const value={size:20,spacing:1.2,leading:1.8,density:.6,font:'kai',narrativeMode:'LONG'};saveReading(adapter,value)
  expect(storage.has(READING_KEY)).toBe(true);expect(loadReading(adapter)).toEqual(value)
  expect(readingVariables(value)).toMatchObject({'--reader-font-size':'20px','--reader-leading':1.8,'--reader-spacing':'1.2px','--reader-density':.6})
  expect(()=>saveReading({setItem:()=>{throw Error('disabled')}},value)).not.toThrow()
 })
 it('uses an explicit gown without changing warrior identity or dressing an incompatible person',()=>{
  const west={culturalStyle:'WESTERN'},pub={gender:'female',ageGroup:'ADULT',culturalStyle:'WESTERN',archetype:'WARRIOR',species:'human',portraitStyle:'CRIMSON_GOWN'}
  expect(characterPortrait({publicAppearance:pub},'DREAMER',west).key).toBe('western-crimson-gown-female')
  expect(characterPortrait({publicAppearance:{...pub,portraitStyle:''}},'DREAMER',west).key).toBe('western-warrior-female')
  for(const override of [{gender:'male'},{ageGroup:'CHILD'},{species:'elf'}])expect(characterPortrait({publicAppearance:{...pub,...override}},'DREAMER',west).key).not.toBe('western-crimson-gown-female')
  expect(characterPortrait({publicAppearance:pub},'DREAMER',{culturalStyle:'EASTERN'}).src).toBeNull()
  expect(characterPortrait({publicAppearance:{...pub,portraitStyle:''},appearance:pub},'DREAMER',west).key).toBe('western-warrior-female')
 })
})
