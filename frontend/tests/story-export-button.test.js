import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {useSessionStore} from '../src/store/session'
const mocks=vi.hoisted(()=>({download:vi.fn(),error:vi.fn()}))
vi.mock('../src/utils/storyExport',()=>({downloadStory:mocks.download}))
vi.mock('element-plus',async original=>({...await original(),ElMessage:{error:mocks.error}}))
import Button from '../src/components/StoryExportButton.vue'
let store,page
const pending=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
beforeEach(()=>{setActivePinia(createPinia());store=useSessionStore();store.setActiveSession({playerId:'owner',sessionId:'active',status:'PLAYING',branchEpoch:0});localStorage.setItem('bs_token','owner-token');mocks.download.mockReset();mocks.error.mockReset()})
afterEach(()=>page?.unmount())
function create(props={}){page=mount(Button,{props:{sessionId:'archive',...props},global:{stubs:{ElButton:{props:['disabled','loading'],template:'<button :disabled="disabled"><slot /></button>'}}}});return page}
describe('story export button ownership and lifetime',()=>{
 it('uses the existing export flow from the compact rail button and disables it while downloading',async()=>{
  const request=pending();mocks.download.mockReturnValue(request.promise);create({sessionId:'active',rail:true})
  const button=page.get('button.story-export-rail');expect(button.attributes('type')).toBe('button');expect(button.attributes('aria-label')).toBe('导出当前存档故事文本');expect(button.get('span').text()).toBe('导出')
  await button.trigger('click');expect(mocks.download).toHaveBeenCalledTimes(1);expect(mocks.download.mock.calls[0][0]).toBe('active');expect(button.element.disabled).toBe(true);expect(button.attributes('aria-busy')).toBe('true');expect(button.get('span').text()).toBe('导出中')
  await button.trigger('click');expect(mocks.download).toHaveBeenCalledTimes(1)
  request.resolve(true);await flushPromises();expect(button.element.disabled).toBe(false);expect(button.get('span').text()).toBe('导出')
 })
 it('exports a nonactive archive without switching the active game and blocks duplicate clicks',async()=>{
  const request=pending();mocks.download.mockReturnValue(request.promise);create();await page.get('button').trigger('click');await page.get('button').trigger('click')
  expect(mocks.download).toHaveBeenCalledTimes(1);expect(mocks.download.mock.calls[0][0]).toBe('archive');expect(mocks.download.mock.calls[0][1].valid()).toBe(true);expect(store.sessionId).toBe('active')
  request.resolve(true);await flushPromises();expect(page.text()).toBe('导出故事')
 })
 it('does not accept a late result or display errors for a different account',async()=>{
  const request=pending();mocks.download.mockReturnValue(request.promise);create();await page.get('button').trigger('click');const options=mocks.download.mock.calls[0][1]
  store.clearSession();store.setActiveSession({playerId:'another'});localStorage.setItem('bs_token','another-token');expect(options.valid()).toBe(false);request.reject(new Error('old account details'));await flushPromises();expect(mocks.error).not.toHaveBeenCalled()
 })
 it('aborts its request on unmount and when the requested archive changes',async()=>{
  const request=pending();mocks.download.mockReturnValue(request.promise);create();await page.get('button').trigger('click');const options=mocks.download.mock.calls[0][1]
  await page.setProps({sessionId:'different'});expect(options.signal.aborted).toBe(true);expect(options.valid()).toBe(false);expect(page.text()).toBe('导出故事')
  await page.get('button').trigger('click');const next=mocks.download.mock.calls[1][1];page.unmount();expect(next.signal.aborted).toBe(true);request.resolve(false);await flushPromises()
 })
 it('allows the server snapshot to finish across a rewind of the same save',async()=>{
  const request=pending();mocks.download.mockReturnValue(request.promise);create({sessionId:'active'});await page.get('button').trigger('click');store.setActiveSession({branchEpoch:1});expect(mocks.download.mock.calls[0][1].valid()).toBe(true);request.resolve(true);await flushPromises()
 })
 it('keeps a failed export retryable and reports the readable server message',async()=>{
  mocks.download.mockRejectedValueOnce(new Error('此存档还没有可导出的故事')).mockResolvedValueOnce(true);create();await page.get('button').trigger('click');await flushPromises();expect(mocks.error).toHaveBeenCalledWith('此存档还没有可导出的故事');expect(page.text()).toBe('导出故事');await page.get('button').trigger('click');await flushPromises();expect(mocks.download).toHaveBeenCalledTimes(2)
 })
 it('does not request an unfinished creation that has been disabled by the archive list',async()=>{
  create({disabled:true});await page.get('button').trigger('click');expect(mocks.download).not.toHaveBeenCalled()
 })
})
