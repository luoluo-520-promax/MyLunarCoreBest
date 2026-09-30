package cn.itcast.demo.mylunarcore.hall; // 声明本类归属大厅模块包，供 Spring 扫描并注册为大厅协议适配 Bean

import cn.itcast.demo.mylunarcore.assist.AssistAnswer;
import cn.itcast.demo.mylunarcore.assist.AssistChatMentionParser;
import cn.itcast.demo.mylunarcore.assist.AssistNettyService;
import cn.itcast.demo.mylunarcore.assist.AssistQuotaLimiter;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.FriendEntity; // 好友关系持久化实体，用于列表查询与协议转换
import cn.itcast.demo.mylunarcore.model.MailEntity; // 邮件持久化实体，承载标题、附件 JSON 等邮件业务数据
import cn.itcast.demo.mylunarcore.model.PlayerEntity; // 玩家基础资料实体，用于补全昵称、等级等展示字段
import cn.itcast.demo.mylunarcore.player.DataChangeScope; // 数据变更维度枚举，指定需通知客户端刷新的缓存分区
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver; // 从 Netty Channel 解析当前登录玩家身份，防止越权
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService; // 向在线客户端推送数据变更通知，保持本地缓存一致
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto; // 大厅系统 protobuf 消息定义，承载 CS/SC 请求响应结构
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository; // 玩家资料仓储，按 uid 加载昵称、等级等展示信息
import com.fasterxml.jackson.core.type.TypeReference; // Jackson 泛型类型引用，解析邮件附件 JSON 为 Map 列表
import com.fasterxml.jackson.databind.ObjectMapper; // JSON 序列化/反序列化工具，专用于邮件附件字段解析
import io.netty.channel.Channel; // Netty 连接通道，绑定当前会话并作为身份解析入口
import org.springframework.stereotype.Service; // Spring 服务层注解，将本类注册为可注入的单例 Bean

import java.util.List; // 列表容器，承载好友、邮件、排行榜等多条业务记录
import java.util.Map; // 键值映射，表示邮件附件中的道具/货币等通用奖励结构

/**
 * 大厅协议适配层。
 * <p>
 * 该类是大厅相关 protobuf 请求的统一入口，负责把“网络包”转换为“业务调用”，
 * 再把业务结果组装成响应包返回。它本身不保存领域状态，只协调下列职责：
 * <ol>
 *   <li>从 {@link Channel} 中识别当前登录玩家；</li>
 *   <li>调用好友、邮件、聊天、排行榜等应用服务；</li>
 *   <li>把领域对象转换为协议对象；</li>
 *   <li>在关键业务动作后触发数据变更通知，保持客户端缓存一致。</li>
 * </ol>
 * </p>
 * <p>
 * 统一错误码约定：1 表示未登录，其余错误码由具体业务定义并透传。
 * 这种设计可以让协议层足够薄，同时保持业务层可复用。
 * </p>
 */
@Service // 标记为 Spring 托管服务，由 Netty 包处理器注入并调度大厅相关协议
public class HallNettyService { // 大厅 Netty 协议门面类，不持有领域状态，仅编排业务调用与响应组装

    /** 好友业务服务，负责申请、回应和列表查询。 */
    private final FriendApplicationService friendApplicationService; // 处理好友申请、同意/拒绝及好友列表查询

    /** 邮件业务服务，负责分页查询与附件领取。 */
    private final MailApplicationService mailApplicationService; // 处理邮件分页拉取、总数统计与附件领取发奖

    /** 内存排行榜服务，负责 TopN 查询。 */
    private final LeaderboardService leaderboardService; // 按榜单类型与 TopN 参数返回内存排行榜条目

    /** 玩家资料仓储，用于补全昵称、等级等展示字段。 */
    private final PlayerDataRepository playerDataRepository; // 按玩家 uid 加载昵称、等级，供好友/聊天展示

    /**
     * 网络上下文解析器。
     * <p>
     * 大厅接口并不直接信任外部传入的 playerId，而是从当前 Channel 绑定状态中恢复身份，
     * 这样可以避免客户端伪造 uid 进行越权调用。
     * </p>
     */
    private final PlayerContextResolver contextResolver; // 从 Channel 会话绑定中解析 playerId/uid，拒绝伪造身份

