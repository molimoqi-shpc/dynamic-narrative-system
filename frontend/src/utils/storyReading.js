import {ref,computed,nextTick} from 'vue'
import {currentMotion} from './motionPreferences'

// Reading owns pixels and unread badges only; the server still owns the story.
export function createStoryReading(getElement,getChapters){
 const unreadCount=ref(0),arrivingChapter=ref(''),unread=new Set()
 const hasNewStory=computed(()=>unreadCount.value>0)
 let initialized=false,sequence=0,alive=true,scheduledJump=false
 const chapters=()=>getChapters().map(item=>({id:String(item.id),signature:String(item.signature || '')}))
 const nearBottom=el=>!!el && el.clientHeight>0 && el.scrollHeight-el.scrollTop-el.clientHeight<96
 function capture(){
  const el=getElement(),bounds=el?.getBoundingClientRect()
  const node=el && [...el.querySelectorAll('[data-story-id]')].find(item=>{const r=item.getBoundingClientRect();return r.bottom>bounds.top+1 && r.top<bounds.bottom-1})
  return {chapters:chapters(),top:el?.scrollTop || 0,height:el?.scrollHeight || 0,follow:nearBottom(el),anchor:node?{id:node.dataset.storyId,offset:node.getBoundingClientRect().top-bounds.top}:null}
 }
 function restore(snapshot,prepend){
  const el=getElement();if(!el)return
  const anchor=snapshot.anchor && [...el.querySelectorAll('[data-story-id]')].find(item=>item.dataset.storyId===snapshot.anchor.id)
  const top=anchor?el.scrollTop+anchor.getBoundingClientRect().top-el.getBoundingClientRect().top-snapshot.anchor.offset:snapshot.top+(prepend?el.scrollHeight-snapshot.height:0)
  el.scrollTop=Math.max(0,top)
 }
 function jumpToLatest({smooth=true,reveal=true}={}){
  const el=getElement();if(!el)return
  const latest=reveal && unread.size?[...el.querySelectorAll('[data-story-id]')].find(node=>unread.has(node.dataset.storyId)) || el.querySelector('.story-chapter:last-child'):el.querySelector('.story-chapter:last-child')
  const top=Math.max(0,latest?latest.getBoundingClientRect().top-el.getBoundingClientRect().top+el.scrollTop-16:0)
  const far=Math.abs(top-el.scrollTop)>el.clientHeight*2
  el.scrollTo({top,behavior:smooth && !far && currentMotion()==='FULL'?'smooth':'auto'})
  if(reveal && unread.size)arrivingChapter.value=latest?.dataset.storyId || chapters().at(-1)?.id || ''
  unread.clear();unreadCount.value=0
 }
 async function present(snapshot,{kind='live',valid=()=>true}={}){
  if(!alive || !valid())return
  const initial=!initialized;initialized=true
  const current=chapters(),previous=new Map(snapshot.chapters.map(item=>[item.id,item.signature])),ids=new Set(current.map(item=>item.id))
  for(const id of unread)if(!ids.has(id))unread.delete(id)
  const appended=kind==='prepend'?[]:current.filter(item=>!previous.has(item.id))
  const changed=kind==='prepend'?[]:current.filter(item=>previous.has(item.id) && previous.get(item.id)!==item.signature)
  if(initial)unread.clear()
  else if(snapshot.follow){unread.clear();if(appended.length)arrivingChapter.value=appended.at(-1).id}
  else [...appended,...changed].forEach(item=>unread.add(item.id))
  unreadCount.value=unread.size
  scheduledJump=scheduledJump || initial || snapshot.follow && appended.length>0
  const token=++sequence
  await nextTick()
  if(!alive || token!==sequence || !valid())return
  if(scheduledJump){scheduledJump=false;jumpToLatest({smooth:false,reveal:false})}
  else restore(snapshot,kind==='prepend')
 }
 function onScroll(){if(nearBottom(getElement())){unread.clear();unreadCount.value=0}}
 function reset(){sequence++;initialized=false;scheduledJump=false;unread.clear();unreadCount.value=0;arrivingChapter.value=''}
 function dispose(){alive=false;reset()}
 return {capture,present,jumpToLatest,onScroll,reset,dispose,hasNewStory,unreadCount,arrivingChapter}
}
