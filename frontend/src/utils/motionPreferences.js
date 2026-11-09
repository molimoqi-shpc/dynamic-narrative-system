import {reactive} from 'vue'
export const MOTION_KEY='bs_motion_preferences_v1'
export const MOTION_EVENT='motion-preference-change'
export const motionPreferences=reactive({mode:'FULL',effective:'FULL',systemReduced:false})
let runtime=null
export const normalizeMotion=raw=>['FULL','LIGHT','STATIC'].includes(raw?.mode ?? raw)?(raw?.mode ?? raw):'FULL'
export function loadMotion(storage){try{return normalizeMotion(JSON.parse(storage.getItem(MOTION_KEY) || '{}'))}catch{return 'FULL'}}
export function currentMotion(){
 const media=runtime?.media || globalThis.matchMedia?.('(prefers-reduced-motion: reduce)')
 return media?.matches?'STATIC':normalizeMotion(motionPreferences.mode)
}
function apply(){
 motionPreferences.systemReduced=!!(runtime?.media || globalThis.matchMedia?.('(prefers-reduced-motion: reduce)'))?.matches
 motionPreferences.effective=currentMotion()
 const root=runtime?.root || (typeof document!=='undefined'?document.body:null)
 if(root)root.dataset.motion=motionPreferences.effective
 const target=runtime?.events || (typeof window!=='undefined'?window:null)
 target?.dispatchEvent(new CustomEvent(MOTION_EVENT,{detail:{...motionPreferences}}))
}
export function setMotionMode(value){
 motionPreferences.mode=normalizeMotion(value)
 try{(runtime?.storage || window.localStorage).setItem(MOTION_KEY,JSON.stringify({mode:motionPreferences.mode}))}catch{}
 apply()
}
export function installMotionPreferences({storage,media=window.matchMedia?.('(prefers-reduced-motion: reduce)'),root=document.body,events=window}={}){
 if(runtime)return ()=>{}
 if(!storage)try{storage=window.localStorage}catch{storage={getItem:()=>null,setItem:()=>{}}}
 const previous=root.dataset.motion
 const owner={storage,media,root,events};runtime=owner
 motionPreferences.mode=loadMotion(storage)
 const preference=()=>apply()
 const storageChanged=event=>{if(event.key!==MOTION_KEY && event.key!==null)return;if(event.storageArea && event.storageArea!==storage)return;motionPreferences.mode=loadMotion(storage);apply()}
 media?.addEventListener?.('change',preference);events.addEventListener('storage',storageChanged);apply()
 return ()=>{
  if(runtime!==owner)return
  media?.removeEventListener?.('change',preference);events.removeEventListener('storage',storageChanged)
  if(previous==null)delete root.dataset.motion;else root.dataset.motion=previous
  runtime=null;Object.assign(motionPreferences,{mode:'FULL',effective:'FULL',systemReduced:false})
 }
}