    /** 数据变更通知服务，用来刷新客户端缓存。 */
    private final PlayerDataSyncService playerDataSyncService; // 业务变更后按维度通知客户端刷新本地玩家数据缓存

    /** 聊天服务，负责消息历史与广播。 */
    private final ChatService chatService; // 处理聊天发送校验、历史拉取及频道内消息广播

    /** 社交增量推送服务，用于好友、邮件等 SC_NOTIFY。 */
    private final SocialSyncService socialSyncService; // 向在线玩家推送好友列表等社交增量 SC_NOTIFY 包

    private final LunarCoreProperties properties;
    private final AssistQuotaLimiter assistQuotaLimiter;
    private final AssistNettyService assistNettyService;
    private final PartyService partyService;
    private final cn.itcast.demo.mylunarcore.party.PartyFollowMigrationService followMigrationService;

    /** 独立 JSON 解析器，用于邮件附件的通用 Map 转换。 */
    private final ObjectMapper objectMapper = new ObjectMapper(); // 专用于将邮件 attachmentsJson 反序列化为奖励 Map 列表

    public HallNettyService(FriendApplicationService friendApplicationService, // 注入好友应用服务
                            MailApplicationService mailApplicationService, // 注入邮件应用服务
                            LeaderboardService leaderboardService, // 注入排行榜查询服务
                            PlayerDataRepository playerDataRepository, // 注入玩家资料仓储
                            PlayerContextResolver contextResolver, // 注入网络会话身份解析器
                            PlayerDataSyncService playerDataSyncService, // 注入玩家数据同步通知服务
                            ChatService chatService, // 注入聊天业务服务
                            SocialSyncService socialSyncService,
                            LunarCoreProperties properties,
                            AssistQuotaLimiter assistQuotaLimiter,
                            AssistNettyService assistNettyService,
                            PartyService partyService) {
        this(friendApplicationService, mailApplicationService, leaderboardService, playerDataRepository,
                contextResolver, playerDataSyncService, chatService, socialSyncService, properties,
                assistQuotaLimiter, assistNettyService, partyService, null);
    }

    public HallNettyService(FriendApplicationService friendApplicationService,
                            MailApplicationService mailApplicationService,
                            LeaderboardService leaderboardService,
                            PlayerDataRepository playerDataRepository,
                            PlayerContextResolver contextResolver,
                            PlayerDataSyncService playerDataSyncService,
                            ChatService chatService,
                            SocialSyncService socialSyncService,
                            LunarCoreProperties properties,
                            AssistQuotaLimiter assistQuotaLimiter,
                            AssistNettyService assistNettyService,
                            PartyService partyService,
                            org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.party.PartyFollowMigrationService> followProvider) {
        this.friendApplicationService = friendApplicationService;
        this.mailApplicationService = mailApplicationService;
        this.leaderboardService = leaderboardService;
        this.playerDataRepository = playerDataRepository;
        this.contextResolver = contextResolver;
        this.playerDataSyncService = playerDataSyncService;
        this.chatService = chatService;
        this.socialSyncService = socialSyncService;
        this.properties = properties;
        this.assistQuotaLimiter = assistQuotaLimiter;
        this.assistNettyService = assistNettyService;
        this.partyService = partyService;
        this.followMigrationService = followProvider == null ? null : followProvider.getIfAvailable();
    }

    /**
     * 拉取好友列表。
     * retcode：0 成功，1 未登录。
     */
    public HallSystemProto.GetFriendListScRsp handleGetFriendList(Channel channel) { // 处理客户端拉取好友列表的 CS 请求
        int playerId = contextResolver.resolvePlayerId(channel); // 从当前连接会话解析登录玩家 uid，不信任包内 playerId
        if (playerId <= 0) { // 未绑定有效登录身份时，拒绝执行业务并返回未登录错误码
            return HallSystemProto.GetFriendListScRsp.newBuilder().setRetcode(1).build(); // 组装 retcode=1 的空好友列表响应
        }
        List<FriendEntity> friends = friendApplicationService.listFriends(playerId); // 查询该玩家全部好友关系记录（含待确认状态）
        HallSystemProto.GetFriendListScRsp.Builder builder = // 创建成功响应构建器，后续逐条填充好友协议对象
                HallSystemProto.GetFriendListScRsp.newBuilder().setRetcode(0); // 预设 retcode=0 表示身份校验通过、查询成功
        for (FriendEntity friend : friends) { // 遍历每条好友关系，转换为客户端可展示的 FriendInfo
            builder.addFriends(toFriendInfo(friend, playerId)); // 解析“对方”玩家资料并追加到响应好友列表
        }
        return builder.build(); // 返回包含完整好友列表的 SC 响应包
    }

