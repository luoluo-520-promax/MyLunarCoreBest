package cn.itcast.demo.mylunarcore.demo;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.challenge.ChallengeManager;
import cn.itcast.demo.mylunarcore.challenge.ChallengeRuntime;
import cn.itcast.demo.mylunarcore.gacha.GachaBannerConfig;
import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 命令行流程演示：无需启动 Spring / MySQL，模拟「登录 → 场景 → 挑战 → 战斗 → 结算 → 抽卡」完整链路。
 */
public final class FlowDemoCli {

    private static final Logger log = LoggerFactory.getLogger(FlowDemoCli.class);
    private static final FlowDemoPrinter PRINTER = new FlowDemoPrinter();

    private FlowDemoCli() {
    }

    public static boolean isDemoMode(String[] args) {
        FlowDemoArgs parsed = new FlowDemoArgs(args);
        if (parsed.helpRequested()) {
            return true;
        }
        String flow = parsed.flow();
        return !flow.isBlank() && !"server".equalsIgnoreCase(flow);
    }

    public static int run(String[] args) {
        FlowDemoArgs parsed = new FlowDemoArgs(args);
        if (parsed.helpRequested()) {
            printHelp();
            return 0;
        }

        String flow = parsed.flow().toLowerCase();
        if (flow.isBlank()) {
            System.err.println("缺少 --flow 参数，使用 --help 查看用法。");
            return 1;
        }

        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║     MyLunarCore 命令行流程演示       ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("模式: " + flow);

        try {
            switch (flow) {
                case "battle" -> runBattleDemo(parsed);
                case "scene" -> runSceneDemo(parsed);
                case "challenge" -> runChallengeDemo(parsed);
                case "gacha" -> runGachaDemo(parsed);
                case "all" -> runFullFlowDemo(parsed);
                default -> {
                    System.err.println("未知 flow: " + flow);
                    printHelp();
                    return 1;
                }
            }
        } catch (Exception e) {
            log.error("流程演示失败", e);
            System.err.println("演示失败: " + e.getMessage());
            return 1;
        }

        System.out.println();
        System.out.println("══ 演示完成 ══");
        return 0;
    }

    /** 完整玩家旅程：六步串联。 */
    private static void runFullFlowDemo(FlowDemoArgs args) {
        long playerUid = args.getLong("playerUid", 10_001L);
        int playerId = args.getInt("playerId", 77);
        int challengeId = args.getInt("challengeId", 1500);
        long battleId = args.getLong("battleId", 9001L);
        int drawCount = Math.max(1, args.getInt("drawCount", 10));
        long seed = args.getLong("seed", 20260705L);
        Random random = new Random(seed);

        PRINTER.resetSteps();
        PRINTER.section("【完整流程】登录 → 场景 → 挑战 → 战斗 → 结算 → 抽卡");

        // 1. 登录
        PRINTER.section("步骤 1/6 · 玩家登录");
        PRINTER.step("创建会话 playerUid=" + playerUid + ", playerId=" + playerId);
        PRINTER.detail("会话状态: 已绑定 Channel，等待协议交互");

        // 2. 场景
        PRINTER.section("步骤 2/6 · 进入场景");
        SceneContext scene = runSceneInternal(playerUid, PRINTER);

        // 3. 挑战
        PRINTER.section("步骤 3/6 · 开启挑战");
        ChallengeManager challengeManager = new ChallengeManager();
        long challengeUid = challengeManager.nextUid();
        ChallengeRuntime challenge = FlowDemoEngine.createChallenge(challengeUid, playerId, challengeId);
        challengeManager.put(challenge);
        PRINTER.step("分配 challengeUid=" + challengeUid + ", challengeId=" + challengeId);
        PRINTER.detail("groupId=" + challenge.getGroupId()
                + ", 映射 stageId=" + challenge.getStageId()
                + ", 波次数=" + challenge.getWaveCount()
                + ", 状态=进行中(1)");
        PRINTER.detail("触发方式: 与场景 NPC[" + scene.getNpcs().values().iterator().next().getNpcId() + "] 交互");

        // 4. 战斗
        PRINTER.section("步骤 4/6 · 进入战斗");
        BattleManager battleManager = new BattleManager();
        MazeSkillActionRepository skillRepo = FlowDemoEngine.demoSkillActionRepo();
        BattleContext battle = BattleContext.createNew(
                battleId,
                playerId,
                1,
                challenge.getStageId(),
                System.currentTimeMillis() / 1000,
                FlowDemoEngine.wavesForStage(challenge.getStageId()));
        battleManager.put(battle);
        PRINTER.step("创建战局 battleId=" + battleId + ", stageId=" + challenge.getStageId());
        FlowDemoEngine.BattleSimResult battleResult = FlowDemoEngine.simulateBattle(battle, skillRepo, PRINTER);
        if (battleResult.victory()) {
            battleManager.remove(battleId);
            PRINTER.detail("战局已从 BattleManager 移除");
        }

        // 5. 挑战结算
        PRINTER.section("步骤 5/6 · 挑战结算");
        if (battleResult.victory()) {
            int score = 2800 + battleResult.killCount() * 200;
            int stars = 0b111;
            int rounds = Math.max(1, battle.getTurn() - 1);
            challenge.markSettled(true, score, stars, rounds);
            PRINTER.step("挑战胜利结算");
            PRINTER.detail("score=" + challenge.getCurrentScore()
                    + ", starsMask=" + Integer.toBinaryString(challenge.getCurrentStarsMask())
                    + ", roundsUsed=" + challenge.getRoundsUsed()
                    + ", status=" + challenge.getStatus() + "(胜利)");
            challengeManager.remove(challengeUid);
        } else {
            challenge.markSettled(false, 0, 0, Math.max(1, battle.getTurn() - 1));
            PRINTER.step("挑战失败结算 status=" + challenge.getStatus());
            challengeManager.remove(challengeUid);
        }

        // 6. 抽卡
        PRINTER.section("步骤 6/6 · 胜利抽卡");
        List<GachaBannerConfig> banners = FlowDemoEngine.loadBanners();
        long now = System.currentTimeMillis() / 1000;
        GachaBannerConfig banner = FlowDemoEngine.pickActiveAvatarUpBanner(banners, now);
        PRINTER.step("选取当前开放卡池 bannerId=" + banner.getId()
                + ", type=" + banner.getGachaType());
        runGachaInternal(banner, drawCount, random, PRINTER);
    }

