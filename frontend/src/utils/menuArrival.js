import {currentMotion,MOTION_EVENT} from './motionPreferences'

// Targets are inside the committed destination. No remounting, forced layout,
// hidden content, animation-finish navigation or changes to story transport.
const movements={
 angel:{title:'translateY(12px)',ornament:'translateY(16px) scale(.98)',item:'translateY(9px)',body:'translateY(14px)'},
 gears:{title:'translateX(28px)',ornament:'rotate(-5deg)',item:'translateX(22px)',body:'translateX(16px)'},
 tide:{title:'translateY(15px)',ornament:'translateY(20px)',item:'translateY(12px)',body:'translateY(16px)'},
 hand:{title:'translateY(-12px)',ornament:'translateY(-8px)',item:'translateY(-10px)',body:'translateY(-8px)'}
}
export function playMenuArrival(kind,root=document){
 if(!movements[kind] || currentMotion()!=='FULL' || document.hidden)return ()=>{}
 const destination=root.querySelector(`[data-menu-destination="${kind}"]`)
 if(!destination)return ()=>{}
 const animations=[]
 let closed=false
 const stop=()=>{
  if(closed)return;closed=true
  animations.forEach(a=>a.cancel())
  delete destination.dataset.menuReceiving
  window.removeEventListener(MOTION_EVENT,stop)
  document.removeEventListener('visibilitychange',visibility)
 }
 const visibility=()=>{if(document.hidden)stop()}
 try{
  // No more than eight targets; a large archive does not create unbounded work.
  const targets=[...destination.querySelectorAll('[data-arrival]')].slice(0,8)
  const duration=kind==='angel'?440:kind==='hand'?680:520
  let item=0
  for(const el of targets){
   if(typeof el.animate!=='function')continue
   const part=el.dataset.arrival,transform=movements[kind][part] || movements[kind].body
   const delay=part==='title'?20:part==='ornament'?0:kind==='angel'?90:Math.min(310,100+(item++)*55)
   animations.push(el.animate([{opacity:part==='ornament'?.55:.2,transform},{opacity:1,transform:'none'}],{duration,delay,easing:'cubic-bezier(.16,1,.3,1)',fill:'backwards'}))
  }
  if(animations.length){destination.dataset.menuReceiving=kind;window.addEventListener(MOTION_EVENT,stop);document.addEventListener('visibilitychange',visibility)}
 }catch{stop()}
 return stop
}
