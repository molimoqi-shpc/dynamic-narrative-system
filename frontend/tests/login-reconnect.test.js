import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {createPinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
const api=vi.hoisted(()=>({post:vi.fn()}))
vi.mock('../src/api',()=>({default:api}))
import Login from '../src/views/LoginView.vue'

let view,router
beforeEach(()=>{localStorage.clear();sessionStorage.clear();localStorage.setItem('bs_token','saved-login');sessionStorage.setItem('bs-selected-session:reader','old-save');api.post.mockReset()})
afterEach(()=>{view?.unmount();view=null})
async function recovery(returnTo='/play'){
 router=createRouter({history:createMemoryHistory(),routes:[
  {path:'/login',component:Login},
  ...['/home','/play'].map(path=>({path,component:{template:'<div />'},meta:{requiresAuth:true}}))
 ]})
 await router.push({path:'/login',query:{reconnect:'1',returnTo}});await router.isReady()
 view=mount(Login,{global:{plugins:[createPinia(),router],stubs:{AmbientParticles:true,CelestialTitle:true,CompassMark:true,FlightMark:true,EtchedOrnament:true}}})
}
describe('connection recovery in the existing account entry',()=>{
 it('lets a temporarily disconnected reader retry the original route without entering credentials or generating a story',async()=>{
  await recovery('/play?chapter=3')
  expect(view.find('input[name=password]').exists()).toBe(false);expect(view.get('[role=status]').text()).toContain('已保留')
  await view.get('.entry-submit').trigger('click');await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/play?chapter=3');expect(api.post).not.toHaveBeenCalled()
  expect(localStorage.getItem('bs_token')).toBe('saved-login');expect(sessionStorage.getItem('bs-selected-session:reader')).toBe('old-save')
 })
 it.each(['https://example.invalid/','//example.invalid/','/login?reconnect=1','/not-an-app-route'])('rejects an unsafe or non-app return route: %s',async path=>{
  await recovery(path);await view.get('.entry-submit').trigger('click');await flushPromises()
  expect(router.currentRoute.value.path).toBe('/home');expect(api.post).not.toHaveBeenCalled()
 })
 it('can return to password login while keeping the saved login until the user signs in',async()=>{
  await recovery();await view.get('.entry-demo button').trigger('click');await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/login');expect(view.find('input[name=password]').exists()).toBe(true)
  expect(localStorage.getItem('bs_token')).toBe('saved-login');expect(api.post).not.toHaveBeenCalled()
 })
})
