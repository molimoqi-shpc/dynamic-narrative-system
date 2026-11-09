// Move decorative sheen and shadow only. Card text never tilts or changes size.
import {currentMotion,MOTION_EVENT} from './motionPreferences'
const surfaces=new WeakMap()
const variables=['--paper-light-x','--paper-light-y','--paper-shadow-x','--paper-shadow-y','--paper-fold-angle']
export const paperLight={
 mounted(el){
  const media=globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')
  const state={frame:0,alive:true}
  const reset=()=>{cancelAnimationFrame(state.frame);variables.forEach(key=>el.style.removeProperty(key));el.classList.remove('paper-lit')}
  state.move=event=>{
   if(event.pointerType!=='mouse' || media?.matches || currentMotion()!=='FULL')return
   const rect=el.getBoundingClientRect();if(!rect.width || !rect.height)return
   const x=Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width)),y=Math.max(0,Math.min(1,(event.clientY-rect.top)/rect.height))
   cancelAnimationFrame(state.frame);state.frame=requestAnimationFrame(()=>{
    if(!state.alive || media?.matches || currentMotion()!=='FULL')return
    el.style.setProperty('--paper-light-x',(x*100).toFixed(1)+'%');el.style.setProperty('--paper-light-y',(y*100).toFixed(1)+'%')
    el.style.setProperty('--paper-shadow-x',(3+(1-x)*4).toFixed(1)+'px');el.style.setProperty('--paper-shadow-y',(5+(1-y)*4).toFixed(1)+'px')
    el.style.setProperty('--paper-fold-angle',(-2*x).toFixed(2)+'deg');el.classList.add('paper-lit')
   })
  }
  state.reset=reset;state.preference=()=>{if(media?.matches)reset()};state.media=media
  el.addEventListener('pointermove',state.move);el.addEventListener('pointerleave',reset);window.addEventListener(MOTION_EVENT,reset);media?.addEventListener?.('change',state.preference);surfaces.set(el,state)
 },
 beforeUnmount(el){const state=surfaces.get(el);if(!state)return;state.alive=false;state.reset();el.removeEventListener('pointermove',state.move);el.removeEventListener('pointerleave',state.reset);window.removeEventListener(MOTION_EVENT,state.reset);state.media?.removeEventListener?.('change',state.preference);surfaces.delete(el)}
}
