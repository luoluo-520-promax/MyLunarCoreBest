package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.player.PlayerAggregateService;
import cn.itcast.demo.mylunarcore.player.PlayerCurrencyHelper;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import cn.itcast.demo.mylunarcore.skin.SkinOwnershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 商店购买应用编排：在线路径经 {@link PlayerAggregateService#commit} 改内存货币并异步乐观落盘；
 * 离线兜底走钱包仓储条件更新。皮肤商品购买前校验已拥有并拦截重复购买。
 */
@Service
public class ShopApplicationService {

    public record BuyResult(boolean success, int boughtCount, WalletApplicationService.WalletChangeResult wallet) {}

    private final ShopConfigRepository shopConfigRepository;
    private final WalletApplicationService walletApplicationService;
    private final ItemRepository itemRepository;
    private final PlayerAggregateService playerAggregateService;
    private final SkinConfigRepository skinConfigRepository;
    private final SkinOwnershipService skinOwnershipService;

    public ShopApplicationService(ShopConfigRepository shopConfigRepository,
                                  WalletApplicationService walletApplicationService,
                                  ItemRepository itemRepository,
                                  PlayerAggregateService playerAggregateService,
                                  SkinConfigRepository skinConfigRepository,
                                  SkinOwnershipService skinOwnershipService) {
        this.shopConfigRepository = shopConfigRepository;
        this.walletApplicationService = walletApplicationService;
        this.itemRepository = itemRepository;
        this.playerAggregateService = playerAggregateService;
        this.skinConfigRepository = skinConfigRepository;
        this.skinOwnershipService = skinOwnershipService;
    }

    /**
     * 执行商店购买：优先在线 commit；玩家不在线时回退直写库。
     */
    public BuyResult buy(int playerId, int shopId, int shopItemId, int count) {
        if (count <= 0) {
            count = 1;
        }
        ShopConfigRepository.ShopItemConfig item = shopConfigRepository.findItem(shopId, shopItemId);
        if (item == null) {
            return new BuyResult(false, 0, null);
        }
        // 真钱商品走 IAP CreateOrder/Confirm，禁止经游戏币购买入口发货
        if (item.isIap()) {
            return new BuyResult(false, 0, null);
        }
        if (isAlreadyOwnedSkin(playerId, item)) {
            return new BuyResult(false, 0, null);
        }
        final int buyCount = count;
        BuyResult online = playerAggregateService.commit(playerId,
                (Function<PlayerData, BuyResult>) data -> buyOnline(data, playerId, item, buyCount));
        if (online != null) {
            return online;
        }
        return buyOffline(playerId, item, buyCount);
    }

    private BuyResult buyOnline(PlayerData data, int playerId, ShopConfigRepository.ShopItemConfig item, int count) {
        PlayerEntity player = data.getPlayer();
        if (player == null) {
            return new BuyResult(false, 0, null);
        }
        if (isAlreadyOwnedSkin(playerId, item)) {
            return new BuyResult(false, 0, null);
        }
        Map<Integer, Integer> balance = new HashMap<>(PlayerCurrencyHelper.parseCurrency(player.getCurrencyJson()));
        int totalPrice = item.price() * count;
        int current = balance.getOrDefault(item.currencyId(), 0);
        if (totalPrice > 0 && current < totalPrice) {
            return new BuyResult(false, 0,
                    new WalletApplicationService.WalletChangeResult(false, balance, "shop:" + item.shopItemId()));
        }
        if (totalPrice > 0) {
            balance.put(item.currencyId(), current - totalPrice);
            player.setCurrencyJson(PlayerCurrencyHelper.toCurrencyJson(balance));
        }
        SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(item.itemId());
        int itemType = skin != null ? SkinOwnershipService.SKIN_ITEM_TYPE : 3;
        long totalItems = skin != null ? 1L : (long) item.itemCount() * count;
        long newId = itemRepository.addSimpleItem(playerId, item.itemId(), itemType, totalItems);
        appendItem(data, playerId, newId, item.itemId(), itemType, totalItems);
        return new BuyResult(true, count,
                new WalletApplicationService.WalletChangeResult(true, balance, "shop:" + item.shopItemId()));
    }

    @Transactional
    protected BuyResult buyOffline(int playerId, ShopConfigRepository.ShopItemConfig item, int count) {
        if (isAlreadyOwnedSkin(playerId, item)) {
            return new BuyResult(false, 0, null);
        }
        int totalPrice = item.price() * count;
        WalletApplicationService.WalletChangeResult wallet = walletApplicationService.deduct(
                playerId, item.currencyId(), totalPrice, "shop:" + item.shopItemId());
        if (!wallet.success()) {
            return new BuyResult(false, 0, wallet);
        }
        SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(item.itemId());
        int itemType = skin != null ? SkinOwnershipService.SKIN_ITEM_TYPE : 3;
        long totalItems = skin != null ? 1L : (long) item.itemCount() * count;
        itemRepository.addSimpleItem(playerId, item.itemId(), itemType, totalItems);
        return new BuyResult(true, count, wallet);
    }

    private boolean isAlreadyOwnedSkin(int playerId, ShopConfigRepository.ShopItemConfig item) {
        if (item == null) {
            return false;
        }
        SkinConfigRepository.SkinConfig byItem = skinConfigRepository.findByItemId(item.itemId());
        if (byItem != null) {
            return skinOwnershipService.owns(playerId, byItem.skinId());
        }
        if (item.isSkinProduct()) {
            for (ShopConfigRepository.RewardSpec reward : item.rewards()) {
                if (reward != null && reward.isItem()) {
                    SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(reward.itemId());
                    if (skin != null && skinOwnershipService.owns(playerId, skin.skinId())) {
                        return true;
                    }
                }
            }
        }
        return false;
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
