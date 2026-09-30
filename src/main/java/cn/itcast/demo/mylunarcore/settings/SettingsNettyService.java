package cn.itcast.demo.mylunarcore.settings; // 设置与客服协议门面所在包

import cn.itcast.demo.mylunarcore.net.CmdIds; // 推送用 PLAYER_SETTINGS_UPDATE_SC_NOTIFY
import cn.itcast.demo.mylunarcore.net.GamePacket; // 封装 cmdId + protobuf 字节
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver; // 从 Channel 解析登录玩家，防伪造 uid
import cn.itcast.demo.mylunarcore.protocol.SettingsSystemProto; // 设置系统生成的 Protobuf 消息
import io.netty.channel.Channel; // 当前客户端连接
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service; // 协议层服务 Bean

/**
 * 游戏设置与客服工单的 Netty 协议门面。
 * <p>负责登录校验、调用应用服务、领域对象与 Protobuf 互转，以及设置变更主动推送。
 */
@Service // 供 SettingsPacketHandlers 注入
public class SettingsNettyService {

    /**
     * 从 Channel 属性解析当前登录玩家的 playerId
     */
    private final PlayerContextResolver contextResolver;
    /**
     * 设置读写、合并更新与重置
     */
    private final PlayerSettingsApplicationService settingsService;
    /**
     * 客服工单提交与列表查询
     */
    private final SupportTicketApplicationService ticketService;
    private final KeyBindCloudService keyBindCloudService;
    private final DeviceHapticsConfigService hapticsConfigService;

    public SettingsNettyService(PlayerContextResolver contextResolver,
                                PlayerSettingsApplicationService settingsService,
                                SupportTicketApplicationService ticketService) {
        this(contextResolver, settingsService, ticketService, null, null);
    }

    @Autowired
    public SettingsNettyService(PlayerContextResolver contextResolver,
                                PlayerSettingsApplicationService settingsService,
                                SupportTicketApplicationService ticketService,
                                ObjectProvider<KeyBindCloudService> keyBindProvider,
                                ObjectProvider<DeviceHapticsConfigService> hapticsProvider) {
        this.contextResolver = contextResolver;
        this.settingsService = settingsService;
        this.ticketService = ticketService;
        this.keyBindCloudService = keyBindProvider == null ? null : keyBindProvider.getIfAvailable();
        this.hapticsConfigService = hapticsProvider == null ? null : hapticsProvider.getIfAvailable();
    }

    /** 兼容旧双参 ObjectProvider 构造 */
    public SettingsNettyService(PlayerContextResolver contextResolver,
                                PlayerSettingsApplicationService settingsService,
                                SupportTicketApplicationService ticketService,
                                ObjectProvider<KeyBindCloudService> keyBindProvider) {
        this(contextResolver, settingsService, ticketService, keyBindProvider, null);
    }

    /**
     * 处理拉取设置：未登录 retcode=1；成功 retcode=0 并带回完整快照（无档则先建默认）
     */
    public SettingsSystemProto.GetPlayerSettingsScRsp handleGetPlayerSettings(
            SettingsSystemProto.GetPlayerSettingsCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析会话玩家
        if (playerId <= 0) { // 未登录或会话未绑定
            return SettingsSystemProto.GetPlayerSettingsScRsp.newBuilder().setRetcode(1).build(); // retcode=1
        } // 登录校验结束
        PlayerSettings settings = settingsService.getOrCreate(playerId); // 读库或首次建档
        pushKeyBind(channel, playerId, "", "login");
        return SettingsSystemProto.GetPlayerSettingsScRsp.newBuilder()
                .setRetcode(0) // 成功
                .setSettings(toProto(settings)) // 领域对象转协议快照
                .build(); // 构建响应
    } // handleGetPlayerSettings 结束

    /**
     * 处理更新设置：合并分区后落库，推送 SETTINGS_UPDATE 通知，回包带回最新快照
     */
    public SettingsSystemProto.UpdatePlayerSettingsScRsp handleUpdatePlayerSettings(
            SettingsSystemProto.UpdatePlayerSettingsCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析玩家
        if (playerId <= 0) { // 未登录
            return SettingsSystemProto.UpdatePlayerSettingsScRsp.newBuilder().setRetcode(1).build(); // retcode=1
        } // 登录校验结束
        PlayerSettings incoming = fromProto(req.hasSettings() ? req.getSettings() : null); // 协议→领域；未带 settings 则为 null
        PlayerSettings updated = settingsService.update(
                playerId, // 目标玩家
                incoming, // 待合并入参
                req.getResetDisplay(), // 是否顺带重置画面
                req.getResetSound(), // 是否重置声音
                req.getResetKeybinds(), // 是否重置按键
                req.getResetGameplay()); // 是否重置细节
        notifySettingsChanged(channel, updated, "update"); // 主动推送，便于其它界面即时刷新
        return SettingsSystemProto.UpdatePlayerSettingsScRsp.newBuilder()
                .setRetcode(0) // 成功
                .setSettings(toProto(updated)) // 回显最终设置
                .build(); // 构建响应
    } // handleUpdatePlayerSettings 结束

