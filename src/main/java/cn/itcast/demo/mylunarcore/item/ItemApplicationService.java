// 道具多步写库事务封装
package cn.itcast.demo.mylunarcore.item;

import cn.itcast.demo.mylunarcore.equipment.EquipmentAffixService;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 道具应用服务：多步 DB 写操作在同一事务内完成，Netty 层只负责协议编解码。
 * 光锥/遗器强化时联动 {@link EquipmentAffixService} 副词条成长。
 */
@Service
public class ItemApplicationService {

    public record UseItemResult(long usedCount, long newCount, boolean discarded, int playerExp, int avatarExp) {}

    public record EnhanceItemResult(int newLevel, long newExp, List<Long> consumedUids) {}

    public record RankUpItemResult(int newRank, long consumedUid) {}

    public record DecomposeResult(boolean success, int returnExp, int returnCurrency) {}

    private final ItemRepository itemRepository;
    private final EquipmentAffixService equipmentAffixService;

    public ItemApplicationService(ItemRepository itemRepository) {
        this(itemRepository, null);
    }

    public ItemApplicationService(ItemRepository itemRepository,
                                  ObjectProvider<EquipmentAffixService> affixProvider) {
        this.itemRepository = itemRepository;
        this.equipmentAffixService = affixProvider == null ? null : affixProvider.getIfAvailable();
    }

    @Transactional
    public UseItemResult useItem(int playerId, long uid, long reqCount, int targetAvatarId) {
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid);
        if (item == null || item.isDiscarded()) {
            return null;
        }
        if (item.isLocked()) {
            return null;
        }

        int type = item.getType();
        long usedCount = type == 3 ? Math.min(reqCount <= 0 ? 1 : reqCount, item.getCount()) : 1;
        if (usedCount <= 0) {
            return null;
        }

        long newCount = item.getCount() - usedCount;
        boolean discard = newCount <= 0 || type != 3;
        itemRepository.updateItemCountAndDiscard(playerId, uid, Math.max(0, newCount), discard);

