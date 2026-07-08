package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("ItemRepository 道具仓储测试")
class ItemRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(ItemRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private ItemRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new ItemRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("countBagItems 应统计未丢弃道具数量")
    void countBagItemsShouldReturnCount() {
        RepoTestFixtures.stubQueryForObjectScalar(jdbcTemplate, 5L);

        long count = repository.countBagItems(77, 2);

        log.info("背包统计: playerId=77, typeFilter=2, itemCount={}", count);
        assertEquals(5L, count);
    }

    @Test
    @DisplayName("toBagItem 应将实体映射为 Proto")
    void toBagItemShouldMapEntityToProto() {
        GameItemEntity entity = new GameItemEntity();
        entity.setId(10001L);
        entity.setItemId(2001);
        entity.setType(1);
        entity.setCount(99L);
        entity.setLevel(5);
        entity.setExp(1200L);
        entity.setPromotion(2);
        entity.setRank(3);
        entity.setLocked(true);
        entity.setMainAffixId(11);
        entity.setEquipAvatarId(77);
        entity.setSubAffixesJson("[]");

        ItemSystemProto.BagItem bagItem = repository.toBagItem(entity);

        assertEquals(10001L, bagItem.getUid());
        log.info("Proto 映射: uid={}, itemId={}, type={}, count={}, level={}, locked={}, mainAffixId={}",
                bagItem.getUid(), bagItem.getItemId(), bagItem.getType(), bagItem.getCount(),
                bagItem.getLevel(), bagItem.getLocked(), bagItem.getMainAffixId());
        assertEquals(2001, bagItem.getItemId());
        assertEquals(99, bagItem.getCount());
        assertTrue(bagItem.getLocked());
        assertEquals(11, bagItem.getMainAffixId());
    }

    @Test
    @DisplayName("existsActiveItemByItemId 应判断是否存在有效道具")
    void existsActiveItemByItemIdShouldDetectActiveItem() {
        RepoTestFixtures.stubQueryForObjectScalar(jdbcTemplate, 1L);

        boolean exists = repository.existsActiveItemByItemId(77, 3001);

        log.info("有效道具检测: playerId=77, itemId=3001, exists={}", exists);
        assertTrue(exists);
    }

    @Test
    @DisplayName("updateItemCountAndDiscard 丢弃应返回受影响行数")
    void updateItemCountAndDiscardShouldDiscardItem() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        int affected = repository.updateItemCountAndDiscard(77, 10001L, 0, true);

        log.info("道具丢弃: playerId=77, uid=10001, discard=true, affectedRows={}", affected);
        assertEquals(1, affected);
    }

    @Test
    @DisplayName("findItemByUid 不存在时应返回 null")
    void findItemByUidShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, Collections.emptyList());

        GameItemEntity item = repository.findItemByUid(77, 99999L);

        log.info("缺失道具校验: playerId=77, uid=99999, itemNull={}", item == null);
        assertEquals(null, item);
    }

    @Test
    @DisplayName("listBagItems 应返回分页背包 Proto 列表")
    void listBagItemsShouldReturnPagedBagItems() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .longCol("id", 10001L).intCol("player_id", 77)
                        .intCol("item_id", 2001).intCol("type", 1)
                        .longCol("count", 10L).intCol("level", 1)
                        .longCol("exp", 0L).intCol("promotion", 0)
                        .intCol("rank", 0).intCol("locked", 0)
                        .objectCol("main_affix_id", null)
                        .stringCol("sub_affixes", null)
                        .objectCol("equip_avatar_id", null))
        ));

        List<ItemSystemProto.BagItem> items = repository.listBagItems(77, 0, 1, 20);

        assertEquals(1, items.size());
        log.info("背包分页查询: playerId=77, page=1, pageSize=20, itemCount={}, firstUid={}, firstCount={}",
                items.size(), items.get(0).getUid(), items.get(0).getCount());
        assertEquals(10001L, items.get(0).getUid());
        assertEquals(10, items.get(0).getCount());
    }

    @Test
    @DisplayName("existsActiveItemByItemId 无记录时应返回 false")
    void existsActiveItemByItemIdShouldReturnFalseWhenMissing() {
        RepoTestFixtures.stubQueryForObjectScalar(jdbcTemplate, 0L);

        boolean exists = repository.existsActiveItemByItemId(77, 0);

        log.info("无有效道具校验: playerId=77, itemId=0, exists={}", exists);
        assertFalse(exists);
    }
}
