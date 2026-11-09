import {it,expect,vi} from 'vitest'
import {createRouter,createMemoryHistory} from 'vue-router'
import {mount,flushPromises} from '@vue/test-utils'
import RouteScene from '../src/components/RouteScene.vue'
import {menuTransition,menuRevealTimings,menuTimings} from '../src/utils/menuTransition'

it('commits actual route DOM under a curtain without synchronous out-in removal or remounting on curtain changes',async()=>{
 const enter=vi.fn(),exit=vi.fn()
 const {onMounted,onUnmounted}=await import('vue')
 const PageA={setup(){onMounted(()=>enter('a'));onUnmounted(()=>exit('a'))},template:'<main>旧页</main>'}
 const PageB={setup(){onMounted(()=>enter('b'));onUnmounted(()=>exit('b'))},template:'<main>新的实际页面</main>'}
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:PageA},{path:'/next',component:PageB}]})
 await router.push('/');await router.isReady()
 const view=mount(RouteScene,{global:{plugins:[router],stubs:{transition:false}}})
 try{
  vi.useFakeTimers()
  const navigation=menuTransition.run('hand',()=>router.push('/next'))
  await vi.advanceTimersByTimeAsync(menuTimings.hand+34);await flushPromises()
  expect(view.text()).toBe('新的实际页面')
  expect(enter.mock.calls).toEqual([['a'],['b']]);expect(exit.mock.calls).toEqual([['a']])
  await vi.advanceTimersByTimeAsync(menuRevealTimings.hand);expect(await navigation).toBe(true)
  expect(view.text()).toBe('新的实际页面');expect(enter).toHaveBeenCalledTimes(2)
 }finally{menuTransition.cancel();view.unmount();vi.useRealTimers()}
})
