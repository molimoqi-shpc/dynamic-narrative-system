export function dialogueFeedback(exchange) {
  if (!exchange) return ''
  const name = exchange.targetName || '对方'
  if (exchange.status === 'NO_REPLY') return `${name}这次没有口头回应；其实际动作仍以故事为准。`
  if (exchange.status === 'OTHER_ADDRESSEE') return `${name}向其他人说了话，这不等于回答了你。`
  if (exchange.status === 'UNAVAILABLE') return '你的话已保存，但人物反应尚未取得。重新发送会成为又一次发言。'
  return ''
}

export function technicalFeedback(issues) {
  if (!Array.isArray(issues)) return ''
  if (issues.some(issue => issue?.kind === 'ENDING_RESULT_UNAVAILABLE'))
    return '此生终章与评价尚未生成完成。已有故事保留，可以续接尚未完成的终章。'
  if (issues.some(issue => issue?.kind === 'STORY_ARCHIVE_UNAVAILABLE'))
    return '此段故事的归档尚未完成。已有正文保留，可以续接尚未完成的归档。'
  if (issues.some(issue => issue?.kind === 'STORY_TEXT_UNAVAILABLE'))
    return '此段故事正文尚未生成完成。已取得的人物意愿和已有内容保留，可以续接正文。'
  if (issues.some(issue => issue?.kind === 'STORY_RESULT_UNAVAILABLE'))
    return '此段故事尚未生成完成。已取得的人物意愿和已有内容保留，可以续接故事生成。'
  if (issues.some(issue => issue?.kind === 'WORLD_RESULT_UNAVAILABLE'))
    return '此段行动结果尚未完成核验。已发生内容保留，可以续接或取消未完成部分。'
  if (issues.some(issue => issue?.kind === 'ROLE_DECISION_UNAVAILABLE'))
    return '此段有尚未取得的人物反应。技术未完成不代表人物沉默、拒绝或放弃；已发生内容保留。'
  return ''
}
