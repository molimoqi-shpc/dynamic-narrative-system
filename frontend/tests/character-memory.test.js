import {describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import Entry from '../src/components/CharacterMemoryEntry.vue'
import Dossier from '../src/components/DossierFields.vue'
import {characterMemory} from '../src/utils/characterMemory'

describe('readable personal history',()=>{
 it('gives saved biography normal full paragraphs instead of a stack of field and array rows',()=>{
  const prose='她在渡口做了许多年抄写员。'.repeat(35)+'\n\n后来才接手这间小铺。'
  const entry={id:1,title:'人物来历',type:'SELF_PROFILE',content:JSON.stringify({name:'知秋',personalHistory:prose,source:'OPENING_AUTHOR'})},original=JSON.stringify(entry)
  const view=mount(Entry,{props:{entry}})
  try{expect(view.findAll('.memory-prose-block p')).toHaveLength(2);expect(view.find('.memory-passage').text()).toContain('后来才接手');expect(view.find('.memory-passage .dossier-fields').exists()).toBe(false);expect(view.text()).not.toMatch(/personalHistory|OPENING_AUTHOR|\{"/);expect(JSON.stringify(entry)).toBe(original)}finally{view.unmount()}
 })
 it('shows one copy of repeated prose, places actual time in the heading and keeps game ticks out of dates',()=>{
  const entry={id:2,title:'角色经历',type:'TURN_EVENT',tick:9000,content:{recordType:'CHAPTER_EXPERIENCE',text:'她已经把账册还给了对方。',situation:'她已经把账册还给了对方。',startSeconds:0,endSeconds:3600,visibility:'PRIVATE'}}
  const view=mount(Entry,{props:{entry,currentSeconds:3600}})
  try{expect(view.findAll('.memory-prose-block p')).toHaveLength(1);expect(view.find('.memory-time').text()).toContain('→');expect(view.text()).not.toMatch(/9000|startSeconds|endSeconds|PRIVATE|CHAPTER_EXPERIENCE/)}finally{view.unmount()}
 })
 it('distinguishes old intentions from events and retains their detailed structured fields',()=>{
  const entry={id:3,type:'ROLE_PROFILE',title:'当时的打算',content:{executionStatus:'PROPOSED',thought:'不想再欠对方人情。',direction:{intention:'愿意替她带路',boundaries:'只到渡口为止'}}}
  const view=mount(Entry,{props:{entry}})
  try{expect(view.find('.memory-intention-note').text()).toContain('不等于已经完成');expect(view.find('.memory-passage').text()).toContain('不想再欠');expect(view.find('.memory-passage').text()).toContain('只到渡口');expect(view.find('details').exists()).toBe(false)}finally{view.unmount()}
 })
 it('keeps unknown future fields accessible without dumping their raw names or technical payloads',()=>{
  const entry={id:4,type:'PERSONAL',title:'留存记录',content:{customBiography:'她曾替人核对过潮历。',rawResponse:'INTERNAL_PROVIDER_REPLY',source_ref:'INTERNAL_REF'}}
  const view=mount(Entry,{props:{entry}})
  try{expect(view.get('details').attributes('open')).toBeDefined();expect(view.text()).toContain('核对过潮历');expect(view.text()).not.toMatch(/customBiography|INTERNAL_PROVIDER_REPLY|INTERNAL_REF/)}finally{view.unmount()}
 })
 it('escapes saved markup and translates references in expanded history',()=>{
  const entry={id:5,type:'TURN_EVENT',title:'一次相逢',content:{text:'<img src=x onerror=alert(1)>',targetId:'npc_1234567890'}}
  const view=mount(Entry,{props:{entry,names:{npc_1234567890:'陆衡'}}})
  try{expect(view.text()).toContain('<img src=x');expect(view.find('img,script,[onerror]').exists()).toBe(false);expect(view.text()).toContain('陆衡');expect(view.text()).not.toContain('npc_1234567890')}finally{view.unmount()}
 })
 it('preserves standalone prose and string-array memories as readable passages',()=>{
  expect(characterMemory({content:'过去的普通叙述。',type:'PERSONAL'}).blocks[0].paragraphs).toEqual(['过去的普通叙述。'])
  expect(characterMemory({content:['第一段。','第二段。'],type:'PERSONAL'}).blocks).toHaveLength(2)
 })
 it('uses horizontal rows for simple fields and wraps short traits while preserving normal dossier rendering',()=>{
  const value={personality:{stableTraits:['寡言','重承诺'],stressResponse:'先确认对方的处境，再决定如何帮助。'}}
  const view=mount(Dossier,{props:{value,reading:true}}),normal=mount(Dossier,{props:{value}})
  try{expect(view.find('.dossier-tags').text()).toContain('重承诺');expect(view.findAll('.dossier-reading')).toHaveLength(2);expect(view.text()).toContain('再决定如何帮助');expect(normal.find('.dossier-reading,.dossier-tags').exists()).toBe(false)}finally{view.unmount();normal.unmount()}
 })
})
