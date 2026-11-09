import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
import {mount} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
const mocks=vi.hoisted(()=>({get:vi.fn(),post:vi.fn(),push:vi.fn(),error:vi.fn()}))
vi.mock('../src/api',()=>({default:{get:mocks.get,post:mocks.post}}))
vi.mock('vue-router',()=>({useRouter:()=>({push:mocks.push}),useRoute:()=>({query:{}})}))
vi.mock('element-plus',async importOriginal=>({...await importOriginal(),ElMessage:{error:mocks.error,warning:vi.fn()}}))
import Home from '../src/views/HomeView.vue'
import {useSessionStore} from '../src/store/session'
import {setMotionMode} from '../src/utils/motionPreferences'
let view
const pending=()=>{let resolve;const promise=new Promise(yes=>resolve=yes);return {promise,resolve}}
beforeEach(()=>{setActivePinia(createPinia());Object.values(mocks).forEach(fn=>fn.mockReset());setMotionMode('STATIC');localStorage.setItem('bs_token','A');useSessionStore().setActiveSession({playerId:'A'});view=mount(Home,{global:{directives:{loading:()=>{}},stubs:{AmbientParticles:true,MenuMotif:true,ElDrawer:true,ElButton:true,ElTag:true,ElEmpty:true}}})})
afterEach(()=>{view.unmount();setMotionMode('FULL')})
function switchAccount(){const store=useSessionStore();store.clearSession();store.setActiveSession({playerId:'B'});localStorage.setItem('bs_token','B')}
describe('home account and save request lifetime',()=>{
 it('opens the local portrait library without creating or loading a game',async()=>{
  await view.get('.home-portrait-entry').trigger('click');expect(view.vm.portraitLibraryOpen).toBe(true);expect(mocks.get).not.toHaveBeenCalled();expect(mocks.post).not.toHaveBeenCalled();expect(mocks.push).not.toHaveBeenCalled()
 })
 it('does not display old account saves after changing login',async()=>{
  const request=pending();mocks.get.mockReturnValue(request.promise);const work=view.vm.loadSessions();switchAccount();request.resolve({sessions:[{sessionId:'A-secret'}]});await work
  expect(view.vm.sessions).toEqual([]);expect(mocks.error).not.toHaveBeenCalled()
 })
 it('does not replace a new account with an old resume response',async()=>{
  const request=pending();mocks.post.mockReturnValue(request.promise);const work=view.vm.resumeSession({sessionId:'A-save',status:'PLAYING'});switchAccount();request.resolve({sessionId:'A-save',status:'PLAYING'});await work
  expect(useSessionStore().playerId).toBe('B');expect(useSessionStore().sessionId).toBeNull();expect(mocks.push).not.toHaveBeenCalled()
 })
 it('does not navigate from an unmounted home page when a resume finishes',async()=>{
  const request=pending();mocks.post.mockReturnValue(request.promise);const work=view.vm.resumeSession({sessionId:'A-save',status:'PLAYING'});view.unmount();request.resolve({sessionId:'A-save',status:'PLAYING'});await work
  expect(useSessionStore().sessionId).toBeNull();expect(mocks.push).not.toHaveBeenCalled()
 })
 it.each(['CREATING','PLAYING'])('still restores a valid %s save for the same account',async status=>{
  mocks.get.mockResolvedValue({});mocks.post.mockResolvedValue({sessionId:'A-save',status})
  await view.vm.resumeSession({sessionId:'A-save',status,viewMode:'DREAMER'})
  expect(useSessionStore().playerId).toBe('A');expect(useSessionStore().sessionId).toBe('A-save');expect(mocks.push).toHaveBeenCalledWith(status==='CREATING'?'/new-game':'/play')
 })
 it('does not clear a newer login when an old logout response arrives',async()=>{
  const request=pending();mocks.post.mockReturnValue(request.promise);const work=view.vm.logout();switchAccount();request.resolve({loggedOut:true});await work
  expect(localStorage.getItem('bs_token')).toBe('B');expect(useSessionStore().playerId).toBe('B');expect(mocks.push).not.toHaveBeenCalled()
 })
})
