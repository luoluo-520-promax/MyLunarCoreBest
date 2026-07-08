// 抽卡 Netty 协议业务所在包：处理查询卡池、执行抽卡、保底兑换与热更推送
package cn.itcast.demo.mylunarcore.gacha;

// GachaBannerConfig：单张卡池 Banner 配置 DTO
import cn.itcast.demo.mylunarcore.gacha.GachaBannerConfig;
// GachaBannerType：卡池类型整型常量
import cn.itcast.demo.mylunarcore.gacha.GachaBannerType;
// GachaConfigService：Banner 配置查询与时间窗筛选
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
// PlayerGachaBannerInfoEntity：玩家单池保底状态（pity5/pity4/failedUpCount）持久化实体
import cn.itcast.demo.mylunarcore.model.PlayerGachaBannerInfoEntity;
// PlayerGachaInfoEntity：玩家全局抽卡信息（300 抽保底计数与领取状态）持久化实体
import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;
// GachaRepository：抽卡保底与 ceiling 数据的数据库访问层
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
// ItemRepository：道具发放与存在性查询
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
// CmdIds：协议命令号常量（如 GACHA_BANNER_UPDATE_SC_NOTIFY = 508）
import cn.itcast.demo.mylunarcore.net.CmdIds;
// GamePacket：Netty 出站数据包封装（cmdId + protobuf 字节）
import cn.itcast.demo.mylunarcore.net.GamePacket;
// GachaSystemProto：抽卡相关 Protobuf 消息定义（请求/响应/通知）
import cn.itcast.demo.mylunarcore.protocol.GachaSystemProto;
// Channel：Netty 客户端连接通道
import io.netty.channel.Channel;
// AppLogger：项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// LogCategory：抽卡业务日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// AttributeKey：Netty Channel 自定义属性键，登录后绑定 playerUid
import io.netty.util.AttributeKey;
// Logger：SLF4J 日志接口
import org.slf4j.Logger;
// Service：声明为 Spring 业务层 Bean（与 @Component 类似，语义上强调业务服务）
import org.springframework.stereotype.Service;
// SecureRandom：密码学强度随机数，用于抽卡概率判定
import java.security.SecureRandom;
// ArrayList：动态数组，组装 Banner 列表与抽卡结果
import java.util.ArrayList;
// Collections：emptyList 等工具
import java.util.Collections;
// List：列表接口
import java.util.List;

/**
 * 抽卡系统 Netty 协议业务实现。
 * <p>协调 {@link GachaConfigService}（卡池配置）、{@link GachaRepository}（保底持久化）、
 * {@link ItemRepository}（发奖）完成以下能力：</p>
 * <ul>
 *   <li>查询当前开放卡池与玩家保底状态（GetGachaInfo）</li>
 *   <li>执行单抽/十连并结算 pity 与 UP 大保底（DoGacha）</li>
 *   <li>常驻池 300 抽自选五星兑换（ExchangeGachaCeiling）</li>
 *   <li>抽卡历史查询占位（GetGachaHistory，当前返回空）</li>
 *   <li>热更后主动推送卡池变更（GachaBannerUpdateScNotify）</li>
 * </ul>
 */
