package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.RogueRoomData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * RogueRoomDataRepository 模拟宇宙房间仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code RogueRoomDataRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("RogueRoomDataRepository 模拟宇宙房间仓储测试")
class RogueRoomDataRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(RogueRoomDataRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private RogueRoomDataRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RogueRoomDataRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    /**
     * 验证点：findByRoomId 应返回房间配置。
     * <p>测试方法 {@code findByRoomIdShouldReturnRoomData}：
     * <ul>
     *   <li>{@code assertEquals(101, room.getRoomId());}</li>
     *   <li>{@code assertEquals(2, room.getRoomType());}</li>
     *   <li>{@code assertEquals(3, room.getPosX());}</li>
     *   <li>{@code assertEquals(4, room.getPosY());}</li>
     * </ul>
     */
    @Test
    @DisplayName("findByRoomId 应返回房间配置")
    void findByRoomIdShouldReturnRoomData() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("room_id", 101).intCol("room_type", 2)
                        .intCol("pos_x", 3).intCol("pos_y", 4))
        ));

        RogueRoomData room = repository.findByRoomId(101);

        assertEquals(101, room.getRoomId());
        log.info("房间配置查询: roomId=101, roomType={}, posX={}, posY={}",
                room.getRoomType(), room.getPosX(), room.getPosY());
        assertEquals(2, room.getRoomType());
        assertEquals(3, room.getPosX());
        assertEquals(4, room.getPosY());
    }

    /**
     * 验证点：房间不存在时应返回 null。
     * <p>测试方法 {@code findByRoomIdShouldReturnNullWhenMissing}：
     * <ul>
     *   <li>{@code assertNull(room);}</li>
     * </ul>
     */
    @Test
    @DisplayName("房间不存在时应返回 null")
    void findByRoomIdShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, Collections.emptyList());

        RogueRoomData room = repository.findByRoomId(999);

        log.info("缺失房间校验: roomId=999, roomNull={}", room == null);
        assertNull(room);
    }
}
