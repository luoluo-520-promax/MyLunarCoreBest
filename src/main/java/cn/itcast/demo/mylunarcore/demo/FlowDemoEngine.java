package cn.itcast.demo.mylunarcore.demo;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleStatisticsUtil;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import cn.itcast.demo.mylunarcore.battle.MonsterRuntime;
import cn.itcast.demo.mylunarcore.battle.WaveRuntime;
import cn.itcast.demo.mylunarcore.challenge.ChallengeRuntime;
import cn.itcast.demo.mylunarcore.gacha.GachaBannerConfig;
import cn.itcast.demo.mylunarcore.gacha.GachaBannerType;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** 演示模式核心逻辑：战斗结算、场景构建、挑战与抽卡模拟。 */
final class FlowDemoEngine {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEMO_SKILL_ID = 1001;
    private static final int BUFF_MAX_STACK = 5;

    private FlowDemoEngine() {
    }

    static MazeSkillActionRepository demoSkillActionRepo() {
        return new DemoMazeSkillActionRepository();
    }

    static List<BattleMonsterWaveRepository.WaveConfig> wavesForStage(int stageId) {
        List<BattleMonsterWaveRepository.WaveConfig> waves = new ArrayList<>();
        waves.add(new BattleMonsterWaveRepository.WaveConfig(1, stageId, 1, "[101,102]", 2));
        waves.add(new BattleMonsterWaveRepository.WaveConfig(2, stageId, 2, "[201]", 3));
        return waves;
    }

    static ChallengeRuntime createChallenge(long challengeUid, int playerId, int challengeId) {
        int groupId = (challengeId / 1000) * 100;
        int stageId = 1000 + challengeId;
        List<BattleSystemProto.EnemyInfo> enemyInfo = new ArrayList<>();
        enemyInfo.add(BattleSystemProto.EnemyInfo.newBuilder().setWaveIndex(1).build());
        enemyInfo.add(BattleSystemProto.EnemyInfo.newBuilder().setWaveIndex(2).build());
        return new ChallengeRuntime(
                challengeUid, playerId, 1, challengeId, groupId, stageId,
                System.currentTimeMillis() / 1000, 2, enemyInfo);
    }

    static SceneContext createDemoScene(long playerUid) {
        SceneContext context = new SceneContext(
                playerUid, 1, 2, 3, new SceneContext.ScenePos(10.5f, 0.0f, -5.2f));
        context.addMonster(new SceneContext.MonsterState(
                1_000_001, 101, 5, 500, 500,
                new SceneContext.ScenePos(1.0f, 2.0f, 3.0f), List.of(7, 8)));
        context.addNpc(new SceneContext.NpcState(
                1_000_002, 501, 42, new SceneContext.ScenePos(4.0f, 5.0f, 6.0f)));
        context.addProp(new SceneContext.PropState(
                1_000_003, 301, 0, new SceneContext.ScenePos(7.0f, 8.0f, 9.0f)));
        context.setInitialized(true);
        return context;
    }

    static BattleSimResult simulateBattle(BattleContext context,
                                          MazeSkillActionRepository skillRepo,
                                          FlowDemoPrinter printer) {
        int totalDamage = 0;
        int killCount = 0;
        int actionCount = 0;

        printer.waveInfo(context);
        printer.battleSnapshot(context, "战局创建");

        while (!context.isEnded() && actionCount < 30) {
            int targetId = firstAliveMonsterId(context);
            if (targetId < 0) {
                break;
            }
            actionCount++;
            SkillCastResult result = castSkill(context, DEMO_SKILL_ID, targetId, skillRepo);
            printer.step("回合 " + context.getTurn() + " | 对实体[" + targetId + "] 施放技能 "
                    + DEMO_SKILL_ID + " -> HP变化=" + result.hpChange()
                    + ", 新增Buff=" + result.addedBuffs()
                    + ", 击杀=" + result.killed());
            if (result.waveSwitched()) {
                printer.detail("当前波次怪物已清空，自动切换至波次 " + context.getCurrentWave());
                printer.battleSnapshot(context, "波次切换后");
            }
            totalDamage += Math.max(0, -result.hpChange());
            if (result.killed()) {
                killCount++;
            }
        }

        boolean allWavesCleared = isAllWavesCleared(context);
        if (allWavesCleared) {
            context.setEnded(true);
        }

        BattleSystemProto.BattleStatistics statistics = BattleSystemProto.BattleStatistics.newBuilder()
                .setDamageDealt(totalDamage)
                .setDamageTaken(0)
                .setTurnCount(Math.max(0, context.getTurn() - 1))
                .setKillCount(killCount)
                .build();
        String statsJson = BattleStatisticsUtil.toJson(statistics);

        printer.battleSnapshot(context, allWavesCleared ? "战斗胜利" : "战斗未完成");
        printer.detail("战斗统计 JSON: " + statsJson);

        return new BattleSimResult(allWavesCleared, totalDamage, killCount, statsJson);
    }

