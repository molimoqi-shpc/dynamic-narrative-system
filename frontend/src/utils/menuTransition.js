import {reactive, nextTick} from 'vue'
import {currentMotion,MOTION_EVENT} from './motionPreferences'

export const menuTimings = Object.freeze({angel:2600, gears:1850, tide:2400, hand:1550})
export const menuRevealTimings = Object.freeze({angel:650, gears:950, tide:850, hand:1450})
export const menuSceneAssets=Object.freeze({
 angel:['/art/menu-transitions/seraph-body-v2.png','/art/menu-transitions/seraph-wings-v2.png'],
 gears:[],
 tide:['/art/menu-transitions/lake-wraith-v2.png','/art/menu-transitions/lake-shade-v2.png','/art/menu-transitions/lake-water-v2.png'],
 hand:['/art/menu-transitions/hand-anime.png','/art/menu-transitions/rain-window-v2.png']
})

// One persistent host survives route changes. Completion never depends on a CSS
// animationend event (which may disappear with reduced motion or a hidden tab).
export function createMenuTransition() {
  const state = reactive({active:false, busy:false, kind:'angel', phase:'idle', reduced:false,origin:null})
  let sequence=0, wake=null, deadline=null
  const pause=ms=>new Promise(resolve=>{
    let timer
    const finish=()=>{clearTimeout(timer);if(wake===finish)wake=null;resolve()}
    wake=finish;timer=setTimeout(finish,ms)
  })
  function skip(){wake?.()}
  function cancel(){sequence++;skip();clearTimeout(deadline);state.active=false;state.busy=false;state.phase='idle';state.origin=null}
  async function run(kind,action,{simplified=false,origin=null}={}){
    if(state.busy)return false
    if(!Object.hasOwn(menuTimings,kind))throw new Error('Unknown menu transition')
    const id=++sequence
    let watchdog
    state.busy=true;state.active=true;state.kind=kind;state.phase='enter'
    // Reopening an archive must respect the chosen motion mode; visit history
    // must never silently replace its gears with the artwork-free short fade.
    state.reduced=simplified || currentMotion()!=='FULL'
    state.origin=state.reduced?null:origin
    const preferenceChanged=()=>{if(currentMotion()!=='FULL'){state.reduced=true;skip()}}
    globalThis.window?.addEventListener(MOTION_EVENT,preferenceChanged)
    try{
      await pause(currentMotion()==='STATIC'?0:state.reduced?90:menuTimings[kind])
      if(id!==sequence)return false
      state.phase='covered'
      await nextTick();await pause(34)
      if(id!==sequence)return false
      // Navigation/import failures release the overlay as well as successful actions.
      await Promise.race([Promise.resolve().then(action),new Promise((_,reject)=>{watchdog=setTimeout(()=>reject(new Error('页面暂未响应，请重试')),12000);deadline=watchdog})])
      if(id!==sequence)return false
      // Keep the curtain closed until Vue has committed the actual destination.
      await nextTick()
      if(id!==sequence)return false
      state.phase='leave';await pause(currentMotion()==='STATIC'?0:state.reduced?90:menuRevealTimings[kind])
      return true
    }finally{
      globalThis.window?.removeEventListener(MOTION_EVENT,preferenceChanged)
      clearTimeout(watchdog);if(deadline===watchdog)deadline=null
      if(id===sequence){state.active=false;state.busy=false;state.phase='idle';state.origin=null}
    }
  }
  return {state,run,skip,cancel}
}

export const menuTransition=createMenuTransition()

const decoded=new Map()
export function prepareMenuAsset(src){
  if(decoded.has(src))return decoded.get(src)
  const ready=new Promise(resolve=>{
    const image=new Image();let settled=false
    const finish=ok=>{if(settled)return;settled=true;clearTimeout(timer);resolve(ok)}
    const timer=setTimeout(()=>finish(false),1800)
    image.onload=()=>{if(typeof image.decode==='function')image.decode().then(()=>finish(true),()=>finish(false));else finish(true)}
    image.onerror=()=>finish(false);image.src=src
    if(image.complete && image.naturalWidth)image.onload()
  })
  const reusable=ready.then(ok=>{if(!ok)decoded.delete(src);return ok})
  decoded.set(src,reusable);return reusable
}
export async function prepareMenuScene(kind){
 if(currentMotion()!=='FULL')return true
 const results=await Promise.all((menuSceneAssets[kind] || []).map(prepareMenuAsset))
 return results.every(Boolean)
}