    private static void runBattleDemo(FlowDemoArgs args) {
        PRINTER.resetSteps();
        PRINTER.section("【战斗模块演示】");

        long battleId = args.getLong("battleId", 9001L);
        int playerId = args.getInt("playerId", 77);
        int stageId = args.getInt("stageId", 2500);

        PRINTER.step("初始化 battleId=" + battleId + ", playerId=" + playerId + ", stageId=" + stageId);
        BattleContext context = BattleContext.createNew(
                battleId, playerId, 1, stageId,
                System.currentTimeMillis() / 1000,
                FlowDemoEngine.wavesForStage(stageId));

        FlowDemoEngine.simulateBattle(context, FlowDemoEngine.demoSkillActionRepo(), PRINTER);
    }

    private static void runSceneDemo(FlowDemoArgs args) {
        PRINTER.resetSteps();
        PRINTER.section("【场景模块演示】");
        runSceneInternal(args.getLong("playerUid", 10_001L), PRINTER);
    }

    private static SceneContext runSceneInternal(long playerUid, FlowDemoPrinter printer) {
        printer.step("请求进入场景 planeId=1, floorId=2, entryId=3");
        SceneContext context = FlowDemoEngine.createDemoScene(playerUid);

        var enterRsp = context.buildEnterSceneRsp(0);
        printer.detail("EnterScene 响应 retcode=" + enterRsp.getRetcode()
                + ", 怪物=" + enterRsp.getEntityList().getMonstersCount()
                + ", NPC=" + enterRsp.getEntityList().getNpcsCount()
                + ", 道具=" + enterRsp.getEntityList().getPropsCount());

        SceneContext.ScenePos from = context.getPlayerPos();
        SceneContext.ScenePos to = new SceneContext.ScenePos(12.0f, 0.0f, -4.0f);
        printer.step("玩家移动 (" + from.getX() + "," + from.getY() + "," + from.getZ()
                + ") → (" + to.getX() + "," + to.getY() + "," + to.getZ() + ")");

        SceneContext.MonsterState monster = context.getMonster(1_000_001);
        if (monster != null) {
            printer.detail("接近怪物 entityId=" + monster.getEntityId()
                    + ", configId=" + monster.getMonsterId()
                    + ", HP=" + monster.getHp() + "/" + monster.getMaxHp());
        }

        printer.step("主循环 tick (delta=1000ms)");
        context.onTick(System.currentTimeMillis(), 1000L);
        printer.detail("场景 initialized=" + context.isInitialized());
        return context;
    }

