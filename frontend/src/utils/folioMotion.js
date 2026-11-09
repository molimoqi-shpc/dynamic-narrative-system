// Short, cancellable content transitions. Story transport and input are never delayed.
import {currentMotion,MOTION_EVENT} from './motionPreferences'
import {menuTransition} from './menuTransition'
export const folioMotionTiming=Object.freeze({duration:300,easing:'cubic-bezier(0.16, 1, 0.3, 1)'})
const instances=new WeakMap()
function stop(state){state.animation?.cancel();state.animation=null}
function enter(el,state){
 stop(state)
 const level=currentMotion()
 // The menu curtain gives its destination a coordinated entrance on reveal.
 if(menuTransition.state.active)return
 if(state.media?.matches || level==='STATIC' || document.hidden || typeof el.animate!=='function')return
 const animation=el.animate([{opacity:level==='LIGHT'?.65:.25,transform:level==='LIGHT'?'none':'translateY(7px)'},{opacity:1,transform:level==='LIGHT'?'none':'translateY(0)'}],{...folioMotionTiming,duration:level==='LIGHT'?160:folioMotionTiming.duration,fill:'none'})
 state.animation=animation
 animation.onfinish=()=>{if(state.animation===animation)state.animation=null}
}
export const folioMotion={
 mounted(el,binding){
  const state={animation:null,media:globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')}
  state.onPreference=()=>{if(state.media?.matches)stop(state)}
  state.onVisibility=()=>{if(document.hidden)stop(state)}
  state.onMode=()=>stop(state)
  window.addEventListener(MOTION_EVENT,state.onMode)
  state.media?.addEventListener?.('change',state.onPreference)
  document.addEventListener('visibilitychange',state.onVisibility)
  instances.set(el,state);if(!binding.modifiers?.update)enter(el,state)
 },
 updated(el,binding){if(!Object.is(binding.value,binding.oldValue)){const state=instances.get(el);if(state)enter(el,state)}},
 beforeUnmount(el){const state=instances.get(el);if(!state)return;stop(state);window.removeEventListener(MOTION_EVENT,state.onMode);state.media?.removeEventListener?.('change',state.onPreference);document.removeEventListener('visibilitychange',state.onVisibility);instances.delete(el)}
}
