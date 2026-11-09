export const statDefinitions = [
  { key: 'zhi', label: '知', description: '情报量：掌握的信息与知识。' },
  { key: 'hui', label: '慧', description: '推理解题：理解信息、辨析线索的能力。' },
  { key: 'dou', label: '斗', description: '战斗力基准：正面对抗的能力。' },
  { key: 'wen', label: '闻', description: '局势感知：发现消息、感知局势的能力。' },
  { key: 'ben', label: '本', description: '能力根基：影响六维实际值的修正系数。' },
  { key: 'gui', label: '归', description: '忠诚意志：归属感、忠诚与抗策反能力。' }
]

const directions = { NORTH: '北', SOUTH: '南', EAST: '东', WEST: '西', UP: '上', DOWN: '下' }

// Advice is derived only from the loaded scene; it never invents items, people or exits.
export function buildSceneGuidance(scene = {}) {
  if (!scene.sceneId) return []
  const advice = [{ key: 'observe', kind: 'action', label: '细查现场', text: '我调查此地已有的动静与痕迹，把能够核实的见闻记下来。' }]
  const npc = (scene.presentGods || []).find(g => g && typeof g === 'object' && g.godId && g.godName)
  if (npc) advice.push({ key: 'talk', kind: 'action', label: `与${npc.godName}交谈`, text: `我向${npc.godName}行礼，请教此地的来历与近况。` })
  const exit = (scene.exits || []).find(ex => directions[ex.direction] && ex.toSceneId)
  if (exit) advice.push({ key: 'move', kind: 'movement', label: `向${directions[exit.direction]}探索`, text: `我沿已知的${directions[exit.direction]}方出口前行。` })
  return advice
}

export function statChanges(before, after) {
  if (!before || !after) return []
  return statDefinitions.flatMap(({key,label}) => {
    const oldValue=before[key], newValue=after[key]
    if (!Number.isFinite(oldValue) || !Number.isFinite(newValue) || oldValue===newValue) return []
    return [{key,label,before:oldValue,after:newValue,delta:newValue-oldValue}]
  })
}