    static SkillCastResult castSkill(BattleContext context,
                                     int skillId,
                                     int targetId,
                                     MazeSkillActionRepository skillRepo) {
        synchronized (context.getLock()) {
            EntityState entity = context.getEntity(targetId);
            if (entity == null) {
                context.incrementTurn();
                return new SkillCastResult(0, List.of(), false, false);
            }

            List<BattleContext.MazeSkillActionRuntime> actions =
                    context.getResolvedActionsForSkill(skillId, skillRepo);

            int hpChangeTotal = 0;
            List<Integer> addedBuffs = new ArrayList<>();
            boolean killed = false;

            for (BattleContext.MazeSkillActionRuntime action : actions) {
                switch (action.getActionType()) {
                    case 1 -> {
                        int delta = action.tryParseHpDelta();
                        if (delta != 0 && !entity.isDead()) {
                            int before = entity.getHp();
                            int after = Math.max(0, before + delta);
                            entity.setHp(after);
                            if (after == 0) {
                                entity.setDead(true);
                                killed = true;
                            }
                            hpChangeTotal += (after - before);
                        }
                    }
                    case 2 -> {
                        for (Integer buffId : action.tryParseBuffIds()) {
                            if (entity.isDead()) {
                                continue;
                            }
                            int oldStacks = entity.getBuffStacks().getOrDefault(buffId, 0);
                            if (oldStacks < BUFF_MAX_STACK) {
                                entity.addBuffStack(buffId, 1, BUFF_MAX_STACK);
                                if (oldStacks == 0) {
                                    addedBuffs.add(buffId);
                                }
                            }
                        }
                    }
                    case 5 -> {
                        if (action.tryParseKillTrue() && !entity.isDead()) {
                            int before = entity.getHp();
                            entity.setHp(0);
                            entity.setDead(true);
                            hpChangeTotal += -before;
                            killed = true;
                        }
                    }
                    default -> {
                        // 演示模式跳过未实现 action_type
                    }
                }
            }

            boolean waveSwitched = false;
            if (context.isAllMonstersDeadInWave(context.getCurrentWave())
                    && context.getCurrentWave() < context.getWaveCount()) {
                context.switchToWave(context.getCurrentWave() + 1);
                waveSwitched = true;
            }
            context.incrementTurn();
            return new SkillCastResult(hpChangeTotal, List.copyOf(addedBuffs), killed, waveSwitched);
        }
    }

    static boolean isAllWavesCleared(BattleContext context) {
        for (int wave = 1; wave <= context.getWaveCount(); wave++) {
            if (!context.isAllMonstersDeadInWave(wave)) {
                return false;
            }
        }
        return context.getWaveCount() > 0;
    }

    static int firstAliveMonsterId(BattleContext context) {
        WaveRuntime wave = waveAt(context, context.getCurrentWave());
        if (wave == null) {
            return -1;
        }
        for (MonsterRuntime monster : wave.getMonsters()) {
            EntityState entity = context.getEntity(monster.getConfigMonsterId());
            if (entity != null && !entity.isDead()) {
                return entity.getId();
            }
        }
        return -1;
    }

    private static WaveRuntime waveAt(BattleContext context, int waveIndex) {
        if (waveIndex <= 0 || waveIndex > context.getWaves().size()) {
            return null;
        }
        return context.getWaves().get(waveIndex - 1);
    }

    static List<GachaBannerConfig> loadBanners() {
        try (InputStream in = FlowDemoEngine.class.getResourceAsStream("/data/Banners.json")) {
            if (in != null) {
                return MAPPER.readValue(in, new TypeReference<>() {});
            }
        } catch (Exception ignored) {
            // 允许回退到内置示例
        }
        return List.of();
    }

    static GachaBannerConfig resolveBanner(List<GachaBannerConfig> banners, int bannerId, long nowSeconds) {
        if (banners != null && !banners.isEmpty()) {
            for (GachaBannerConfig banner : banners) {
                if (banner.getId() == bannerId && isBannerOpen(banner, nowSeconds)) {
                    return banner;
                }
            }
            for (GachaBannerConfig banner : banners) {
                if (banner.getId() == bannerId) {
                    return banner;
                }
            }
        }
        return sampleBanner(bannerId);
    }

