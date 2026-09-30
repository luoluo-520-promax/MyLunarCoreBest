package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.achievement.AchievementService;
import cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;
import cn.itcast.demo.mylunarcore.protocol.GachaSystemProto;
import cn.itcast.demo.mylunarcore.repo.GachaHistoryRepository;
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.security.BizReplayGuardService;
import cn.itcast.demo.mylunarcore.tx.DistributedLockService;
import cn.itcast.demo.mylunarcore.tx.LocalTxLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 抽卡写库编排：本地事务日志 + 扣费 + 发奖 + pity + 历史审计同一事务。
 */
@Service
public class GachaApplicationService {

    public record DrawItemGrant(int itemId, boolean isNew) {}

    public record DrawPersistResult(boolean success, int retcode,
                                    GachaSystemProto.CeilingInfo updatedCeiling) {
        public static DrawPersistResult fail(int retcode) {
            return new DrawPersistResult(false, retcode, GachaSystemProto.CeilingInfo.getDefaultInstance());
        }
    }

    public record ExchangePersistResult(GachaSystemProto.GachaItem reward,
                                        GachaSystemProto.CeilingInfo updatedCeiling) {}

    public static final int RET_INSUFFICIENT = 6;
    public static final int RET_REPLAY = 7;
    public static final int RET_FROZEN = 9;

    private final GachaRepository gachaRepository;
    private final ItemRepository itemRepository;
    private final WalletApplicationService walletApplicationService;
    private final GachaHistoryRepository gachaHistoryRepository;
    private final LunarCoreProperties properties;
    private final LocalTxLogService localTxLogService;
    private final DistributedLockService distributedLockService;
    private final BusinessMetrics businessMetrics;
    private final BizReplayGuardService replayGuard;
    private final AchievementService achievementService;
    private final AnalyticsEventPublisher analytics;
    private final GachaRebateService gachaRebateService;
    private final cn.itcast.demo.mylunarcore.economy.iap.NegativeBalanceFreezeService freezeService;

    public GachaApplicationService(GachaRepository gachaRepository,
                                   ItemRepository itemRepository,
                                   WalletApplicationService walletApplicationService,
                                   GachaHistoryRepository gachaHistoryRepository,
                                   LunarCoreProperties properties,
                                   ObjectProvider<LocalTxLogService> localTxLogServiceProvider,
                                   ObjectProvider<DistributedLockService> distributedLockServiceProvider,
                                   ObjectProvider<BusinessMetrics> businessMetricsProvider,
                                   ObjectProvider<BizReplayGuardService> replayGuardProvider,
                                   ObjectProvider<AchievementService> achievementServiceProvider,
                                   ObjectProvider<AnalyticsEventPublisher> analyticsProvider,
                                   ObjectProvider<GachaRebateService> gachaRebateProvider,
                                   ObjectProvider<cn.itcast.demo.mylunarcore.economy.iap.NegativeBalanceFreezeService> freezeProvider) {
        this.gachaRepository = gachaRepository;
        this.itemRepository = itemRepository;
        this.walletApplicationService = walletApplicationService;
        this.gachaHistoryRepository = gachaHistoryRepository;
        this.properties = properties;
        this.localTxLogService = localTxLogServiceProvider.getIfAvailable();
        this.distributedLockService = distributedLockServiceProvider.getIfAvailable();
        this.businessMetrics = businessMetricsProvider.getIfAvailable();
        this.replayGuard = replayGuardProvider.getIfAvailable();
        this.achievementService = achievementServiceProvider.getIfAvailable();
        this.analytics = analyticsProvider.getIfAvailable();
        this.gachaRebateService = gachaRebateProvider.getIfAvailable();
        this.freezeService = freezeProvider.getIfAvailable();
    }

    @Transactional
    public DrawPersistResult persistDraw(int playerId,
                                         int bannerType,
                                         int times,
                                         List<DrawItemGrant> grants,
                                         int pity5,
                                         int pity4,
                                         int failedUpCount) {
        return persistDraw(playerId, bannerType, times, grants, pity5, pity4, failedUpCount, null);
    }

    @Transactional
    public DrawPersistResult persistDraw(int playerId,
                                         int bannerType,
                                         int times,
                                         List<DrawItemGrant> grants,
                                         int pity5,
                                         int pity4,
                                         int failedUpCount,
                                         String clientNonce) {
        if (distributedLockService != null) {
            return distributedLockService.withPlayerGachaLock(playerId,
                    () -> doPersistDraw(playerId, bannerType, times, grants, pity5, pity4, failedUpCount, clientNonce));
        }
        return doPersistDraw(playerId, bannerType, times, grants, pity5, pity4, failedUpCount, clientNonce);
    }

