package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.RogueRoomData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RogueRoomCache 模拟宇宙房间缓存测试")
class RogueRoomCacheTest {

    private static final Logger log = LoggerFactory.getLogger(RogueRoomCacheTest.class);

    private RogueRoomDataRepository repository;
    private RogueRoomCache cache;

    @BeforeEach
    void setUp() {
        repository = mock(RogueRoomDataRepository.class);
        cache = new RogueRoomCache(repository);
        log.info("缓存初始化: cache={}", cache.getClass().getSimpleName());
    }

    @Test
    @DisplayName("getOrLoad 应从 L2 加载并缓存房间数据")
    void getOrLoadShouldLoadFromRepositoryAndCache() {
        when(repository.findByRoomId(201)).thenReturn(new RogueRoomData(201, 3, 5, 6));

        RogueRoomData first = cache.getOrLoad(201);
        RogueRoomData second = cache.getOrLoad(201);

        assertEquals(201, first.getRoomId());
        log.info("L1 缓存校验: roomId=201, roomType={}, posX={}, posY={}, sameInstance={}",
                first.getRoomType(), first.getPosX(), first.getPosY(), first == second);
        assertEquals(3, first.getRoomType());
        assertEquals(first, second);
        verify(repository, times(1)).findByRoomId(201);
    }

    @Test
    @DisplayName("数据库无行时应返回默认房间")
    void getOrLoadShouldReturnDefaultRoomWhenMissing() {
        when(repository.findByRoomId(302)).thenReturn(null);

        RogueRoomData room = cache.getOrLoad(302);

        assertNotNull(room);
        log.info("默认房间校验: roomId=302, roomType={}, posX={}, posY={}",
                room.getRoomType(), room.getPosX(), room.getPosY());
        assertEquals(302, room.getRoomId());
        assertEquals(1, room.getRoomType());
        assertEquals(0, room.getPosX());
        assertEquals(0, room.getPosY());
    }

    @Test
    @DisplayName("invalidate 后应重新从 L2 加载")
    void invalidateShouldForceReload() {
        when(repository.findByRoomId(401)).thenReturn(new RogueRoomData(401, 2, 1, 1));

        cache.getOrLoad(401);
        cache.invalidate(401);
        cache.getOrLoad(401);

        log.info("缓存失效校验: roomId=401, reloadCount=2");
        verify(repository, times(2)).findByRoomId(401);
    }
}
