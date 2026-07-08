// 协议包体序列化结果缓存（进程内不变响应）
package cn.itcast.demo.mylunarcore.common;

// 登出响应 Protobuf
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
// 登录服务：复用 RET_OK 常量
import cn.itcast.demo.mylunarcore.player.PlayerSessionService;
// Spring 组件
import org.springframework.stereotype.Component;

// 并发 Map：cmdId -> 已序列化 byte[]
import java.util.concurrent.ConcurrentHashMap;
// IntFunction：懒构建序列化结果
import java.util.function.IntFunction;

/**
 * L1：缓存已序列化的协议包体（byte[]），避免对内容完全相同的响应重复执行 protobuf {@code toByteArray()}。
 * 仅适用于包体在进程内不可变的情形；含时间戳等易变字段的响应不可放入本缓存。
 */
@Component // 注册为 Spring Bean
public class GameServerPacketCache {

    private final ConcurrentHashMap<Integer, byte[]> payloadByCmdId = new ConcurrentHashMap<>(); // 指令号 -> 缓存载荷

    /** 登出成功响应的缓存字段（双重检查锁定初始化） */
    private volatile byte[] playerLogoutOkPayload;

    /**
     * 按 cmdId 缓存静态包体；若已存在则直接返回，否则用 {@code buildPayload} 生成并写入。
     */
    public byte[] getOrPutSerializedPayload(int cmdId, IntFunction<byte[]> buildPayload) {
        return payloadByCmdId.computeIfAbsent(cmdId, buildPayload::apply); // 原子 compute-if-absent
    }

    /**
     * @param cmdId 失效指定指令缓存
     */
    public void invalidateCmd(int cmdId) {
        payloadByCmdId.remove(cmdId);
    }

    /** 登出成功响应包体固定，可安全缓存。 */
    public byte[] playerLogoutOkPayload() {
        byte[] p = playerLogoutOkPayload;
        if (p != null) {
            return p;
        }
        synchronized (this) {
            if (playerLogoutOkPayload == null) {
                playerLogoutOkPayload = PlayerSessionProto.PlayerLogoutScRsp.newBuilder()
                        .setRetcode(PlayerSessionService.RET_OK)
                        .build()
                        .toByteArray(); // 序列化一次常驻内存
            }
            return playerLogoutOkPayload;
        }
    }
}