    private DrawPersistResult doPersistDraw(int playerId,
                                            int bannerType,
                                            int times,
                                            List<DrawItemGrant> grants,
                                            int pity5,
                                            int pity4,
                                            int failedUpCount,
                                            String clientNonce) {
        if (replayGuard != null && clientNonce != null && !clientNonce.isBlank()
                && !replayGuard.tryAcquire("gacha", playerId, clientNonce)) {
            if (businessMetrics != null) {
                businessMetrics.recordGachaError();
            }
            return DrawPersistResult.fail(RET_REPLAY);
        }
        if (freezeService != null && !freezeService.assertCanSpend(playerId)) {
            if (businessMetrics != null) {
                businessMetrics.recordGachaError();
            }
            return DrawPersistResult.fail(RET_FROZEN);
        }

        LunarCoreProperties.GachaEconomyProperties economy = properties.getGachaEconomy();
        int costCurrencyId = economy.getCostCurrencyId();
        int costPerDraw = Math.max(0, economy.getCostPerDraw());
        int totalCost = costPerDraw * Math.max(0, times);
        String payload = "{\"bannerType\":" + bannerType + ",\"times\":" + times
                + ",\"costCurrencyId\":" + costCurrencyId + ",\"totalCost\":" + totalCost + "}";
        String txId = localTxLogService != null
                ? localTxLogService.begin("gacha", playerId, payload)
                : UUID.randomUUID().toString();

        if (economy.isCostEnabled() && totalCost > 0) {
            WalletApplicationService.WalletChangeResult paid = walletApplicationService.deduct(
                    playerId, costCurrencyId, totalCost, "gacha:" + bannerType + ":" + txId);
            if (!paid.success()) {
                if (localTxLogService != null) {
                    localTxLogService.markFailed(txId);
                }
                if (businessMetrics != null) {
                    businessMetrics.recordGachaError();
                }
                return DrawPersistResult.fail(RET_INSUFFICIENT);
            }
        }

        try {
            for (DrawItemGrant grant : grants) {
                itemRepository.addSimpleItem(playerId, grant.itemId(), 3, 1);
            }
            gachaRepository.updateBannerPity(playerId, bannerType, pity5, pity4, failedUpCount);

            List<GachaHistoryRepository.ItemGrant> hist = new ArrayList<>(grants.size());
            for (DrawItemGrant grant : grants) {
                hist.add(new GachaHistoryRepository.ItemGrant(grant.itemId(), grant.isNew()));
            }
            gachaHistoryRepository.insertBatch(playerId, bannerType, hist, costCurrencyId, costPerDraw, txId);

            GachaSystemProto.CeilingInfo updatedCeiling = GachaSystemProto.CeilingInfo.getDefaultInstance();
            if (bannerType == GachaBannerType.NORMAL) {
                gachaRepository.incrementCeilingNum(playerId, times);
                PlayerGachaInfoEntity ceiling = gachaRepository.loadOrCreateGachaInfo(playerId);
                updatedCeiling = GachaSystemProto.CeilingInfo.newBuilder()
                        .setCeilingNum(ceiling == null ? 0 : Math.max(0, ceiling.getCeilingNum()))
                        .setCeilingClaimed(ceiling != null && ceiling.isCeilingClaimed())
                        .build();
            }
            if (localTxLogService != null) {
                localTxLogService.markCommitted(txId);
            }
            if (businessMetrics != null) {
                businessMetrics.recordGachaSuccess();
                businessMetrics.recordGachaUniquePlayer(playerId);
            }
            if (achievementService != null) {
                achievementService.addProgress(playerId, "gacha_10", times);
                achievementService.addProgress(playerId, "gacha_100", times);
            }
            if (analytics != null) {
                analytics.gacha(playerId, bannerType, times);
            }
            if (gachaRebateService != null) {
                gachaRebateService.grantForDraws(playerId, times);
            }
            return new DrawPersistResult(true, 0, updatedCeiling);
        } catch (RuntimeException ex) {
            // 扣费已成功但发奖失败：标记 FAILED，由 TxCompensationJob 退款补偿
            if (localTxLogService != null) {
                localTxLogService.markFailed(txId);
            }
            if (businessMetrics != null) {
                businessMetrics.recordGachaError();
            }
            throw ex;
        }
    }

    @Transactional
    public ExchangePersistResult persistCeilingExchange(int playerId, int targetAvatarId) {
        boolean isNew = !itemRepository.existsActiveItemByItemId(playerId, targetAvatarId);
        itemRepository.addSimpleItem(playerId, targetAvatarId, 3, 1);
        gachaRepository.setCeilingClaimed(playerId, true);

        PlayerGachaInfoEntity after = gachaRepository.loadOrCreateGachaInfo(playerId);
        GachaSystemProto.CeilingInfo updatedCeiling = GachaSystemProto.CeilingInfo.newBuilder()
                .setCeilingNum(after == null ? 300 : Math.max(0, after.getCeilingNum()))
                .setCeilingClaimed(after != null && after.isCeilingClaimed())
                .build();
        GachaSystemProto.GachaItem reward = GachaSystemProto.GachaItem.newBuilder()
                .setItemId(targetAvatarId)
                .setCount(1)
                .setIsNew(isNew)
                .build();
        return new ExchangePersistResult(reward, updatedCeiling);
    }
}
