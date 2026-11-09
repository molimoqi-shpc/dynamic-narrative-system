import {reactive} from 'vue'
// UI state only. It contains no character dossier, story or save data.
export const characterPanelPresentation=reactive({open:false,tab:'self'})
export function navigationSelection({characterOpen,characterTab,book,history,info,infoTab,formula,scene,other}){
 if(characterOpen)return characterTab==='items'?'items':'self'
 if(book)return 'book'
 if(history)return 'past'
 if(formula || info && infoTab==='六维')return 'stats'
 if(scene)return 'scene'
 return info || other?'other':'story'
}
