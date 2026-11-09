import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
import {AxiosError} from 'axios'
import http from '../src/api'
const redirect=vi.fn()
beforeEach(()=>{localStorage.clear();redirect.mockReset();vi.stubGlobal('location',{assign:redirect})})
afterEach(()=>vi.unstubAllGlobals())
async function unauthorized(url='/game/sessions'){
 let fail,config
 const result=http.get(url,{adapter:request=>new Promise((_,reject)=>{config=request;fail=()=>reject(new AxiosError('unauthorized','ERR_BAD_REQUEST',request,null,{status:401,config:request}))})}).catch(e=>e)
 await vi.waitFor(()=>expect(config).toBeTruthy())
 return {result,fail}
}
describe('authentication response ownership',()=>{
 it('retains normal JSON unwrapping and exposes binary response headers only when requested',async()=>{
  const adapter=async config=>({data:config.rawResponse?'binary-data':{ok:true},status:200,statusText:'OK',headers:{'content-disposition':'attachment; filename="story.txt"'},config})
  expect(await http.get('/game/scene',{adapter})).toEqual({ok:true})
  const file=await http.get('/game/story-export',{adapter,rawResponse:true,responseType:'blob'});expect(file.data).toBe('binary-data');expect(file.headers.get('content-disposition')).toContain('story.txt')
 })
 it('does not remove a newer login when an older token gets a delayed 401',async()=>{
  localStorage.setItem('bs_token','old-token');const request=await unauthorized()
  localStorage.setItem('bs_token','new-token');request.fail();await request.result
  expect(localStorage.getItem('bs_token')).toBe('new-token');expect(redirect).not.toHaveBeenCalled()
 })
 it('clears and redirects for an expired token that is still the current login',async()=>{
  localStorage.setItem('bs_token','expired');const request=await unauthorized();request.fail();await request.result
  expect(localStorage.getItem('bs_token')).toBeNull();expect(redirect).toHaveBeenCalledWith('/login')
 })
 it('does not clear an active account when a login form returns incorrect credentials',async()=>{
  localStorage.setItem('bs_token','active');const request=await unauthorized('/auth/login');request.fail();await request.result
  expect(localStorage.getItem('bs_token')).toBe('active');expect(redirect).not.toHaveBeenCalled()
 })
})
