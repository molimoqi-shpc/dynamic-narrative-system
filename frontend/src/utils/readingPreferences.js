export const READING_KEY='bs_reading_preferences_v1'
export const readingDefaults=Object.freeze({size:16,spacing:.45,leading:2.15,density:1,font:'serif',narrativeMode:'NORMAL'})
export const readingFonts={serif:'"Noto Serif SC","Source Han Serif SC","Songti SC",SimSun,serif',sans:'"Microsoft YaHei","PingFang SC",system-ui,sans-serif',kai:'KaiTi,"STKaiti","Noto Serif SC",serif'}
export const normalizeNarrativeMode=value=>value==='LONG'?'LONG':'NORMAL'
export function normalizeReading(raw={}){
 const value=raw && typeof raw==='object'?raw:{}
 const number=(key,min,max)=>typeof value[key]==='number' && Number.isFinite(value[key])?Math.max(min,Math.min(max,value[key])):readingDefaults[key]
 return {size:number('size',13,24),spacing:number('spacing',0,3),leading:number('leading',1.5,2.8),density:number('density',.4,1.6),font:Object.hasOwn(readingFonts,value.font)?value.font:'serif',narrativeMode:normalizeNarrativeMode(value.narrativeMode)}
}
export function loadReading(storage){try{return normalizeReading(JSON.parse(storage.getItem(READING_KEY) || '{}'))}catch{return {...readingDefaults}}}
export function saveReading(storage,value){try{storage.setItem(READING_KEY,JSON.stringify(normalizeReading(value)))}catch{}}
export function readingVariables(raw){const p=normalizeReading(raw);return {'--reader-font-size':p.size+'px','--reader-spacing':p.spacing+'px','--reader-leading':p.leading,'--reader-density':p.density,'--reader-font':readingFonts[p.font]}}
