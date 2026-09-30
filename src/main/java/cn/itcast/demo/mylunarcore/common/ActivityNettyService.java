package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.mapper.ActivityProtoMapper;
import cn.itcast.demo.mylunarcore.protocol.ActivitySystemProto;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 活动配置 Netty 协议门面：仅做 protobuf 组包与推送，业务查询交给 {@link ActivityQueryService}。
 */
@Service
public class ActivityNettyService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityNettyService.class);
    public static final int RET_OK = 0;
    /** 活动未开始（普通玩家看不到未来版本时返回空列表 + 此码语义由客户端解释） */
    public static final int RET_NOT_STARTED = 2;

    private final ActivityQueryService activityQueryService;
    private final ActivityProtoMapper activityProtoMapper;

    public ActivityNettyService(ActivityQueryService activityQueryService, ActivityProtoMapper activityProtoMapper) {
        this.activityQueryService = activityQueryService;
        this.activityProtoMapper = activityProtoMapper;
    }

    public ActivitySystemProto.GetActivityInfoScRsp handleGetActivityInfo(int activityId) {
        return handleGetActivityInfo(activityId, false);
    }

    public ActivitySystemProto.GetActivityInfoScRsp handleGetActivityInfo(int activityId, boolean qaTester) {
        List<ActivityConfig> configs = activityQueryService.resolveActivities(activityId, qaTester);
        ActivitySystemProto.GetActivityInfoScRsp.Builder builder = ActivitySystemProto.GetActivityInfoScRsp.newBuilder()
                .setRetcode(RET_OK);
        if (activityId > 0 && configs.isEmpty() && !qaTester) {
            builder.setRetcode(RET_NOT_STARTED);
        }
        for (ActivityConfig config : configs) {
            builder.addActivities(activityProtoMapper.toProto(config));
        }
        return builder.build();
    }

    public void pushActivityConfigUpdateNotify(Channel channel) {
        pushActivityConfigUpdateNotify(channel, false);
    }

    public void pushActivityConfigUpdateNotify(Channel channel, boolean qaTester) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        try {
            List<ActivityConfig> active = activityQueryService.listCurrentlyActiveConfigs(qaTester);
            ActivitySystemProto.ActivityConfigUpdateScNotify notify = ActivitySystemProto.ActivityConfigUpdateScNotify.newBuilder()
                    .addAllUpdatedActivities(activityProtoMapper.toProtoList(active))
                    .build();
            channel.writeAndFlush(new GamePacket(CmdIds.ACTIVITY_CONFIG_UPDATE_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception e) {
            log.debug("pushActivityConfigUpdateNotify failed", e);
        }
    }

    public void pushActivityConfigUpdateNotify(Channel channel, List<ActivityConfig> configs) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        try {
            ActivitySystemProto.ActivityConfigUpdateScNotify notify = ActivitySystemProto.ActivityConfigUpdateScNotify.newBuilder()
                    .addAllUpdatedActivities(activityProtoMapper.toProtoList(configs))
                    .build();
            channel.writeAndFlush(new GamePacket(CmdIds.ACTIVITY_CONFIG_UPDATE_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception e) {
            log.debug("pushActivityConfigUpdateNotify failed", e);
        }
    }
}
