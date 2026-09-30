package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.economy.MonthlyCardService;
import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.economy.TopUpBonusService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.player.PlayerAggregateService;
import cn.itcast.demo.mylunarcore.player.PlayerCurrencyHelper;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import cn.itcast.demo.mylunarcore.skin.SkinOwnershipService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * IAP 发货：按 rewards / packKind 发放，写 grant_log 保证 order_id 幂等。
 * 皮肤道具已拥有时跳过重复发放，礼包内其它奖励仍正常发放。
 */
@Service
public class IapGrantService {

    public record GrantResult(boolean success, boolean alreadyGranted, Map<Integer, Integer> balance) {
    }

    private final WalletApplicationService walletApplicationService;
    private final ItemRepository itemRepository;
    private final PlayerAggregateService playerAggregateService;
    private final IapEntitlementService entitlementService;
    private final PurchaseLimitService purchaseLimitService;
    private final SkinConfigRepository skinConfigRepository;
    private final SkinOwnershipService skinOwnershipService;
    private final ObjectProvider<BusinessMetrics> businessMetricsProvider;
    private final ObjectProvider<TopUpBonusService> topUpBonusProvider;
    private final ObjectProvider<MonthlyCardService> monthlyCardProvider;
    private final ConcurrentHashMap<String, Instant> grantLog = new ConcurrentHashMap<>();

    public IapGrantService(WalletApplicationService walletApplicationService,
                           ItemRepository itemRepository,
                           PlayerAggregateService playerAggregateService,
                           IapEntitlementService entitlementService,
                           PurchaseLimitService purchaseLimitService,
                           SkinConfigRepository skinConfigRepository,
                           SkinOwnershipService skinOwnershipService,
                           ObjectProvider<BusinessMetrics> businessMetricsProvider,
                           ObjectProvider<TopUpBonusService> topUpBonusProvider,
                           ObjectProvider<MonthlyCardService> monthlyCardProvider) {
        this.walletApplicationService = walletApplicationService;
        this.itemRepository = itemRepository;
        this.playerAggregateService = playerAggregateService;
        this.entitlementService = entitlementService;
        this.purchaseLimitService = purchaseLimitService;
        this.skinConfigRepository = skinConfigRepository;
        this.skinOwnershipService = skinOwnershipService;
        this.businessMetricsProvider = businessMetricsProvider;
        this.topUpBonusProvider = topUpBonusProvider;
        this.monthlyCardProvider = monthlyCardProvider;
    }

    public boolean alreadyGranted(String orderId) {
        return grantLog.containsKey(orderId);
    }

    public GrantResult grant(IapOrder order, ShopConfigRepository.ShopItemConfig item, Instant now) {
        if (order == null || item == null) {
            return new GrantResult(false, false, Map.of());
        }
        Instant previous = grantLog.putIfAbsent(order.orderId(), now);
        if (previous != null) {
            Map<Integer, Integer> balance = walletApplicationService.getBalance(order.playerId());
            return new GrantResult(true, true, balance);
        }

        String reason = "iap:" + order.orderId();
        List<ShopConfigRepository.RewardSpec> toGrant = new ArrayList<>(item.rewards());
        if ("DIRECT_TOPUP".equalsIgnoreCase(item.productCategory())
                && !entitlementService.isFirstTopupDone(order.playerId())
                && item.firstPurchaseBonus() != null) {
            toGrant.addAll(item.firstPurchaseBonus());
        }

        Map<Integer, Integer> balance = grantRewards(order.playerId(), toGrant, reason);
        if (balance == null) {
            grantLog.remove(order.orderId());
            return new GrantResult(false, false, Map.of());
        }

        // 首充双倍 / 年度重置：配置未带 firstPurchaseBonus 时由服务叠加比例赠送
        TopUpBonusService topUpBonus = topUpBonusProvider.getIfAvailable();
        if (topUpBonus != null && "DIRECT_TOPUP".equalsIgnoreCase(item.productCategory())) {
            boolean configHadFirstBonus = item.firstPurchaseBonus() != null && !item.firstPurchaseBonus().isEmpty();
            int baseCurrency = item.rewards() == null ? 0 : item.rewards().stream()
                    .filter(r -> r != null && r.isCurrency())
                    .mapToInt(ShopConfigRepository.RewardSpec::amount)
                    .sum();
            TopUpBonusService.BonusDecision decision = topUpBonus.decideAndRecord(
                    order.playerId(), baseCurrency, Math.max(0L, order.amountCents()));
            if (!configHadFirstBonus && decision.bonusAmount() > 0) {
                int currencyId = item.rewards() == null ? 1 : item.rewards().stream()
                        .filter(r -> r != null && r.isCurrency())
                        .map(ShopConfigRepository.RewardSpec::currencyId)
                        .findFirst()
                        .orElse(1);
                walletApplicationService.add(order.playerId(), currencyId, decision.bonusAmount(),
                        "topup_bonus:" + (decision.firstTopup() ? "first" : "year"));
                balance = walletApplicationService.getBalance(order.playerId());
            }
            entitlementService.markFirstTopupDone(order.playerId());
        }

        applyPackSideEffects(order.playerId(), item, toGrant, now);
        if ("DIRECT_TOPUP".equalsIgnoreCase(item.productCategory())
                && !entitlementService.isFirstTopupDone(order.playerId())
                && item.firstPurchaseBonus() != null && !item.firstPurchaseBonus().isEmpty()) {
            entitlementService.markFirstTopupDone(order.playerId());
        }
        purchaseLimitService.consume(order.playerId(), item, now);
        order.markGranted(now);
        BusinessMetrics metrics = businessMetricsProvider.getIfAvailable();
        if (metrics != null) {
            metrics.recordPayingUser(order.playerId());
        }
        return new GrantResult(true, false, balance);
    }