    private static void runChallengeDemo(FlowDemoArgs args) {
        PRINTER.resetSteps();
        PRINTER.section("【挑战模块演示】");

        ChallengeManager manager = new ChallengeManager();
        int playerId = args.getInt("playerId", 77);
        int challengeId = args.getInt("challengeId", 1500);
        long challengeUid = manager.nextUid();

        PRINTER.step("创建挑战 challengeUid=" + challengeUid + ", challengeId=" + challengeId);
        ChallengeRuntime runtime = FlowDemoEngine.createChallenge(challengeUid, playerId, challengeId);
        manager.put(runtime);

        PRINTER.detail("初始: status=" + runtime.getStatus()
                + ", stageId=" + runtime.getStageId()
                + ", groupId=" + runtime.getGroupId()
                + ", waveCount=" + runtime.getWaveCount());

        for (int round = 1; round <= 3; round++) {
            PRINTER.step("推进阶段 " + round + " / 模拟战斗回合");
            PRINTER.detail("enemyWaveCount=" + runtime.getEnemyInfo().size());
        }

        runtime.markSettled(true, 3200, 0b111, 3);
        PRINTER.step("胜利结算");
        PRINTER.detail("score=" + runtime.getCurrentScore()
                + ", starsMask=" + Integer.toBinaryString(runtime.getCurrentStarsMask())
                + ", roundsUsed=" + runtime.getRoundsUsed()
                + ", status=" + runtime.getStatus());

        manager.remove(challengeUid);
        PRINTER.detail("挑战运行时已从 ChallengeManager 移除");
    }

    private static void runGachaDemo(FlowDemoArgs args) {
        PRINTER.resetSteps();
        PRINTER.section("【抽卡模块演示】");

        int bannerId = args.getInt("bannerId", 2001);
        int drawCount = Math.max(1, args.getInt("drawCount", 10));
        long seed = args.getLong("seed", -1L);
        Random random = seed >= 0 ? new Random(seed) : ThreadLocalRandom.current();

        List<GachaBannerConfig> banners = FlowDemoEngine.loadBanners();
        long now = System.currentTimeMillis() / 1000;
        GachaBannerConfig banner = FlowDemoEngine.resolveBanner(banners, bannerId, now);

        PRINTER.step("加载卡池 bannerId=" + banner.getId()
                + ", type=" + banner.getGachaType()
                + ", 5星UP=" + sizeOf(banner.getRateUpItems5())
                + ", 4星UP=" + sizeOf(banner.getRateUpItems4()));
        runGachaInternal(banner, drawCount, random, PRINTER);
    }

    private static void runGachaInternal(GachaBannerConfig banner,
                                         int drawCount,
                                         Random random,
                                         FlowDemoPrinter printer) {
        FlowDemoEngine.GachaSummary summary = FlowDemoEngine.simulateGacha(banner, drawCount, random);
        for (String line : summary.details()) {
            printer.detail(line);
        }
        printer.step("汇总 " + drawCount + " 抽: 5星=" + summary.count5()
                + ", 4星=" + summary.count4()
                + ", 3星=" + summary.count3());
    }

    private static int sizeOf(List<Integer> list) {
        return list == null ? 0 : list.size();
    }

    private static void printHelp() {
        System.out.println("""
                MyLunarCore 命令行流程演示

                用法:
                  java -jar MyLunarCore.jar --flow=<模块> [选项]

                模块 (--flow):
                  all        完整流程（推荐）：登录→场景→挑战→战斗→结算→抽卡
                  battle     战斗：按真实 action_type 结算技能、自动切波
                  scene      场景：进入场景、移动、tick
                  challenge  挑战：创建、推进、结算
                  gacha      抽卡：卡池选取、软保底模拟
                  server     启动完整游戏服务器（需 MySQL）

                常用选项:
                  --playerUid=10001     玩家 UID（默认 10001）
                  --playerId=77         玩家 ID（默认 77）
                  --battleId=9001       战斗 ID（默认 9001）
                  --stageId=2500        战斗关卡 ID（默认 2500）
                  --challengeId=1500    挑战关卡 ID（默认 1500）
                  --bannerId=2001       卡池 Banner ID（默认 2001）
                  --drawCount=10        抽卡次数（默认 10）
                  --seed=20260705       随机种子（可复现抽卡结果）

                示例:
                  java -jar MyLunarCore.jar --flow=all
                  java -jar MyLunarCore.jar --flow=battle --stageId=2500
                  java -jar MyLunarCore.jar --flow=gacha --bannerId=2001 --drawCount=10 --seed=1
                  java -jar MyLunarCore.jar --flow=server
                """);
    }
}
