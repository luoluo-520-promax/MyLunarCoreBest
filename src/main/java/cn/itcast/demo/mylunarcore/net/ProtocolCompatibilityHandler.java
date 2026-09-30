package cn.itcast.demo.mylunarcore.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.springframework.stereotype.Component;

/**
 * 协议兼容层：灰度期内将历史 CmdId 重映射到现行号段，并拒绝不兼容的大版本。
 * <p>
 * 升级流程：
 * <ol>
 *   <li>服务端发布支持 N 与 N-1（本 Handler + {@link ProtocolCompatService}）</li>
 *   <li>客户端灰度升级到 N</li>
 *   <li>观察无 N-1 流量后，下一版本可移除垫片</li>
 * </ol>
 * 破坏性变更由 CI {@code buf breaking} 拦截。
 */
@Component
public class ProtocolCompatibilityHandler extends ChannelInboundHandlerAdapter {

    public static final String ATTR_CLIENT_WIRE = "client_wire_version";

    private final ProtocolCompatService compatService;

    public ProtocolCompatibilityHandler(ProtocolCompatService compatService) {
        this.compatService = compatService;
    }

    /**
     * 登录握手后写入客户端声明的 wire version；业务包可经此重映射。
     */
    public boolean acceptClientVersion(int clientWireVersion) {
        return compatService.isCompatible(clientWireVersion);
    }

    /**
     * 将可能的 legacy CmdId 映射为现行 CmdId；未知则原样返回。
     */
    public int remapCmdId(int cmdId, int clientWireVersion) {
        if (clientWireVersion >= compatService.currentWireVersion()) {
            return cmdId;
        }
        Integer mapped = compatService.legacyCharacterCmdRemap().get(cmdId);
        return mapped != null ? mapped : cmdId;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // 帧解码后业务 Handler 主动调用 remapCmdId；此处透传以保持 pipeline 可插拔
        super.channelRead(ctx, msg);
    }
}
