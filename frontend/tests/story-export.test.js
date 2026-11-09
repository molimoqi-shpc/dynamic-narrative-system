import {Blob as NativeBlob} from 'node:buffer'
import {afterEach,beforeEach,describe,it,expect,vi} from 'vitest'
const mocks=vi.hoisted(()=>({get:vi.fn()}))
vi.mock('../src/api',()=>({default:{get:mocks.get}}))
import {downloadStory,storyExportComplete} from '../src/utils/storyExport'

let clicks,createUrl,revokeUrl
const complete=()=>new NativeBlob(['\ufeff世界与序章\r\n正文保留“引号”。\r\n'+storyExportComplete+'\r\n'],{type:'text/plain;charset=utf-8'})
beforeEach(()=>{
 mocks.get.mockReset();clicks=[];createUrl=vi.fn(()=> 'blob:story-export');revokeUrl=vi.fn();vi.stubGlobal('URL',{createObjectURL:createUrl,revokeObjectURL:revokeUrl})
 vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(function(){clicks.push({filename:this.download,url:this.href})})
 mocks.get.mockResolvedValue({data:complete(),headers:{'content-disposition':"attachment; filename*=UTF-8''"+encodeURIComponent('渡口余灯-林舟-故事.txt')}})
})
afterEach(()=>{vi.useRealTimers();vi.restoreAllMocks();vi.unstubAllGlobals()})
describe('complete public story download',()=>{
 it('downloads the server-provided UTF-8 filename with authentication handled by the existing client',async()=>{
  vi.useFakeTimers();expect(await downloadStory('owned-save')).toBe(true)
  expect(mocks.get).toHaveBeenCalledWith('/game/story-export',expect.objectContaining({params:{sessionId:'owned-save'},responseType:'blob',rawResponse:true}))
  expect(clicks).toEqual([{filename:'渡口余灯-林舟-故事.txt',url:'blob:story-export'}]);expect(document.querySelector('a[download]')).toBeNull()
  expect(revokeUrl).not.toHaveBeenCalled();await vi.advanceTimersByTimeAsync(1000);expect(revokeUrl).toHaveBeenCalledWith('blob:story-export')
 })
 it('rejects a cut-off stream without creating a misleading partial download',async()=>{
  mocks.get.mockResolvedValue({data:new NativeBlob(['只有半截故事'],{type:'text/plain'}),headers:{}})
  await expect(downloadStory('A')).rejects.toThrow('尚未完整结束');expect(createUrl).not.toHaveBeenCalled();expect(clicks).toEqual([])
 })
 it('turns a JSON error returned as a blob into the real readable message',async()=>{
  mocks.get.mockRejectedValue({response:{data:new NativeBlob([JSON.stringify({message:'此存档还没有可以导出的已发表故事',traceId:'private-trace'})],{type:'application/json'})}})
  await expect(downloadStory('A')).rejects.toThrow('此存档还没有');expect(createUrl).not.toHaveBeenCalled()
 })
 it('does not save an HTML or JSON response as a story file',async()=>{
  mocks.get.mockResolvedValue({data:new NativeBlob(['<script>not a story</script>'],{type:'text/html'}),headers:{}})
  await expect(downloadStory('A')).rejects.toThrow('未收到完整');expect(createUrl).not.toHaveBeenCalled();expect(document.querySelector('script')).toBeNull()
 })
 it('suppresses the old download after its owner or requested save has changed',async()=>{
  expect(await downloadStory('A',{valid:()=>false})).toBe(false);expect(createUrl).not.toHaveBeenCalled()
 })
 it('checks ownership again after asynchronously verifying the last bytes',async()=>{
  let resolve;let owns=true;const tail=new Promise(yes=>{resolve=yes})
  mocks.get.mockResolvedValue({data:{size:100,type:'text/plain',slice:()=>({text:()=>tail})},headers:{}})
  const work=downloadStory('A',{valid:()=>owns});await vi.waitFor(()=>expect(mocks.get).toHaveBeenCalled());owns=false;resolve(storyExportComplete)
  expect(await work).toBe(false);expect(createUrl).not.toHaveBeenCalled()
 })
 it('uses a safe txt filename even if attachment metadata contains path or control characters',async()=>{
  mocks.get.mockResolvedValue({data:complete(),headers:{'content-disposition':"attachment; filename*=UTF-8''"+encodeURIComponent('../坏名\r\n/文件.exe')}})
  await downloadStory('A');expect(clicks[0].filename).toMatch(/\.txt$/);expect(clicks[0].filename).not.toMatch(/[\\/\r\n]/)
 })
 it('preserves cancellation and never starts a download for an aborted request',async()=>{
  const aborted=new AbortController();aborted.abort();expect(await downloadStory('A',{signal:aborted.signal})).toBe(false);expect(createUrl).not.toHaveBeenCalled()
  mocks.get.mockRejectedValue({code:'ERR_CANCELED'});await expect(downloadStory('A')).rejects.toEqual({code:'ERR_CANCELED'})
 })
})