    /**
     * 处理重置设置：全量或按分区恢复默认，并推送 reason=reset 的变更通知
     */
    public SettingsSystemProto.ResetPlayerSettingsScRsp handleResetPlayerSettings(
            SettingsSystemProto.ResetPlayerSettingsCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析玩家
        if (playerId <= 0) { // 未登录
            return SettingsSystemProto.ResetPlayerSettingsScRsp.newBuilder().setRetcode(1).build(); // retcode=1
        } // 登录校验结束
        PlayerSettings reset = settingsService.reset(
                playerId,
                req.getResetAll(), // true=四分区全部默认
                req.getResetDisplay(),
                req.getResetSound(),
                req.getResetKeybinds(),
                req.getResetGameplay());
        notifySettingsChanged(channel, reset, "reset"); // 推送重置后的快照
        return SettingsSystemProto.ResetPlayerSettingsScRsp.newBuilder()
                .setRetcode(0)
                .setSettings(toProto(reset))
                .build(); // 回包
    } // handleResetPlayerSettings 结束

    /**
     * 处理提交客服工单：未登录 retcode=1；否则透传应用层 retcode 与 ticketId
     */
    public SettingsSystemProto.SubmitSupportTicketScRsp handleSubmitSupportTicket(
            SettingsSystemProto.SubmitSupportTicketCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析玩家
        if (playerId <= 0) { // 未登录
            return SettingsSystemProto.SubmitSupportTicketScRsp.newBuilder().setRetcode(1).build(); // retcode=1
        } // 登录校验结束
        SupportTicketApplicationService.SubmitResult result = ticketService.submit(
                playerId, req.getCategory(), req.getSubject(), req.getContent()); // 校验并落库
        return SettingsSystemProto.SubmitSupportTicketScRsp.newBuilder()
                .setRetcode(result.retcode()) // 0 成功，2/3/4 为业务失败码
                .setTicketId(result.ticketId()) // 成功时非 0
                .build(); // 构建响应
    } // handleSubmitSupportTicket 结束

