import http from '../api'

export const storyExportComplete='—— 故事文本导出完毕 ——'

function filename(headers){
 const disposition=headers?.get?.('content-disposition') ?? headers?.['content-disposition'] ?? ''
 let name='故事文本.txt'
 const encoded=disposition.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
 if(encoded)try{name=decodeURIComponent(encoded[1].trim().replace(/^"|"$/g,''))}catch{}
 else {const plain=disposition.match(/filename\s*=\s*"([^"]*)"/i);if(plain)name=plain[1]}
 name=name.replace(/[<>:"/\\|?*\x00-\x1f\x7f]/g,'_').replace(/[. ]+$/,'')
 return Array.from(name.replace(/\.txt$/i,'')).slice(0,100).join('')+'.txt'
}
async function errorMessage(data,fallback){
 try{const value=typeof data?.text==='function'?JSON.parse(await data.text()):data;if(typeof value?.message==='string')return value.message.slice(0,300)}catch{}
 return fallback
}
export async function downloadStory(sessionId,{valid=()=>true,signal}={}){
 let response
 try{response=await http.get('/game/story-export',{params:{sessionId},responseType:'blob',rawResponse:true,timeout:120000,signal})}
 catch(error){
  if(error?.code==='ERR_CANCELED' || error?.name==='AbortError')throw error
  throw new Error(await errorMessage(error?.response?.data,'故事暂未导出成功，请稍后重试。'))
 }
 if(!valid() || signal?.aborted)return false
 const blob=response.data
 if(typeof blob?.slice!=='function' || blob.size<=3 || !String(blob.type).toLowerCase().startsWith('text/plain'))throw new Error(await errorMessage(blob,'未收到完整的故事文本，请重试导出。'))
 // A failed streaming response must not become a seemingly successful, cut-off file.
 const tail=await blob.slice(Math.max(0,blob.size-256)).text()
 if(!tail.trimEnd().endsWith(storyExportComplete))throw new Error('故事导出尚未完整结束，请重试；存档内容没有改变。')
 if(!valid() || signal?.aborted)return false
 const url=URL.createObjectURL(blob),link=document.createElement('a');let downloaded=false
 try{
  link.href=url;link.download=filename(response.headers);link.hidden=true;document.body.appendChild(link);link.click();downloaded=true
 }finally{
  link.remove()
  if(downloaded)setTimeout(()=>URL.revokeObjectURL(url),1000)
  else URL.revokeObjectURL(url)
 }
 return true
}
