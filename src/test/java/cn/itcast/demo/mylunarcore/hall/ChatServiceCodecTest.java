package cn.itcast.demo.mylunarcore.hall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ChatService Pub/Sub 编解码测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ChatServiceCodecTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ChatService Pub/Sub 编解码测试")
class ChatServiceCodecTest {

    /**
     * 验证点：世界聊天记录应可往返编解码。
     * <p>测试方法 {@code encodeDecodeRoundTrip}：
     * <ul>
     *   <li>{@code assertNotNull(decoded);}</li>
     *   <li>{@code assertEquals(original, decoded);}</li>
     * </ul>
     */
    @Test
    @DisplayName("世界聊天记录应可往返编解码")
    void encodeDecodeRoundTrip() {
        ChatService.ChatRecord original = new ChatService.ChatRecord(
                0, 1001, "Alice", 0, "hello world", 1_700_000_000_000L);
        String encoded = ChatService.encode(original);
        ChatService.ChatRecord decoded = ChatService.decode(encoded);
        assertNotNull(decoded);
        assertEquals(original, decoded);
    }

    /**
     * 验证点：私聊记录应可往返编解码（跨节点 Pub/Sub）。
     * <p>测试方法 {@code privateChatEncodeDecodeRoundTrip}：
     * <ul>
     *   <li>{@code assertNotNull(decoded);}</li>
     *   <li>{@code assertEquals(original, decoded);}</li>
     *   <li>{@code assertEquals(1, decoded.channelType());}</li>
     *   <li>{@code assertEquals(2002, decoded.targetId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("私聊记录应可往返编解码（跨节点 Pub/Sub）")
    void privateChatEncodeDecodeRoundTrip() {
        ChatService.ChatRecord original = new ChatService.ChatRecord(
                1, 1001, "Alice", 2002, "whisper", 1_700_000_000_100L);
        ChatService.ChatRecord decoded = ChatService.decode(ChatService.encode(original));
        assertNotNull(decoded);
        assertEquals(original, decoded);
        assertEquals(1, decoded.channelType());
        assertEquals(2002, decoded.targetId());
    }
}
