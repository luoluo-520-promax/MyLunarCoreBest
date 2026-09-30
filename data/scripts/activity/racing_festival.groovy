// 跑酷活动脚本：热更即可调检查点与结算
def playerId = binding.hasVariable('playerId') ? (binding.getVariable('playerId') as Number).intValue() : 0
def checkpointIndex = binding.hasVariable('checkpointIndex') ? (binding.getVariable('checkpointIndex') as Number).intValue() : 0
def action = binding.hasVariable('action') ? String.valueOf(binding.getVariable('action')) : 'tick'
if (action == 'start') {
  return [retcode: 0, value: [phase: 'START', checkpoints: 4], miniGameId: 'racing_festival']
}
if (checkpointIndex >= 3) {
  def elapsed = binding.hasVariable('elapsedMs') ? (binding.getVariable('elapsedMs') as Number).longValue() : 0L
  def score = (int) Math.max(0, 100000 - elapsed)
  return [retcode: 0, value: [phase: 'END', score: score, elapsedMs: elapsed], addPoints: score]
}
return [retcode: 0, value: [phase: 'TICK', checkpointIndex: checkpointIndex], nextCheckpoint: checkpointIndex + 1]
