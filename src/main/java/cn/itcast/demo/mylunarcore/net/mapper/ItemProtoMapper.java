// Entity → Protobuf 映射（仅 net 层使用）
package cn.itcast.demo.mylunarcore.net.mapper;

import cn.itcast.demo.mylunarcore.item.ItemJsonParser;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 道具领域实体到客户端 Protobuf 的转换，Repository 层只返回 {@link GameItemEntity}。
 */
@Component
public class ItemProtoMapper {

    private final ItemJsonParser jsonParser = new ItemJsonParser();

    public ItemSystemProto.BagItem toBagItem(GameItemEntity entity) {
        int count = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entity.getCount()));
        int exp = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, entity.getExp()));
        ItemSystemProto.BagItem.Builder builder = ItemSystemProto.BagItem.newBuilder()
                .setUid(entity.getId())
                .setItemId(entity.getItemId())
                .setType(entity.getType())
                .setCount(count)
                .setLevel(Math.max(0, entity.getLevel()))
                .setExp(exp)
                .setPromotion(Math.max(0, entity.getPromotion()))
                .setRank(Math.max(0, entity.getRank()))
                .setLocked(entity.isLocked())
                .setMainAffixId(entity.getMainAffixId() == null ? 0 : entity.getMainAffixId())
                .setEquipAvatarId(entity.getEquipAvatarId() == null ? 0 : entity.getEquipAvatarId());
        List<ItemSystemProto.SubAffix> subs = jsonParser.parseSubAffixes(entity.getSubAffixesJson());
        builder.addAllSubAffixes(subs);
        return builder.build();
    }

    public List<ItemSystemProto.BagItem> toBagItems(List<GameItemEntity> entities) {
        List<ItemSystemProto.BagItem> out = new ArrayList<>(entities.size());
        for (GameItemEntity entity : entities) {
            out.add(toBagItem(entity));
        }
        return out;
    }
}
