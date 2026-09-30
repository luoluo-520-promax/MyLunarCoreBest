// 战斗辅助策略所在包（方案 D）

package cn.itcast.demo.mylunarcore.battle.assist;



// 敌人弱点与角色克制标签内容包

import cn.itcast.demo.mylunarcore.assist.AssistFeatureContent;

import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;

// 战局与实体运行时

import cn.itcast.demo.mylunarcore.battle.BattleContext;

import cn.itcast.demo.mylunarcore.battle.EntityState;

import cn.itcast.demo.mylunarcore.battle.MonsterRuntime;

import cn.itcast.demo.mylunarcore.battle.WaveRuntime;

// battleHintEnabled 开关

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

import org.springframework.stereotype.Component;



import java.util.Comparator;

import java.util.List;

import java.util.Locale;

import java.util.Map;



/**

 * 启发式自动战斗策略：结合战斗状态向量动态调整目标/技能与微观干预建议。

 */

@Component

public class HeuristicBattleAssistPolicy implements BattleAssistPolicy {



    private final LunarCoreProperties properties;

    private final AssistFeatureContentRepository featureContentRepository;

    private final BattleAssistDecisionCache decisionCache;



    public HeuristicBattleAssistPolicy(LunarCoreProperties properties,

                                       AssistFeatureContentRepository featureContentRepository,

                                       BattleAssistDecisionCache decisionCache) {

        this.properties = properties;

        this.featureContentRepository = featureContentRepository;

        this.decisionCache = decisionCache;

    }

    /** 单测便捷：内建决策缓存。 */
    public HeuristicBattleAssistPolicy(LunarCoreProperties properties,

                                       AssistFeatureContentRepository featureContentRepository) {

        this(properties, featureContentRepository, new BattleAssistDecisionCache());

    }



    @Override

    public boolean isEnabledFor(BattleContext context) {

        return properties.getAiAssist().isBattleHintEnabled() && context != null && !context.isEnded();

    }



    @Override

    public Suggestion suggest(BattleContext context, int actorEntityId) {

        if (!isEnabledFor(context)) {

            return Suggestion.none();

        }

        var cached = decisionCache.get(context, actorEntityId);

        if (cached.isPresent()) {

            return cached.get();

        }

        BattleStateVectorSerializer.tickEconomy(context);

        BattleStateVectorSerializer.BattleStateVector stateVector =

                BattleStateVectorSerializer.serialize(context);



        EntityState actor = context.getEntity(actorEntityId);

        if (actor == null || actor.isDead()) {

            return Suggestion.none();

        }

        List<Integer> targets = context.listAliveMonsterIdsInCurrentWave();

        if (targets.isEmpty()) {

            return Suggestion.none();

        }

        int bestTarget = pickHighestThreat(context, targets);

        int skillId = pickSkillIdDynamic(context, actor, stateVector);

        AssistFeatureContent.EnemyWeakness weakness = resolveWeakness(context, bestTarget);

        String weaknessAdvice = buildWeaknessAdvice(weakness);

        String switchAdvice = buildSwitchAdvice(actor, weakness);



        int lockSkill = 0;

        int lockCaster = 0;

        boolean revert = false;

        if (stateVector.enrageImminent() && context.getTeamSkillPoints() >= 1) {

            lockSkill = 2;

            lockCaster = actorEntityId;

            revert = true;

            switchAdvice = "BOSS 即将狂暴，微观干预：下一动留战技给盾奶（技能2）";

            skillId = 2;

        } else if (stateVector.lowHpExecute() && context.getTeamSkillPoints() <= 2) {

            lockSkill = 1;

            lockCaster = actorEntityId;

            revert = true;

            weaknessAdvice = "小怪丝血，建议普攻补刀，不要浪费战技点";

            skillId = 1;

        } else if (stateVector.energyOverflow()) {

            lockSkill = 3;

            lockCaster = actorEntityId;

            revert = true;

            weaknessAdvice = "终结技充能溢出，建议立即释放大招";

            skillId = 3;

        }



        String stateSummary = stateVector.summary();

        String reason = "heuristic:threat_target=" + bestTarget

                + ",skill=" + skillId

                + ",wave=" + context.getCurrentWave()

                + ",actorHp=" + actor.getHp()

                + ",state=" + stateSummary

                + (weaknessAdvice.isEmpty() ? "" : ",weakness=" + weaknessAdvice)

                + (switchAdvice.isEmpty() ? "" : ",switch=" + switchAdvice);

        Suggestion suggestion = new Suggestion(skillId, List.of(bestTarget), reason, switchAdvice, weaknessAdvice,

                stateSummary, lockSkill, lockCaster, revert);

        decisionCache.put(context, actorEntityId, suggestion);

        return suggestion;

    }



    private int pickSkillIdDynamic(BattleContext context, EntityState actor,

                                   BattleStateVectorSerializer.BattleStateVector state) {

        if (state.energyOverflow()) {

            return 3;

        }

        if (state.lowHpExecute() && context.getTeamSkillPoints() <= 2) {

            return 1;

        }

        if (context.getTeamSkillPoints() >= 2 && actor.getHp() > 0 && actor.getHp() >= 300) {

            return 2;

        }

        if (actor.getHp() > 0 && actor.getHp() < 300) {

            return 2;

        }

        return 1;

    }



