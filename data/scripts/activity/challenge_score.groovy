// 限时挑战特殊积分：按连击与剩余时间加权，热更脚本即可调参
def baseScore = binding.hasVariable('baseScore') ? (binding.getVariable('baseScore') as Number).intValue() : 0
def combo = binding.hasVariable('combo') ? (binding.getVariable('combo') as Number).intValue() : 0
def remainSec = binding.hasVariable('remainSec') ? (binding.getVariable('remainSec') as Number).intValue() : 0
def comboFactor = binding.hasVariable('comboFactor') ? (binding.getVariable('comboFactor') as Number).doubleValue() : 0.05d
def timeFactor = binding.hasVariable('timeFactor') ? (binding.getVariable('timeFactor') as Number).intValue() : 2
def timeBonus = Math.max(0, remainSec) * timeFactor
def score = (int) Math.floor(baseScore * (1.0d + combo * comboFactor) + timeBonus)
return [retcode: 0, value: [score: score, addPoints: score, detail: "challenge_score"], addPoints: score]