@Service // Spring 业务 Bean，由协议 Handler 与 GachaBannerHotReloadService 注入调用
public class GachaNettyService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_GACHA, GachaNettyService.class); // 抽卡业务日志

    // Netty Channel 属性键：登录成功后写入的玩家 uid（Long），getPlayerId 从中解析 playerId
    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid");

    private static final int RET_OK = 0;                      // 成功
    private static final int RET_SESSION_INVALID = 1;         // 未登录或 Channel 未绑定 uid
    private static final int RET_BAD_REQUEST = 2;             // 请求参数非法（如抽卡次数非 1/10）
    private static final int RET_CONFIG_NOT_FOUND = 3;        // 指定类型当前无开放中的 Banner
    private static final int RET_CEILING_NOT_REACHED = 4;     // 300 抽保底计数未达阈值
    private static final int RET_CEILING_ALREADY_CLAIMED = 5; // 本期 300 抽自选已领取

    private static final int COST_ITEM_ID_DEFAULT = 101;  // 默认抽卡消耗道具模板 id（如星轨票）
    private static final int COST_COUNT_DEFAULT = 1;      // 单次抽卡消耗数量（十连由客户端传 times=10，此处为展示用单价）

    private final GachaConfigService configService;   // Banner 配置查询
    private final GachaRepository gachaRepository;    // 保底与 ceiling 持久化
    private final ItemRepository itemRepository;      // 道具发放与“是否新获得”判定

    private final SecureRandom rng = new SecureRandom(); // 抽卡随机源，比 Random 更适合概率场景

    /**
     * 构造器注入三个依赖。
     *
     * @param configService    卡池配置服务
     * @param gachaRepository  抽卡数据仓储
     * @param itemRepository   道具仓储
     */
    public GachaNettyService(GachaConfigService configService,
                             GachaRepository gachaRepository,
                             ItemRepository itemRepository) {
        this.configService = configService;       // 查询当前开放 Banner 与 UP 列表
        this.gachaRepository = gachaRepository; // 读写玩家 pity 与 300 抽 ceiling
        this.itemRepository = itemRepository;   // 发放抽卡所得道具
    }

    /**
     * 从 Netty Channel 属性读取登录 uid，并映射为 int 型 playerId。
     * <p>uid 为 64 位 Long，playerId 取低 32 位无符号值，与项目其他模块约定一致。</p>
     *
     * @param channel 客户端 Netty 连接
     * @return playerId；未登录（uid 未设置）时返回 0
     */
    private int getPlayerId(Channel channel) {
        Long uid = channel.attr(UID_KEY).get(); // 读取登录阶段写入的 uid 属性
        if (uid == null) { // Channel 尚未完成登录绑定
            return 0; // 调用方据此返回 RET_SESSION_INVALID
        }
        return (int) (uid.longValue() & 0xffffffffL); // 取 uid 低 32 位作为 playerId（无符号截断）
    }

    /**
     * 处理「查询抽卡信息」协议（GetGachaInfoCsReq → GetGachaInfoScRsp）。
     * <p>汇总四类卡池（新手/常驻/角色UP/武器UP）的当前开放 Banner、UP 列表、
     * 玩家各池 pity 状态，以及全局 300 抽 ceiling 信息。</p>
     *
     * @param channel 客户端连接
     * @return 卡池信息响应；未登录返回 retcode=RET_SESSION_INVALID
     */
    public GachaSystemProto.GetGachaInfoScRsp handleGetGachaInfo(Channel channel) {
        int playerId = getPlayerId(channel); // 解析当前连接对应玩家 id
        if (playerId <= 0) { // 未登录或 uid 非法
            return GachaSystemProto.GetGachaInfoScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }

        long now = System.currentTimeMillis() / 1000L; // 当前 Unix 秒，用于筛选开放中的 Banner

        List<GachaSystemProto.GachaBannerInfo> banners = new ArrayList<>(); // 待下发的卡池展示列表
        addBannerInfos(banners, playerId, GachaBannerType.NEWBIE, now);     // 新手池（有开放 Banner 才追加）
        addBannerInfos(banners, playerId, GachaBannerType.NORMAL, now);     // 常驻池
        addBannerInfos(banners, playerId, GachaBannerType.AVATAR_UP, now);  // 角色 UP 池
        addBannerInfos(banners, playerId, GachaBannerType.WEAPON_UP, now);  // 武器 UP 池

        PlayerGachaInfoEntity ceiling = gachaRepository.loadOrCreateGachaInfo(playerId); // 加载全局 300 抽 ceiling 数据
        GachaSystemProto.CeilingInfo ceilingInfo = GachaSystemProto.CeilingInfo.newBuilder()
                .setCeilingNum(ceiling == null ? 0 : Math.max(0, ceiling.getCeilingNum())) // 已累计常驻抽数，下限钳制为 0
                .setCeilingClaimed(ceiling != null && ceiling.isCeilingClaimed())             // 本期自选五星是否已领取
                .build();

        return GachaSystemProto.GetGachaInfoScRsp.newBuilder()
                .setRetcode(RET_OK)           // 成功
                .addAllBanners(banners)       // 全部开放中卡池
                .setCeilingInfo(ceilingInfo)  // 300 抽保底进度
                .build();
    }

    /**
     * 将单个卡池类型的展示信息追加到输出列表（若当前无开放 Banner 则静默跳过）。
     * <p>包含：Banner 元数据、消耗、UP 列表、玩家该池 pity 与大保底标记。</p>
     *
     * @param out        输出列表，本方法向其中 add 一条 GachaBannerInfo
     * @param playerId   玩家 id
     * @param bannerType 卡池类型整型常量
     * @param nowSeconds 当前 Unix 秒
     */
    private void addBannerInfos(List<GachaSystemProto.GachaBannerInfo> out, int playerId, int bannerType, long nowSeconds) {
        GachaBannerConfig active = configService.pickActiveBanner(bannerType, nowSeconds); // 当前时间窗内生效的 Banner
        if (active == null) { // 该类型暂无开放卡池，不下发
            return;
        }

        PlayerGachaBannerInfoEntity pity = gachaRepository.loadOrCreateBannerInfo(playerId, bannerType); // 该玩家在此池的保底状态
        int pity5 = pity == null ? 0 : Math.max(0, pity.getPity5());               // 距上次 5 星已抽次数（软保底计数）
        int pity4 = pity == null ? 0 : Math.max(0, pity.getPity4());               // 距上次 4 星已抽次数
        int failedUpCount = pity == null ? 0 : Math.max(0, pity.getFailedUpCount()); // UP 池歪 UP 次数，>0 表示处于大保底

        // UP 池且 failedUpCount>0 时，客户端展示“下次五星必为 UP”（guarantee5 标记）
        boolean guarantee5 = bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP;
        guarantee5 = guarantee5 && failedUpCount > 0;

        String name; // 卡池展示名称，按类型映射中文名
        switch (bannerType) {
            case GachaBannerType.NEWBIE:
                name = "新手召集";
                break;
            case GachaBannerType.NORMAL:
                name = "群星跃迁";
                break;
            case GachaBannerType.AVATAR_UP:
                name = "角色跃迁";
                break;
            case GachaBannerType.WEAPON_UP:
                name = "光锥跃迁";
                break;
            default:
                name = "卡池"; // 未知类型的兜底展示名
        }

        // UP 池展示 50% UP 概率提示；非 UP 池 eventChance 为 0
        int eventChance = (bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP) ? 50 : 0;

        GachaSystemProto.PlayerPityInfo playerPity = GachaSystemProto.PlayerPityInfo.newBuilder()
                .setPity5(pity5)
                .setPity4(pity4)
                .setFailedUpCount(failedUpCount)
                .setGuarantee5(guarantee5) // 是否处于 UP 大保底状态
                .build();

        GachaSystemProto.GachaBannerInfo.Builder b = GachaSystemProto.GachaBannerInfo.newBuilder()
                .setBannerType(bannerType)                              // 协议用整型类型
                .setBannerId(active.getId())                            // 配置 id
                .setName(name)                                          // 展示名
                .setBeginTime(Math.max(0L, active.getBeginTime()))      // 开放开始（负数钳制为 0）
                .setEndTime(Math.max(0L, active.getEndTime()))          // 开放结束
                .setCostItemId(COST_ITEM_ID_DEFAULT)                    // 单次消耗道具 id
                .setCostCount(COST_COUNT_DEFAULT)                       // 单次消耗数量
                .setEventChance(eventChance)                            // UP 概率展示（50 或 0）
                .setPlayerPity(playerPity);                             // 玩家保底快照

        if (active.getRateUpItems5() != null) { // 5 星 UP 列表非空才写入 proto
            b.addAllRateUpItems5(active.getRateUpItems5());
        }
        if (active.getRateUpItems4() != null) { // 4 星 UP 列表非空才写入
            b.addAllRateUpItems4(active.getRateUpItems4());
        }

        out.add(b.build()); // 追加到汇总列表
    }

    /**
     * 处理「执行抽卡」协议（DoGachaCsReq → DoGachaScRsp）。
     * <p>支持 1 抽与 10 连；每次抽卡按单抽粒度推进 pity，保证十连与连续单抽概率行为一致。
     * 常驻池抽卡会累加 300 抽 ceiling 计数。</p>
     *
     * @param req     抽卡请求（bannerType、times）
     * @param channel 客户端连接
     * @return 抽卡结果列表、更新后的 pity 与 ceiling；参数非法或未登录返回对应 retcode
     */
    public GachaSystemProto.DoGachaScRsp handleDoGacha(GachaSystemProto.DoGachaCsReq req, Channel channel) {
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.DoGachaScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }

        int bannerType = (int) req.getBannerType(); // 目标卡池类型
        int times = (int) req.getTimes();           // 抽卡次数，协议约定仅 1 或 10
        if (times != 1 && times != 10) { // 非法次数直接拒绝，不扣道具、不写库
            return GachaSystemProto.DoGachaScRsp.newBuilder().setRetcode(RET_BAD_REQUEST).setBannerType(bannerType).build();
        }

        long now = System.currentTimeMillis() / 1000L;
        GachaBannerConfig banner = configService.pickActiveBanner(bannerType, now); // 当前应使用的 Banner 配置
        if (banner == null) { // 卡池未开放或配置缺失
            return GachaSystemProto.DoGachaScRsp.newBuilder().setRetcode(RET_CONFIG_NOT_FOUND).setBannerType(bannerType).build();
        }

        PlayerGachaBannerInfoEntity pity = gachaRepository.loadOrCreateBannerInfo(playerId, bannerType);
        int pity5 = pity == null ? 0 : Math.max(0, pity.getPity5());
        int pity4 = pity == null ? 0 : Math.max(0, pity.getPity4());
        int failedUpCount = pity == null ? 0 : Math.max(0, pity.getFailedUpCount());

        GachaBannerConfig fallbackNormal = configService.pickNormalBannerForFallback(now); // UP 池歪池/列表兜底用常驻池

        List<GachaSystemProto.GachaItem> items = new ArrayList<>(times); // 预分配容量，存放本次全部抽卡结果
        for (int i = 0; i < times; i++) {
            // 每一抽独立调用 doOneDraw 并更新 pity 局部变量，确保十连内部也逐抽推进软保底与 UP 大保底
            DrawResult r = doOneDraw(bannerType, banner, fallbackNormal, pity5, pity4, failedUpCount);
            pity5 = r.pity5After;                   // 本抽后的 5 星 pity
            pity4 = r.pity4After;                   // 本抽后的 4 星 pity
            failedUpCount = r.failedUpCountAfter;     // 本抽后的 UP 歪计数

            boolean isNew = !itemRepository.existsActiveItemByItemId(playerId, r.itemId); // 玩家是否首次获得该模板
            itemRepository.addSimpleItem(playerId, r.itemId, 3, 1); // 发放道具：level=3, count=1（简化实现）

            items.add(GachaSystemProto.GachaItem.newBuilder()
                    .setItemId(r.itemId)
                    .setCount(1)
                    .setIsNew(isNew) // 客户端用于“新”角标展示
                    .build());
        }

        gachaRepository.updateBannerPity(playerId, bannerType, pity5, pity4, failedUpCount); // 持久化最终 pity 状态

        GachaSystemProto.PlayerPityInfo updatedPity = GachaSystemProto.PlayerPityInfo.newBuilder()
                .setPity5(pity5)
                .setPity4(pity4)
                .setFailedUpCount(failedUpCount)
                .setGuarantee5((bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP) && failedUpCount > 0)
                .build();

        GachaSystemProto.CeilingInfo updatedCeiling = GachaSystemProto.CeilingInfo.getDefaultInstance(); // 非常驻池默认不更新 ceiling
        if (bannerType == GachaBannerType.NORMAL) { // 仅常驻池累加 300 抽计数
            gachaRepository.incrementCeilingNum(playerId, times); // 增加 times 次常驻抽数
            PlayerGachaInfoEntity ceiling = gachaRepository.loadOrCreateGachaInfo(playerId);
            updatedCeiling = GachaSystemProto.CeilingInfo.newBuilder()
                    .setCeilingNum(ceiling == null ? 0 : Math.max(0, ceiling.getCeilingNum()))
                    .setCeilingClaimed(ceiling != null && ceiling.isCeilingClaimed())
                    .build();
        }

        return GachaSystemProto.DoGachaScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setBannerType(bannerType)
                .addAllItems(items)                              // 本次全部抽卡结果
                .addAllExtraRewards(Collections.emptyList())     // 额外奖励占位，当前无实现
                .setUpdatedPity(updatedPity)                     // 抽后 pity 快照
                .setUpdatedCeiling(updatedCeiling)               // 抽后 300 抽进度（非常驻池为默认空）
                .build();
    }

    /**
     * 处理「300 抽保底兑换」协议（ExchangeGachaCeilingCsReq → ExchangeGachaCeilingScRsp）。
     * <p>玩家在常驻池累计抽满 300 次且尚未领取时，可指定 targetAvatarId 兑换一个五星角色。</p>
     *
     * @param req     兑换请求（targetAvatarId）
     * @param channel 客户端连接
     * @return 兑换结果与更新后的 ceiling；未达 300 或已领取返回对应 retcode
     */
    public GachaSystemProto.ExchangeGachaCeilingScRsp handleExchangeCeiling(GachaSystemProto.ExchangeGachaCeilingCsReq req, Channel channel) {
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }

        int targetAvatarId = (int) req.getTargetAvatarId(); // 玩家选择的五星角色模板 id
        if (targetAvatarId <= 0) {
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_BAD_REQUEST).build();
        }

        PlayerGachaInfoEntity ceiling = gachaRepository.loadOrCreateGachaInfo(playerId);
        int num = ceiling == null ? 0 : ceiling.getCeilingNum();           // 当前累计常驻抽数
        boolean claimed = ceiling != null && ceiling.isCeilingClaimed();  // 是否已领取本期自选

        if (num < 300) { // 未达兑换门槛
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_CEILING_NOT_REACHED).build();
        }
        if (claimed) { // 已领取过，不可重复兑换
            return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder().setRetcode(RET_CEILING_ALREADY_CLAIMED).build();
        }

        boolean isNew = !itemRepository.existsActiveItemByItemId(playerId, targetAvatarId);
        itemRepository.addSimpleItem(playerId, targetAvatarId, 3, 1); // 发放自选五星
        gachaRepository.setCeilingClaimed(playerId, true);            // 标记本期已领取

        PlayerGachaInfoEntity after = gachaRepository.loadOrCreateGachaInfo(playerId); // 重新读取最新 ceiling 状态
        GachaSystemProto.CeilingInfo updatedCeiling = GachaSystemProto.CeilingInfo.newBuilder()
                .setCeilingNum(after == null ? 300 : Math.max(0, after.getCeilingNum())) // 计数保持 ≥300
                .setCeilingClaimed(after != null && after.isCeilingClaimed())
                .build();

        GachaSystemProto.GachaItem reward = GachaSystemProto.GachaItem.newBuilder()
                .setItemId(targetAvatarId)
                .setCount(1)
                .setIsNew(isNew)
                .build();

        return GachaSystemProto.ExchangeGachaCeilingScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setRewardItem(reward)
                .setUpdatedCeiling(updatedCeiling)
                .build();
    }

    /**
     * 处理「查询抽卡历史」协议（GetGachaHistoryCsReq → GetGachaHistoryScRsp）。
     * <p>当前项目尚未实现抽卡历史表，固定返回空列表与 totalCount=0，预留扩展点。</p>
     *
     * @param req     历史查询请求（分页参数等，当前未使用）
     * @param channel 客户端连接
     * @return 空历史响应；未登录返回 RET_SESSION_INVALID
     */
    public GachaSystemProto.GetGachaHistoryScRsp handleGetHistory(GachaSystemProto.GetGachaHistoryCsReq req, Channel channel) {
        int playerId = getPlayerId(channel);
        if (playerId <= 0) {
            return GachaSystemProto.GetGachaHistoryScRsp.newBuilder().setRetcode(RET_SESSION_INVALID).build();
        }
        // 历史表待扩展：接入 DB 后在此查询并填充 records 与 totalCount
        return GachaSystemProto.GetGachaHistoryScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setTotalCount(0)
                .addAllRecords(Collections.emptyList())
                .build();
    }

    /**
     * 向指定客户端主动推送卡池更新通知（GACHA_BANNER_UPDATE_SC_NOTIFY / CmdIds.GACHA_BANNER_UPDATE_SC_NOTIFY）。
     * <p>由 {@link GachaBannerHotReloadService} 在 Banners.json 热更成功后调用，
     * 复用 {@link #handleGetGachaInfo(Channel)} 组装最新 Banner 列表后下发。</p>
     *
     * @param channel 目标客户端连接；null 或非 active 时直接忽略
     */
    public void pushBannerUpdateNotify(Channel channel) {
        if (channel == null || !channel.isActive()) { // 连接已断开或未建立
            return;
        }
        try {
            GachaSystemProto.GetGachaInfoScRsp info = handleGetGachaInfo(channel); // 按当前配置与玩家 pity 重新组装卡池列表
            if (info.getRetcode() != RET_OK) { // 玩家未登录等导致无法组装，跳过推送
                return;
            }
            GachaSystemProto.GachaBannerUpdateScNotify notify = GachaSystemProto.GachaBannerUpdateScNotify.newBuilder()
                    .addAllUpdatedBanners(info.getBannersList()) // 全量替换客户端展示的 Banner 列表
                    .build();
            channel.writeAndFlush(new GamePacket(CmdIds.GACHA_BANNER_UPDATE_SC_NOTIFY, notify.toByteArray())); // 异步写出 Netty 包
        } catch (Exception e) {
            log.debug("pushBannerUpdateNotify failed", e); // 单连接推送失败不影响其他玩家
        }
    }

    /**
     * 执行一次抽卡随机判定并返回产出 itemId 与更新后的保底计数。
     * <p>概率规则（简化版）：5 星基础 0.6%，90 抽硬保底；4 星基础 5.1%，10 抽硬保底；
     * 其余为 3 星默认物 21000。UP 池五星另走 {@link #pickFiveStar} 的 50/50 与大保底。</p>
     *
     * @param bannerType       卡池类型
     * @param banner           当前生效 Banner 配置
     * @param fallbackNormal   常驻池 Banner，UP 歪池与列表兜底用
     * @param pity5            抽前 5 星 pity 计数
     * @param pity4            抽前 4 星 pity 计数
     * @param failedUpCount    抽前 UP 歪次数（大保底状态）
     * @return 单抽结果封装（itemId + 抽后三项计数）
     */
    private DrawResult doOneDraw(int bannerType,
                                GachaBannerConfig banner,
                                GachaBannerConfig fallbackNormal,
                                int pity5,
                                int pity4,
                                int failedUpCount) {
        int nextPity5 = pity5 + 1; // 本抽计入 5 星 pity（若出 5 星则 reset 为 0）
        int nextPity4 = pity4 + 1; // 本抽计入 4 星 pity（若出 4/5 星则按规则 reset）

        // 5 星：90 抽硬保底 或 0.6% 概率命中；二者先判 5 星再判 4 星，互斥
        boolean got5 = nextPity5 >= 90 || rollPercent(0.6);
        // 4 星：未出 5 星的前提下，10 抽硬保底 或 5.1% 概率
        boolean got4 = !got5 && (nextPity4 >= 10 || rollPercent(5.1));

        int itemId;
        if (got5) {
            itemId = pickFiveStar(bannerType, banner, fallbackNormal, failedUpCount); // 按池类型与 UP 规则选 5 星 id
            nextPity5 = 0;              // 出 5 星重置 5 星 pity
            nextPity4 = nextPity4 + 1;  // 出 5 星仍推进 4 星 pity（星铁类规则：5 星占用 4 星保底进度）
            // UP 池：根据本次是否命中 UP 更新 failedUpCount（歪则 +1 直至 255，中 UP 则清零）
            if (bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP) {
                boolean isUp = banner.getRateUpItems5() != null && banner.getRateUpItems5().contains(itemId);
                if (isUp) {
                    failedUpCount = 0; // 命中 UP，大保底状态清除
                } else {
                    failedUpCount = Math.min(255, failedUpCount + 1); // 歪 UP，进入或加深大保底（上限 255 防溢出）
                }
            } else {
                failedUpCount = 0; // 非 UP 池不使用 failedUpCount
            }
        } else if (got4) {
            itemId = pickFromListOrFallback(banner.getRateUpItems4(), fallbackNormal == null ? null : fallbackNormal.getRateUpItems4(), 20001);
            nextPity4 = 0; // 出 4 星重置 4 星 pity
        } else {
            itemId = 21000; // 3 星填充物默认模板 id（简化实现，未做完整 3 星池）
        }

        return new DrawResult(itemId, nextPity5, nextPity4, failedUpCount);
    }

    /**
     * 五星产出 id 选取：非 UP 池直接从 UP 列表或常驻列表随机；UP 池含 50/50 与大保底。
     * <p>大保底（failedUpCount>0）时强制走 UP 列表；否则 50% 概率 UP 列表，50% 常驻歪池。</p>
     *
     * @param bannerType     卡池类型
     * @param banner         当前 UP/目标池 Banner
     * @param fallbackNormal 常驻池 Banner（歪池来源）
     * @param failedUpCount  当前大保底计数，>0 表示必 UP
     * @return 5 星道具模板 id
     */
    private int pickFiveStar(int bannerType, GachaBannerConfig banner, GachaBannerConfig fallbackNormal, int failedUpCount) {
        List<Integer> up = banner.getRateUpItems5();                                      // 本池 5 星 UP 列表
        List<Integer> off = fallbackNormal == null ? null : fallbackNormal.getRateUpItems5(); // 常驻池 5 星列表（歪池）

        boolean isUpBanner = bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP;
        if (!isUpBanner) { // 新手池/常驻池等：无 50/50，直接从本池 UP 列表或常驻列表抽样
            return pickFromListOrFallback(up, off, 10001);
        }

        boolean guarantee = failedUpCount > 0; // 大保底：上次歪 UP 后本次五星必为 UP
        int chance = 50;                         // 小保底 UP 概率 50%
        boolean win = guarantee || rollInt(100) < chance; // 大保底或 50% 随机命中 UP
        if (win) {
            return pickFromListOrFallback(up, off, 10001); // 从 UP 列表抽（空则 fallback）
        }
        // 50% 歪：从常驻池 5 星列表抽，仍空则默认 10001
        return pickFromListOrFallback(off, up, 10001);
    }

    /**
     * 从主列表均匀随机一个 itemId；主列表空则回退备用列表；皆空则返回默认值。
     *
     * @param primary         优先抽样列表
     * @param fallback        主列表不可用时的备用列表
     * @param defaultItemId   两列表皆空时的兜底 id
     * @return 随机选中的 itemId
     */
    private int pickFromListOrFallback(List<Integer> primary, List<Integer> fallback, int defaultItemId) {
        List<Integer> list = (primary == null || primary.isEmpty()) ? fallback : primary; // 主列表优先
        if (list == null || list.isEmpty()) {
            return defaultItemId; // 配置缺失时的硬编码兜底
        }
        int idx = rollInt(list.size()); // [0, size) 均匀随机下标
        return list.get(idx);
    }

    /**
     * 按百分比概率判定是否命中（左闭区间 [0, percent) 意义上的命中）。
     *
     * @param percent 命中概率百分比，如 0.6 表示 0.6%，100 表示必中
     * @return 是否命中
     */
    private boolean rollPercent(double percent) {
        if (percent <= 0) {
            return false; // 0 或负概率永不命中
        }
        if (percent >= 100) {
            return true; // 100% 及以上必中
        }
        return rng.nextDouble() * 100.0 < percent; // [0,1)*100 与 percent 比较
    }

    /**
     * 生成 [0, bound) 均匀随机整数；bound 非法时用 1 兜底，避免 nextInt(0) 抛异常中断抽卡流程。
     *
     * @param bound 上界（不含）
     * @return 随机整数
     */
    private int rollInt(int bound) {
        return rng.nextInt(Math.max(1, bound));
    }

    /**
     * 单抽内部结果载体：产出 itemId 与本抽后的三项保底计数。
     * <p>静态内部类，仅 {@link #doOneDraw} 与 {@link #handleDoGacha} 使用。</p>
     */
    private static final class DrawResult {
        final int itemId;              // 本抽获得的道具模板 id
        final int pity5After;          // 本抽后的 5 星 pity
        final int pity4After;          // 本抽后的 4 星 pity
        final int failedUpCountAfter;  // 本抽后的 UP 歪计数

        DrawResult(int itemId, int pity5After, int pity4After, int failedUpCountAfter) {
            this.itemId = itemId;
            this.pity5After = pity5After;
            this.pity4After = pity4After;
            this.failedUpCountAfter = failedUpCountAfter;
        }
    }
}
