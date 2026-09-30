package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.security.BizReplayGuardService;
import cn.itcast.demo.mylunarcore.security.RedisBizReplayGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * IAP 下单 / 验单 / 发货编排；channelTx 防重放（DB + Redis TTL）与分布式锁防并发发货。
 */
@Service
public class IapOrderService {

    public record CreateResult(boolean success, int retcode, IapOrder order, String message) {
        public static CreateResult ok(IapOrder order) {
            return new CreateResult(true, 0, order, "ok");
        }

        public static CreateResult fail(int retcode, String message) {
            return new CreateResult(false, retcode, null, message);
        }
    }

    public record ConfirmResult(boolean success, int retcode, IapOrder order,
                                Map<Integer, Integer> balance, String message) {
        public static ConfirmResult ok(IapOrder order, Map<Integer, Integer> balance) {
            return new ConfirmResult(true, 0, order, balance, "ok");
        }

        public static ConfirmResult fail(int retcode, String message) {
            return new ConfirmResult(false, retcode, null, Map.of(), message);
        }
    }

    private final IapProductCatalog catalog;
    private final PurchaseLimitService purchaseLimitService;
    private final IapVerifyGateway verifyGateway;
    private final IapGrantService grantService;
    private final WalletApplicationService walletApplicationService;
    private final BizReplayGuardService replayGuard;
    private final RedisBizReplayGuard redisReplayGuard;
    private final ConcurrentHashMap<String, IapOrder> ordersById = new ConcurrentHashMap<>();

    public IapOrderService(IapProductCatalog catalog,
                           PurchaseLimitService purchaseLimitService,
                           IapVerifyGateway verifyGateway,
                           IapGrantService grantService,
                           WalletApplicationService walletApplicationService,
                           ObjectProvider<BizReplayGuardService> replayGuardProvider,
                           ObjectProvider<RedisBizReplayGuard> redisReplayGuardProvider) {
        this.catalog = catalog;
        this.purchaseLimitService = purchaseLimitService;
        this.verifyGateway = verifyGateway;
        this.grantService = grantService;
        this.walletApplicationService = walletApplicationService;
        this.replayGuard = replayGuardProvider.getIfAvailable();
        this.redisReplayGuard = redisReplayGuardProvider.getIfAvailable();
    }

    public CreateResult createOrder(int playerId, int shopId, int shopItemId) {
        Instant now = Instant.now();
        ShopConfigRepository.ShopItemConfig item = catalog.find(shopId, shopItemId);
        if (!catalog.canCreateOrder(item, now)) {
            return CreateResult.fail(2, "item_unavailable");
        }
        if (!purchaseLimitService.canPurchase(playerId, item, now)) {
            return CreateResult.fail(3, "purchase_limit");
        }
        String orderId = "iap_" + UUID.randomUUID().toString().replace("-", "");
        IapOrder order = new IapOrder(
                orderId, playerId, shopId, shopItemId,
                item.skuId(), item.priceCents(), now);
        order.markPaying();
        ordersById.put(orderId, order);
        return CreateResult.ok(order);
    }

    public ConfirmResult confirmOrder(int playerId, String orderId, String channel, String receipt) {
        if (orderId == null || orderId.isBlank()) {
            return ConfirmResult.fail(2, "missing_order");
        }
        IapOrder order = ordersById.get(orderId);
        if (order == null) {
            return ConfirmResult.fail(2, "order_not_found");
        }
        if (order.playerId() != playerId) {
            return ConfirmResult.fail(3, "player_mismatch");
        }
        if (order.status() == IapOrderStatus.GRANTED || grantService.alreadyGranted(orderId)) {
            return ConfirmResult.ok(order, walletApplicationService.getBalance(playerId));
        }

        Instant now = Instant.now();
        ShopConfigRepository.ShopItemConfig item = catalog.find(order.shopId(), order.shopItemId());
        if (!catalog.canCreateOrder(item, now) && order.status() != IapOrderStatus.PAID
                && order.status() != IapOrderStatus.GRANT_RETRY) {
            if (order.status() == IapOrderStatus.CREATED || order.status() == IapOrderStatus.PAYING) {
                return ConfirmResult.fail(4, "item_unavailable");
            }
        }

        boolean awaitingGrant = order.status() == IapOrderStatus.PAID
                || order.status() == IapOrderStatus.GRANT_RETRY;

        Supplier<ConfirmResult> grantPath = () -> {
            IapGrantService.GrantResult grant = grantService.grant(order, item, now);
            if (!grant.success()) {
                order.markGrantRetry();
                return ConfirmResult.fail(6, "grant_failed");
            }
            return ConfirmResult.ok(order, grant.balance());
        };

        if (!awaitingGrant) {
            IapVerifyGateway.VerifyResult verify = verifyGateway.verify(channel, orderId, order.skuId(), receipt);
            if (!verify.success()) {
                order.markFailed();
                return ConfirmResult.fail(5, verify.message());
            }

            String channelTx = verify.channelTxId();
            if (!acquireReplay(playerId, channelTx)) {
                return ConfirmResult.fail(7, "replay_rejected");
            }
            order.markPaid(channel, channelTx, now);
            if (redisReplayGuard != null && channelTx != null && !channelTx.isBlank()) {
                return redisReplayGuard.withIapGrantLock(channelTx, grantPath);
            }
            return grantPath.get();
        }

        String channelTx = order.channelTxId();
        if (redisReplayGuard != null && channelTx != null && !channelTx.isBlank()) {
            return redisReplayGuard.withIapGrantLock(channelTx, grantPath);
        }
        return grantPath.get();
    }

    private boolean acquireReplay(int playerId, String channelTx) {
        if (channelTx == null || channelTx.isBlank()) {
            return false;
        }
        if (redisReplayGuard != null && !redisReplayGuard.tryAcquire("iap", channelTx)) {
            return false;
        }
        if (replayGuard != null && !replayGuard.tryAcquire("iap", playerId, channelTx)) {
            return false;
        }
        return true;
    }

    public IapOrder findOrder(String orderId) {
        return ordersById.get(orderId);
    }

    /** 按渠道流水估算应追回的货币数量（发货面额；无订单时返回 0）。 */
    public int estimateClawbackAmount(String channelTxId) {
        if (channelTxId == null || channelTxId.isBlank()) {
            return 0;
        }
        for (IapOrder order : ordersById.values()) {
            if (channelTxId.equals(order.channelTxId()) && order.status() == IapOrderStatus.GRANTED) {
                ShopConfigRepository.ShopItemConfig item = catalog.find(order.shopId(), order.shopItemId());
                if (item != null && item.itemCount() > 0) {
                    return item.itemCount();
                }
                return Math.max(1, order.amountCents());
            }
        }
        return 0;
    }
}