    private void applyPackSideEffects(int playerId,
                                      ShopConfigRepository.ShopItemConfig item,
                                      List<ShopConfigRepository.RewardSpec> rewards,
                                      Instant now) {
        String packKind = item.packKind();
        if (packKind == null || packKind.isBlank()) {
            return;
        }
        if ("MONTHLY_CARD".equalsIgnoreCase(packKind) || "MINI_MONTHLY".equalsIgnoreCase(packKind)) {
            for (ShopConfigRepository.RewardSpec reward : rewards) {
                if (reward.isDailyClaim()) {
                    entitlementService.grantMonthlyCard(
                            playerId, reward.currencyId(), reward.amount(), reward.days(), now);
                    MonthlyCardService monthly = monthlyCardProvider.getIfAvailable();
                    if (monthly != null) {
                        String kind = "MINI_MONTHLY".equalsIgnoreCase(packKind)
                                ? MonthlyCardService.KIND_MINI : MonthlyCardService.KIND_MONTHLY;
                        monthly.activate(playerId, kind, reward.days(),
                                reward.currencyId(), reward.amount(), 60);
                    }
                    return;
                }
            }
        }
        if ("STARTER".equalsIgnoreCase(packKind) || "GROWTH_FUND".equalsIgnoreCase(packKind)
                || "BATTLE_PASS".equalsIgnoreCase(packKind)) {
            entitlementService.grantLifetimeMarker(playerId, packKind.toUpperCase(), now);
        }
    }

    private Map<Integer, Integer> grantRewards(int playerId,
                                               List<ShopConfigRepository.RewardSpec> rewards,
                                               String reason) {
        List<ShopConfigRepository.RewardSpec> immediate = rewards.stream()
                .filter(r -> r != null && (r.isCurrency() || r.isItem()))
                .toList();
        if (immediate.isEmpty()) {
            return walletApplicationService.getBalance(playerId);
        }
        Map<Integer, Integer> online = playerAggregateService.commit(playerId,
                (Function<PlayerData, Map<Integer, Integer>>) data ->
                        grantOnline(data, playerId, immediate));
        if (online != null) {
            return online;
        }
        return grantOffline(playerId, immediate, reason);
    }

    private Map<Integer, Integer> grantOnline(PlayerData data, int playerId,
                                              List<ShopConfigRepository.RewardSpec> rewards) {
        PlayerEntity player = data.getPlayer();
        if (player == null) {
            return null;
        }
        Map<Integer, Integer> balance = new HashMap<>(PlayerCurrencyHelper.parseCurrency(player.getCurrencyJson()));
        for (ShopConfigRepository.RewardSpec reward : rewards) {
            if (reward.isCurrency()) {
                int current = balance.getOrDefault(reward.currencyId(), 0);
                long next = (long) current + reward.amount();
                if (next > Integer.MAX_VALUE) {
                    return null;
                }
                balance.put(reward.currencyId(), (int) next);
            }
            if (reward.isItem()) {
                grantItemOnline(data, playerId, reward.itemId(), reward.count());
            }
        }
        player.setCurrencyJson(PlayerCurrencyHelper.toCurrencyJson(balance));
        return balance;
    }

    private Map<Integer, Integer> grantOffline(int playerId,
                                               List<ShopConfigRepository.RewardSpec> rewards,
                                               String reason) {
        Map<Integer, Integer> balance = walletApplicationService.getBalance(playerId);
        for (ShopConfigRepository.RewardSpec reward : rewards) {
            if (reward.isCurrency()) {
                WalletApplicationService.WalletChangeResult result =
                        walletApplicationService.add(playerId, reward.currencyId(), reward.amount(), reason);
                if (!result.success()) {
                    return null;
                }
                balance = result.balance();
            }
            if (reward.isItem()) {
                grantItemOffline(playerId, reward.itemId(), reward.count());
            }
        }
        return balance;
    }

    private void grantItemOnline(PlayerData data, int playerId, int itemId, int count) {
        SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(itemId);
        if (skin != null) {
            if (skinOwnershipService.owns(playerId, skin.skinId())) {
                return; // 已拥有皮肤：跳过道具，避免重复堆叠
            }
            skinOwnershipService.grantSkinItemOnline(data, playerId, skin);
            return;
        }
        long id = itemRepository.addSimpleItem(playerId, itemId, 3, count);
        appendItem(data, playerId, id, itemId, 3, count);
    }

    private void grantItemOffline(int playerId, int itemId, int count) {
        SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(itemId);
        if (skin != null) {
            if (skinOwnershipService.owns(playerId, skin.skinId())) {
                return;
            }
            skinOwnershipService.markOwned(playerId, itemId, "iap");
            return;
        }
        itemRepository.addSimpleItem(playerId, itemId, 3, count);
    }

    private static void appendItem(PlayerData data, int playerId, long id, int itemId, int type, long count) {
        if (id <= 0) {
            return;
        }
        List<GameItemEntity> items = data.getItems();
        if (items == null) {
            items = new ArrayList<>();
            data.setItems(items);
        } else if (!(items instanceof ArrayList)) {
            items = new ArrayList<>(items);
            data.setItems(items);
        }
        GameItemEntity entity = new GameItemEntity();
        entity.setId(id);
        entity.setPlayerId(playerId);
        entity.setItemId(itemId);
        entity.setType(type);
        entity.setCount(Math.max(1L, count));
        entity.setLevel(1);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        items.add(entity);
    }
}