    /**
     * 发送好友申请。
     * retcode：0 成功，1 未登录；2~4 由 {@link FriendApplicationService#sendRequest} 透传。
     */
    public HallSystemProto.SendFriendRequestScRsp handleSendFriendRequest( // 处理向目标玩家发起好友申请的 CS 请求
            HallSystemProto.SendFriendRequestCsReq req, Channel channel) { // req 携带目标玩家 id 与申请附言 remark
        int playerId = contextResolver.resolvePlayerId(channel); // 从 Channel 获取申请人真实 uid，防止伪造发起方
        if (playerId <= 0) { // 申请人未登录则无法创建好友申请记录
            return HallSystemProto.SendFriendRequestScRsp.newBuilder().setRetcode(1).build(); // 返回未登录错误，不写入任何好友数据
        }
        FriendApplicationService.FriendRequestResult result = // 承载申请业务结果：成功标志与具体错误码
                friendApplicationService.sendRequest(playerId, req.getTargetPlayerId(), req.getRemark()); // 校验目标合法性并持久化好友申请
        if (!result.success()) { // 目标不存在、已是好友、重复申请等业务失败时直接透传错误码
            return HallSystemProto.SendFriendRequestScRsp.newBuilder().setRetcode(result.retcode()).build(); // 将业务层 retcode 原样返回给客户端
        }
        // 通知客户端刷新 FRIENDS 维度缓存，并向在线玩家推送增量好友列表
        notifyFriends(channel); // 触发 FRIENDS 维度数据变更通知，让客户端主动拉取最新好友缓存
        pushFriendNotify(channel, playerId); // 向当前在线玩家推送 FRIEND_LIST_UPDATE_SC_NOTIFY 增量快照
        return HallSystemProto.SendFriendRequestScRsp.newBuilder() // 构建申请成功的 SC 响应
                .setRetcode(0) // 表示好友申请已成功写入并进入待对方确认状态
                .setTargetPlayerId(req.getTargetPlayerId()) // 回显申请目标 id，便于客户端 UI 确认操作对象
                .build(); // 序列化为 protobuf 响应包发回客户端
    }

    /**
     * 回应好友申请（同意/拒绝）。
     * retcode：0 成功，1 未登录；2 找不到待处理申请。
     */
    public HallSystemProto.RespondFriendRequestScRsp handleRespondFriendRequest( // 处理同意或拒绝好友申请的 CS 请求
            HallSystemProto.RespondFriendRequestCsReq req, Channel channel) { // req 含申请人 id 与 accept 布尔标志
        int playerId = contextResolver.resolvePlayerId(channel); // 解析当前操作者 uid，即被申请方玩家身份
        if (playerId <= 0) { // 未登录玩家不能处理任何待确认的好友申请
            return HallSystemProto.RespondFriendRequestScRsp.newBuilder().setRetcode(1).build(); // 返回未登录错误码
        }
        FriendApplicationService.FriendRequestResult result = // 承载回应操作的业务执行结果
                friendApplicationService.respond(playerId, req.getRequesterPlayerId(), req.getAccept()); // 同意则建立双向好友，拒绝则关闭申请
        if (!result.success()) { // 找不到对应待处理申请或状态已变更时失败
            return HallSystemProto.RespondFriendRequestScRsp.newBuilder().setRetcode(result.retcode()).build(); // 透传业务错误码（如申请不存在）
        }
        notifyFriends(channel); // 好友关系变更后通知客户端刷新 FRIENDS 缓存维度
        pushFriendNotify(channel, playerId); // 推送最新好友列表快照给当前在线玩家
        return HallSystemProto.RespondFriendRequestScRsp.newBuilder() // 构建回应成功的 SC 响应
                .setRetcode(0) // 表示申请已被成功处理（同意或拒绝均已落库）
                .setRequesterPlayerId(req.getRequesterPlayerId()) // 回显申请人 id，供客户端更新对应申请项 UI
                .build(); // 返回 protobuf 响应
    }

