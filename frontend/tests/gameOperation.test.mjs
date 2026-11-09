import test from 'node:test'
import assert from 'node:assert/strict'
import { canApplyOperation, mergeOperationChapter, operationNotice } from '../src/utils/gameOperation.js'

test('a recovered response replaces the saved chapter instead of duplicating it', () => {
  const partial={operationId:'same',logId:12,round:1,status:'PARTIAL',playerInput:'借刀',lines:[{godId:'narrator',speech:'你询问借刀。'}]}
  const first=mergeOperationChapter([],partial,'借刀')
  const final=mergeOperationChapter(first,{...partial,status:'COMPLETED',lines:[{godId:'narrator',speech:'你询问借刀。甲拒绝。'}]},'借刀')
  assert.equal(final.length,1)
  assert.equal(final[0].id,'saved-12')
  assert.equal(final[0].actors[0].displayedDialogue,'你询问借刀。甲拒绝。')
})
test('old branch, old revision, mismatched scene version and other session cannot overwrite current play', () => {
  const current={branchEpoch:3,revision:8},r={status:'COMPLETED',branchEpoch:3,revision:9,scene:{branchEpoch:3,revision:9}}
  assert.equal(canApplyOperation(r,current,'mine','mine'),true)
  assert.equal(canApplyOperation({...r,branchEpoch:2},current,'mine','mine'),false)
  assert.equal(canApplyOperation({...r,revision:7,scene:{branchEpoch:3,revision:7}},current,'mine','mine'),false)
  assert.equal(canApplyOperation({...r,scene:{branchEpoch:3,revision:10}},current,'mine','mine'),false)
  assert.equal(canApplyOperation(r,current,'mine','other'),false)
})
test('technical pending and cancellation remain outside story prose', () => {
  assert.match(operationNotice('PARTIAL','RESULT_UNRESOLVED'),/可靠结果/)
  assert.match(operationNotice('CANCELLED'),/已经发生/)
  assert.equal(operationNotice('COMPLETED'),'')
})
test('recovery replaces the chapter time, timeline and wait results without a duplicate',()=>{
 const first={operationId:'waiting',logId:21,status:'PARTIAL',ruleSnapshot:{storyStartSeconds:0,storySeconds:2,elapsedSeconds:2,eventTimeline:[{text:'询问',endSeconds:2}],waitResults:[]}}
 const saved=mergeOperationChapter([],first,'等人离开')
 const final=mergeOperationChapter(saved,{...first,status:'COMPLETED',ruleSnapshot:{storyStartSeconds:0,storySeconds:7,elapsedSeconds:7,eventTimeline:[{text:'询问',endSeconds:2},{text:'此人离开',endSeconds:7}],waitResults:[{status:'SATISFIED',atSeconds:7}]}},'等人离开')
 assert.equal(final.length,1);assert.equal(final[0].elapsedSeconds,7);assert.equal(final[0].events.length,2);assert.equal(final[0].waitResults[0].status,'SATISFIED')
})

test('polling and recovery replace cumulative usage instead of adding it twice',()=>{
 const partial={operationId:'cost',logId:1,status:'PARTIAL',usage:{calls:1,total:100,costCny:0.02,unconfirmedCalls:1}}
 let turns=mergeOperationChapter([],partial,'询问');turns=mergeOperationChapter(turns,partial,'询问');assert.equal(turns[0].usage.total,100)
 const final={...partial,status:'COMPLETED',usage:{calls:2,total:230,costCny:0.04,unconfirmedCalls:0}}
 turns=mergeOperationChapter(turns,final,'询问');assert.equal(turns.length,1);assert.deepEqual(turns[0].usage,final.usage)
})

test('an unchanged poll preserves the existing chapter and list identity',()=>{
 const response={operationId:'unchanged',logId:31,status:'RUNNING',playerInput:'学习',lines:[{godId:'narrator',speech:'已保存的故事。'}],usage:{calls:1,total:100,costCny:0.02}}
 const first=mergeOperationChapter([],response,'学习')
 const repeated=mergeOperationChapter(first,JSON.parse(JSON.stringify(response)),'学习')
 assert.equal(repeated,first);assert.equal(repeated[0],first[0])
 const finished=mergeOperationChapter(first,{...response,status:'COMPLETED'},'学习')
 assert.notEqual(finished,first);assert.equal(finished.length,1);assert.equal(finished[0].operationStatus,'COMPLETED')
})

test('story progress keeps the human decision separately from technical recovery',()=>{
 const turns=mergeOperationChapter([],{logId:9,operationId:'story',status:'COMPLETED',ruleSnapshot:{storyProgress:{beats:2,requiresInput:true,decisionType:'ALLIANCE',reason:'对方邀请你结盟。'}}},'前去探访')
 assert.equal(turns[0].storyProgress.requiresInput,true);assert.equal(turns[0].storyProgress.decisionType,'ALLIANCE');assert.equal(turns[0].operationStatus,'COMPLETED');assert.deepEqual(turns[0].technicalIssues,[])
})
