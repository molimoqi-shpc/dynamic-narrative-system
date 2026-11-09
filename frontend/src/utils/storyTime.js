// Display only: seconds remain the server's canonical clock, never a calendar date.
export function durationText(value){
 if(typeof value!=='number' || !Number.isFinite(value) || value<0)return '时长未记录'
 let n=Math.floor(value);if(!n)return '不足1秒'
 const parts=[];for(const [unit,size] of [['天',86400],['小时',3600],['分钟',60],['秒',1]]){const amount=Math.floor(n/size);n%=size;if(amount)parts.push(amount+unit)}
 return parts.slice(0,2).join('')
}
export function storyMoment(value,{currentSeconds,currentLabel,recordedLabel}={}){
 if(recordedLabel?.trim())return recordedLabel.trim()
 if(typeof value!=='number' || !Number.isFinite(value) || value<0)return '时刻未记录'
 if(Number.isFinite(currentSeconds) && currentSeconds>=value){const ago=currentSeconds-value;if(!ago)return currentLabel?.trim() || '此刻';return durationText(ago)+'前'}
 return value===0?'旅程起始':'启程后'+durationText(value)
}
