package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.player.PlayerAggregateService;
import cn.itcast.demo.mylunarcore.player.PlayerCurrencyHelper;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 通用奖励分发器：在线优先经 {@link PlayerAggregateService#commit}，离线走钱包条件更新。
 */
@Service
public class RewardDistributor {

    private final WalletApplicationService walletApplicationService;
    private final ItemRepository itemRepository;
    private final PlayerAggregateService playerAggregateService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RewardDistributor(WalletApplicationService walletApplicationService,
                             ItemRepository itemRepository,
                             PlayerAggregateService playerAggregateService) {
        this.walletApplicationService = walletApplicationService;
        this.itemRepository = itemRepository;
        this.playerAggregateService = playerAggregateService;
    }

    @Transactional
    public boolean grantQuestRewards(int playerId, QuestConfigRepository.QuestConfig config) {
        if (config.rewards() == null) {
            return true;
        }
        Boolean online = playerAggregateService.commit(playerId,
                (Function<PlayerData, Boolean>) data -> grantQuestOnline(data, playerId, config));
        if (online != null) {
            return online;
        }
        return grantQuestOffline(playerId, config);
    }

    @Transactional
    public boolean grantMailAttachments(int playerId, List<Map<String, Object>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return true;
        }
        Boolean online = playerAggregateService.commit(playerId,
                (Function<PlayerData, Boolean>) data -> grantMailOnline(data, playerId, attachments));
        if (online != null) {
            return online;
        }
        return grantMailOffline(playerId, attachments);
    }

    /**
     * 战斗/场景通用发奖：道具 + 货币 + 玩家经验。
     *
     * @return 实际发放的道具列表（用于协议回填）
     */
    @Transactional
    public List<GrantedItem> grantBattleRewards(int playerId,
                                                List<EncounterConfig.DropEntry> drops,
                                                int playerExp,
                                                String reason) {
        List<GrantedItem> granted = new ArrayList<>();
        if ((drops == null || drops.isEmpty()) && playerExp <= 0) {
            return granted;
        }
        List<EncounterConfig.DropEntry> safeDrops = drops == null ? List.of() : drops;
        Boolean online = playerAggregateService.commit(playerId,
                (Function<PlayerData, Boolean>) data -> grantBattleOnline(data, playerId, safeDrops, playerExp, granted));
        if (online != null) {
            return granted;
        }
        grantBattleOffline(playerId, safeDrops, playerExp, reason, granted);
        return granted;
    }

    public record GrantedItem(int itemId, int count) {
    }

    public static List<Map<String, Object>> parseAttachmentsJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json,
                    mapper.getTypeFactory().constructCollectionType(ArrayList.class, Map.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean grantQuestOnline(PlayerData data, int playerId, QuestConfigRepository.QuestConfig config) {
        PlayerEntity player = data.getPlayer();
        if (player == null) {
            return false;
        }
        Map<Integer, Integer> balance = new HashMap<>(PlayerCurrencyHelper.parseCurrency(player.getCurrencyJson()));
        for (QuestConfigRepository.RewardConfig reward : config.rewards()) {
            if (reward.currencyId() != null && reward.amount() != null && reward.amount() > 0) {
                int current = balance.getOrDefault(reward.currencyId(), 0);
                long next = (long) current + reward.amount();
                if (next > Integer.MAX_VALUE) {
                    return false;
                }
                balance.put(reward.currencyId(), (int) next);
            }
            if (reward.itemId() != null && reward.count() != null && reward.count() > 0) {
                long id = itemRepository.addSimpleItem(playerId, reward.itemId(), 3, reward.count());
                appendItem(data, playerId, id, reward.itemId(), reward.count());
            }
        }
        player.setCurrencyJson(PlayerCurrencyHelper.toCurrencyJson(balance));
        return true;
    }

    private boolean grantQuestOffline(int playerId, QuestConfigRepository.QuestConfig config) {
        for (QuestConfigRepository.RewardConfig reward : config.rewards()) {
            if (reward.currencyId() != null && reward.amount() != null && reward.amount() > 0) {
                WalletApplicationService.WalletChangeResult result =
                        walletApplicationService.add(playerId, reward.currencyId(), reward.amount(),
                                "quest:" + config.questId());
                if (!result.success()) {
                    return false;
                }
            }
            if (reward.itemId() != null && reward.count() != null && reward.count() > 0) {
                itemRepository.addSimpleItem(playerId, reward.itemId(), 3, reward.count());
            }
        }
        return true;
    }

    private boolean grantMailOnline(PlayerData data, int playerId, List<Map<String, Object>> attachments) {
        PlayerEntity player = data.getPlayer();
        if (player == null) {
            return false;
        }
        Map<Integer, Integer> balance = new HashMap<>(PlayerCurrencyHelper.parseCurrency(player.getCurrencyJson()));
        for (Map<String, Object> attachment : attachments) {
            Number currencyId = (Number) attachment.get("currencyId");
            Number currencyAmount = (Number) attachment.get("currencyAmount");
            if (currencyId != null && currencyAmount != null && currencyAmount.intValue() > 0) {
                int current = balance.getOrDefault(currencyId.intValue(), 0);
                long next = (long) current + currencyAmount.intValue();
                if (next > Integer.MAX_VALUE) {
                    return false;
                }
                balance.put(currencyId.intValue(), (int) next);
            }
            Number itemId = (Number) attachment.get("itemId");
            Number count = (Number) attachment.get("count");
            if (itemId != null && count != null && count.intValue() > 0) {
                long id = itemRepository.addSimpleItem(playerId, itemId.intValue(), 3, count.intValue());
                appendItem(data, playerId, id, itemId.intValue(), count.intValue());
            }
        }
        player.setCurrencyJson(PlayerCurrencyHelper.toCurrencyJson(balance));
        return true;
    }

    private boolean grantMailOffline(int playerId, List<Map<String, Object>> attachments) {
        for (Map<String, Object> attachment : attachments) {
            Number currencyId = (Number) attachment.get("currencyId");
            Number currencyAmount = (Number) attachment.get("currencyAmount");
            if (currencyId != null && currencyAmount != null && currencyAmount.intValue() > 0) {
                walletApplicationService.add(playerId, currencyId.intValue(), currencyAmount.intValue(), "mail");
            }
            Number itemId = (Number) attachment.get("itemId");
            Number count = (Number) attachment.get("count");
            if (itemId != null && count != null && count.intValue() > 0) {
                itemRepository.addSimpleItem(playerId, itemId.intValue(), 3, count.intValue());
            }
        }
        return true;
    }

    private boolean grantBattleOnline(PlayerData data,
                                      int playerId,
                                      List<EncounterConfig.DropEntry> drops,
                                      int playerExp,
                                      List<GrantedItem> granted) {
        PlayerEntity player = data.getPlayer();
        if (player == null) {
            return false;
        }
        Map<Integer, Integer> balance = new HashMap<>(PlayerCurrencyHelper.parseCurrency(player.getCurrencyJson()));
        for (EncounterConfig.DropEntry drop : drops) {
            if (drop.hasCurrency()) {
                int current = balance.getOrDefault(drop.currencyId(), 0);
                long next = (long) current + drop.amount();
                if (next > Integer.MAX_VALUE) {
                    return false;
                }
                balance.put(drop.currencyId(), (int) next);
            }
            if (drop.hasItem()) {
                long id = itemRepository.addSimpleItem(playerId, drop.itemId(), 3, drop.count());
                appendItem(data, playerId, id, drop.itemId(), drop.count());
                granted.add(new GrantedItem(drop.itemId(), drop.count()));
            }
        }
        if (playerExp > 0) {
            long nextExp = player.getExp() + playerExp;
            if (nextExp > Long.MAX_VALUE - 1) {
                nextExp = Long.MAX_VALUE;
            }
            player.setExp(nextExp);
        }
        player.setCurrencyJson(PlayerCurrencyHelper.toCurrencyJson(balance));
        return true;
    }

    private void grantBattleOffline(int playerId,
                                    List<EncounterConfig.DropEntry> drops,
                                    int playerExp,
                                    String reason,
                                    List<GrantedItem> granted) {
        for (EncounterConfig.DropEntry drop : drops) {
            if (drop.hasCurrency()) {
                walletApplicationService.add(playerId, drop.currencyId(), drop.amount(),
                        reason == null ? "battle" : reason);
            }
            if (drop.hasItem()) {
                itemRepository.addSimpleItem(playerId, drop.itemId(), 3, drop.count());
                granted.add(new GrantedItem(drop.itemId(), drop.count()));
            }
        }
        if (playerExp > 0) {
            playerAggregateService.commit(playerId, (Function<PlayerData, Boolean>) data -> {
                PlayerEntity player = data.getPlayer();
                if (player == null) {
                    return false;
                }
                player.setExp(player.getExp() + playerExp);
                return true;
            });
        }
    }

    private static void appendItem(PlayerData data, int playerId, long id, int itemId, long count) {
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
        entity.setType(3);
        entity.setCount(Math.max(1L, count));
        entity.setLevel(1);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        items.add(entity);
    }
}