    public SettingsSystemProto.SyncKeyBindScRsp handleSyncKeyBind(SettingsSystemProto.SyncKeyBindCsReq req,
                                                                  Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return SettingsSystemProto.SyncKeyBindScRsp.newBuilder().setRetcode(1).build();
        }
        if (req.getKeybindsCount() <= 0) {
            return SettingsSystemProto.SyncKeyBindScRsp.newBuilder().setRetcode(2).build();
        }
        java.util.List<KeyBindCloudService.Bind> binds = new java.util.ArrayList<>();
        java.util.List<PlayerSettings.KeyBinding> domain = new java.util.ArrayList<>();
        for (SettingsSystemProto.KeyBinding kb : req.getKeybindsList()) {
            String device = kb.getDeviceType().isBlank() ? req.getDeviceType() : kb.getDeviceType();
            binds.add(new KeyBindCloudService.Bind(kb.getActionId(), kb.getKeyCode(), device,
                    kb.getScale(), kb.getPosX(), kb.getPosY()));
            domain.add(new PlayerSettings.KeyBinding(kb.getActionId(), kb.getKeyCode(), device,
                    kb.getScale(), kb.getPosX(), kb.getPosY()));
        }
        if (keyBindCloudService != null) {
            keyBindCloudService.save(playerId, req.getDeviceType(), binds);
        }
        PlayerSettings incoming = new PlayerSettings();
        incoming.setKeybinds(domain);
        settingsService.update(playerId, incoming, false, false, false, false);
        pushKeyBind(channel, playerId, req.getDeviceType(), "sync");
        SettingsSystemProto.SyncKeyBindScRsp.Builder b = SettingsSystemProto.SyncKeyBindScRsp.newBuilder().setRetcode(0);
        for (KeyBindCloudService.Bind bind : binds) {
            b.addKeybinds(toProtoBind(bind));
        }
        return b.build();
    }

    public SettingsSystemProto.GetDeviceHapticsScRsp handleGetDeviceHaptics(
            SettingsSystemProto.GetDeviceHapticsCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return SettingsSystemProto.GetDeviceHapticsScRsp.newBuilder().setRetcode(1).build();
        }
        String family = req.getDeviceFamily() == null || req.getDeviceFamily().isBlank()
                ? "generic" : req.getDeviceFamily();
        SettingsSystemProto.GetDeviceHapticsScRsp.Builder b =
                SettingsSystemProto.GetDeviceHapticsScRsp.newBuilder()
                        .setRetcode(0)
                        .setDeviceFamily(family);
        if (hapticsConfigService != null) {
            for (DeviceHapticsConfigService.WaveformCfg w : hapticsConfigService.forFamily(family)) {
                SettingsSystemProto.HapticWaveform.Builder wb = SettingsSystemProto.HapticWaveform.newBuilder()
                        .setWaveformId(w.waveformId())
                        .setDeviceFamily(w.deviceFamily() == null ? family : w.deviceFamily())
                        .setName(w.name() == null ? "" : w.name())
                        .setDurationMs(w.durationMs())
                        .setTriggerForce(w.triggerForce());
                if (w.amplitudes() != null) {
                    wb.addAllAmplitudes(w.amplitudes());
                }
                b.addWaveforms(wb.build());
            }
        }
        if (channel != null && channel.isActive() && b.getWaveformsCount() > 0) {
            SettingsSystemProto.PushDeviceHapticsScNotify.Builder n =
                    SettingsSystemProto.PushDeviceHapticsScNotify.newBuilder()
                            .setDeviceFamily(family)
                            .setReason("login");
            n.addAllWaveforms(b.getWaveformsList());
            channel.writeAndFlush(new GamePacket(CmdIds.PUSH_DEVICE_HAPTICS_SC_NOTIFY, n.build().toByteArray()));
        }
        return b.build();
    }

    /**
     * 处理查询本人工单列表；页码/页大小非法时回退为第 1 页、每页 20 条
     */
    public SettingsSystemProto.GetSupportTicketListScRsp handleGetSupportTicketList(
            SettingsSystemProto.GetSupportTicketListCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析玩家
        if (playerId <= 0) { // 未登录
            return SettingsSystemProto.GetSupportTicketListScRsp.newBuilder().setRetcode(1).build(); // retcode=1
        } // 登录校验结束
        int page = req.getPage() <= 0 ? 1 : req.getPage(); // 非法页码按第 1 页
        int pageSize = req.getPageSize() <= 0 ? 20 : req.getPageSize(); // 非法页大小按 20
        SupportTicketApplicationService.ListResult result =
                ticketService.listForPlayer(playerId, page, pageSize); // 查本页与总数
        SettingsSystemProto.GetSupportTicketListScRsp.Builder builder =
                SettingsSystemProto.GetSupportTicketListScRsp.newBuilder()
                        .setRetcode(0) // 成功
                        .setTotal(result.total()); // 分页总数
        for (SupportTicketEntity ticket : result.tickets()) { // 逐条转协议
            builder.addTickets(toProtoTicket(ticket)); // 追加到 repeated tickets
        } // 循环结束
        return builder.build(); // 完成列表响应
    } // handleGetSupportTicketList 结束

    /**
     * 向当前连接推送设置变更通知，reason 标明来源（update / reset）
     */
    private void notifySettingsChanged(Channel channel, PlayerSettings settings, String reason) {
        SettingsSystemProto.PlayerSettingsUpdateScNotify notify =
                SettingsSystemProto.PlayerSettingsUpdateScNotify.newBuilder()
                        .setSettings(toProto(settings)) // 最新完整快照
                        .setReason(reason == null ? "" : reason) // 变更原因文案
                        .build(); // 构建推送体
        channel.writeAndFlush(new GamePacket(CmdIds.PLAYER_SETTINGS_UPDATE_SC_NOTIFY, notify.toByteArray())); // 下行推送
    } // notifySettingsChanged 结束

    /**
     * 领域设置 → Protobuf 快照；先 sanitize 保证下发数值合法
     */
    static SettingsSystemProto.PlayerSettingsSnapshot toProto(PlayerSettings settings) {
        PlayerSettings s = PlayerSettingsDefaults.sanitize(settings); // 夹紧后再编码
        SettingsSystemProto.PlayerSettingsSnapshot.Builder builder =
                SettingsSystemProto.PlayerSettingsSnapshot.newBuilder()
                        .setDisplay(SettingsSystemProto.DisplaySettings.newBuilder()
                                .setGraphicsQuality(s.getDisplay().getGraphicsQuality()) // 画质档
                                .setResolutionScale(s.getDisplay().getResolutionScale()) // 分辨率缩放
                                .setVsync(s.getDisplay().isVsync()) // 垂直同步
                                .setBrightness(s.getDisplay().getBrightness()) // 亮度
                                .setFullscreen(s.getDisplay().isFullscreen()) // 全屏
                                .setAntiAliasing(s.getDisplay().isAntiAliasing()) // 抗锯齿
                                .build()) // 画面消息结束
                        .setSound(SettingsSystemProto.SoundSettings.newBuilder()
                                .setMasterVolume(s.getSound().getMasterVolume()) // 主音量
                                .setMusicVolume(s.getSound().getMusicVolume()) // 音乐
                                .setSfxVolume(s.getSound().getSfxVolume()) // 音效
                                .setVoiceVolume(s.getSound().getVoiceVolume()) // 语音
                                .setMuted(s.getSound().isMuted()) // 静音
                                .build()) // 声音消息结束
                        .setGameplay(SettingsSystemProto.GameplaySettings.newBuilder()
                                .setLanguage(s.getGameplay().getLanguage()) // 语言
                                .setCameraSensitivity(s.getGameplay().getCameraSensitivity()) // 灵敏度
                                .setAutoBattle(s.getGameplay().isAutoBattle()) // 自动战斗
                                .setShowDamageNumbers(s.getGameplay().isShowDamageNumbers()) // 伤害数字
                                .setSkipStoryCutscene(s.getGameplay().isSkipStoryCutscene()) // 跳过剧情
                                .setVibration(s.getGameplay().isVibration()) // 震动
                                .setBattleSpeed(s.getGameplay().getBattleSpeed()) // 战斗倍速
                                .build()); // 细节消息结束
        for (PlayerSettings.KeyBinding kb : s.getKeybinds()) { // 逐条键位
            builder.addKeybinds(SettingsSystemProto.KeyBinding.newBuilder()
                    .setActionId(kb.getActionId())
                    .setKeyCode(kb.getKeyCode())
                    .setDeviceType(kb.getDeviceType() == null ? "" : kb.getDeviceType())
                    .setScale(kb.getScale())
                    .setPosX(kb.getPosX())
                    .setPosY(kb.getPosY())
                    .build());
        } // 键位循环结束
        return builder.build(); // 完整快照
    } // toProto 结束

    /**
     * Protobuf 快照 → 领域对象。
     * <p>未 hasXxx 的分区设为 null，供 update 合并时「跳过该分区」；
     * 键位列表长度为 0 也视为未携带（设 null），避免空列表误清空自定义键位。
     */
    static PlayerSettings fromProto(SettingsSystemProto.PlayerSettingsSnapshot snapshot) {
        if (snapshot == null) { // 请求未带 settings 字段
            return null; // 上层按「仅可能带 reset 标志」处理
        } // null 结束
        PlayerSettings settings = new PlayerSettings(); // 组装入参聚合
        if (snapshot.hasDisplay()) { // 客户端显式携带了画面
            SettingsSystemProto.DisplaySettings d = snapshot.getDisplay(); // 协议画面
            PlayerSettings.DisplaySettings display = new PlayerSettings.DisplaySettings(); // 领域画面
            display.setGraphicsQuality(d.getGraphicsQuality()); // 拷贝画质
            display.setResolutionScale(d.getResolutionScale()); // 拷贝缩放
            display.setVsync(d.getVsync()); // 拷贝 VSync
            display.setBrightness(d.getBrightness()); // 拷贝亮度
            display.setFullscreen(d.getFullscreen()); // 拷贝全屏
            display.setAntiAliasing(d.getAntiAliasing()); // 拷贝抗锯齿
            settings.setDisplay(display); // 写入携带的画面
        } else { // 未携带画面
            settings.setDisplay(null); // null=合并时保留库中画面
        } // 画面分支结束
        if (snapshot.hasSound()) { // 携带了声音
            SettingsSystemProto.SoundSettings s = snapshot.getSound();
            PlayerSettings.SoundSettings sound = new PlayerSettings.SoundSettings();
            sound.setMasterVolume(s.getMasterVolume());
            sound.setMusicVolume(s.getMusicVolume());
            sound.setSfxVolume(s.getSfxVolume());
            sound.setVoiceVolume(s.getVoiceVolume());
            sound.setMuted(s.getMuted());
            settings.setSound(sound); // 写入声音
        } else {
            settings.setSound(null); // 未携带则跳过声音分区
        } // 声音分支结束
        if (snapshot.getKeybindsCount() > 0) { // 至少一条键位才算「携带了按键表」
            settings.setKeybinds(snapshot.getKeybindsList().stream()
                    .map(kb -> new PlayerSettings.KeyBinding(kb.getActionId(), kb.getKeyCode(),
                            kb.getDeviceType(), kb.getScale(), kb.getPosX(), kb.getPosY()))
                    .toList()); // 收集列表
        } else {
            settings.setKeybinds(null); // 空列表不覆盖原键位
        } // 按键分支结束
        if (snapshot.hasGameplay()) { // 携带了游戏细节
            SettingsSystemProto.GameplaySettings g = snapshot.getGameplay();
            PlayerSettings.GameplaySettings gameplay = new PlayerSettings.GameplaySettings();
            gameplay.setLanguage(g.getLanguage());
            gameplay.setCameraSensitivity(g.getCameraSensitivity());
            gameplay.setAutoBattle(g.getAutoBattle());
            gameplay.setShowDamageNumbers(g.getShowDamageNumbers());
            gameplay.setSkipStoryCutscene(g.getSkipStoryCutscene());
            gameplay.setVibration(g.getVibration());
            gameplay.setBattleSpeed(g.getBattleSpeed());
            settings.setGameplay(gameplay); // 写入细节
        } else {
            settings.setGameplay(null); // 未携带则保留原细节
        } // 细节分支结束
        return settings; // 供 update 合并
    } // fromProto 结束

    /**
     * 工单实体 → 协议 SupportTicketInfo；null 字符串转空串，时间戳转 epoch 毫秒
     */
    private static SettingsSystemProto.SupportTicketInfo toProtoTicket(SupportTicketEntity ticket) {
        return SettingsSystemProto.SupportTicketInfo.newBuilder()
                .setTicketId(ticket.getId()) // 工单号
                .setCategory(ticket.getCategory() == null ? "" : ticket.getCategory()) // 分类
                .setSubject(ticket.getSubject() == null ? "" : ticket.getSubject()) // 标题
                .setContent(ticket.getContent() == null ? "" : ticket.getContent()) // 正文
                .setStatus(ticket.getStatus()) // 状态码
                .setAdminReply(ticket.getAdminReply() == null ? "" : ticket.getAdminReply()) // 客服回复
                .setCreatedAtMs(toEpochMs(ticket.getCreatedAt())) // 创建时间毫秒
                .setUpdatedAtMs(toEpochMs(ticket.getUpdatedAt())) // 更新时间毫秒
                .build(); // 单条工单协议对象
    } // toProtoTicket 结束

    /**
     * Timestamp 转 epoch 毫秒；null 返回 0，避免客户端 NPE
     */
    private static long toEpochMs(java.sql.Timestamp ts) {
        return ts == null ? 0L : ts.getTime(); // JDBC 时间 → 毫秒时间戳
    } // toEpochMs 结束

    private void pushKeyBind(Channel channel, int playerId, String deviceType, String reason) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        java.util.List<KeyBindCloudService.Bind> binds = keyBindCloudService == null
                ? java.util.List.of() : keyBindCloudService.load(playerId, deviceType);
        SettingsSystemProto.PushKeyBindScNotify.Builder n = SettingsSystemProto.PushKeyBindScNotify.newBuilder()
                .setDeviceType(deviceType == null ? "" : deviceType)
                .setReason(reason == null ? "" : reason);
        if (binds.isEmpty()) {
            PlayerSettings settings = settingsService.getOrCreate(playerId);
            for (PlayerSettings.KeyBinding kb : settings.getKeybinds()) {
                n.addKeybinds(SettingsSystemProto.KeyBinding.newBuilder()
                        .setActionId(kb.getActionId())
                        .setKeyCode(kb.getKeyCode())
                        .setDeviceType(kb.getDeviceType() == null ? "" : kb.getDeviceType())
                        .setScale(kb.getScale())
                        .setPosX(kb.getPosX())
                        .setPosY(kb.getPosY())
                        .build());
            }
        } else {
            for (KeyBindCloudService.Bind b : binds) {
                n.addKeybinds(toProtoBind(b));
            }
        }
        channel.writeAndFlush(new GamePacket(CmdIds.PUSH_KEY_BIND_SC_NOTIFY, n.build().toByteArray()));
    }

    private static SettingsSystemProto.KeyBinding toProtoBind(KeyBindCloudService.Bind b) {
        return SettingsSystemProto.KeyBinding.newBuilder()
                .setActionId(b.actionId())
                .setKeyCode(b.keyCode())
                .setDeviceType(b.deviceType())
                .setScale(b.scale())
                .setPosX(b.posX())
                .setPosY(b.posY())
                .build();
    }
} // SettingsNettyService 结束