    private AssistFeatureContent.EnemyWeakness resolveWeakness(BattleContext context, int monsterEntityId) {

        MonsterRuntime runtime = findMonster(context, monsterEntityId);

        int configId = runtime == null ? monsterEntityId : runtime.getConfigMonsterId();

        for (AssistFeatureContent.EnemyWeakness w : featureContentRepository.current().enemyWeaknesses()) {

            if (w != null && w.monsterId() == configId) {

                return w;

            }

        }

        return null;

    }



    private static String buildWeaknessAdvice(AssistFeatureContent.EnemyWeakness w) {

        if (w == null) {

            return "";

        }

        StringBuilder sb = new StringBuilder();

        if (w.name() != null && !w.name().isBlank()) {

            sb.append(w.name());

        }

        if (!w.weakTags().isEmpty()) {

            if (!sb.isEmpty()) {

                sb.append(' ');

            }

            sb.append("弱点=").append(String.join("/", w.weakTags()));

        }

        if (w.strategyHint() != null && !w.strategyHint().isBlank()) {

            if (!sb.isEmpty()) {

                sb.append('；');

            }

            sb.append(w.strategyHint());

        }

        return sb.toString();

    }



    private String buildSwitchAdvice(EntityState actor, AssistFeatureContent.EnemyWeakness w) {

        if (actor == null) {

            return "";

        }

        double threshold = w == null ? 0.35 : w.switchWhenHpBelow();

        if (actor.getHp() > 0 && actor.getHp() < 300) {

            return "当前角色残血，建议切换生存/治疗位再输出";

        }

        if (w != null && !w.weakTags().isEmpty()) {

            Map<String, List<String>> counters = featureContentRepository.current().avatarCounterTags();

            String preferred = findPreferredAvatar(counters, w.weakTags());

            if (!preferred.isEmpty()) {

                return "建议切至克制位(avatarId=" + preferred + ") 打弱点 "

                        + String.join("/", w.weakTags())

                        + "；阈值≈" + String.format(Locale.ROOT, "%.0f%%", threshold * 100);

            }

            return "建议在血量低于 " + String.format(Locale.ROOT, "%.0f%%", threshold * 100)

                    + " 前切至克制 " + String.join("/", w.weakTags()) + " 的角色";

        }

        return "";

    }



    private static String findPreferredAvatar(Map<String, List<String>> counters, List<String> weakTags) {

        if (counters == null || counters.isEmpty() || weakTags == null) {

            return "";

        }

        String bestId = "";

        int best = 0;

        for (Map.Entry<String, List<String>> e : counters.entrySet()) {

            if (e.getKey() == null || e.getValue() == null) {

                continue;

            }

            int hit = 0;

            for (String tag : e.getValue()) {

                if (tag == null) {

                    continue;

                }

                for (String w : weakTags) {

                    if (w != null && w.equalsIgnoreCase(tag)) {

                        hit++;

                    }

                }

            }

            if (hit > best) {

                best = hit;

                bestId = e.getKey();

            }

        }

        return bestId;

    }



    private static int pickHighestThreat(BattleContext context, List<Integer> targets) {

        return targets.stream()

                .max(Comparator.comparingDouble(id -> threatScore(context, id)))

                .orElse(targets.get(0));

    }



    private static double threatScore(BattleContext context, int monsterId) {

        EntityState entity = context.getEntity(monsterId);

        if (entity == null || entity.isDead()) {

            return Double.NEGATIVE_INFINITY;

        }

        MonsterRuntime runtime = findMonster(context, monsterId);

        int level = runtime == null ? 1 : Math.max(1, runtime.getLevel());

        int maxHp = runtime == null ? Math.max(1, entity.getHp()) : Math.max(1, runtime.getMaxHp());

        double hpRatio = entity.getHp() * 1.0 / maxHp;

        double executeBonus = hpRatio <= 0.35 ? 40.0 : 0.0;

        double levelThreat = level * 3.0;

        double remainingHpPressure = entity.getHp() * 0.05;

        boolean eliteLike = level >= 20 || maxHp >= 2500;

        return executeBonus + levelThreat + remainingHpPressure + (eliteLike ? 25.0 : 0.0);

    }



    private static MonsterRuntime findMonster(BattleContext context, int monsterId) {

        List<WaveRuntime> waves = context.getWaves();

        if (waves == null) {

            return null;

        }

        for (WaveRuntime wave : waves) {

            if (wave == null || wave.getMonsters() == null) {

                continue;

            }

            for (MonsterRuntime monster : wave.getMonsters()) {

                if (monster != null

                        && (monster.getRuntimeEntityId() == monsterId

                        || monster.getConfigMonsterId() == monsterId)) {

                    return monster;

                }

            }

        }

        return null;

    }

}


