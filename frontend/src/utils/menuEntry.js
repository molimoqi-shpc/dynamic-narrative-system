import {reactive} from 'vue'
import {menuTransition,prepareMenuScene} from './menuTransition'
import {currentMotion} from './motionPreferences'

export function menuOrigin(element){
 if(!element?.getBoundingClientRect)return null
 const card=element.getBoundingClientRect()
 if(!card.width || !card.height)return null
 const motif=element.querySelector('.menu-motif')?.getBoundingClientRect() || card
 const clamp=(v,min,max)=>Math.min(max,Math.max(min,v))
 return {x:clamp(motif.left+motif.width/2,Math.max(16,card.left+16),Math.min(window.innerWidth-16,card.right-16)),
  y:clamp(motif.top+motif.height/2,Math.max(16,card.top+16),Math.min(window.innerHeight-16,card.bottom-16)),
  scale:clamp(motif.width/240,.35,1)}
}

// The cue and asset decoding run together. Leaving Home while loading cannot
// later trigger its old navigation. Once the curtain starts it owns navigation.
export function createMenuLauncher({prepare=prepareMenuScene,transition=menuTransition}={}){
 const state=reactive({preparing:false,kind:null})
 let disposed=false,sequence=0,finishCue=null
 async function enter(kind,action,element){
  if(disposed || state.preparing || transition.state.busy)return false
  const id=++sequence,origin=menuOrigin(element)
  state.preparing=true;state.kind=kind
  let stopCue=()=>{}
  try{
   const cue=new Promise(resolve=>{
    const finish=()=>{clearTimeout(timer);if(finishCue===finish)finishCue=null;resolve()}
    const timer=setTimeout(finish,currentMotion()==='FULL'?220:0);finishCue=finish;stopCue=finish
   })
   const [ready]=await Promise.all([prepare(kind),cue])
   if(disposed || id!==sequence)return false
   return await transition.run(kind,action,{simplified:!ready,origin})
  }finally{stopCue();if(id===sequence){state.preparing=false;state.kind=null}}
 }
 function dispose(){disposed=true;sequence++;finishCue?.();state.preparing=false;state.kind=null}
 return {state,enter,dispose}
}
