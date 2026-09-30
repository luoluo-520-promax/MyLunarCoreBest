package cn.itcast.demo.mylunarcore.settings; // 客服工单实体所在包

import lombok.Data; // 生成 getter/setter/equals/hashCode，字段 Javadoc 仍可供 IDE 悬停

import java.sql.Timestamp; // JDBC 时间戳，对应 created_at / updated_at

/**
 * 客服工单实体，一一映射表 support_ticket 的一行。
 * <p>玩家可通过设置相关问题提交工单；客服在后台回复后 status 变为已回复。
 */
@Data // Lombok：为下列字段生成访问器，便于 Repository 与 Admin 组装视图
public class SupportTicketEntity {
    /**
     * 工单主键，自增；协议 ticket_id 与此一致
     */
    private long id;
    /**
     * 提交工单的玩家 uid，与 player.uid / 会话 playerId 对齐
     */
    private int playerId;
    /**
     * 问题分类：display / sound / keybind / gameplay / other，便于客服筛选设置类咨询
     */
    private String category;
    /**
     * 工单标题，最长 128，展示在列表中
     */
    private String subject;
    /**
     * 工单正文，最长 2000，描述具体现象（如「高画质掉帧」）
     */
    private String content;
    /**
     * 工单状态：0=待处理，1=处理中，2=已回复，3=已关闭
     */
    private int status;
    /**
     * 客服回复正文；未回复时为 null，协议下发时转为空串
     */
    private String adminReply;
    /**
     * 创建时间（玩家提交时刻）
     */
    private Timestamp createdAt;
    /**
     * 最后更新时间（回复或关闭时刷新）
     */
    private Timestamp updatedAt;
} // SupportTicketEntity 结束