    static GachaBannerConfig pickActiveAvatarUpBanner(List<GachaBannerConfig> banners, long nowSeconds) {
        if (banners != null) {
            for (GachaBannerConfig banner : banners) {
                if (GachaBannerType.AVATAR_UP == GachaBannerType.fromGachaTypeString(banner.getGachaType())
                        && isBannerOpen(banner, nowSeconds)) {
                    return banner;
                }
            }
        }
        return sampleBanner(2001);
    }

    static GachaSummary simulateGacha(GachaBannerConfig banner, int drawCount, Random random) {
        int count5 = 0;
        int count4 = 0;
        int count3 = 0;
        int pity = 0;
        List<String> details = new ArrayList<>();

        List<Integer> up5 = banner.getRateUpItems5() == null ? List.of() : banner.getRateUpItems5();
        int up5Item = up5.isEmpty() ? 1102 : up5.get(0);

        for (int i = 1; i <= drawCount; i++) {
            pity++;
            int rarity = rollRarity(random, pity >= 90);
            if (rarity == 5) {
                pity = 0;
            }
            int itemId = pickItem(banner, rarity, up5Item, random);
            if (rarity == 5) {
                count5++;
            } else if (rarity == 4) {
                count4++;
            } else {
                count3++;
            }
            details.add("第" + i + "抽: " + rarity + "星 itemId=" + itemId
                    + (rarity == 5 && itemId == up5Item ? " [UP]" : ""));
        }
        return new GachaSummary(count5, count4, count3, List.copyOf(details));
    }

    private static boolean isBannerOpen(GachaBannerConfig banner, long nowSeconds) {
        long begin = banner.getBeginTime();
        long end = banner.getEndTime();
        return (begin <= 0 || nowSeconds >= begin) && (end <= 0 || nowSeconds <= end);
    }

    private static GachaBannerConfig sampleBanner(int bannerId) {
        GachaBannerConfig banner = new GachaBannerConfig();
        banner.setId(bannerId);
        banner.setGachaType("AvatarUp");
        banner.setBeginTime(0);
        banner.setEndTime(1_924_992_000L);
        banner.setRateUpItems5(List.of(1102));
        banner.setRateUpItems4(List.of(1105, 1106, 1109));
        return banner;
    }

    private static int rollRarity(Random random, boolean softPity) {
        int roll = random.nextInt(1000);
        int threshold5 = softPity ? 800 : 6;
        if (roll < threshold5) {
            return 5;
        }
        if (roll < 57) {
            return 4;
        }
        return 3;
    }

    private static int pickItem(GachaBannerConfig banner, int rarity, int up5Item, Random random) {
        if (rarity == 5) {
            return random.nextInt(100) < 75 ? up5Item : randomUp(banner.getRateUpItems5(), up5Item, random);
        }
        if (rarity == 4) {
            return randomUp(banner.getRateUpItems4(), 1105, random);
        }
        return 23000 + random.nextInt(3);
    }

    private static int randomUp(List<Integer> pool, int fallback, Random random) {
        if (pool == null || pool.isEmpty()) {
            return fallback;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    record SkillCastResult(int hpChange, List<Integer> addedBuffs, boolean killed, boolean waveSwitched) {
    }

    record BattleSimResult(boolean victory, int damageDealt, int killCount, String statisticsJson) {
    }

    record GachaSummary(int count5, int count4, int count3, List<String> details) {
    }

    private static final class DemoMazeSkillActionRepository extends MazeSkillActionRepository {
        DemoMazeSkillActionRepository() {
            super(null);
        }

        @Override
        public List<MazeSkillActionRepository.MazeSkillActionRow> findBySkillId(int skillId) {
            if (skillId != DEMO_SKILL_ID) {
                return List.of();
            }
            return List.of(
                    new MazeSkillActionRepository.MazeSkillActionRow(1, DEMO_SKILL_ID, 1, 0, 1, "{\"hp_change\":-150}"),
                    new MazeSkillActionRepository.MazeSkillActionRow(2, DEMO_SKILL_ID, 2, 0, 2, "{\"buff_id\":5}"),
                    new MazeSkillActionRepository.MazeSkillActionRow(3, DEMO_SKILL_ID, 5, 0, 3, "{\"kill\":true}")
            );
        }
    }
}
