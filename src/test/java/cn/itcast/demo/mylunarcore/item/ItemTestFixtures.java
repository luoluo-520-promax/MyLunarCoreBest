package cn.itcast.demo.mylunarcore.item;

import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;

/**
 * 道具模块测试用公共数据构造工具。
 */
final class ItemTestFixtures {

    private ItemTestFixtures() {
    }

    static GameItemEntity item(long uid, int itemId, int type) {
        return item(uid, itemId, type, 1, 0, false, false);
    }

    static GameItemEntity item(long uid, int itemId, int type, long count, long exp,
                               boolean locked, boolean discarded) {
        GameItemEntity entity = new GameItemEntity();
        entity.setId(uid);
        entity.setPlayerId(77);
        entity.setItemId(itemId);
        entity.setType(type);
        entity.setCount(count);
        entity.setLevel(1);
        entity.setExp(exp);
        entity.setPromotion(0);
        entity.setRank(0);
        entity.setLocked(locked);
        entity.setDiscarded(discarded);
        entity.setMainAffixId(null);
        entity.setSubAffixesJson(null);
        entity.setEquipAvatarId(null);
        return entity;
    }

    static ItemSystemProto.BagItem bagItem(long uid, int itemId, int type, int count) {
        return ItemSystemProto.BagItem.newBuilder()
                .setUid(uid)
                .setItemId(itemId)
                .setType(type)
                .setCount(count)
                .build();
    }
}
