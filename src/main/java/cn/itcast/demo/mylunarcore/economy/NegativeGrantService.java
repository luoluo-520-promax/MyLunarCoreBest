package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.mapper.ItemProtoMapper;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负向发放：运维强制扣除道具/货币，并推送 ItemChangeScNotify / CurrencyChange 同步客户端。
 * count 传负数表示扣除；正数表示补发。
 */
@Service
public class NegativeGrantService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SYNC, NegativeGrantService.class);

    private final ItemRepository itemRepository;
    private final WalletApplicationService walletApplicationService;
    private final GameSessionManager sessionManager;
    private final ObjectProvider<ItemProtoMapper> itemProtoMapperProvider;

    public NegativeGrantService(ItemRepository itemRepository,
                                WalletApplicationService walletApplicationService,
                                GameSessionManager sessionManager,
                                ObjectProvider<ItemProtoMapper> itemProtoMapperProvider) {
        this.itemRepository = itemRepository;
        this.walletApplicationService = walletApplicationService;
        this.sessionManager = sessionManager;
        this.itemProtoMapperProvider = itemProtoMapperProvider;
    }

    public Map<String, Object> grant(int uid, int itemId, long count, boolean currency, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uid", uid);
        out.put("itemId", itemId);
        out.put("count", count);
        out.put("currency", currency);
        out.put("reason", reason == null ? "ops_negative_grant" : reason);
        if (uid <= 0 || itemId <= 0 || count == 0) {
            out.put("ok", false);
            out.put("message", "invalid_args");
            return out;
        }
        if (currency) {
            return grantCurrency(uid, itemId, count, reason, out);
        }
        return grantItem(uid, itemId, count, reason, out);
    }

    private Map<String, Object> grantCurrency(int uid, int itemId, long count, String reason, Map<String, Object> out) {
        String r = reason == null ? "ops_negative_grant" : reason;
        WalletApplicationService.WalletChangeResult result;
        if (count < 0) {
            result = walletApplicationService.forceDeductAllowNegative(uid, itemId, (int) Math.min(Integer.MAX_VALUE, -count), r);
        } else {
            result = walletApplicationService.add(uid, itemId, (int) Math.min(Integer.MAX_VALUE, count), r);
        }
        out.put("ok", result.success());
        out.put("balance", result.balance());
        pushCurrencyNotify(uid, itemId, (int) count, result.balance().getOrDefault(itemId, 0));
        log.warn("negative_grant currency uid={} currencyId={} delta={} ok={}", uid, itemId, count, result.success());
        return out;
    }

    private Map<String, Object> grantItem(int uid, int itemId, long count, String reason, Map<String, Object> out) {
        if (count > 0) {
            long id = itemRepository.addSimpleItem(uid, itemId, 3, count);
            out.put("ok", id > 0);
            out.put("newItemUid", id);
            pushItemChange(uid, itemId, count, false);
            return out;
        }
        long need = -count;
        ItemRepository.SubtractResult sub = itemRepository.forceSubtractByItemId(uid, itemId, need);
        out.put("ok", sub.subtracted() > 0 || need == 0);
        out.put("subtracted", sub.subtracted());
        out.put("remain", sub.remain());
        out.put("affectedRows", sub.affectedRows());
        pushItemSubtractNotify(uid, itemId, sub.subtracted(), sub.affectedItems());
        log.warn("negative_grant item uid={} itemId={} need={} subtracted={}", uid, itemId, need, sub.subtracted());
        return out;
    }

    private void pushItemSubtractNotify(int uid, int itemId, long subtracted, List<GameItemEntity> affected) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null || session.getChannel() == null || !session.getChannel().isActive()) {
            return;
        }
        try {
            // 专用 ItemSubtract 语义：走 ITEM_CHANGE changeType=3(移除)/2(数量变更)
            ItemSystemProto.ItemChangeScNotify.Builder b = ItemSystemProto.ItemChangeScNotify.newBuilder()
                    .setChangeType(2);
            ItemProtoMapper mapper = itemProtoMapperProvider.getIfAvailable();
            if (mapper != null && affected != null) {
                for (GameItemEntity e : affected) {
                    b.addItems(mapper.toBagItem(e));
                }
            }
            // 附带 subtract 元数据到空 items 时仍推送 cmd，客户端可按 itemId 刷新
            if (b.getItemsCount() == 0) {
                ItemSystemProto.BagItem stub = ItemSystemProto.BagItem.newBuilder()
                        .setItemId(itemId)
                        .setCount(0)
                        .build();
                b.addItems(stub);
                b.setChangeType(3);
            }
            session.send(new GamePacket(CmdIds.ITEM_CHANGE_SC_NOTIFY, b.build().toByteArray()));
            // 兼容：额外推 ITEM_RECYCLE 作为 ItemSubtractScNotify 语义通道
            String meta = "{\"itemId\":" + itemId + ",\"subtracted\":" + subtracted + ",\"op\":\"ItemSubtractScNotify\"}";
            session.send(new GamePacket(CmdIds.ITEM_RECYCLE_SC_NOTIFY, meta.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.debug("push item subtract notify failed uid={}: {}", uid, e.toString());
        }
    }

    private void pushItemChange(int uid, int itemId, long count, boolean discard) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null || session.getChannel() == null || !session.getChannel().isActive()) {
            return;
        }
        try {
            ItemSystemProto.BagItem stub = ItemSystemProto.BagItem.newBuilder()
                    .setItemId(itemId)
                    .setCount((int) Math.min(Integer.MAX_VALUE, count))
                    .build();
            ItemSystemProto.ItemChangeScNotify notify = ItemSystemProto.ItemChangeScNotify.newBuilder()
                    .setChangeType(discard ? 3 : 1)
                    .addItems(stub)
                    .build();
            session.send(new GamePacket(CmdIds.ITEM_CHANGE_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception ignored) {
        }
    }

    private void pushCurrencyNotify(int uid, int currencyId, int delta, int balance) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null || session.getChannel() == null || !session.getChannel().isActive()) {
            return;
        }
        try {
            String json = "{\"currencyId\":" + currencyId + ",\"delta\":" + delta + ",\"balance\":" + balance + "}";
            session.send(new GamePacket(CmdIds.CURRENCY_CHANGE_SC_NOTIFY, json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
        }
    }
}
