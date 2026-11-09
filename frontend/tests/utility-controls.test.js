import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {createPinia} from 'pinia'
import {useSessionStore} from '../src/store/session'
import LoginView from '../src/views/LoginView.vue'
import MusicPlayer from '../src/components/MusicPlayer.vue'
import StatFormulaDialog from '../src/components/StatFormulaDialog.vue'

const mocks=vi.hoisted(()=>({post:vi.fn(),replace:vi.fn()}))
vi.mock('../src/api',()=>({default:{post:mocks.post}}))
vi.mock('vue-router',()=>({useRouter:()=>({replace:mocks.replace}),useRoute:()=>({query:{}})}))
const stubs={AmbientParticles:true,CelestialTitle:true,CompassMark:true,FlightMark:true,EtchedOrnament:true}
let views=[]
const render=(component,options={})=>{const view=mount(component,{global:{plugins:[createPinia()],stubs},...options});views.push(view);return view}
beforeEach(()=>{localStorage.clear();sessionStorage.clear();mocks.post.mockReset();mocks.replace.mockReset()})
afterEach(()=>{views.forEach(v=>v.unmount());views=[];vi.restoreAllMocks()})

describe('account entry native form recovery',()=>{
 it.each([true,false])('does not replace a newer account after an old login returns (unmounted: %s)',async unmounted=>{
  let finish;mocks.post.mockReturnValue(new Promise(resolve=>{finish=resolve}));const view=render(LoginView)
  await view.get('[name=username]').setValue('fixture_reader');await view.get('[name=password]').setValue('fixture-password');await view.get('form').trigger('submit')
  if(unmounted)view.unmount();const store=useSessionStore();store.clearSession();store.setActiveSession({playerId:'new-account'});localStorage.setItem('bs_token','new-token')
  finish({token:'old-late-token',playerId:'old-account'});await flushPromises()
  expect(localStorage.getItem('bs_token')).toBe('new-token');expect(store.playerId).toBe('new-account');expect(mocks.replace).not.toHaveBeenCalled()
 })
 it('submits native autofilled values even without Vue input events',async()=>{
  mocks.post.mockResolvedValue({token:'fixture-token',playerId:'fixture-player'})
  const view=render(LoginView)
  view.get('[name=username]').element.value='fixture_reader'
  view.get('[name=password]').element.value='fixture-password'
  expect(view.get('[type=submit]').element.disabled).toBe(false)
  await view.get('form').trigger('submit');await flushPromises()
  expect(mocks.post).toHaveBeenCalledWith('/auth/login',{username:'fixture_reader',password:'fixture-password'})
  expect(mocks.replace).toHaveBeenCalledWith('/home')
 })
 it('keeps registration mismatch local and makes no request',async()=>{
  const view=render(LoginView)
  await view.findAll('.entry-tabs button')[1].trigger('click')
  await view.get('[name=username]').setValue('fixture_reader')
  await view.get('[name=password]').setValue('fixture-password')
  await view.get('[name=confirmPassword]').setValue('different-password')
  await view.get('form').trigger('submit')
  expect(view.get('[role=alert]').text()).toContain('不一致')
  expect(mocks.post).not.toHaveBeenCalled()
 })
 it('rejects duplicate submit without wiping disabled fields',async()=>{
  let finish;mocks.post.mockReturnValue(new Promise(resolve=>{finish=resolve}))
  const view=render(LoginView)
  await view.get('[name=username]').setValue('fixture_reader')
  await view.get('[name=password]').setValue('fixture-password')
  await view.get('form').trigger('submit');await view.get('form').trigger('submit')
  expect(mocks.post).toHaveBeenCalledTimes(1)
  expect(view.get('[name=password]').element.value).toBe('fixture-password')
  finish({token:'fixture-token',playerId:'fixture-player'});await flushPromises()
  expect(mocks.replace).toHaveBeenCalledTimes(1)
 })
})

describe('music controls preserve audio independently of their panel',()=>{
 const media=()=>{
  vi.spyOn(HTMLMediaElement.prototype,'pause').mockImplementation(function(){this.dispatchEvent(new Event('pause'))})
  vi.spyOn(HTMLMediaElement.prototype,'load').mockImplementation(()=>{})
  return vi.spyOn(HTMLMediaElement.prototype,'play').mockImplementation(function(){this.dispatchEvent(new Event('play'));return Promise.resolve()})
 }
 it('does not autoplay and closes the panel without stopping an explicit playback',async()=>{
  const play=media(),view=render(MusicPlayer)
  expect(play).not.toHaveBeenCalled()
  await view.get('[aria-label="展开背景音乐播放台"]').trigger('click')
  await view.get('[aria-label="播放背景音乐"]').trigger('click');await flushPromises()
  window.dispatchEvent(new Event('close-music-panel'));await flushPromises()
  expect(view.find('.music-console').exists()).toBe(false)
  await view.get('[aria-label="展开背景音乐播放台"]').trigger('click')
  expect(view.find('[aria-label="暂停背景音乐"]').exists()).toBe(true)
  expect(play).toHaveBeenCalledTimes(1)
 })
 it('seeks against real duration and restores the prior nonzero volume',async()=>{
  media();const view=render(MusicPlayer),audio=view.get('audio')
  Object.defineProperty(audio.element,'duration',{value:120,configurable:true})
  await audio.trigger('loadedmetadata')
  await view.get('[aria-label="展开背景音乐播放台"]').trigger('click')
  await view.get('[aria-label="播放进度"]').setValue('60')
  expect(audio.element.currentTime).toBe(60)
  expect(view.get('.music-timeline').text()).toContain('1:00')
  await view.get('[aria-label="背景音乐音量"]').setValue('0.65')
  await view.get('[aria-label="静音"]').trigger('click');expect(audio.element.volume).toBe(0)
  await view.get('[aria-label="恢复音量"]').trigger('click');expect(audio.element.volume).toBe(.65)
  expect(JSON.parse(localStorage.getItem('bs_music_preferences')).volume).toBe(.65)
 })
})

describe('formula manuscript uses authoritative server detail',()=>{
 it('switches six-stat entries without recomputing the server result',async()=>{
  const sample={description:'服务端定义',base:17,growthValue:2,masteryMultiplier:3,synergyMultiplier:4,unrounded:24,supportNames:['慧','闻'],supportBases:[1,2],synergy:.1}
  const view=render(StatFormulaDialog,{props:{modelValue:true,book:{stats:[{...sample,key:'zhi',label:'知',actual:731,formula:'服务端公式甲'},{...sample,key:'wen',label:'闻',actual:907,formula:'服务端公式乙'}]}},global:{stubs:{EtchedOrnament:true,ElDialog:{template:'<section><slot/></section>'}}}})
  expect(view.get('.formula-values').text()).toContain('731')
  await view.findAll('.formula-tabs button').find(b=>b.text()==='闻').trigger('click')
  expect(view.get('.formula-expression').text()).toBe('服务端公式乙')
  expect(view.get('.formula-values').text()).toContain('907')
  expect(view.get('.formula-values').text()).not.toContain('731')
 })
})
