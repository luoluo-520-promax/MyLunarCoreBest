package cn.itcast.demo.mylunarcore.guild;

import cn.itcast.demo.mylunarcore.net.ClientFeatureFlags;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.GuildSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 公会系统的网络协议适配层：负责把客户端通过 Netty 通道发来的公会请求
 * 解析为业务调用，并将业务结果组装成 Protobuf 响应回传给客户端。
 *
 * <p>职责划分：
 * <ul>
 *   <li>从 Channel 解析出玩家 ID（{@link PlayerContextResolver}），非法连接直接返回 retcode=1；</li>
 *   <li>将请求参数转发给底层业务服务（{@link GuildService}、{@link GuildWarService}、{@link GuildTechService}）；</li>
 *   <li>把业务返回的实体/错误码映射为 {@code GuildSystemProto} 中定义的响应消息；</li>
 *   <li>针对公会战相关协议做客户端能力位（{@link ClientFeatureFlags#GUILD_WAR}）校验，
 *       不支持公会战的客户端直接隐藏入口（retcode=20），避免下发伪造战况。</li>
 * </ul>
 */
@Service
public class GuildNettyService {

    /** 公会基础服务：创建/加入/退出/贡献/商店。 */
    private final GuildService guildService;
    /** 公会战服务：匹配、报分、赛季排行。 */
    private final GuildWarService guildWarService;
    /** 公会科技服务：按公会等级提供属性增益（当前仅在信息查询时触达）。 */
    private final GuildTechService guildTechService;
    /** 玩家上下文解析器：从 Netty Channel 安全解析当前玩家 ID。 */
    private final PlayerContextResolver playerContextResolver;
    /** 会话管理器：按玩家 ID 获取在线会话以读取客户端能力位。 */
    private final GameSessionManager sessionManager;

    public GuildNettyService(GuildService guildService,
                             GuildWarService guildWarService,
                             GuildTechService guildTechService,
                             PlayerContextResolver playerContextResolver,
                             GameSessionManager sessionManager) {
        this.guildService = guildService;
        this.guildWarService = guildWarService;
        this.guildTechService = guildTechService;
        this.playerContextResolver = playerContextResolver;
        this.sessionManager = sessionManager;
    }

    /**
     * 校验玩家客户端是否声明支持公会战能力。
     * <p>无会话上下文（单测/内部调用）默认放行；有会话时按能力位掩码判断。
     * 目的是让老版本客户端看不到公会战入口，避免客户端渲染未知协议。
     */
    private boolean supportsGuildWar(int uid) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null) {
            return true; // 无会话上下文不拦截（单测/内部）；有会话则校验位掩码
        }
        return ClientFeatureFlags.supports(session.getEnabledFeatures(), ClientFeatureFlags.GUILD_WAR);
    }

    /**
     * 创建公会请求处理：解析玩家 → 调用 {@link GuildService#create} → 成功后附带公会概要回包。
     * 业务错误码原样透传给客户端。
     */
    public GuildSystemProto.CreateGuildScRsp handleCreate(GuildSystemProto.CreateGuildCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.CreateGuildScRsp.newBuilder().setRetcode(1).build();
        }
        GuildService.OpResult result = guildService.create(uid, req.getName(), req.getNotice());
        GuildSystemProto.CreateGuildScRsp.Builder b = GuildSystemProto.CreateGuildScRsp.newBuilder()
                .setRetcode(result.retcode());
        if (result.success()) {
            GuildService.GuildInfo g = guildService.loadGuildForPlayer(uid);
            if (g != null) {
                b.setGuild(toSummary(g));
            }
        }
        return b.build();
    }

    /**
     * 加入公会请求处理：解析玩家 → 调用 {@link GuildService#join} → 成功后附带目标公会概要回包。
     */
    public GuildSystemProto.JoinGuildScRsp handleJoin(GuildSystemProto.JoinGuildCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.JoinGuildScRsp.newBuilder().setRetcode(1).build();
        }
        GuildService.OpResult result = guildService.join(uid, req.getGuildId());
        GuildSystemProto.JoinGuildScRsp.Builder b = GuildSystemProto.JoinGuildScRsp.newBuilder()
                .setRetcode(result.retcode());
        if (result.success()) {
            GuildService.GuildInfo g = guildService.loadGuild(req.getGuildId());
            if (g != null) {
                b.setGuild(toSummary(g));
            }
        }
        return b.build();
    }

    /** 退出公会请求处理：直接透传 {@link GuildService#leave} 的结果码。 */
    public GuildSystemProto.LeaveGuildScRsp handleLeave(Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.LeaveGuildScRsp.newBuilder().setRetcode(1).build();
        }
        return GuildSystemProto.LeaveGuildScRsp.newBuilder()
                .setRetcode(guildService.leave(uid).retcode())
                .build();
    }

    /**
     * 公会信息查询：返回公会概要 + 成员列表 + 请求者的累计贡献。
     * <p>同时触达一次公会科技查询（保证配置加载路径被覆盖，后续可扩展为向客户端下发增益提示）。
     */
    public GuildSystemProto.GetGuildInfoScRsp handleGetInfo(Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.GetGuildInfoScRsp.newBuilder().setRetcode(1).build();
        }
        GuildService.GuildInfo g = guildService.loadGuildForPlayer(uid);
        if (g == null) {
            return GuildSystemProto.GetGuildInfoScRsp.newBuilder().setRetcode(3).build();
        }
        // 触达科技 Buff 查询，保证配置加载路径被使用（后续可下发 notice 附加）
        guildTechService.buffForGuildLevel(g.level());
        List<GuildService.MemberInfo> members = guildService.listMembers(g.guildId());
        GuildSystemProto.GetGuildInfoScRsp.Builder b = GuildSystemProto.GetGuildInfoScRsp.newBuilder()
                .setRetcode(0)
                .setGuild(toSummary(g));
        int myContrib = 0;
        for (GuildService.MemberInfo m : members) {
            b.addMembers(GuildSystemProto.GuildMemberInfo.newBuilder()
                    .setPlayerId(m.playerId())
                    .setRole(m.role())
                    .setContribution(m.contribution())
                    .setWeeklyContrib(m.weeklyContrib())
                    .build());
            if (m.playerId() == uid) {
                myContrib = m.contribution();
            }
        }
        return b.setMyContribution(myContrib).build();
    }

    /**
     * 贡献请求处理：调用 {@link GuildService#contribute} 累加贡献与公会经验，
     * 成功后回传该玩家最新累计/周贡献以及公会总经验。
     */
    public GuildSystemProto.ContributeGuildScRsp handleContribute(GuildSystemProto.ContributeGuildCsReq req,
                                                                  Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.ContributeGuildScRsp.newBuilder().setRetcode(1).build();
        }
        GuildService.OpResult result = guildService.contribute(uid, req.getAmount());
        GuildSystemProto.ContributeGuildScRsp.Builder b = GuildSystemProto.ContributeGuildScRsp.newBuilder()
                .setRetcode(result.retcode());
        if (result.success()) {
            GuildService.GuildInfo g = guildService.loadGuildForPlayer(uid);
            if (g != null) {
                GuildService.MemberInfo me = guildService.loadMember(g.guildId(), uid);
                if (me != null) {
                    b.setContribution(me.contribution()).setWeeklyContrib(me.weeklyContrib());
                }
                b.setGuildExp(g.exp());
            }
        }
        return b.build();
    }

    /**
     * 公会商店列表查询：返回全部在售商品，并为每件商品附带该玩家本周已购数量
     * （供客户端展示剩余可购额度）。
     */
    public GuildSystemProto.GetGuildShopScRsp handleGetShop(Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.GetGuildShopScRsp.newBuilder().setRetcode(1).build();
        }
        GuildSystemProto.GetGuildShopScRsp.Builder b = GuildSystemProto.GetGuildShopScRsp.newBuilder().setRetcode(0);
        for (GuildService.ShopProduct p : guildService.listShopProducts()) {
            b.addProducts(GuildSystemProto.GuildShopProduct.newBuilder()
                    .setProductId(p.productId())
                    .setItemId(p.itemId())
                    .setCount(p.count())
                    .setContributionCost(p.contributionCost())
                    .setWeeklyLimit(p.weeklyLimit())
                    .setName(p.name() == null ? "" : p.name())
                    .setBoughtThisWeek(guildService.boughtThisWeek(uid, p.productId()))
                    .build());
        }
        return b.build();
    }

    /**
     * 商店购买请求处理：调用 {@link GuildService#buyShop} 扣贡献并发放道具，
     * 回包附带更新后的本周已购次数。
     */
    public GuildSystemProto.BuyGuildShopScRsp handleBuyShop(GuildSystemProto.BuyGuildShopCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.BuyGuildShopScRsp.newBuilder().setRetcode(1).build();
        }
        GuildService.OpResult result = guildService.buyShop(uid, req.getProductId());
        return GuildSystemProto.BuyGuildShopScRsp.newBuilder()
                .setRetcode(result.retcode())
                .setProductId(req.getProductId())
                .setBoughtThisWeek(guildService.boughtThisWeek(uid, req.getProductId()))
                .build();
    }

    /**
     * 公会战匹配请求：先做客户端能力位校验，再调用 {@link GuildWarService#requestMatch}
     * 为玩家所属公会寻找对手或进入等待队列，成功后回传比赛信息。
     */
    public GuildSystemProto.GuildWarMatchScRsp handleWarMatch(Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.GuildWarMatchScRsp.newBuilder().setRetcode(1).build();
        }
        if (!supportsGuildWar(uid)) {
            return GuildSystemProto.GuildWarMatchScRsp.newBuilder().setRetcode(20).build(); // 客户端不支持公会战
        }
        GuildWarService.OpResult r = guildWarService.requestMatch(uid);
        GuildSystemProto.GuildWarMatchScRsp.Builder b =
                GuildSystemProto.GuildWarMatchScRsp.newBuilder().setRetcode(r.retcode());
        if (r.match() != null) {
            b.setMatch(toWarMatch(r.match()));
        }
        return b.build();
    }

    /**
     * 公会战报分请求：战斗结束后上报本场比分，由 {@link GuildWarService#reportBattleResult}
     * 结算胜负并累加赛季积分，回包携带结算后的比赛信息。
     */
    public GuildSystemProto.GuildWarReportScRsp handleWarReport(GuildSystemProto.GuildWarReportCsReq req,
                                                                Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.GuildWarReportScRsp.newBuilder().setRetcode(1).build();
        }
        GuildWarService.OpResult r = guildWarService.reportBattleResult(
                uid, req.getMatchId(), req.getScoreSelf(), req.getScoreOpponent());
        GuildSystemProto.GuildWarReportScRsp.Builder b =
                GuildSystemProto.GuildWarReportScRsp.newBuilder().setRetcode(r.retcode());
        if (r.match() != null) {
            b.setMatch(toWarMatch(r.match()));
        }
        return b.build();
    }

    /**
     * 公会战赛季排行查询：客户端未指定赛季时默认当前赛季；取 TopN（默认 20）返回排行榜。
     * 不支持公会战的客户端返回空榜（retcode=20）。
     */
    public GuildSystemProto.GuildWarRankScRsp handleWarRank(GuildSystemProto.GuildWarRankCsReq req,
                                                            Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return GuildSystemProto.GuildWarRankScRsp.newBuilder().setRetcode(1).build();
        }
        if (!supportsGuildWar(uid)) {
            // 不支持时直接隐藏：空榜 + retcode=20
            return GuildSystemProto.GuildWarRankScRsp.newBuilder().setRetcode(20).build();
        }
        long seasonId = req.getSeasonId();
        if (seasonId <= 0) {
            seasonId = guildWarService.ensureCurrentSeason().seasonId();
        }
        int topN = req.getTopN() <= 0 ? 20 : req.getTopN();
        GuildSystemProto.GuildWarRankScRsp.Builder b = GuildSystemProto.GuildWarRankScRsp.newBuilder()
                .setRetcode(0)
                .setSeasonId(seasonId);
        for (GuildWarService.RankEntry e : guildWarService.seasonRank(seasonId, topN)) {
            b.addRanks(GuildSystemProto.GuildWarRankEntry.newBuilder()
                    .setGuildId(e.guildId())
                    .setPoints(e.points())
                    .setWins(e.wins())
                    .setLosses(e.losses())
                    .setRank(e.rank())
                    .build());
        }
        return b.build();
    }

    /** 将公会战比赛实体映射为协议消息（空值统一转 0 / 空串，避免 protobuf 序列化异常）。 */
    private static GuildSystemProto.GuildWarMatchInfo toWarMatch(GuildWarService.MatchInfo m) {
        return GuildSystemProto.GuildWarMatchInfo.newBuilder()
                .setMatchId(m.matchId())
                .setSeasonId(m.seasonId())
                .setGuildAId(m.guildAId())
                .setGuildBId(m.guildBId())
                .setStatus(m.status() == null ? "" : m.status())
                .setScoreA(m.scoreA())
                .setScoreB(m.scoreB())
                .setWinnerGuildId(m.winnerGuildId() == null ? 0L : m.winnerGuildId())
                .build();
    }

    /** 将公会概要实体映射为协议消息（公告为空时下发空串）。 */
    private static GuildSystemProto.GuildSummary toSummary(GuildService.GuildInfo g) {
        return GuildSystemProto.GuildSummary.newBuilder()
                .setGuildId(g.guildId())
                .setName(g.name())
                .setNotice(g.notice() == null ? "" : g.notice())
                .setLevel(g.level())
                .setMemberCount(g.memberCount())
                .setMaxMembers(g.maxMembers())
                .setLeaderId(g.leaderId())
                .build();
    }
}