        long totalExp = item.getExp() * usedCount;
        int playerExp = (int) Math.max(0, Math.min(Integer.MAX_VALUE, totalExp));
        int avatarExp = targetAvatarId > 0 ? playerExp : 0;
        return new UseItemResult(usedCount, Math.max(0, newCount), discard, playerExp, avatarExp);
    }

    @Transactional
    public boolean equipItem(int playerId, long uid, int avatarId) {
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid);
        if (item == null || item.isDiscarded() || item.isLocked()) {
            return false;
        }
        if (item.getType() != 1 && item.getType() != 2) {
            return false;
        }
        return itemRepository.setEquipAvatarId(playerId, uid, avatarId) > 0;
    }

    @Transactional
    public Integer unequipItem(int playerId, long uid) {
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid);
        if (item == null || item.isDiscarded() || item.isLocked()) {
            return null;
        }
        int avatarId = item.getEquipAvatarId() == null ? 0 : item.getEquipAvatarId();
        itemRepository.setEquipAvatarId(playerId, uid, null);
        return avatarId;
    }

    @Transactional
    public EnhanceItemResult enhanceItem(int playerId, long targetUid, List<Long> materialUids) {
        GameItemEntity target = itemRepository.findItemByUid(playerId, targetUid);
        if (target == null || target.isDiscarded() || target.isLocked()) {
            return null;
        }

        long sumMaterialExp = 0;
        List<Long> consumed = new ArrayList<>();
        for (Long mu : materialUids) {
            if (mu == null || mu <= 0) {
                continue;
            }
            GameItemEntity material = itemRepository.findItemByUid(playerId, mu);
            if (material == null || material.isDiscarded() || material.isLocked()) {
                continue;
            }
            sumMaterialExp += material.getExp() * material.getCount();
            consumed.add(mu);
            itemRepository.markDiscarded(playerId, mu);
        }

        int oldLevel = target.getLevel();
        int newLevel = oldLevel;
        long newExpTotal = target.getExp() + sumMaterialExp;
        if (sumMaterialExp > 0) {
            newLevel = oldLevel + (int) Math.max(1L, sumMaterialExp / 1000L);
        }
        itemRepository.applyEnhance(playerId, targetUid, newLevel, newExpTotal);

        // 光锥/遗器：跨强化阈值时成长或追加副词条
        if (equipmentAffixService != null && (target.getType() == 1 || target.getType() == 2) && newLevel > oldLevel) {
            String nextSubs = equipmentAffixService.maybeUpgradeSubOnEnhance(target, oldLevel, newLevel);
            if (nextSubs != null) {
                itemRepository.updateSubAffixes(playerId, targetUid, nextSubs);
            }
        }
        return new EnhanceItemResult(newLevel, newExpTotal, consumed);
    }

    /**
     * 分解光锥/遗器：软删实例并返回强化材料等价经验/货币提示。
     */
    @Transactional
    public DecomposeResult decomposeEquipment(int playerId, long uid) {
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid);
        if (item == null || item.isDiscarded() || item.isLocked()) {
            return new DecomposeResult(false, 0, 0);
        }
        if (item.getType() != 1 && item.getType() != 2) {
            return new DecomposeResult(false, 0, 0);
        }
        int returnExp = 0;
        int returnCurrency = 0;
        if (equipmentAffixService != null) {
            EquipmentAffixService.DecomposeResult r = equipmentAffixService.decompose(item);
            returnExp = r.returnExp();
            returnCurrency = r.returnCurrency();
        }
        itemRepository.markDiscarded(playerId, uid);
        return new DecomposeResult(true, returnExp, returnCurrency);
    }

    /**
     * 为新掉落遗器/光锥抽取词条并落库。
     */
    @Transactional
    public boolean rollAffixesForNewItem(int playerId, long uid, String slot) {
        if (equipmentAffixService == null) {
            return false;
        }
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid);
        if (item == null || item.isDiscarded()) {
            return false;
        }
        EquipmentAffixService.RollResult roll = equipmentAffixService.rollNew(slot, item.getItemId());
        return itemRepository.updateAffixes(playerId, uid, roll.mainAffixId(), roll.subAffixesJson()) > 0;
    }

    @Transactional
    public Integer promoteItem(int playerId, long uid) {
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid);
        if (item == null || item.isDiscarded() || item.isLocked()) {
            return null;
        }
        int newPromotion = item.getPromotion() + 1;
        itemRepository.updatePromotion(playerId, uid, newPromotion);
        return newPromotion;
    }

    @Transactional
    public RankUpItemResult rankUpItem(int playerId, long baseUid, long materialUid) {
        GameItemEntity base = itemRepository.findItemByUid(playerId, baseUid);
        GameItemEntity material = itemRepository.findItemByUid(playerId, materialUid);
        if (base == null || base.isDiscarded() || material == null || material.isDiscarded()) {
            return null;
        }
        if (base.isLocked() || material.isLocked()) {
            return null;
        }
        int newRank = base.getRank() + 1;
        itemRepository.updateRank(playerId, baseUid, newRank);
        itemRepository.markDiscarded(playerId, materialUid);
        return new RankUpItemResult(newRank, materialUid);
    }

    @Transactional
    public boolean setLocked(int playerId, long uid, boolean lock) {
        return itemRepository.setLocked(playerId, uid, lock) > 0;
    }

    @Transactional
    public Long discardItem(int playerId, long uid, long reqCount) {
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid);
        if (item == null || item.isDiscarded() || item.isLocked()) {
            return null;
        }
        long count = reqCount <= 0 ? 1 : reqCount;
        long newCount = Math.max(0, item.getCount() - count);
        boolean discard = newCount == 0;
        itemRepository.updateItemCountAndDiscard(playerId, uid, newCount, discard);
        return newCount;
    }

    public GameItemEntity findItem(int playerId, long uid) {
        return itemRepository.findItemByUid(playerId, uid);
    }

    public List<GameItemEntity> listBagItems(int playerId, int typeFilter, int page, int pageSize) {
        return itemRepository.listBagItems(playerId, typeFilter, page, pageSize);
    }

    public long countBagItems(int playerId, int typeFilter) {
        return itemRepository.countBagItems(playerId, typeFilter);
    }

    public record ItemSource(int itemId, int stageId, String stageName, boolean sweepUnlocked,
                             boolean challengeUnlocked, boolean bonusToday) {
        public ItemSource(int itemId, int stageId, String stageName, boolean sweepUnlocked,
                          boolean challengeUnlocked) {
            this(itemId, stageId, stageName, sweepUnlocked, challengeUnlocked, false);
        }
    }

    /**
     * 材料反查：返回可掉落关卡及扫荡/挑战快捷入口；当日双倍关置顶并标记 is_bonus_today。
     */
    public List<ItemSource> queryItemSources(int itemId) {
        if (itemId <= 0) {
            return List.of();
        }
        List<ItemSource> catalog = ITEM_SOURCES.get(itemId);
        List<ItemSource> raw;
        if (catalog != null && !catalog.isEmpty()) {
            raw = catalog;
        } else {
            int stageId = 1000 + (itemId % 50);
            raw = List.of(new ItemSource(itemId, stageId, "材料本-" + stageId, true, true));
        }
        java.time.DayOfWeek dow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).getDayOfWeek();
        List<ItemSource> marked = new ArrayList<>(raw.size());
        for (ItemSource src : raw) {
            boolean bonus = isBonusStageToday(src.stageId(), dow);
            marked.add(new ItemSource(src.itemId(), src.stageId(), src.stageName(),
                    src.sweepUnlocked(), src.challengeUnlocked(), bonus));
        }
        marked.sort((a, b) -> Boolean.compare(b.bonusToday(), a.bonusToday()));
        return List.copyOf(marked);
    }

    /** 简易双倍日程：工作日 101/301，周末 102/201。 */
    public static boolean isBonusStageToday(int stageId, java.time.DayOfWeek dow) {
        boolean weekend = dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY;
        if (weekend) {
            return stageId == 102 || stageId == 201;
        }
        return stageId == 101 || stageId == 301;
    }

    private static final Map<Integer, List<ItemSource>> ITEM_SOURCES = Map.of(
            201, List.of(new ItemSource(201, 101, "忘却之庭·材料 I", true, true),
                    new ItemSource(201, 201, "历战余响", false, true)),
            202, List.of(new ItemSource(202, 102, "拟造花萼·金", true, false)),
            301, List.of(new ItemSource(301, 301, "侵蚀隧洞", true, true))
    );

    public record CraftStep(int fromItemId, int fromCount, int toItemId, int toCount) {}

    public record CraftPath(int itemId, List<CraftStep> steps, boolean canCraft) {}

    /** 高级材料若不直掉，给出低级合成路径。 */
    public List<CraftPath> queryCraftPaths(int itemId) {
        if (itemId == 301) {
            return List.of(new CraftPath(301, List.of(new CraftStep(201, 3, 301, 1)), true));
        }
        if (itemId == 401) {
            return List.of(new CraftPath(401, List.of(new CraftStep(301, 2, 401, 1), new CraftStep(203, 4, 401, 1)), true));
        }
        return List.of();
    }
}
