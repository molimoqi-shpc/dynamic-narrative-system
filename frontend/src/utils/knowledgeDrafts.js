import {visibleText} from './visibleText'

// Carry the selected evidence into the next author request; a title alone can
// refer to a notebook entry older than the recent-context window.
export function knowledgeExcerpt(entry) {
 const content=visibleText(String(entry.content || '')).trim()
 return content.length>2800?`以下为节选：“${content.slice(0,2800)}”`:`原记录：“${content}”`
}
function provenance(entry) {
 const source=String(entry.sourceLabel || '自己的见闻记录').slice(0,120)
 const checked=entry.verificationNote?`已记录的核对结果：“${visibleText(entry.verificationNote).slice(0,400)}”。`:''
 return `记录来源为${source}。${checked}`
}
export function shareKnowledgeDraft(person,entry) {
 return `我向${person.godName}转述「${visibleText(entry.title)}」。${knowledgeExcerpt(entry)}。${provenance(entry)}请对方自行判断；转述不代表已经证实。`
}
export function questionKnowledgeDraft(person,entry) {
 return `我向${person.godName}询问「${visibleText(entry.title)}」。${knowledgeExcerpt(entry)}。${provenance(entry)}请其说明有哪些亲见依据、哪些仍是推测，按对方实际所知自然交谈。`
}
