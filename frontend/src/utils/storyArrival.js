import {currentMotion,MOTION_EVENT} from './motionPreferences'
const states=new WeakMap()
function stop(state){state.animations.forEach(a=>a.cancel());state.animations=[]}
function reveal(el,state){
 stop(state)
 const level=currentMotion();if(level==='STATIC' || document.hidden || typeof el.animate!=='function')return
 const parts=level==='LIGHT'?[el]:[el.querySelector('.chapter-mark'),...el.querySelectorAll('.story-prose>p')].filter(Boolean).slice(0,9)
 state.animations=parts.map((node,index)=>node.animate([{opacity:.55,transform:level==='FULL'?'translateY(5px)':'none'},{opacity:1,transform:level==='FULL'?'translateY(0)':'none'}],{duration:level==='FULL'?280:140,delay:level==='FULL'?Math.min(index,6)*22:0,easing:'cubic-bezier(.16,1,.3,1)',fill:'none'}))
}
export const storyArrival={
 mounted(el,binding){
  const state={animations:[],media:globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')}
  state.onMode=()=>stop(state);state.onVisibility=()=>{if(document.hidden)stop(state)};state.onSystem=()=>{if(state.media?.matches)stop(state)}
  window.addEventListener(MOTION_EVENT,state.onMode);document.addEventListener('visibilitychange',state.onVisibility);state.media?.addEventListener?.('change',state.onSystem)
  states.set(el,state);if(binding.value)reveal(el,state)
 },
 updated(el,binding){if(binding.value && binding.value!==binding.oldValue){const state=states.get(el);if(state)reveal(el,state)}},
 beforeUnmount(el){const state=states.get(el);if(!state)return;stop(state);window.removeEventListener(MOTION_EVENT,state.onMode);document.removeEventListener('visibilitychange',state.onVisibility);state.media?.removeEventListener?.('change',state.onSystem);states.delete(el)}
}