    /**
     * 分页拉取邮件列表。
     * retcode：0 成功，1 未登录。
     * page 默认 1，pageSize 默认 20、上限 50。
     */
    public HallSystemProto.GetMailListScRsp handleGetMailList( // 处理客户端分页拉取收件箱邮件的 CS 请求
            HallSystemProto.GetMailListCsReq req, Channel channel) { // req 指定页码 page 与每页条数 pageSize
        int playerId = contextResolver.resolvePlayerId(channel); // 解析当前收件箱所属玩家 uid
        if (playerId <= 0) { // 未登录无法查询个人邮件
            return HallSystemProto.GetMailListScRsp.newBuilder().setRetcode(1).build(); // 返回未登录错误，邮件列表为空
        }
        int page = req.getPage() <= 0 ? 1 : req.getPage(); // 非法页码归零时默认第 1 页，避免仓储层偏移异常
        int pageSize = req.getPageSize() <= 0 ? 20 : Math.min(req.getPageSize(), 50); // 默认每页 20 条，上限 50 条防止单次拉取过大
        List<MailEntity> mails = mailApplicationService.listMails(playerId, page, pageSize); // 按玩家与分页参数查询当前页邮件实体
        HallSystemProto.GetMailListScRsp.Builder builder = HallSystemProto.GetMailListScRsp.newBuilder() // 创建邮件列表响应构建器
                .setRetcode(0) // 查询身份校验通过
                .setTotalCount((int) mailApplicationService.countMails(playerId)); // 附带邮件总条数，供客户端计算分页 UI
        for (MailEntity mail : mails) { // 将当前页每条邮件实体转为协议 MailInfo
            builder.addMails(toMailInfo(mail)); // 解析附件 JSON 并填充标题、状态、时间戳等字段
        }
        return builder.build(); // 返回含分页邮件与总数的 SC 响应
    }

    /**
     * 领取邮件附件。
     * retcode：0 成功，1 未登录；2 邮件不存在，3 已领取，4 发奖失败。
     */
    public HallSystemProto.ClaimMailScRsp handleClaimMail( // 处理领取指定邮件附件奖励的 CS 请求
            HallSystemProto.ClaimMailCsReq req, Channel channel) { // req 携带待领取邮件的唯一 mailId
        int playerId = contextResolver.resolvePlayerId(channel); // 解析领取操作执行者 uid，确保只能领自己的邮件
        if (playerId <= 0) { // 未登录玩家无权领取任何邮件附件
            return HallSystemProto.ClaimMailScRsp.newBuilder().setRetcode(1).build(); // 返回未登录错误码
        }
        MailApplicationService.ClaimResult result = mailApplicationService.claim(playerId, req.getMailId()); // 校验邮件归属与领取状态，并发放道具/货币
        if (!result.success()) { // 邮件不存在、已领取或发奖流程失败时中止
            return HallSystemProto.ClaimMailScRsp.newBuilder().setRetcode(result.retcode()).build(); // 透传 2/3/4 等业务错误码
        }
        // 领取成功：在线发奖已由 commit 推送 DATA_CHANGE，无需再 notifyCoreAndItems
        HallSystemProto.ClaimMailScRsp.Builder builder = HallSystemProto.ClaimMailScRsp.newBuilder() // 构建领取成功响应
                .setRetcode(0) // 表示附件已成功发放且邮件状态已更新为已领取
                .setMailId(req.getMailId()); // 回显已领取的邮件 id，供客户端标记对应邮件项
        for (Map<String, Object> attachment : result.attachments()) { // 遍历本次实际发放的每条奖励记录
            builder.addClaimed(toAttachment(attachment)); // 将道具/货币奖励转为协议 MailAttachment 填入响应
        }
        return builder.build(); // 返回含已领取附件明细的 SC 响应，客户端可展示获得奖励弹窗
    }

