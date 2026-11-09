/** Session generation handles A -> B -> A; branch identity handles rewind in place. */
export function requestIdentity(store, alive = () => true, {allowBranchChange = false} = {}) {
 const identity = {playerId:store.playerId, sessionId:store.sessionId, generation:store.sessionGeneration, branch:store.branchEpoch}
 const owned = () => alive() && identity.playerId === store.playerId && identity.sessionId === store.sessionId && identity.generation === store.sessionGeneration
 const valid = () => owned() && (allowBranchChange || identity.branch === store.branchEpoch)
 valid.owned = owned
 return valid
}
