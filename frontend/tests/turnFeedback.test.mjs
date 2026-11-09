import test from 'node:test'
import assert from 'node:assert/strict'
import { dialogueFeedback, technicalFeedback } from '../src/utils/turnFeedback.js'

test('technical failure is visible without inventing a refusal or suggesting a second attempt', () => {
  assert.match(dialogueFeedback({status:'UNAVAILABLE'}), /又一次发言/)
  assert.match(technicalFeedback([{kind:'ROLE_DECISION_UNAVAILABLE',phase:1}]), /技术未完成不代表/)
})
test('another addressee is distinguished from no speech and a valid reply', () => {
  assert.match(dialogueFeedback({status:'OTHER_ADDRESSEE',targetName:'甲'}), /不等于回答了你/)
  assert.match(dialogueFeedback({status:'NO_REPLY',targetName:'甲'}), /实际动作/)
  assert.equal(dialogueFeedback({status:'SPOKEN'}), '')
  assert.equal(technicalFeedback(null), '')
})

test('an unresolved action result is not presented as a missing NPC response', () => {
  const feedback = technicalFeedback([{kind:'WORLD_RESULT_UNAVAILABLE'}])
  assert.match(feedback, /行动结果尚未完成核验/)
  assert.doesNotMatch(feedback, /人物反应|沉默|拒绝/)
})

test('story planning, prose, archiving and ending pauses identify the actual incomplete stage',()=>{
 for(const [kind,phrase]of [
  ['STORY_RESULT_UNAVAILABLE','故事尚未生成完成'],
  ['STORY_TEXT_UNAVAILABLE','故事正文尚未生成完成'],
  ['STORY_ARCHIVE_UNAVAILABLE','归档尚未完成'],
  ['ENDING_RESULT_UNAVAILABLE','终章与评价尚未生成完成']
 ]){
  const feedback=technicalFeedback([{kind,stage:'RESULT_UNRESOLVED'}])
  assert.ok(feedback.includes(phrase));assert.match(feedback,/保留/);assert.match(feedback,/续接/)
  assert.doesNotMatch(feedback,/尚未取得的人物反应|沉默|拒绝|放弃/)
 }
})
