// 签到第 N 天多倍奖励：策划配 Excel 指向 scriptId=sign_in_bonus，改倍数无需发版
def day = binding.hasVariable('day') ? (binding.getVariable('day') as Number).intValue() : 1
def baseReward = binding.hasVariable('baseReward') ? (binding.getVariable('baseReward') as Number).intValue() : 10
def multiDay = binding.hasVariable('multiDay') ? (binding.getVariable('multiDay') as Number).intValue() : 7
def multiplier = binding.hasVariable('multiplier') ? (binding.getVariable('multiplier') as Number).intValue() : 2
def reward = (day == multiDay) ? baseReward * multiplier : baseReward
return [retcode: 0, value: [reward: reward, day: day, multiplied: (day == multiDay), detail: "sign_day_" + day]]
