// 抽卡 Netty 协议业务层：处理客户端抽卡相关 protobuf 请求，负责概率计算、保底状态推进与响应组包
package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.character.ConstellationService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.model.PlayerGachaBannerInfoEntity;
import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.DataChangeScope;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService;
import cn.itcast.demo.mylunarcore.protocol.GachaSystemProto;
import cn.itcast.demo.mylunarcore.repo.GachaHistoryRepository;
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 抽卡系统 Netty 协议实现层。
 * <p>
 * 职责划分：
 * <ul>
 *   <li>本类：protobuf 请求解析、抽卡概率模拟、保底状态内存推进、响应组包、热更推送</li>
 *   <li>{@link GachaApplicationService}：抽卡结果与保底状态的事务性数据库持久化</li>
 *   <li>{@link GachaConfigService}：卡池 Banner 配置读取与时间窗筛选</li>
 * </ul>
 * </p>
 * <p>
 * 协议返回码（retcode）定义：
 * 0=成功；1=会话无效（Channel 未绑定 playerId）；
 * 2=参数非法（抽卡次数非 1/10 或 targetAvatarId ≤ 0）；
 * 3=卡池不存在（当前时间无开放中的对应类型卡池）；
 * 4=300 抽未达（ceilingNum &lt; 300）；5=300 抽已领（ceilingClaimed=true）。
 * </p>
 */
@Service
public class GachaNettyService {

