import {beforeEach,afterEach,describe,it,expect,vi} from 'vitest'
import {mount,flushPromises} from '@vue/test-utils'
import {createPinia,setActivePinia} from 'pinia'
import {useSessionStore} from '../src/store/session'
const api=vi.hoisted(()=>({get:vi.fn(),post:vi.fn()}))
vi.mock('../src/api',()=>({default:api}))
import Selector from '../src/components/MainModelSelector.vue'
const flash='deepseek-v4-flash',pro='deepseek-v4-pro'
let view,store
beforeEach(()=>{const pinia=createPinia();setActivePinia(pinia);store=useSessionStore();store.setActiveSession({playerId:'A',sessionId:'one'});api.get.mockReset().mockResolvedValue({model:flash});api.post.mockReset();view=mount(Selector,{props:{sessionId:'one'},global:{plugins:[pinia],stubs:{teleport:true}}})})
afterEach(()=>view.unmount())
async function open(){await flushPromises();await view.get('.main-model-trigger').trigger('click');await flushPromises()}
describe('persisted main API selector',()=>{
 it('reads back a saved model when the write succeeded but its response was lost',async()=>{
  await open();api.post.mockRejectedValue(new Error('response lost after commit'));api.get.mockResolvedValue({model:pro})
  await view.get('[role=radio]:nth-child(2)').trigger('click');await flushPromises()
  expect(view.get('.main-model-trigger small').text()).toBe('Pro');expect(view.get('[role=status]').text()).toContain('核对')
  expect(api.post).toHaveBeenCalledTimes(1);expect(api.get).toHaveBeenCalledTimes(2)
 })
 it('does not claim the original model is still selected when both save response and readback are unavailable',async()=>{
  await open();api.post.mockRejectedValue(new Error('offline'));api.get.mockRejectedValue(new Error('still offline'))
  await view.get('[role=radio]:nth-child(2)').trigger('click');await flushPromises()
  expect(view.get('[role=alert]').text()).toContain('尚未确认');expect(view.get('[role=alert]').text()).not.toContain('仍使用原模型')
  expect(view.get('.main-model-trigger small').text()).not.toBe('Flash');expect(api.post).toHaveBeenCalledTimes(1)
  expect(view.findAll('[role=radio]').every(button=>button.element.disabled)).toBe(true)
  api.get.mockResolvedValue({model:pro});await view.get('[role=alert] button').trigger('click');await flushPromises()
  expect(view.get('.main-model-trigger small').text()).toBe('Pro');expect(api.post).toHaveBeenCalledTimes(1)
 })
 it('ignores a delayed readback once another save has become active',async()=>{
  await open();let resolve;api.post.mockRejectedValue(new Error('response lost'));api.get.mockReturnValueOnce(new Promise(done=>{resolve=done}))
  await view.get('[role=radio]:nth-child(2)').trigger('click');await flushPromises();expect(api.get).toHaveBeenCalledTimes(2)
  api.get.mockResolvedValue({model:flash});store.setActiveSession({sessionId:'two'});await view.setProps({sessionId:'two'});await flushPromises()
  resolve({model:pro});await flushPromises()
  expect(view.get('.main-model-trigger small').text()).toBe('Flash');expect(api.post).toHaveBeenCalledTimes(1)
 })
 it('reads the saved choice and changes only after a successful save',async()=>{
  await open();expect(view.get('.main-model-trigger small').text()).toBe('Flash');api.post.mockResolvedValue({model:pro});await view.get('[role=radio]:nth-child(2)').trigger('click');await flushPromises()
  expect(api.post).toHaveBeenCalledWith('/game/main-model',{sessionId:'one',model:pro},{timeout:10000});expect(view.get('.main-model-trigger small').text()).toBe('Pro');expect(view.get('[role=status]').text()).toContain('下次行动')
 })
 it('keeps the original selected model when saving fails',async()=>{
  await open();api.post.mockRejectedValue(new Error('offline'));await view.get('[role=radio]:nth-child(2)').trigger('click');await flushPromises();expect(view.get('.main-model-trigger small').text()).toBe('Flash');expect(view.get('[role=alert]').text()).toContain('仍使用原模型')
 })
 it('ignores delayed saves from an earlier session and can close without changing models',async()=>{
  await open();let resolve;api.post.mockReturnValue(new Promise(done=>{resolve=done}));await view.get('[role=radio]:nth-child(2)').trigger('click')
  store.setActiveSession({sessionId:'two'});await view.setProps({sessionId:'two'});await flushPromises();resolve({model:pro});await flushPromises();expect(view.get('.main-model-trigger small').text()).toBe('Flash')
  await view.get('.main-model-trigger').trigger('click');await view.get('[aria-label="关闭主 API 选择"]').trigger('click');expect(view.find('[role=dialog]').exists()).toBe(false)
 })
})
