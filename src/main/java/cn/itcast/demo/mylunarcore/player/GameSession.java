// 单连接会话对象：绑定 Channel、会话状态与内存玩家数据
package cn.itcast.demo.mylunarcore.player;

// 内存中的玩家聚合数据（L2），含角色、阵容、挑战等子集合
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 下行指令号常量，标识统一同步通知的包类型
import cn.itcast.demo.mylunarcore.net.CmdIds;

// 通用游戏下行封包外壳，封装 cmdId + payload
import cn.itcast.demo.mylunarcore.net.GamePacket;

// 会话相关 Protobuf 消息定义
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

// KCP 连接对象，KCP 模式下非 null
import io.jpower.kcp.netty.Ukcp;

// Lombok：自动生成 getter 方法
import lombok.Getter;

// Lombok：自动生成 setter 方法（用于可变字段）
import lombok.Setter;

// Netty IO 通道，所有下行包经此写出
import io.netty.channel.Channel;

// 原子长整型，保证异步加载版本号线程安全递增
import java.util.concurrent.atomic.AtomicLong;

/**
 * L1 在线会话缓存：单玩家 O(1) 访问（见 {@link GameSessionManager}）。
 * <p>
 * 生命周期随 TCP/KCP 连接存在；存放会话内快照字段与 {@link PlayerData} 引用。
 * 下行发送统一走 {@link #send(GamePacket)}，编码由 Channel Pipeline 完成，避免 ByteBuf 生命周期问题。
 * </p>
 */
@Getter // Lombok：为 final 及普通字段生成 getter
public class GameSession {

    /** 玩家 uid，会话主键，与 GameSessionManager.sessionsByUid 的 key 一致 */
    private final long uid;

    /** Netty 出站通道，客户端连接的唯一 IO 对象 */
    private final Channel channel;

    /**
     * KCP 连接对象；纯 TCP 测试客户端时为 null。
     * 保留引用便于 KCP 层诊断或后续扩展。
     */
    private final Ukcp ukcp;

    /** 玩家昵称快照，登录时写入，供 GetSessionInfo 等轻量查询 */
    @Setter
    private String nickname;

    /** 玩家等级快照，登录时写入 */
    @Setter
    private int level;

    /**
     * 最后一次活跃时间（毫秒时间戳），心跳与业务包会刷新；
     * volatile 保证超时清理线程与 IO 线程之间的可见性。
     */
    @Setter
    private volatile long lastActiveMillis;

    /**
     * 会话令牌，与 {@link GameSessionManager} 中 token 表一致；
     * 顶号或登出时由管理器颁发新 token 并使旧 token 失效。
     */
    @Setter
    private volatile String sessionToken;

    // volatile 写，对其他线程立即可见
    /**
     * 会话挂载的玩家聚合数据引用；
     * 登录时先挂 core 切片，异步全量加载完成后可能被整体替换。
     * -- GETTER --
     *
     *
     * -- SETTER --
     *  替换会话内存中的聚合数据（通常由异步全量加载完成后调用）。
     *
     @return 当前会话内的玩家聚合数据引用（可能仅为 core 切片或尚未异步补全）
      * @param playerData 新的 PlayerData 引用

     */
    @Setter
    @Getter
    private volatile PlayerData playerData;

    /**
     * 全量异步加载版本号：每次触发 reloadFullAsync 前递增，
     * 加载完成时比对版本，丢弃已被更新请求取代的过期结果。
     */
    private final AtomicLong dataLoadVersion = new AtomicLong(0);

    /**
     * 构造会话对象，绑定 uid 与网络通道。
     *
     * @param uid     玩家 uid
     * @param channel Netty 通道
     * @param ukcp    KCP 对象；TCP-only 时传 null
     */
    public GameSession(long uid, Channel channel, Ukcp ukcp) {
        this.uid = uid; // 不可变主键
        this.channel = channel; // 不可变出站通道
        this.ukcp = ukcp; // 不可变 KCP 引用（可为 null）
    }

    /**
     * 写出通用下行包，编码与 flush 由 pipeline 中的 Handler 完成。
     *
     * @param packet 待发送的游戏包
     */
    public void send(GamePacket packet) {
        channel.writeAndFlush(packet); // 异步写入并立即 flush 到网络
    }

    /**
     * 发送玩家统一同步通知：序列化 Protobuf 后封装为 GamePacket 写出。
     *
     * @param notify 已构建或待发送的统一同步 Protobuf 消息
     */
    public void sendPlayerUnifiedSync(PlayerSessionProto.PlayerUnifiedSyncScNotify notify) {
        send(new GamePacket(CmdIds.PLAYER_SYNC_SC_NOTIFY, notify.toByteArray())); // cmdId + protobuf 二进制载荷
    }

    /**
     * 递增并返回新的加载版本号；每次发起异步全量加载前调用。
     *
     * @return 递增后的版本号
     */
    public long nextDataLoadVersion() {
        return dataLoadVersion.incrementAndGet(); // 原子 +1 并返回新值
    }

    /**
     * 判断给定版本号是否仍为当前有效版本（未被更新的加载请求取代）。
     *
     * @param version 异步任务携带的版本号
     * @return 若仍与当前会话版本一致则 true，否则 false（结果应丢弃）
     */
    public boolean isCurrentDataLoadVersion(long version) {
        return dataLoadVersion.get() == version; // 原子读当前版本并比较
    }
}
