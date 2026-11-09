export function canApplyOperation(response, current, sessionId, requestedSessionId) {
  if (sessionId !== requestedSessionId || response.status === 'STALE_BRANCH') return false
  if (!response.scene || Number(response.branchEpoch) !== Number(response.scene.branchEpoch)) return false
  if (current.branchEpoch != null && Number(response.branchEpoch) !== Number(current.branchEpoch)) return false
  return Number(response.revision) >= Number(current.revision || 0) && Number(response.revision) === Number(response.scene.revision)
}

/** The server owns the pending ID; cached details belong only to that exact operation and branch. */
export function reconcilePendingOperation(scene, previous, sessionId=previous?.submission?.sessionId) {
  // A scene read can overtake a timed-out POST. Absence is not proof that the
  // original submission cannot arrive later; keep its exact replay identity.
  if (!scene.pendingOperationId) return replayableSubmission(previous,sessionId,scene.branchEpoch)
    && (['RUNNING','UNKNOWN'].includes(previous.status) || previous.pendingKind==='SUBMISSION_NOT_REGISTERED')
    ? {...previous,status:previous.pendingKind==='SUBMISSION_NOT_REGISTERED'?'PARTIAL':'UNKNOWN'} : null
  const same = previous?.operationId === scene.pendingOperationId && Number(previous.branchEpoch) === Number(scene.branchEpoch)
  return {
    ...(same ? previous : {status: 'UNKNOWN', playerInput: ''}),
    operationId: scene.pendingOperationId,
    branchEpoch: scene.branchEpoch
  }
}

export function replayableSubmission(operation, sessionId, branchEpoch) {
  const request=operation?.submission
  return request && typeof sessionId==='string' && request.sessionId===sessionId
    && request.operationId===operation.operationId
    && Number(request.branchEpoch)===Number(branchEpoch) && Number(operation.branchEpoch)===Number(branchEpoch)
    && typeof request.sceneId==='string' && typeof request.playerText==='string' && request.playerText.trim()
    ? request : null
}

export function mergeOperationChapter(turns, response, playerInput) {
  const chapter = {
    id: `saved-${response.logId}`, round: response.round, operationId: response.operationId,
    playerInput: response.playerInput ?? playerInput, operationStatus: response.status,
    actors: (response.lines || []).map(line => ({ godId: line.godId, godName: line.godId === 'narrator' ? '旁白' : '', displayedDialogue: line.speech || '' })),
    technicalIssues: response.ruleSnapshot?.technicalIssues || [], events: response.ruleSnapshot?.eventTimeline || [], statChanges: response.ruleSnapshot?.statChanges || [],
    storyStartSeconds: response.ruleSnapshot?.storyStartSeconds, storySeconds: response.ruleSnapshot?.storySeconds,
    usage: response.usage, inputNotice: response.ruleSnapshot?.inputNotice || '', storyProgress: response.ruleSnapshot?.storyProgress,
    elapsedSeconds: response.ruleSnapshot?.elapsedSeconds, waitResults: response.ruleSnapshot?.waitResults || response.waitResults || []
  }
  const index = turns.findIndex(turn => turn.id === chapter.id)
  if (index < 0) return [...turns, chapter]
  const merged={...turns[index],...chapter}
  // Unchanged polls are observations, not new chapter arrivals.
  if(JSON.stringify(turns[index])===JSON.stringify(merged))return turns
  return turns.map((turn, i) => i === index ? merged : turn)
}

export function operationNotice(status, pendingKind) {
  if(status==='PARTIAL' && pendingKind==='SUBMISSION_NOT_REGISTERED')return '暂未查到原行动的登记结果，原请求已保留。可先查询，或按原请求重试；若原提交随后到达，服务器仍按同一笔行动处理。'
  if(status==='PARTIAL' && pendingKind==='INPUT_REVIEW_REQUIRED')return '这段输入被理解为心想，尚未执行。请确认仅保留心想，或要求按行动意愿重新理解；完整原文和条件会保留。'
  if (status === 'RUNNING') return '这一操作仍在执行。可查询进度，暂时不能提交下一行动。'
  if (status === 'PARTIAL' && pendingKind === 'MODEL_CALL_LIMIT') return '本次模型请求额度已用完，已发生内容已保存。查询进度不会追加模型请求；主动续接会允许追加模型用量，也可取消未完成部分。'
  if(status==='PARTIAL' && pendingKind==='MODEL_OUTPUT_TRUNCATED')return '模型回复达到长度上限，已有内容和成功的角色回复已保留；续接只处理尚未完成的生成或归档。'
  if(status==='PARTIAL' && pendingKind==='MODEL_PAYMENT_REQUIRED')return '已有内容已保存，模型服务余额不足。充值恢复后可续接，原行动不会重演。'
  if(status==='PARTIAL' && pendingKind==='MODEL_RATE_LIMITED')return '已有内容已保存，模型服务暂时限流。稍后可查询或续接。'
  if(status==='PARTIAL' && pendingKind==='MODEL_TIMEOUT')return '已有内容已保存，模型响应超时。可续接未完成部分。'
  if(status==='PARTIAL' && pendingKind==='MODEL_REQUEST_INVALID')return '已有内容已保存，模型服务拒绝了请求格式。需要修正请求配置后再续接，反复重试不能解决。'
  if (status === 'PARTIAL') return pendingKind === 'RESULT_UNRESOLVED'
    ? '已有内容已保存，部分行动效果尚未得到可靠结果。续接只处理未完成部分；也可取消剩余部分。'
    : '已有内容已保存，后续生成尚未完成。续接会保留人物已经作出的决定。'
  if (status === 'CANCELLED') return '已取消未完成部分。已经发生的发言和动作仍保留；如需回到此前，请使用回溯。'
  return ''
}
