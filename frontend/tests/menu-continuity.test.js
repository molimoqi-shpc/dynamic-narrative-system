import {it,expect,vi,afterEach} from 'vitest'
import {createMenuLauncher,menuOrigin} from '../src/utils/menuEntry'
import {playMenuArrival} from '../src/utils/menuArrival'
import {menuRevealTimings} from '../src/utils/menuTransition'
import {setMotionMode} from '../src/utils/motionPreferences'

let launcher,cleanup=()=>{}
afterEach(()=>{launcher?.dispose();launcher=null;cleanup();cleanup=()=>{};document.body.innerHTML='';vi.useRealTimers();vi.restoreAllMocks();setMotionMode('FULL')})
const transition=()=>({state:{busy:false},run:vi.fn(async()=>true)})
const deferred=()=>{let resolve;const promise=new Promise(done=>resolve=done);return {promise,resolve}}

it('responds on the clicked card immediately, shares decoding time with the cue and rejects duplicate clicks',async()=>{
 vi.useFakeTimers();const load=deferred(),flow=transition(),prepare=vi.fn(()=>load.promise)
 launcher=createMenuLauncher({prepare,transition:flow})
 const first=launcher.enter('angel',vi.fn())
 expect(launcher.state).toMatchObject({preparing:true,kind:'angel'})
 expect(await launcher.enter('hand',vi.fn())).toBe(false)
 await vi.advanceTimersByTimeAsync(500);expect(flow.run).not.toHaveBeenCalled()
 load.resolve(true);expect(await first).toBe(true)
 expect(flow.run).toHaveBeenCalledTimes(1);expect(prepare).toHaveBeenCalledTimes(1)
 expect(launcher.state).toMatchObject({preparing:false,kind:null})
})
it('never opens an old menu target when Home was removed during asset decoding',async()=>{
 vi.useFakeTimers();const load=deferred(),flow=transition()
 launcher=createMenuLauncher({prepare:()=>load.promise,transition:flow})
 const pending=launcher.enter('hand',vi.fn());launcher.dispose();load.resolve(true)
 expect(await pending).toBe(false);expect(flow.run).not.toHaveBeenCalled();expect(vi.getTimerCount()).toBe(0)
})
it('releases a failed launch so the next click can use the simplified scene',async()=>{
 vi.useFakeTimers();const flow=transition(),prepare=vi.fn().mockRejectedValueOnce(Error('decode failed')).mockResolvedValue(false)
 launcher=createMenuLauncher({prepare,transition:flow})
 await expect(launcher.enter('angel',vi.fn())).rejects.toThrow('decode failed')
 expect(launcher.state.preparing).toBe(false)
 const next=launcher.enter('hand',vi.fn());await vi.advanceTimersByTimeAsync(220);expect(await next).toBe(true)
 expect(flow.run).toHaveBeenCalledWith('hand',expect.any(Function),{simplified:true,origin:null})
})
it('captures the actual motif location but keeps a clipped motif within its card and viewport',()=>{
 const card={left:20,top:100,right:220,bottom:260,width:200,height:160}
 const motif={left:-30,top:80,width:100,height:150}
 expect(menuOrigin({getBoundingClientRect:()=>card,querySelector:()=>({getBoundingClientRect:()=>motif})})).toMatchObject({x:36,y:155})
 expect(menuOrigin(null)).toBeNull()
})
function destination(kind){
 const el=document.createElement('section');el.dataset.menuDestination=kind
 el.innerHTML='<h1 data-arrival="title">标题</h1><aside data-arrival="ornament">纹样</aside>'+Array.from({length:12},()=>'<article data-arrival="item">实际内容</article>').join('')
 document.body.append(el)
 const animations=[]
 const animate=vi.fn(()=>{const animation={cancel:vi.fn()};animations.push(animation);return animation})
 el.querySelectorAll('[data-arrival]').forEach(target=>target.animate=animate)
 return {el,animate,animations}
}
it('coordinates only the committed destination, bounds archive work and finishes inside the curtain reveal',()=>{
 for(const kind of ['angel','gears','tide','hand']){
  const target=destination(kind),unrelated=destination('unrelated')
  cleanup=playMenuArrival(kind)
  expect(target.animate).toHaveBeenCalledTimes(8);expect(unrelated.animate).not.toHaveBeenCalled()
  for(const [,options] of target.animate.mock.calls)expect(options.delay+options.duration).toBeLessThanOrEqual(menuRevealTimings[kind])
  cleanup();expect(target.animations.every(a=>a.cancel.mock.calls.length===1)).toBe(true)
  expect(target.el.dataset.menuReceiving).toBeUndefined();document.body.innerHTML=''
 }
})
it('cancels destination movement immediately on a motion preference change without removing content',()=>{
 const target=destination('hand');cleanup=playMenuArrival('hand');setMotionMode('STATIC')
 expect(target.animations.every(a=>a.cancel.mock.calls.length===1)).toBe(true)
 expect(target.el.textContent).toContain('实际内容');expect(target.el.dataset.menuReceiving).toBeUndefined()
})
it('leaves a destination readable without browser animation support or while motion is static',()=>{
 const target=destination('angel');target.el.querySelectorAll('[data-arrival]').forEach(el=>delete el.animate)
 cleanup=playMenuArrival('angel');expect(target.el.dataset.menuReceiving).toBeUndefined()
 setMotionMode('STATIC');const other=destination('hand');cleanup=playMenuArrival('hand')
 expect(other.animate).not.toHaveBeenCalled();expect(other.el.textContent).toContain('实际内容')
})