    /**
     * 查询排行榜 TopN。
     * retcode：0 成功，1 未登录。
     */
    public HallSystemProto.GetLeaderboardScRsp handleGetLeaderboard( // 处理查询指定榜单 TopN 排名的 CS 请求
            HallSystemProto.GetLeaderboardCsReq req, Channel channel) { // req 指定榜单类型 boardType 与名次上限 topN
        int playerId = contextResolver.resolvePlayerId(channel); // 解析请求者身份，排行榜查询亦要求已登录
        if (playerId <= 0) { // 未登录不允许查看排行榜（与业务鉴权策略一致）
            return HallSystemProto.GetLeaderboardScRsp.newBuilder().setRetcode(1).build(); // 返回未登录错误
        }
        List<LeaderboardService.Entry> entries = // 内存排行榜条目列表，含名次、玩家 id、昵称、分数
                leaderboardService.topN(req.getBoardType(), req.getTopN()); // 按榜单类型取前 N 名，数据来源于内存排行服务
        HallSystemProto.GetLeaderboardScRsp.Builder builder = HallSystemProto.GetLeaderboardScRsp.newBuilder() // 创建排行榜响应构建器
                .setRetcode(0) // 查询成功
                .setBoardType(req.getBoardType()); // 回显请求的榜单类型，便于客户端匹配 UI  Tab
        for (LeaderboardService.Entry entry : entries) { // 将每条排行记录转为协议 LeaderboardEntry
            builder.addEntries(HallSystemProto.LeaderboardEntry.newBuilder() // 为单条排名创建协议条目构建器
                    .setRank(entry.rank()) // 写入名次（1 为榜首）
                    .setPlayerId(entry.playerId()) // 写入上榜玩家 uid
                    .setNickname(entry.nickname()) // 写入展示用昵称
                    .setScore(entry.score()) // 写入排行分数（战力、等级等依榜单类型而定）
                    .build()); // 完成单条排行条目并追加到响应列表
        }
        return builder.build(); // 返回完整 TopN 排行榜 SC 响应
    }

    /**
     * 发送聊天消息。
     * retcode：0 成功，1 未登录，2 内容为空（由 {@link ChatService#send} 返回）。
     */
    public HallSystemProto.SendChatScRsp handleSendChat( // 处理玩家在指定频道发送聊天内容的 CS 请求
            HallSystemProto.SendChatCsReq req, Channel channel) { // req 含频道类型、目标 id（私聊对象）与消息正文
        int playerId = contextResolver.resolvePlayerId(channel); // 解析发言者 uid，消息发送者必须与当前会话一致
        if (playerId <= 0) { // 未登录玩家不能在大厅频道发言
            return HallSystemProto.SendChatScRsp.newBuilder().setRetcode(1).build(); // 返回未登录错误
        }
        PlayerEntity player = playerDataRepository.loadPlayerByUid(playerId); // 加载发言者资料，用于获取真实昵称展示给频道其他玩家
        String nickname = player == null || player.getNickname() == null // 资料缺失或昵称为空时使用兜底展示名
                ? "Player" + playerId : player.getNickname(); // 兜底格式为 Player{uid}，否则使用玩家设置的昵称
        String content = req.getContent();
        String assistantQuestion = extractAssistantQuestion(content);
        int retcode = chatService.send(playerId, nickname, req.getChannelType(), // 将消息写入历史并按频道类型广播给在线玩家
                req.getTargetId(), content); // targetId 在私聊场景为对方 uid，公频可为 0 或频道 id
        if (retcode == 0 && assistantQuestion != null) {
            handleAssistantMention(playerId, channel, assistantQuestion);
        }
        return HallSystemProto.SendChatScRsp.newBuilder().setRetcode(retcode).build(); // 返回发送结果：0 成功，2 表示内容为空等
    }

    /**
     * 解析「@助手 问题」；未命中返回 null。
     */
    String extractAssistantQuestion(String content) {
        if (!properties.getAiAssist().isEnabled() || !properties.getAiAssist().isChatMentionEnabled()) {
            return null;
        }
        return AssistChatMentionParser.extractQuestion(content, properties.getAiAssist().getChatMentionPrefix());
    }

    private void handleAssistantMention(int playerId, Channel channel, String question) {
        long uid = contextResolver.resolveUid(channel).orElse(playerId);
        if (!assistQuotaLimiter.tryAcquire(uid)) {
            chatService.replyAsAssistant(playerId, "助手请求过于频繁，请稍后再试。");
            return;
        }
        AssistAnswer answer = assistNettyService.askAndNotify(uid, channel, question, "general");
        String reply = answer.answer();
        if (reply == null || reply.isBlank()) {
            reply = "助手暂时无法回答，请稍后再试。";
        }
        chatService.replyAsAssistant(playerId, reply);
    }