    /** 抽卡业务专用日志记录器 */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_GACHA, GachaNettyService.class);
    /** 协议成功返回码 */
    private static final int RET_OK = 0;
    /** Channel 未绑定有效 playerId，玩家未登录或会话已失效 */
    private static final int RET_SESSION_INVALID = 1;
    /** 请求参数非法：抽卡次数非 1/10，或兑换目标角色 ID ≤ 0 等 */
    private static final int RET_BAD_REQUEST = 2;
    /** 当前时间无开放中的对应类型卡池（配置未加载或时间窗未覆盖） */
    private static final int RET_CONFIG_NOT_FOUND = 3;
    /** 常驻累计抽数不足 300，不满足保底兑换条件 */
    private static final int RET_CEILING_NOT_REACHED = 4;
    /** 本周期 300 抽保底已兑换过，不可重复领取 */
    private static final int RET_CEILING_ALREADY_CLAIMED = 5;
    /** 抽卡货币不足 */
    private static final int RET_INSUFFICIENT = 6;
    /** 单次抽卡默认消耗的道具模板 ID（如专票/星轨票），展示在 Banner 信息中 */
    private static final int COST_ITEM_ID_DEFAULT = 101;
    /** 单次抽卡默认消耗道具数量 */
    private static final int COST_COUNT_DEFAULT = 1;

    /** 卡池 Banner 配置读取与时间窗筛选服务 */
    private final GachaConfigService configService;
    /** 玩家保底与 ceiling 状态持久化仓储 */
    private final GachaRepository gachaRepository;
    /** 道具仓储：判断 isNew、抽卡发奖前查重 */
    private final ItemRepository itemRepository;
    /** 抽卡结果与保底状态的事务性写库编排服务 */
    private final GachaApplicationService gachaApplicationService;
    /** 抽卡历史查询 */
    private final GachaHistoryRepository gachaHistoryRepository;
    /** 抽卡概率引擎（领域算法） */
    private final GachaDrawEngine drawEngine;
    /** 从 Netty Channel 解析 playerId / uid 的上下文解析器 */
    private final PlayerContextResolver contextResolver;
    /** 抽卡数据变更后通知客户端刷新 GACHA 维度缓存 */
    private final PlayerDataSyncService playerDataSyncService;
    private final GachaPresentationService presentationService;
    private final GachaRebateService rebateService;
    private final ObjectProvider<cn.itcast.demo.mylunarcore.assist.memory.AssistGachaMemoryHook> gachaMemoryHookProvider;
    private final ConstellationService constellationService;

    public GachaNettyService(GachaConfigService configService,
                             GachaRepository gachaRepository,
                             ItemRepository itemRepository,
                             GachaApplicationService gachaApplicationService,
                             GachaHistoryRepository gachaHistoryRepository,
                             GachaDrawEngine drawEngine,
                             PlayerContextResolver contextResolver,
                             PlayerDataSyncService playerDataSyncService,
                             ObjectProvider<GachaPresentationService> presentationProvider,
                             ObjectProvider<GachaRebateService> rebateProvider,
                             ObjectProvider<cn.itcast.demo.mylunarcore.assist.memory.AssistGachaMemoryHook> gachaMemoryHookProvider,
                             ObjectProvider<ConstellationService> constellationProvider) {
        this.configService = configService;
        this.gachaRepository = gachaRepository;
        this.itemRepository = itemRepository;
        this.gachaApplicationService = gachaApplicationService;
        this.gachaHistoryRepository = gachaHistoryRepository;
        this.drawEngine = drawEngine;
        this.contextResolver = contextResolver;
        this.playerDataSyncService = playerDataSyncService;
        this.presentationService = presentationProvider == null ? null : presentationProvider.getIfAvailable();
        this.rebateService = rebateProvider == null ? null : rebateProvider.getIfAvailable();
        this.gachaMemoryHookProvider = gachaMemoryHookProvider;
        this.constellationService = constellationProvider == null ? null : constellationProvider.getIfAvailable();
    }

    /**
     * 从 Netty Channel 解析当前登录玩家的 playerId（即 uid）。
     *
     * @param channel 客户端连接通道
     * @return 玩家 ID，未登录时返回 ≤ 0
     */
    private int getPlayerId(Channel channel) {
        return contextResolver.resolvePlayerId(channel);
    }

    /**
     * 抽卡数据变更后，若玩家仍在线则通知客户端刷新 GACHA 维度缓存。
     * 客户端收到通知后会重新拉取或增量同步抽卡相关数据。
     *
     * @param channel 客户端连接通道
     */
    private void notifyDataChangedIfOnline(Channel channel) {
        // 解析 uid（字符串形式玩家标识），存在时才发送变更通知
        contextResolver.resolveUid(channel)
                .ifPresent(uid -> playerDataSyncService.notifyDataChanged(uid, DataChangeScope.GACHA));
    }

    /**
     * 处理 GetGachaInfo 请求：返回四类卡池的当前开放 Banner 及玩家保底快照。
     * <p>
     * 客户端打开抽卡界面时调用，用于展示卡池列表、UP 角色、保底进度与 300 抽 ceiling 状态。
     * </p>
     *
     * @param channel 客户端连接通道
     * @return GetGachaInfoScRsp 协议响应，含 banners 列表与 ceilingInfo
     */
    public GachaSystemProto.GetGachaInfoScRsp handleGetGachaInfo(Channel channel) {
        // 从 Channel 解析玩家 ID
        int playerId = getPlayerId(channel);
        // 未登录或会话无效，直接返回错误码
        if (playerId <= 0) {
            return GachaSystemProto.GetGachaInfoScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }
        // 当前 Unix 时间戳（秒），用于筛选开放中的卡池
        long now = System.currentTimeMillis() / 1000L;
        // 存放四类卡池的 Banner 信息
        List<GachaSystemProto.GachaBannerInfo> banners = new ArrayList<>();
        // 按卡池类型依次追加当前开放中的 Banner（含 UP 列表、消耗、保底进度）
        addBannerInfos(banners, playerId, GachaBannerType.NEWBIE, now);     // 新手池
        addBannerInfos(banners, playerId, GachaBannerType.NORMAL, now);     // 常驻池
        addBannerInfos(banners, playerId, GachaBannerType.AVATAR_UP, now);  // 角色 UP 池
        addBannerInfos(banners, playerId, GachaBannerType.WEAPON_UP, now);  // 武器/光锥 UP 池
        // 加载玩家全局 300 抽 ceiling 进度
        PlayerGachaInfoEntity ceiling = gachaRepository.loadOrCreateGachaInfo(playerId);
        // 组装 CeilingInfo 协议消息
        GachaSystemProto.CeilingInfo ceilingInfo = GachaSystemProto.CeilingInfo.newBuilder()
                .setCeilingNum(ceiling == null ? 0 : Math.max(0, ceiling.getCeilingNum()))       // 累计抽数
                .setCeilingClaimed(ceiling != null && ceiling.isCeilingClaimed())                // 是否已兑换
                .build();
        // 组装并返回完整响应
        return GachaSystemProto.GetGachaInfoScRsp.newBuilder()
                .setRetcode(RET_OK)
                .addAllBanners(banners)
                .setCeilingInfo(ceilingInfo)
                .build();
    }

    /**
     * 若指定卡池类型存在当前开放的 Banner，则读取玩家该池保底状态并组装 GachaBannerInfo 追加到 out。
     *
     * @param out        输出列表，调用方传入的空 ArrayList
     * @param playerId   玩家 ID
     * @param bannerType 卡池类型（新手/常驻/角色UP/武器UP）
     * @param nowSeconds 当前 Unix 时间戳（秒）
     */
    private void addBannerInfos(List<GachaSystemProto.GachaBannerInfo> out, int playerId, int bannerType, long nowSeconds) {
        // 从配置服务选取当前时间开放的 Banner
        GachaBannerConfig active = configService.pickActiveBanner(bannerType, nowSeconds);
        // 该类型当前无开放卡池，跳过不追加
        if (active == null) {
            return;
        }
        // 读取该玩家在此 bannerType 下的保底计数（数据库不存在记录时 loadOrCreate 返回新实体）
        PlayerGachaBannerInfoEntity pity = gachaRepository.loadOrCreateBannerInfo(playerId, bannerType);
        // 5 星保底计数：距离上次 5 星已抽次数，90 抽硬保底
        int pity5 = pity == null ? 0 : Math.max(0, pity.getPity5());
        // 4 星保底计数：距离上次 4 星已抽次数，10 抽硬保底
        int pity4 = pity == null ? 0 : Math.max(0, pity.getPity4());
        // UP 歪池计数：连续未命中 UP 的次数，≥1 时下一发 5 星必为 UP（大保底）
        int failedUpCount = pity == null ? 0 : Math.max(0, pity.getFailedUpCount());
        // UP 池且 failedUpCount>0 时，客户端展示"下一发 5 星必为 UP"提示
        boolean guarantee5 = (bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP)
                && failedUpCount > 0;
        // 按卡池类型映射客户端展示名称
        String name;
        switch (bannerType) {
            case GachaBannerType.NEWBIE -> name = "新手召集";      // 新手限定池
            case GachaBannerType.NORMAL -> name = "群星跃迁";      // 常驻池
            case GachaBannerType.AVATAR_UP -> name = "角色跃迁";   // 角色 UP 池
            case GachaBannerType.WEAPON_UP -> name = "光锥跃迁";   // 武器/光锥 UP 池
            default -> name = "卡池";                              // 未知类型兜底
        }
        // UP 池 eventChance=50 表示 50% 基础 UP 概率；非 UP 池为 0（无 UP 机制）
        int eventChance = (bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP) ? 50 : 0;
        // 组装玩家保底信息协议消息
        GachaSystemProto.PlayerPityInfo playerPity = GachaSystemProto.PlayerPityInfo.newBuilder()
                .setPity5(pity5)
                .setPity4(pity4)
                .setFailedUpCount(failedUpCount)
                .setGuarantee5(guarantee5)
                .build();
        // 组装 Banner 基础信息
        GachaSystemProto.GachaBannerInfo.Builder b = GachaSystemProto.GachaBannerInfo.newBuilder()
                .setBannerType(bannerType)                              // 卡池类型整数
                .setBannerId(active.getId())                            // 配置中的 Banner 唯一 ID
                .setName(name)                                          // 展示名称
                .setBeginTime(Math.max(0L, active.getBeginTime()))      // 开放起始时间
                .setEndTime(Math.max(0L, active.getEndTime()))          // 开放结束时间
                .setCostItemId(COST_ITEM_ID_DEFAULT)                    // 单次抽卡消耗道具 ID
                .setCostCount(COST_COUNT_DEFAULT)                       // 单次抽卡消耗数量
                .setEventChance(eventChance)                            // UP 基础概率（百分比）
                .setPlayerPity(playerPity);                             // 玩家保底快照
        // 追加 5 星 UP 列表（角色/光锥 ID 列表）
        if (active.getRateUpItems5() != null) {
            b.addAllRateUpItems5(active.getRateUpItems5());
        }
        // 追加 4 星 UP 列表
        if (active.getRateUpItems4() != null) {
            b.addAllRateUpItems4(active.getRateUpItems4());
        }
        // 将组装完成的 Banner 信息追加到输出列表
        out.add(b.build());
    }

    /**
     * 处理 DoGacha 抽卡请求：校验参数 → 逐抽概率模拟 → 事务写库 → 返回物品列表与更新后的 pity/ceiling。
     * <p>
     * 十连抽在内存中逐抽累进 pity，全部模拟完成后再一次性事务持久化，保证十连内保底逻辑正确。
     * </p>
     *
     * @param req     客户端抽卡请求，含 bannerType 与 times（1 或 10）
     * @param channel 客户端连接通道
     * @return DoGachaScRsp 协议响应，含产出物品、更新后 pity 与 ceiling
     */
    public GachaSystemProto.DoGachaScRsp handleDoGacha(GachaSystemProto.DoGachaCsReq req, Channel channel) {
        // 解析玩家 ID
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.DoGachaScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }
        // 从请求中取出卡池类型与抽卡次数
        int bannerType = (int) req.getBannerType();
        int times = (int) req.getTimes();
        // 仅支持单抽(1)与十连(10)，其他次数视为非法参数
        if (times != 1 && times != 10) {
            return GachaSystemProto.DoGachaScRsp.newBuilder()
                    .setRetcode(RET_BAD_REQUEST)
                    .setBannerType(bannerType)
                    .build();
        }
        // 当前时间戳，用于选取开放中的卡池
        long now = System.currentTimeMillis() / 1000L;
        // 选取当前开放的 Banner 配置
        GachaBannerConfig banner = configService.pickActiveBanner(bannerType, now);
        if (banner == null) {
            return GachaSystemProto.DoGachaScRsp.newBuilder()
                    .setRetcode(RET_CONFIG_NOT_FOUND)
                    .setBannerType(bannerType)
                    .build();
        }
        // 加载抽卡前的保底状态，十连时在内存中逐抽累进
        PlayerGachaBannerInfoEntity pity = gachaRepository.loadOrCreateBannerInfo(playerId, bannerType);
        int pity5 = pity == null ? 0 : Math.max(0, pity.getPity5());
        int pity4 = pity == null ? 0 : Math.max(0, pity.getPity4());
        int failedUpCount = pity == null ? 0 : Math.max(0, pity.getFailedUpCount());
        // UP 池歪到常驻时，从常驻池 5 星列表中抽取；提前加载避免十连内重复查询
        GachaBannerConfig fallbackNormal = configService.pickNormalBannerForFallback(now);
        // 协议响应中的产出物品列表
        List<GachaSystemProto.GachaItem> items = new ArrayList<>(times);
        // 写库编排用的待发奖记录列表
        List<GachaApplicationService.DrawItemGrant> grants = new ArrayList<>(times);
        // 逐抽模拟：每抽的 pity 输出作为下一抽的输入
        for (int i = 0; i < times; i++) {
            GachaDrawEngine.DrawResult r = drawEngine.doOneDraw(
                    bannerType, banner, fallbackNormal, pity5, pity4, failedUpCount);
            pity5 = r.pity5After();
            pity4 = r.pity4After();
            failedUpCount = r.failedUpCountAfter();
            boolean isNew = !itemRepository.existsActiveItemByItemId(playerId, r.itemId());
            grants.add(new GachaApplicationService.DrawItemGrant(r.itemId(), isNew));
            items.add(GachaSystemProto.GachaItem.newBuilder()
                    .setItemId(r.itemId())
                    .setCount(1)
                    .setIsNew(isNew)
                    .build());
        }
        // 同一事务内完成：扣费流水 + 发奖 + 更新 pity + 历史 + 常驻池累加 ceiling
        GachaApplicationService.DrawPersistResult persistResult =
                gachaApplicationService.persistDraw(playerId, bannerType, times, grants, pity5, pity4, failedUpCount);
        if (!persistResult.success()) {
            return GachaSystemProto.DoGachaScRsp.newBuilder()
                    .setRetcode(persistResult.retcode() > 0 ? persistResult.retcode() : RET_INSUFFICIENT)
                    .setBannerType(bannerType)
                    .build();
        }
        // 角色池：重复抽取升命座，回写 is_new_constellation / current_layer
        if (bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.NEWBIE
                || bannerType == GachaBannerType.NORMAL) {
            items = applyConstellationLayers(playerId, items, grants);
            pushGachaResultNotify(channel, bannerType, items);
        }
        // 通知在线客户端刷新 GACHA 数据
        notifyDataChangedIfOnline(channel);
        recordGachaMemory(playerId, bannerType, banner, items, failedUpCount);
        // 组装更新后的保底信息，回显给客户端
        GachaSystemProto.PlayerPityInfo updatedPity = GachaSystemProto.PlayerPityInfo.newBuilder()
                .setPity5(pity5)
                .setPity4(pity4)
                .setFailedUpCount(failedUpCount)
                .setGuarantee5((bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP)
                        && failedUpCount > 0)
                .build();
        // 组装并返回抽卡响应
        GachaSystemProto.DoGachaScRsp.Builder rsp = GachaSystemProto.DoGachaScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setBannerType(bannerType)
                .addAllItems(items)
                .addAllExtraRewards(Collections.emptyList())
                .setUpdatedPity(updatedPity)
                .setUpdatedCeiling(persistResult.updatedCeiling());
        if (presentationService != null) {
            String nonce = req.getClientNonce();
            GachaPresentationService.StartResult started =
                    presentationService.start(playerId, bannerType, times, nonce);
            if (started.ok() && started.session() != null) {
                presentationService.markDrawing(started.session().sessionId(), playerId);
                rsp.setPresentationSessionId(started.session().sessionId());
                cn.itcast.demo.mylunarcore.common.ClientUiParams ui = started.session().ui();
                GachaSystemProto.ClientUiParams.Builder uiB = GachaSystemProto.ClientUiParams.newBuilder()
                        .setFxId(ui.fxId())
                        .setCameraPreset(ui.cameraPreset())
                        .setSfxId(ui.sfxId())
                        .setTimelineId(ui.timelineId());
                if (ui.textPlaceholders() != null) {
                    uiB.putAllTextPlaceholders(ui.textPlaceholders());
                }
                rsp.setClientUi(uiB);
            }
        }
        if (rebateService != null) {
            GachaRebateService.Balance bal = rebateService.balance(playerId);
            rsp.setRebatePointsGained(times);
            rsp.setRebatePointsBalance(bal.points());
        }
        return rsp.build();
    }

    /** 表现层三次握手 1/3：动画开始前预检。 */
    public GachaSystemProto.GachaStartScRsp handleGachaStart(GachaSystemProto.GachaStartCsReq req, Channel channel) {
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.GachaStartScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }
        if (presentationService == null) {
            return GachaSystemProto.GachaStartScRsp.newBuilder().setRetcode(RET_OK).build();
        }
        int times = (int) req.getTimes();
        GachaPresentationService.StartResult r = presentationService.start(
                playerId, (int) req.getBannerType(), times, req.getClientNonce());
        if (!r.ok() || r.session() == null) {
            return GachaSystemProto.GachaStartScRsp.newBuilder().setRetcode(r.retcode()).build();
        }
        cn.itcast.demo.mylunarcore.common.ClientUiParams ui = r.session().ui();
        return GachaSystemProto.GachaStartScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setPresentationSessionId(r.session().sessionId())
                .setEstimatedCost(times)
                .setClientUi(GachaSystemProto.ClientUiParams.newBuilder()
                        .setFxId(ui.fxId())
                        .setCameraPreset(ui.cameraPreset())
                        .setSfxId(ui.sfxId())
                        .setTimelineId(ui.timelineId())
                        .putAllTextPlaceholders(ui.textPlaceholders()))
                .build();
    }

    /** 表现层三次握手 3/3：动画结束 ACK。 */
    public GachaSystemProto.GachaResultAckScRsp handleGachaResultAck(
            GachaSystemProto.GachaResultAckCsReq req, Channel channel) {
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.GachaResultAckScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }
        if (presentationService == null) {
            return GachaSystemProto.GachaResultAckScRsp.newBuilder()
                    .setRetcode(RET_OK)
                    .setPresentationSessionId(req.getPresentationSessionId())
                    .build();
        }
        boolean ok = presentationService.ack(req.getPresentationSessionId(), playerId, req.getSkipped());
        return GachaSystemProto.GachaResultAckScRsp.newBuilder()
                .setRetcode(ok ? RET_OK : RET_BAD_REQUEST)
                .setPresentationSessionId(req.getPresentationSessionId())
                .build();
    }

    /**
     * 处理 ExchangeGachaCeiling 请求：常驻累计满 300 抽后兑换自选五星角色。
     *
     * @param req     客户端兑换请求，含 targetAvatarId（自选角色 ID）
     * @param channel 客户端连接通道
     * @return ExchangeGachaCeilingScRsp 协议响应
     */
    public GachaSystemProto.ExchangeGachaCeilingScRsp handleExchangeCeiling(
            GachaSystemProto.ExchangeGachaCeilingCsReq req, Channel channel) {
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }
        // 玩家自选的目标五星角色道具 ID
        int targetAvatarId = (int) req.getTargetAvatarId();
        if (targetAvatarId <= 0) {
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_BAD_REQUEST).build();
        }
        // 读取玩家全局 ceiling 状态
        PlayerGachaInfoEntity ceiling = gachaRepository.loadOrCreateGachaInfo(playerId);
        int num = ceiling == null ? 0 : ceiling.getCeilingNum();           // 累计抽数
        boolean claimed = ceiling != null && ceiling.isCeilingClaimed();   // 是否已兑换
        // 累计抽数不足 300，拒绝兑换
        if (num < 300) {
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_CEILING_NOT_REACHED).build();
        }
        // 本周期已兑换过，拒绝重复领取
        if (claimed) {
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_CEILING_ALREADY_CLAIMED).build();
        }
        // 事务内完成：发奖 + 标记 ceilingClaimed=true
        GachaApplicationService.ExchangePersistResult result =
                gachaApplicationService.persistCeilingExchange(playerId, targetAvatarId);
        // 通知客户端刷新 GACHA 数据
        notifyDataChangedIfOnline(channel);
        return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setRewardItem(result.reward())              // 兑换得到的角色
                .setUpdatedCeiling(result.updatedCeiling())  // 更新后 ceiling 状态
                .build();
    }

    /**
     * 处理 GetGachaHistory 抽卡历史查询请求。
     */
    public GachaSystemProto.GetGachaHistoryScRsp handleGetHistory(
            GachaSystemProto.GetGachaHistoryCsReq req, Channel channel) {
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.GetGachaHistoryScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }
        int bannerType = (int) req.getBannerType();
        int page = (int) req.getPage();
        int pageSize = (int) req.getPageSize();
        if (page <= 0) {
            page = 1;
        }
        if (pageSize <= 0) {
            pageSize = 20;
        }
        int total = gachaHistoryRepository.count(playerId, bannerType);
        List<GachaSystemProto.GachaHistoryRecord> records = new ArrayList<>();
        for (GachaHistoryRepository.HistoryRow row : gachaHistoryRepository.page(playerId, bannerType, page, pageSize)) {
            records.add(GachaSystemProto.GachaHistoryRecord.newBuilder()
                    .setTimestamp(row.timestampMillis())
                    .setBannerType(row.bannerType())
                    .setItemId(row.itemId())
                    .setCount(row.count())
                    .setIsNew(row.isNew())
                    .build());
        }
        return GachaSystemProto.GetGachaHistoryScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setTotalCount(total)
                .addAllRecords(records)
                .build();
    }

    /**
     * 热更后主动向指定 Channel 推送 GachaBannerUpdateScNotify。
     * <p>
     * 由 GachaBannerHotReloadService 在 Banners.json 重载成功后调用，
     * 让在线客户端刷新卡池列表，无需重新登录或主动发起 GetGachaInfo。
     * </p>
     *
     * @param channel 目标客户端连接通道
     */
    public void pushBannerUpdateNotify(Channel channel) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        try {
            GachaSystemProto.GetGachaInfoScRsp info = handleGetGachaInfo(channel);
            if (info.getRetcode() != RET_OK) {
                return;
            }
            GachaSystemProto.GachaBannerUpdateScNotify notify = GachaSystemProto.GachaBannerUpdateScNotify.newBuilder()
                    .addAllUpdatedBanners(info.getBannersList())
                    .build();
            channel.writeAndFlush(new GamePacket(CmdIds.GACHA_BANNER_UPDATE_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception e) {
            log.debug("pushBannerUpdateNotify failed", e);
        }
    }

    private void recordGachaMemory(long playerId, int bannerType, GachaBannerConfig banner,
                                   List<GachaSystemProto.GachaItem> items, int failedUpCount) {
        if (gachaMemoryHookProvider == null || items == null || items.isEmpty()) {
            return;
        }
        cn.itcast.demo.mylunarcore.assist.memory.AssistGachaMemoryHook hook =
                gachaMemoryHookProvider.getIfAvailable();
        if (hook == null) {
            return;
        }
        int upItemId = 0;
        if (banner != null && banner.getRateUpItems5() != null && !banner.getRateUpItems5().isEmpty()) {
            upItemId = banner.getRateUpItems5().get(0);
        }
        final int featuredUpId = upItemId;
        boolean hitUp = featuredUpId > 0 && items.stream().anyMatch(i -> i.getItemId() == featuredUpId);
        boolean pityLike = failedUpCount > 0
                || (bannerType == GachaBannerType.AVATAR_UP && !hitUp && items.size() >= 10);
        hook.onGachaResult(playerId, bannerType, hitUp, pityLike && !hitUp, "");
    }

    /** 抽卡发奖后应用命座升层，回写 is_new_constellation / current_layer。 */
    private List<GachaSystemProto.GachaItem> applyConstellationLayers(
            int playerId,
            List<GachaSystemProto.GachaItem> items,
            List<GachaApplicationService.DrawItemGrant> grants) {
        if (constellationService == null || items == null || items.isEmpty()) {
            return items;
        }
        List<GachaSystemProto.GachaItem> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            GachaSystemProto.GachaItem item = items.get(i);
            boolean isNew = grants != null && i < grants.size() && grants.get(i).isNew();
            ConstellationService.ConstellationResult cr =
                    constellationService.onAvatarObtained(playerId, item.getItemId(), isNew);
            out.add(item.toBuilder()
                    .setIsNewConstellation(cr.isNewConstellation())
                    .setCurrentLayer(cr.currentLayer())
                    .build());
        }
        return out;
    }

    private void pushGachaResultNotify(Channel channel, int bannerType,
                                       List<GachaSystemProto.GachaItem> items) {
        if (channel == null || !channel.isActive() || items == null || items.isEmpty()) {
            return;
        }
        try {
            GachaSystemProto.GachaResultScNotify notify = GachaSystemProto.GachaResultScNotify.newBuilder()
                    .setBannerType(bannerType)
                    .addAllItems(items)
                    .build();
            channel.writeAndFlush(new GamePacket(CmdIds.GACHA_RESULT_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception e) {
            // Proto 未生成时回退 JSON，保证客户端仍能收到命座层变化
            try {
                StringBuilder sb = new StringBuilder("{\"bannerType\":")
                        .append(bannerType).append(",\"items\":[");
                for (int i = 0; i < items.size(); i++) {
                    GachaSystemProto.GachaItem it = items.get(i);
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"itemId\":").append(it.getItemId())
                            .append(",\"isNew\":").append(it.getIsNew())
                            .append(",\"isNewConstellation\":").append(it.getIsNewConstellation())
                            .append(",\"currentLayer\":").append(it.getCurrentLayer())
                            .append('}');
                }
                sb.append("]}");
                channel.writeAndFlush(new GamePacket(CmdIds.GACHA_RESULT_SC_NOTIFY,
                        sb.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
                log.debug("pushGachaResultNotify failed: {}", e.getMessage());
            }
        }
    }
}
