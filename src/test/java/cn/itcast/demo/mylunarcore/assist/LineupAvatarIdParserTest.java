package cn.itcast.demo.mylunarcore.assist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LineupAvatarIdParser}：从多种 JSON 形态提取 avatarId 并去重；非法输入返回空列表。
 */
@DisplayName("LineupAvatarIdParser 阵容 ID 解析测试")
class LineupAvatarIdParserTest {

    private static final Logger log = LoggerFactory.getLogger(LineupAvatarIdParserTest.class);

    /**
     * 覆盖纯数字数组（去重）、[{avatarId}/{id}]、avatars/avatarIds 包装、members[].id。
     */
    @Test
    @DisplayName("应解析纯数组、对象数组与包装字段，并去重")
    void shouldParseCommonJsonShapesAndDeduplicate() {
        List<Integer> plain = LineupAvatarIdParser.parse("[1001,1002,1001]");
        List<Integer> objects = LineupAvatarIdParser.parse("[{\"avatarId\":1003},{\"id\":1004}]");
        List<Integer> wrapped = LineupAvatarIdParser.parse("{\"avatars\":[1005,{\"avatarId\":1006}],\"avatarIds\":[1007]}");
        List<Integer> members = LineupAvatarIdParser.parse("{\"members\":[{\"id\":1008}]}");

        log.info("阵容ID解析校验: plain={}, objects={}, wrapped={}, members={}",
                plain, objects, wrapped, members);
        assertEquals(List.of(1001, 1002), plain);
        assertEquals(List.of(1003, 1004), objects);
        assertEquals(List.of(1005, 1006, 1007), wrapped);
        assertEquals(List.of(1008), members);
    }

    /** 空白、null、残缺 JSON 均返回 empty，不抛异常。 */
    @Test
    @DisplayName("空串、非法 JSON 应返回空列表")
    void blankOrInvalidShouldReturnEmpty() {
        List<Integer> blank = LineupAvatarIdParser.parse("  ");
        List<Integer> nullJson = LineupAvatarIdParser.parse(null);
        List<Integer> bad = LineupAvatarIdParser.parse("{not-json");
        log.info("非法输入解析校验: blankSize={}, nullSize={}, badSize={}",
                blank.size(), nullJson.size(), bad.size());
        assertTrue(blank.isEmpty());
        assertTrue(nullJson.isEmpty());
        assertTrue(bad.isEmpty());
    }
}