    /**
     * 拉取聊天历史。
     * retcode：0 成功，1 未登录。
     */
    public HallSystemProto.GetChatHistoryScRsp handleGetChatHistory( // 处理拉取指定频道近期聊天历史的 CS 请求
            HallSystemProto.GetChatHistoryCsReq req, Channel channel) { // req 含频道类型、目标 id 与拉取条数上限 limit
        int playerId = contextResolver.resolvePlayerId(channel); // 解析请求者身份，历史拉取要求已登录
        if (playerId <= 0) { // 未登录无法查看聊天历史记录
            return HallSystemProto.GetChatHistoryScRsp.newBuilder().setRetcode(1).build(); // 返回未登录错误
        }
        HallSystemProto.GetChatHistoryScRsp.Builder builder = // 创建聊天历史响应构建器
                HallSystemProto.GetChatHistoryScRsp.newBuilder().setRetcode(0); // 身份校验通过，准备填充历史消息列表
        for (ChatService.ChatRecord record : chatService.history( // 从聊天服务按频道与条数上限获取近期消息记录
                req.getChannelType(), req.getTargetId(), req.getLimit())) { // 私聊时 targetId 为对方 uid，公频按频道维度查询
            builder.addMessages(ChatService.toProto(record)); // 将内存聊天记录转为 protobuf 消息结构追加到响应
        }
        return builder.build(); // 返回按时间顺序排列的聊天历史 SC 响应
    }

    /**
     * 向当前在线玩家推送最新好友列表快照（FRIEND_LIST_UPDATE_SC_NOTIFY）。
     */
    private void pushFriendNotify(Channel channel, int playerId) { // 好友关系变更后主动向在线玩家推送增量好友列表
        List<FriendEntity> friends = friendApplicationService.listFriends(playerId); // 重新查询该玩家最新好友关系全集
        List<HallSystemProto.FriendInfo> infos = // 协议层好友信息列表，供 SC_NOTIFY 包直接序列化
                friends.stream().map(f -> toFriendInfo(f, playerId)).toList(); // 将每条好友实体转为含对方昵称/等级的 FriendInfo
        contextResolver.resolveUid(channel) // 从 Channel 解析玩家对外 uid（可能与内部 playerId 映射不同）
                .ifPresent(uid -> socialSyncService.pushFriendUpdate(uid, infos)); // 仅当玩家仍在线时推送 FRIEND_LIST_UPDATE 通知
    }

    /**
     * 将 FriendEntity 转为协议 FriendInfo，自动解析“对方”playerId 并补全昵称/等级。
     */
    private HallSystemProto.FriendInfo toFriendInfo(FriendEntity friend, int selfId) { // 把双向好友关系记录转为客户端展示用的 FriendInfo
        int otherId = friend.getPlayerId1() == selfId ? friend.getPlayerId2() : friend.getPlayerId1(); // 从关系两端 uid 中识别“对方”玩家 id
        PlayerEntity player = playerDataRepository.loadPlayerByUid(otherId); // 加载对方玩家资料以补全昵称与等级
        return HallSystemProto.FriendInfo.newBuilder() // 构建协议好友信息对象
                .setPlayerId(otherId) // 设置好友列表中展示的玩家 uid（对方）
                .setNickname(player == null || player.getNickname() == null // 对方资料缺失时使用兜底昵称
                        ? "Player" + otherId : player.getNickname()) // 有昵称则展示真实昵称
                .setLevel(player == null ? 1 : player.getLevel()) // 资料缺失时默认 1 级，否则展示真实等级
                .setStatus(friend.getStatus()) // 写入好友关系状态（如正常、待确认、已拉黑等）
                .setRemark(friend.getRemark() == null ? "" : friend.getRemark()) // 写入好友备注，空值转空串避免 protobuf null
                .build(); // 返回完整的 FriendInfo 协议对象
    }

