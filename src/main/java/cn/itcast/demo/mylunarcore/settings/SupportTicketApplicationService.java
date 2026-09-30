package cn.itcast.demo.mylunarcore.settings; // 客服工单应用服务所在包

import org.springframework.stereotype.Service; // 业务服务 Bean

import java.util.List; // 工单列表
import java.util.Locale; // 分类小写归一
import java.util.Optional; // 按 id 查询
import java.util.Set; // 合法分类白名单

/**
 * 客服工单应用服务：玩家提交与查询、客服回复与关闭、分类与长度校验。
 */
@Service // 注册为 Spring 服务
public class SupportTicketApplicationService {

    /**
     * 状态：待处理（刚提交）
     */
    public static final int STATUS_OPEN = 0;
    /**
     * 状态：处理中（客服已接单，预留）
     */
    public static final int STATUS_PROCESSING = 1;
    /**
     * 状态：已回复（客服写入 admin_reply 后）
     */
    public static final int STATUS_REPLIED = 2;
    /**
     * 状态：已关闭（结案，不再跟进）
     */
    public static final int STATUS_CLOSED = 3;

    /**
     * 允许的工单分类；与设置分区对应，非法值归为 other
     */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "display", "sound", "keybind", "gameplay", "other"); // 画面/声音/按键/细节/其它

    /**
     * 单玩家工单总数上限，防止刷单占库（含历史单；可按需改为仅统计未关闭）
     */
    private static final int MAX_OPEN_PER_PLAYER = 20;
    /**
     * 标题最大字符数，与表 subject varchar(128) 对齐
     */
    private static final int MAX_SUBJECT_LEN = 128;
    /**
     * 正文与回复最大字符数，与表 content/admin_reply varchar(2000) 对齐
     */
    private static final int MAX_CONTENT_LEN = 2000;

    /**
     * 工单表仓储
     */
    private final SupportTicketRepository repository;

    /**
     * 构造注入仓储
     */
    public SupportTicketApplicationService(SupportTicketRepository repository) {
        this.repository = repository; // 保存持久化依赖
    } // 构造结束

    /**
     * 玩家提交工单。
     * <p>retcode：0 成功；2 标题或正文为空；3 超过数量上限；4 插入未拿到主键。
     *
     * @param playerId 玩家 uid
     * @param category 原始分类，会 normalize
     * @param subject  标题
     * @param content  正文
     * @return 含成功标志、错误码与 ticketId 的结果对象
     */
    public SubmitResult submit(int playerId, String category, String subject, String content) {
        String normalizedCategory = normalizeCategory(category); // 白名单归一
        String normalizedSubject = trimTo(subject, MAX_SUBJECT_LEN); // 去首尾空白并截断标题
        String normalizedContent = trimTo(content, MAX_CONTENT_LEN); // 去首尾空白并截断正文
        if (normalizedSubject.isBlank() || normalizedContent.isBlank()) { // 标题或正文缺失
            return SubmitResult.fail(2); // retcode=2：内容不合法
        } // 空内容校验结束
        if (repository.countByPlayer(playerId) >= MAX_OPEN_PER_PLAYER) { // 已达上限
            return SubmitResult.fail(3); // retcode=3：拒绝继续提交
        } // 上限校验结束
        long id = repository.insert(playerId, normalizedCategory, normalizedSubject, normalizedContent); // 落库
        if (id <= 0) { // 自增主键未回填
            return SubmitResult.fail(4); // retcode=4：持久化异常
        } // 主键校验结束
        return SubmitResult.ok(id); // 成功带回 ticketId
    } // submit 结束

    /**
     * 玩家查询本人工单分页列表，并附带总数供客户端分页控件使用
     */
    public ListResult listForPlayer(int playerId, int page, int pageSize) {
        int total = repository.countByPlayer(playerId); // 该玩家工单总数
        List<SupportTicketEntity> tickets = repository.listByPlayer(playerId, page, pageSize); // 当前页数据
        return new ListResult(tickets, total); // 打包列表与总数
    } // listForPlayer 结束

    /**
     * 客服后台按状态分页拉单；status 为 null 表示不限状态
     */
    public List<SupportTicketEntity> listForAdmin(Integer status, int page, int pageSize) {
        return repository.listByStatus(status, page, pageSize); // 直接委托仓储
    } // listForAdmin 结束

    /**
     * 按工单 id 查询详情（玩家或客服均可，权限由上层控制）
     */
    public Optional<SupportTicketEntity> find(long ticketId) {
        return repository.findById(ticketId); // 主键查询
    } // find 结束

    /**
     * 客服回复：写入回复文本并将状态置为已回复；空回复返回 false
     */
    public boolean reply(long ticketId, String adminReply) {
        String reply = trimTo(adminReply, MAX_CONTENT_LEN); // 截断到列长上限
        if (reply.isBlank()) { // 不允许空回复
            return false; // 调用方返回 400
        } // 空回复校验结束
        return repository.reply(ticketId, reply, STATUS_REPLIED) > 0; // 更新成功则 true
    } // reply 结束

    /**
     * 关闭工单：保留已有 admin_reply，状态改为已关闭；工单不存在返回 false
     */
    public boolean close(long ticketId) {
        return repository.findById(ticketId) // 先确认存在
                .map(t -> repository.reply(ticketId,
                        t.getAdminReply() == null ? "" : t.getAdminReply(), // 关闭时不丢回复，null 写成空串
                        STATUS_CLOSED) > 0) // 状态改为关闭
                .orElse(false); // 无此工单
    } // close 结束

    /**
     * 将客户端传入的分类归一为小写白名单值；空或未知归为 other
     */
    static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) { // 未传分类
            return "other"; // 默认其它
        } // 空值结束
        String c = category.trim().toLowerCase(Locale.ROOT); // 去空白并小写，忽略 Display 等大小写差异
        return ALLOWED_CATEGORIES.contains(c) ? c : "other"; // 白名单外一律 other
    } // normalizeCategory 结束

    /**
     * 去掉首尾空白并截断到 max 长度，避免超出 DB 列定义
     */
    private static String trimTo(String value, int max) {
        if (value == null) { // null 视为空
            return ""; // 统一成空串便于 isBlank 判断
        } // null 结束
        String trimmed = value.trim(); // 去首尾空白
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max); // 超长截断，不抛异常
    } // trimTo 结束

    /**
     * 提交结果：success 是否成功，retcode 业务码，ticketId 成功时的工单号
     */
    public record SubmitResult(boolean success, int retcode, long ticketId) {
        /**
         * 构造成功结果，retcode 固定 0
         */
        static SubmitResult ok(long ticketId) {
            return new SubmitResult(true, 0, ticketId); // 成功携带新 id
        } // ok 结束

        /**
         * 构造失败结果，ticketId 固定 0
         */
        static SubmitResult fail(int retcode) {
            return new SubmitResult(false, retcode, 0L); // 失败不分配工单号
        } // fail 结束
    } // SubmitResult 结束

    /**
     * 玩家列表查询结果：当前页 tickets 与总数 total
     */
    public record ListResult(List<SupportTicketEntity> tickets, int total) {
    } // ListResult 结束
} // SupportTicketApplicationService 结束
