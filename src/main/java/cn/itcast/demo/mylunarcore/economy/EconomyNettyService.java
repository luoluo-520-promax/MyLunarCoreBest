package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.economy.iap.IapEntitlementService;
import cn.itcast.demo.mylunarcore.economy.iap.IapOrderService;
import cn.itcast.demo.mylunarcore.economy.iap.PurchaseLimitService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.EconomySystemProto;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import cn.itcast.demo.mylunarcore.skin.SkinOwnershipService;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 经济系统 Netty 协议门面：商店列表、游戏币购买、创建/确认 IAP、日领权益，
 * 以及 {@link CmdIds#CURRENCY_CHANGE_SC_NOTIFY} 货币变动推送。
 * <p>
 * 皮肤类商品会在 ShopItem 上附加 skinId、avatarId、owned，供客户端展示已拥有态。
 * 通用 retcode：1=未登录/无效玩家；商店相关 2=配置或购买失败；IAP 日领 2=无可领 3=入账失败。
 */
@Service
public class EconomyNettyService {

    // 游戏币购买编排（扣款+发货）
    private final ShopApplicationService shopApplicationService;
    // 商店与货架配置
    private final ShopConfigRepository shopConfigRepository;
    // Channel → playerId
    private final PlayerContextResolver contextResolver;
    // IAP 下单与收据确认
    private final IapOrderService iapOrderService;
    // 日限/总限剩余次数
    private final PurchaseLimitService purchaseLimitService;
    // 月卡类日领权益
    private final IapEntitlementService entitlementService;
    // 钱包加款（日领入账）
    private final WalletApplicationService walletApplicationService;
    // 道具/奖励 → 皮肤配置
    private final SkinConfigRepository skinConfigRepository;
    // 查询玩家是否已拥有皮肤
    private final SkinOwnershipService skinOwnershipService;

    public EconomyNettyService(ShopApplicationService shopApplicationService,
                               ShopConfigRepository shopConfigRepository,
                               PlayerContextResolver contextResolver,
                               IapOrderService iapOrderService,
                               PurchaseLimitService purchaseLimitService,
                               IapEntitlementService entitlementService,
                               WalletApplicationService walletApplicationService,
                               SkinConfigRepository skinConfigRepository,
                               SkinOwnershipService skinOwnershipService) {
        this.shopApplicationService = shopApplicationService;
        this.shopConfigRepository = shopConfigRepository;
        this.contextResolver = contextResolver;
        this.iapOrderService = iapOrderService;
        this.purchaseLimitService = purchaseLimitService;
        this.entitlementService = entitlementService;
        this.walletApplicationService = walletApplicationService;
        this.skinConfigRepository = skinConfigRepository;
        this.skinOwnershipService = skinOwnershipService;
    }

    /**
     * 拉取商店货架：校验玩家与 shopId → 遍历启用商品，填剩余限购与皮肤元数据。
     * 不在销售窗口内的商品 remainLimit 强制为 0（仍返回条目便于灰显）。
     */
    public EconomySystemProto.GetShopListScRsp handleGetShopList(
            EconomySystemProto.GetShopListCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return EconomySystemProto.GetShopListScRsp.newBuilder().setRetcode(1).build();
        }
        ShopConfigRepository.ShopConfig shop = shopConfigRepository.findShop(req.getShopId());
        if (shop == null) {
            return EconomySystemProto.GetShopListScRsp.newBuilder().setRetcode(2).build(); // 商店不存在
        }
        Instant now = Instant.now(); // 统一限购与销售窗判断时刻
        EconomySystemProto.GetShopListScRsp.Builder builder = EconomySystemProto.GetShopListScRsp.newBuilder()
                .setRetcode(0)
                .setShopId(shop.shopId());
        for (ShopConfigRepository.ShopItemConfig item : shop.items()) {
            if (item == null || !item.isEnabled()) {
                continue; // 跳过关闭货架位
            }
            int remain = purchaseLimitService.remaining(playerId, item, now);
            if (!item.inSaleWindow(now)) {
                remain = 0; // 未开售/已结束：可展示但不可买
            }
            EconomySystemProto.ShopItem.Builder itemBuilder = EconomySystemProto.ShopItem.newBuilder()
                    .setShopItemId(item.shopItemId())
                    .setItemId(item.itemId())
                    .setItemCount(item.itemCount())
                    // IAP 商品不展示游戏币 currencyId，置 0
                    .setCurrencyId(item.isIap() ? 0 : item.currencyId())
                    .setPrice(item.price())
                    .setDailyLimit(item.dailyLimit())
                    .setProductCategory(nullToEmpty(item.productCategory()))
                    .setPayType(nullToEmpty(item.payType()))
                    .setSkuId(nullToEmpty(item.skuId()))
                    .setPriceCents(item.priceCents())
                    .setOriginalPriceCents(item.originalPriceCents())
                    .setDiscountRate(item.discountRate())
                    .setSaleEndAtMs(item.saleEndAtMs())
                    .setRemainLimit(remain)
                    .setDisplayName(nullToEmpty(item.displayName()));
            attachSkinMeta(itemBuilder, playerId, item); // 皮肤类追加 owned 等
            builder.addItems(itemBuilder.build());
        }
        return builder.build();
    }

    /**
     * 若商品关联皮肤配置，写入 skinId/avatarId/owned；非皮肤商品直接返回。
     */
    private void attachSkinMeta(EconomySystemProto.ShopItem.Builder itemBuilder,
                                int playerId,
                                ShopConfigRepository.ShopItemConfig item) {
        SkinConfigRepository.SkinConfig skin = resolveSkin(item);
        if (skin == null) {
            return;
        }
        itemBuilder.setSkinId(skin.skinId())
                .setAvatarId(skin.avatarId())
                .setOwned(skinOwnershipService.owns(playerId, skin.skinId()));
    }

    /**
     * 解析皮肤：先按商品 itemId 查；再扫 rewards 里的道具奖励 itemId。
     */
    private SkinConfigRepository.SkinConfig resolveSkin(ShopConfigRepository.ShopItemConfig item) {
        if (item.itemId() > 0) {
            SkinConfigRepository.SkinConfig byItem = skinConfigRepository.findByItemId(item.itemId());
            if (byItem != null) {
                return byItem;
            }
        }
        if (item.rewards() != null) {
            for (ShopConfigRepository.RewardSpec reward : item.rewards()) {
                if (reward != null && reward.isItem()) {
                    SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(reward.itemId());
                    if (skin != null) {
                        return skin;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 游戏币购买：调用 ShopApplicationService.buy，成功后推送货币变更并回剩余货币列表。
     */
    public EconomySystemProto.BuyShopItemScRsp handleBuyShopItem(
            EconomySystemProto.BuyShopItemCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return EconomySystemProto.BuyShopItemScRsp.newBuilder().setRetcode(1).build();
        }
        ShopApplicationService.BuyResult result = shopApplicationService.buy(
                playerId, req.getShopId(), req.getShopItemId(), req.getCount());
        if (!result.success()) {
            return EconomySystemProto.BuyShopItemScRsp.newBuilder().setRetcode(2).build();
        }
        // 通知客户端刷新钱包，reason=shop
        notifyCurrencyAndData(channel, playerId, result.wallet().balance(), "shop");
        return EconomySystemProto.BuyShopItemScRsp.newBuilder()
                .setRetcode(0)
                .setShopItemId(req.getShopItemId())
                .setBoughtCount(result.boughtCount())
                .addAllRemainingCurrency(toProtoCurrency(result.wallet().balance()))
                .build();
    }

    /**
     * 创建 IAP 订单：成功返回 orderId/skuId/priceCents；失败透传 IapOrderService.retcode。
     */
    public EconomySystemProto.CreateIapOrderScRsp handleCreateIapOrder(
            EconomySystemProto.CreateIapOrderCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return EconomySystemProto.CreateIapOrderScRsp.newBuilder().setRetcode(1).build();
        }
        IapOrderService.CreateResult result = iapOrderService.createOrder(
                playerId, req.getShopId(), req.getShopItemId());
        if (!result.success()) {
            return EconomySystemProto.CreateIapOrderScRsp.newBuilder()
                    .setRetcode(result.retcode())
                    .build();
        }
        return EconomySystemProto.CreateIapOrderScRsp.newBuilder()
                .setRetcode(0)
                .setOrderId(result.order().orderId())
                .setSkuId(result.order().skuId())
                .setPriceCents(result.order().amountCents())
                .build();
    }

    /**
     * 确认 IAP：校验渠道收据后发货，推送货币变更；失败带回 orderId 与渠道 retcode。
     */
    public EconomySystemProto.ConfirmIapOrderScRsp handleConfirmIapOrder(
            EconomySystemProto.ConfirmIapOrderCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return EconomySystemProto.ConfirmIapOrderScRsp.newBuilder().setRetcode(1).build();
        }
        IapOrderService.ConfirmResult result = iapOrderService.confirmOrder(
                playerId, req.getOrderId(), req.getChannel(), req.getChannelReceipt());
        if (!result.success()) {
            return EconomySystemProto.ConfirmIapOrderScRsp.newBuilder()
                    .setRetcode(result.retcode())
                    .setOrderId(req.getOrderId())
                    .build();
        }
        notifyCurrencyAndData(channel, playerId, result.balance(), "iap:" + result.order().orderId());
        return EconomySystemProto.ConfirmIapOrderScRsp.newBuilder()
                .setRetcode(0)
                .setOrderId(result.order().orderId())
                .addAllRemainingCurrency(toProtoCurrency(result.balance()))
                .build();
    }

    /**
     * 领取 IAP 日奖励：claimDaily 返回 [currencyId, amount]；入账失败 retcode=3；
     * 成功带回 remainingDays 与最新货币列表。
     */
    public EconomySystemProto.ClaimIapDailyScRsp handleClaimIapDaily(
            EconomySystemProto.ClaimIapDailyCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return EconomySystemProto.ClaimIapDailyScRsp.newBuilder().setRetcode(1).build();
        }
        Instant now = Instant.now();
        int[] claimed = entitlementService.claimDaily(playerId, now);
        if (claimed == null) {
            return EconomySystemProto.ClaimIapDailyScRsp.newBuilder().setRetcode(2).build(); // 今日已领或无权益
        }
        WalletApplicationService.WalletChangeResult wallet = walletApplicationService.add(
                playerId, claimed[0], claimed[1], "iap:daily_claim");
        if (!wallet.success()) {
            return EconomySystemProto.ClaimIapDailyScRsp.newBuilder().setRetcode(3).build();
        }
        notifyCurrencyAndData(channel, playerId, wallet.balance(), "iap:daily_claim");
        return EconomySystemProto.ClaimIapDailyScRsp.newBuilder()
                .setRetcode(0)
                .setCurrencyId(claimed[0])
                .setAmount(claimed[1])
                .setRemainingDays(entitlementService.remainingDays(playerId, now))
                .addAllRemainingCurrency(toProtoCurrency(wallet.balance()))
                .build();
    }

    /**
     * 向客户端推送货币变更通知：balance 转 CurrencyEntry 列表，reason 标明来源（shop/iap:...）。
     */
    public void notifyCurrencyAndData(Channel channel, int playerId, Map<Integer, Integer> balance, String reason) {
        EconomySystemProto.CurrencyChangeScNotify notify = EconomySystemProto.CurrencyChangeScNotify.newBuilder()
                .addAllCurrency(toProtoCurrency(balance))
                .setReason(reason)
                .build();
        channel.writeAndFlush(new GamePacket(CmdIds.CURRENCY_CHANGE_SC_NOTIFY, notify.toByteArray()));
    }

    /** Map&lt;currencyId, amount&gt; → Protobuf CurrencyEntry 可迭代集合。 */
    private static Iterable<EconomySystemProto.CurrencyEntry> toProtoCurrency(Map<Integer, Integer> balance) {
        return balance.entrySet().stream()
                .map(e -> EconomySystemProto.CurrencyEntry.newBuilder()
                        .setCurrencyId(e.getKey())
                        .setAmount(e.getValue())
                        .build())
                .toList();
    }

    /** proto string 字段避免 null。 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