    /**
     * 将 MailEntity 转为协议 MailInfo，含附件列表与时间戳字段。
     */
    private HallSystemProto.MailInfo toMailInfo(MailEntity mail) { // 把持久化邮件实体转为客户端收件箱展示的 MailInfo
        HallSystemProto.MailInfo.Builder builder = HallSystemProto.MailInfo.newBuilder() // 创建邮件协议对象构建器
                .setMailId(mail.getId()) // 设置邮件唯一 id，领取附件时客户端凭此 id 发起请求
                .setTitle(mail.getTitle() == null ? "" : mail.getTitle()) // 设置邮件标题，空标题转空串
                .setContent(mail.getContent() == null ? "" : mail.getContent()) // 设置邮件正文内容
                .setStatus(mail.getStatus()) // 设置邮件状态（未读、已读、已领取等）
                .setSendTime(mail.getSendTime() == null ? 0 : mail.getSendTime().getTime()) // 发送时间转毫秒时间戳，无则填 0
                .setExpireTime(mail.getExpireTime() == null ? 0 : mail.getExpireTime().getTime()); // 过期时间转毫秒时间戳，供客户端显示倒计时
        for (Map<String, Object> attachment : parseAttachments(mail.getAttachmentsJson())) { // 解析附件 JSON 为通用奖励 Map 列表
            builder.addAttachments(toAttachment(attachment)); // 将每条奖励转为 MailAttachment 协议结构
        }
        return builder.build(); // 返回含正文、状态、时间戳及附件列表的完整 MailInfo
    }

    /**
     * 将通用附件 Map 转为协议 MailAttachment。
     */
    private HallSystemProto.MailAttachment toAttachment(Map<String, Object> attachment) { // 把 JSON 附件条目转为强类型 protobuf 附件结构
        HallSystemProto.MailAttachment.Builder builder = HallSystemProto.MailAttachment.newBuilder(); // 创建单条附件协议构建器
        Number itemId = (Number) attachment.get("itemId"); // 读取道具类奖励的道具配置 id（可能为 null）
        Number count = (Number) attachment.get("count"); // 读取道具类奖励的数量（可能为 null）
        Number currencyId = (Number) attachment.get("currencyId"); // 读取货币类奖励的货币类型 id（可能为 null）
        Number currencyAmount = (Number) attachment.get("currencyAmount"); // 读取货币类奖励的数量（可能为 null）
        if (itemId != null) { // 附件含道具奖励时写入道具 id 字段
            builder.setItemId(itemId.intValue()); // 将 Number 转为 int 填入协议 itemId
        }
        if (count != null) { // 附件含道具数量时写入 count 字段
            builder.setCount(count.intValue()); // 将道具数量转为 int 填入协议
        }
        if (currencyId != null) { // 附件含货币奖励时写入货币类型 id
            builder.setCurrencyId(currencyId.intValue()); // 将货币类型转为 int 填入协议
        }
        if (currencyAmount != null) { // 附件含货币数量时写入货币数额字段
            builder.setCurrencyAmount(currencyAmount.intValue()); // 将货币数量转为 int 填入协议
        }
        return builder.build(); // 返回单条 MailAttachment，可能仅含道具或仅含货币或两者兼有
    }

