import test from 'node:test'
import assert from 'node:assert/strict'
import {buildSceneGuidance,statChanges,statDefinitions} from '../src/utils/sceneGuidance.js'

test('unloaded scene offers no advice',()=>assert.deepEqual(buildSceneGuidance(),[]))
test('empty scene only offers observation and invents no characters',()=>{
  const result=buildSceneGuidance({sceneId:'empty',presentGods:[],exits:[]})
  assert.deepEqual(result.map(a=>a.key),['observe'])
})
test('advice uses only named present NPC and valid known exit',()=>{
  const result=buildSceneGuidance({sceneId:'s',presentGods:[null,'unknown-id',{godId:'rain',godName:'雨师'}],exits:[{direction:'OTHER',toSceneId:'bad'},{direction:'EAST',toSceneId:'next'}]})
  assert.equal(result.length,3)
  assert.equal(result[1].text,'我向雨师行礼，请教此地的来历与近况。')
  assert.equal(result[2].kind,'movement')
  assert.match(result[2].text,/东方出口/)
})
test('absent targets and invalid exit direction cannot become advice',()=>{
  assert.equal(buildSceneGuidance({sceneId:'s',presentGods:[{godName:'无档案人物'}],exits:[{direction:'NORTH'}]}).length,1)
})
test('stat changes compare server values and ignore missing or invalid data',()=>{
  assert.deepEqual(statChanges({dou:10,gui:75,ben:20},{dou:12,gui:74,ben:20}),[{key:'dou',label:'斗',before:10,after:12,delta:2},{key:'gui',label:'归',before:75,after:74,delta:-1}])
  assert.deepEqual(statChanges({dou:undefined},{dou:10}),[])
  assert.deepEqual(statChanges(null,{dou:10}),[])
  assert.deepEqual(statDefinitions.map(s=>s.label),['知','慧','斗','闻','本','归'])
})


test('inventory alone does not suggest arbitrary gift giving',()=>{
  for(const item of ['麻绳','水囊','映潮印'])assert(!buildSceneGuidance({sceneId:'s',presentGods:[{godId:'boat',godName:'渡者'}],playerInventory:[item]}).some(a=>a.key==='give'))
})
