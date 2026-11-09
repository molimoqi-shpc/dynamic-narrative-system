import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
const transport=vi.hoisted(()=>({get:vi.fn(),post:vi.fn()}))
vi.mock('../src/api',()=>({default:transport}))
import router from '../src/router'
import {useSessionStore} from '../src/store/session'
beforeEach(async()=>{vi.spyOn(window,'scrollTo').mockImplementation(()=>{});setActivePinia(createPinia());transport.get.mockReset();transport.post.mockReset();localStorage.clear();sessionStorage.clear();await router.replace('/login')})
afterEach(()=>vi.restoreAllMocks())
describe('navigation account response lifetime',()=>{
 it.each([new Error('network offline'),{response:{status:503}}])('preserves the login and selected save when account verification is temporarily unavailable',async failure=>{
  localStorage.setItem('bs_token','token-A');sessionStorage.setItem('bs-selected-session:A','save-A');transport.get.mockRejectedValue(failure)
  await router.push('/play')
  expect(localStorage.getItem('bs_token')).toBe('token-A')
  expect(sessionStorage.getItem('bs-selected-session:A')).toBe('save-A')
  expect(router.currentRoute.value.path).toBe('/login')
  expect(router.currentRoute.value.query).toEqual({reconnect:'1',returnTo:'/play'})
  expect(transport.post).not.toHaveBeenCalled()
  transport.get.mockResolvedValue({playerId:'A'});transport.post.mockResolvedValue({sessionId:'save-A',status:'PLAYING'})
  await router.replace('/play')
  expect(useSessionStore().sessionId).toBe('save-A');expect(router.currentRoute.value.path).toBe('/play')
  expect(transport.post).toHaveBeenCalledTimes(1)
  expect(transport.post).toHaveBeenCalledWith('/game/resume',{sessionId:'save-A',playerId:'A'},{timeout:15000})
 })
 it('requires a fresh login when the server confirms the token is expired',async()=>{
  localStorage.setItem('bs_token','expired');transport.get.mockRejectedValue({response:{status:401}})
  await router.push('/home')
  expect(localStorage.getItem('bs_token')).toBeNull();expect(router.currentRoute.value.path).toBe('/login');expect(router.currentRoute.value.query.reconnect).toBeUndefined()
 })
 it.each(['resolve','reject'])('keeps the new account when old /me later %ss',async completion=>{
  let resolve,reject;transport.get.mockReturnValue(new Promise((yes,no)=>{resolve=yes;reject=no}))
  localStorage.setItem('bs_token','A');const old=router.push('/home')
  await vi.waitFor(()=>expect(transport.get).toHaveBeenCalledWith('/auth/me',{timeout:15000}))
  const store=useSessionStore();store.clearSession();store.setActiveSession({playerId:'B'})
  localStorage.setItem('bs_token','B');await router.push('/about')
  if(completion==='resolve')resolve({playerId:'A'});else reject(new Error('old login expired'))
  await old
  expect(store.playerId).toBe('B');expect(localStorage.getItem('bs_token')).toBe('B');expect(router.currentRoute.value.path).toBe('/about')
 })
 it.each(['resolve','reject'])('ignores the old save resume when the account changes before it %ss',async completion=>{
  let resolve,reject;transport.post.mockReturnValue(new Promise((yes,no)=>{resolve=yes;reject=no}))
  const store=useSessionStore();store.setActiveSession({playerId:'A'});sessionStorage.setItem('bs-selected-session:A','save-A');localStorage.setItem('bs_token','token-A')
  const old=router.push('/play')
  await vi.waitFor(()=>expect(transport.post).toHaveBeenCalledWith('/game/resume',{sessionId:'save-A',playerId:'A'},{timeout:15000}))
  store.clearSession();store.setActiveSession({playerId:'B'});sessionStorage.setItem('bs-selected-session:B','save-B');localStorage.setItem('bs_token','token-B');await router.push('/about')
  if(completion==='resolve')resolve({sessionId:'save-A',status:'PLAYING'});else reject({response:{status:403}})
  await old
  expect(store.playerId).toBe('B');expect(store.sessionId).toBeNull();expect(store.rememberedSessionId()).toBe('save-B');expect(localStorage.getItem('bs_token')).toBe('token-B');expect(router.currentRoute.value.path).toBe('/about')
 })
 it.each(['resolve','reject'])('keeps a newly selected save when the previous resume later %ss',async completion=>{
  let resolve,reject;transport.post.mockReturnValue(new Promise((yes,no)=>{resolve=yes;reject=no}))
  const store=useSessionStore();store.setActiveSession({playerId:'A'});sessionStorage.setItem('bs-selected-session:A','save-old');localStorage.setItem('bs_token','token-A')
  const old=router.push('/play');await vi.waitFor(()=>expect(transport.post).toHaveBeenCalledTimes(1))
  sessionStorage.setItem('bs-selected-session:A','save-new');await router.push('/about')
  if(completion==='resolve')resolve({sessionId:'save-old',status:'PLAYING'});else reject({response:{status:404}})
  await old
  expect(store.sessionId).toBeNull();expect(store.rememberedSessionId()).toBe('save-new');expect(router.currentRoute.value.path).toBe('/about')
 })
})