    /**
     * 解析邮件实体中的 attachmentsJson 字符串；失败或空串时返回空列表。
     */
    private List<Map<String, Object>> parseAttachments(String json) { // 将数据库中存储的附件 JSON 字符串反序列化为奖励列表
        if (json == null || json.isBlank()) { // 无附件或空字符串时无需解析
            return List.of(); // 返回不可变空列表，避免 NPE 且表示该邮件无附件
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {}); // 按 List<Map<String,Object>> 结构解析 JSON 附件数组
        } catch (Exception e) { // JSON 格式损坏或字段类型不匹配时容错
            return List.of(); // 解析失败时返回空列表，邮件仍可展示但附件列表为空，不影响列表接口稳定性
        }
    }

    /** 通知客户端刷新好友列表缓存（FRIENDS 维度）。 */
    private void notifyFriends(Channel channel) { // 好友申请/回应成功后触发客户端 FRIENDS 缓存刷新
        contextResolver.resolveUid(channel) // 从当前连接解析玩家对外 uid
                .ifPresent(uid -> playerDataSyncService.notifyDataChanged(uid, DataChangeScope.FRIENDS)); // 通知该在线玩家拉取最新好友数据
    }

    // ---------- Party ----------

    public HallSystemProto.CreatePartyScRsp handleCreateParty(Channel channel) {
        long uid = contextResolver.resolveUid(channel).orElse(0L);
        if (uid <= 0) {
            return HallSystemProto.CreatePartyScRsp.newBuilder().setRetcode(1).build();
        }
        PartyService.PartyResult result = partyService.create(uid);
        return HallSystemProto.CreatePartyScRsp.newBuilder()
                .setRetcode(mapPartyCode(result.code()))
                .setParty(toPartyInfo(result.party()))
                .build();
    }

    public HallSystemProto.InvitePartyScRsp handleInviteParty(HallSystemProto.InvitePartyCsReq req, Channel channel) {
        long uid = contextResolver.resolveUid(channel).orElse(0L);
        if (uid <= 0) {
            return HallSystemProto.InvitePartyScRsp.newBuilder().setRetcode(1).build();
        }
        PartyService.PartyResult result = partyService.invite(uid, req.getTargetUid());
        if (result.code() == PartyService.PartyResultCode.OK && result.party() != null
                && followMigrationService != null) {
            followMigrationService.followAfterInvite(result.party(), req.getTargetUid());
        }
        return HallSystemProto.InvitePartyScRsp.newBuilder()
                .setRetcode(mapPartyCode(result.code()))
                .setParty(toPartyInfo(result.party()))
                .build();
    }

    public HallSystemProto.LeavePartyScRsp handleLeaveParty(Channel channel) {
        long uid = contextResolver.resolveUid(channel).orElse(0L);
        if (uid <= 0) {
            return HallSystemProto.LeavePartyScRsp.newBuilder().setRetcode(1).build();
        }
        PartyService.PartyResult result = partyService.leave(uid);
        return HallSystemProto.LeavePartyScRsp.newBuilder()
                .setRetcode(mapPartyCode(result.code()))
                .setParty(toPartyInfo(result.party()))
                .build();
    }

    public HallSystemProto.DisbandPartyScRsp handleDisbandParty(Channel channel) {
        long uid = contextResolver.resolveUid(channel).orElse(0L);
        if (uid <= 0) {
            return HallSystemProto.DisbandPartyScRsp.newBuilder().setRetcode(1).build();
        }
        PartyService.PartyResult result = partyService.disband(uid);
        return HallSystemProto.DisbandPartyScRsp.newBuilder()
                .setRetcode(mapPartyCode(result.code()))
                .build();
    }

    public HallSystemProto.GetPartyInfoScRsp handleGetPartyInfo(Channel channel) {
        long uid = contextResolver.resolveUid(channel).orElse(0L);
        if (uid <= 0) {
            return HallSystemProto.GetPartyInfoScRsp.newBuilder().setRetcode(1).build();
        }
        PartyService.Party party = partyService.getByUid(uid);
        if (party == null) {
            return HallSystemProto.GetPartyInfoScRsp.newBuilder().setRetcode(2).build();
        }
        return HallSystemProto.GetPartyInfoScRsp.newBuilder()
                .setRetcode(0)
                .setParty(toPartyInfo(party))
                .build();
    }

    private static int mapPartyCode(PartyService.PartyResultCode code) {
        return switch (code) {
            case OK -> 0;
            case ALREADY_IN_PARTY -> 2;
            case NOT_IN_PARTY -> 3;
            case NOT_LEADER -> 4;
            case TARGET_BUSY -> 5;
            case PARTY_FULL -> 6;
            case NOT_FOUND -> 7;
            case NOT_OWNER_NODE -> 8;
            case VERSION_CONFLICT -> 9;
        };
    }

    private static HallSystemProto.PartyInfo toPartyInfo(PartyService.Party party) {
        if (party == null) {
            return HallSystemProto.PartyInfo.getDefaultInstance();
        }
        HallSystemProto.PartyInfo.Builder b = HallSystemProto.PartyInfo.newBuilder()
                .setPartyId(party.partyId())
                .setLeaderUid(party.leaderUid());
        for (Long member : party.memberUids()) {
            b.addMembers(HallSystemProto.PartyMemberInfo.newBuilder()
                    .setPlayerUid(member)
                    .setIsLeader(member == party.leaderUid())
                    .build());
        }
        return b.build();
    }
}
