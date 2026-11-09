import {describe,it,expect} from 'vitest'
import {mount} from '@vue/test-utils'
import Note from '../src/components/StoryDecisionNote.vue'
describe('story decision consequences',()=>{
 it('labels earlier choices as history while retaining their original consequences',()=>{
  const panel=mount(Note,{props:{current:false,progress:{reason:'是否担保？',requiresInput:true,stakes:'承担连带赔偿责任。'}}})
  expect(panel.text()).toContain('当时的选择');expect(panel.text()).toContain('连带赔偿');expect(panel.text()).not.toContain('此刻需要你表态');panel.unmount()
 })
 it('shows the actual new player obligation beside the question',()=>{
  const panel=mount(Note,{props:{progress:{reason:'是否为她担保？',requiresInput:true,stakes:'若日后款项不实，担保人可能承担赔款。'}}})
  expect(panel.text()).toContain('此刻需要你表态');expect(panel.text()).toContain('承担赔款');panel.unmount()
 })
 it('keeps ordinary progress free of an obsolete decision and renders text safely',()=>{
  const panel=mount(Note,{props:{progress:{reason:'旧线索已得到解释。',requiresInput:false,stakes:'OLD_OBLIGATION'}}})
  expect(panel.text()).toContain('这一段的进展');expect(panel.text()).not.toContain('OLD_OBLIGATION');panel.unmount()
  const text=mount(Note,{props:{progress:{reason:'<img src=x>',requiresInput:true,stakes:'<script>bad()</script>'}}})
  expect(text.find('img').exists()).toBe(false);expect(text.find('script').exists()).toBe(false);text.unmount()
 })
})
