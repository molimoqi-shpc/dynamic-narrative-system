import {beforeEach,afterEach,it,expect,vi} from 'vitest'
import {createStoryReading} from '../src/utils/storyReading'
let el,items,reading,heights
const render=()=>{el.replaceChildren(...items.map(item=>{const node=document.createElement('article');node.className='story-chapter';node.dataset.storyId=item.id;node.textContent=item.signature;node.getBoundingClientRect=()=>{let top=0;for(const entry of items){if(entry.id===item.id)break;top+=heights[entry.id] || 400}top+=100-el.scrollTop;return {top,bottom:top+(heights[item.id] || 400)}};return node}))}
const add=async(id,options={})=>{const snapshot=reading.capture();items=[...items,{id,signature:'正文'+id}];render();await reading.present(snapshot,options)}
beforeEach(async()=>{
 el=document.createElement('div');document.body.append(el);items=[];heights={}
 Object.defineProperties(el,{clientHeight:{get:()=>200},scrollHeight:{get:()=>items.reduce((sum,item)=>sum+(heights[item.id] || 400),0)}})
 el.getBoundingClientRect=()=>({top:100,bottom:300,height:200});el.scrollTo=vi.fn(({top})=>{el.scrollTop=top})
 reading=createStoryReading(()=>el,()=>items)
 const snapshot=reading.capture();items=['1','2','3'].map(id=>({id,signature:'正文'+id}));render();await reading.present(snapshot,{kind:'restore'});el.scrollTo.mockClear()
})
afterEach(()=>{reading.dispose();el.remove()})
it('receives new stories without moving a reader of older paragraphs',async()=>{
 el.scrollTop=120;await add('4');expect(el.scrollTop).toBe(120);expect(reading.unreadCount.value).toBe(1);expect(el.scrollTo).not.toHaveBeenCalled()
 const snapshot=reading.capture();render();await reading.present(snapshot);expect(reading.unreadCount.value).toBe(1);expect(el.scrollTop).toBe(120)
})
it('begins an automatically followed new chapter at its first line rather than its end',async()=>{
 el.scrollTop=1000;await add('4');expect(el.scrollTo).toHaveBeenCalledWith({top:1184,behavior:'auto'});expect(reading.arrivingChapter.value).toBe('4');expect(reading.hasNewStory.value).toBe(false)
})
it('returns to the first unseen chapter when several arrive, keeping the story in order',async()=>{
 el.scrollTop=120;await add('4');await add('5');expect(reading.unreadCount.value).toBe(2)
 reading.jumpToLatest({smooth:false});expect(el.scrollTop).toBe(1184);expect(reading.arrivingChapter.value).toBe('4');expect(reading.unreadCount.value).toBe(0)
})
it('retains the current paragraph when a partial chapter is completed in place',async()=>{
 el.scrollTop=1000;const snapshot=reading.capture();items=items.map(item=>item.id==='3'?{...item,signature:'正文三，接上此前未完成的一段。'}:item);heights['3']=700;render();await reading.present(snapshot)
 expect(el.scrollTop).toBe(1000);expect(el.scrollTo).not.toHaveBeenCalled();expect(reading.hasNewStory.value).toBe(false)
})
it('announces actual text completion to an older-story reader but ignores identical polling',async()=>{
 el.scrollTop=100;let snapshot=reading.capture();items[2]={...items[2],signature:'补全的第三段'};render();await reading.present(snapshot)
 expect(reading.unreadCount.value).toBe(1);snapshot=reading.capture();render();await reading.present(snapshot);expect(reading.unreadCount.value).toBe(1)
})
it('anchors the live scroll position when older pages finish loading',async()=>{
 el.scrollTop=500;const snapshot=reading.capture();items=[{id:'0',signature:'旧页'},...items];render();await reading.present(snapshot,{kind:'prepend'})
 expect(el.scrollTop).toBe(900);expect(reading.hasNewStory.value).toBe(false)
})
it('does not lose an intended jump when updates are batched before DOM presentation',async()=>{
 el.scrollTop=1000;const snapshot=reading.capture();items.push({id:'4',signature:'第四段'});render()
 const first=reading.present(snapshot),second=reading.present(reading.capture());await Promise.all([first,second]);expect(el.scrollTop).toBe(1184)
})
it('invalidates queued positioning after a branch reset or unmount',async()=>{
 el.scrollTop=1000;const snapshot=reading.capture();items.push({id:'4',signature:'第四段'});render();const result=reading.present(snapshot);reading.reset();await result
 expect(el.scrollTo).not.toHaveBeenCalled();expect(reading.arrivingChapter.value).toBe('');expect(reading.unreadCount.value).toBe(0)
 const queued=reading.present(reading.capture());reading.dispose();await queued;expect(el.scrollTo).not.toHaveBeenCalled()
})
