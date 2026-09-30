// 版本/资源热更新相关的 Netty 协议门面：响应查询请求与主动推送通知
package cn.itcast.demo.mylunarcore.common;

// 协议指令号常量（VERSION_UPDATE_SC_NOTIFY 等）
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 通用协议包封装：cmdId + 序列化字节
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 版本更新协议消息生成类
import cn.itcast.demo.mylunarcore.protocol.VersionUpdateProto;
// Netty 通道：向具体客户端连接写数据
import io.netty.channel.Channel;
// SLF4J 日志接口
import org.slf4j.Logger;
// 注册为 Spring 业务服务
import org.springframework.stereotype.Service;

// 校验问题列表
import java.util.List;

/**
 * 版本/资源热更新 Netty 推送与查询。
 * <p>承接客户端 {@code GET_VERSION_INFO_CS_REQ} 查询与服务器主动的
 * {@code VERSION_UPDATE_SC_NOTIFY} 推送，统一使用 {@link HotfixDataService} 当前数据。</p>
 */
@Service
public class VersionNettyService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, VersionNettyService.class);
    // 协议约定成功码：客户端收到 0 表示查询成功
    public static final int RET_OK = 0;

    // 热修复数据源：提供当前生效的 HotfixData
    private final HotfixDataService hotfixDataService;
    // 映射器：HotfixData → 协议消息
    private final VersionUpdateMapper versionUpdateMapper;

    /**
     * 构造器注入数据服务与映射器。
     */
    public VersionNettyService(HotfixDataService hotfixDataService, VersionUpdateMapper versionUpdateMapper) {
        this.hotfixDataService = hotfixDataService;
        this.versionUpdateMapper = versionUpdateMapper;
    }

    /**
     * 处理客户端版本信息查询：返回当前热修复版本与文件清单。
     *
     * @return GetVersionInfoScRsp（retcode=0，version 字段承载完整热更信息）
     */
    public VersionUpdateProto.GetVersionInfoScRsp handleGetVersionInfo() {
        HotfixData data = hotfixDataService.current();
        // 推送前校验 manifest 完整性；有告警只记日志，不阻塞返回（客户端可自行容错）
        List<String> issues = versionUpdateMapper.validateManifest(data);
        if (!issues.isEmpty()) {
            log.warn("Version manifest validation issues: {}", issues);
        }
        return VersionUpdateProto.GetVersionInfoScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setVersion(versionUpdateMapper.toNotify(data))
                .build();
    }

    /**
     * 主动向单个连接推送版本更新通知（热更成功或新连接登录引导时调用）。
     *
     * @param channel 目标连接；为 null 或已失效时静默跳过
     */
    public void pushVersionUpdateNotify(Channel channel) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        try {
            // 用当前最新数据组包并异步写出（writeAndFlush 由 Netty 事件循环调度）
            VersionUpdateProto.VersionUpdateScNotify notify = versionUpdateMapper.toNotify(hotfixDataService.current());
            channel.writeAndFlush(new GamePacket(CmdIds.VERSION_UPDATE_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception e) {
            // 单连接推送失败不影响其它连接；记 debug 便于排障
            log.debug("pushVersionUpdateNotify failed", e);
        }
    }
}
