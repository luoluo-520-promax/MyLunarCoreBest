// 邮件实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：自动生成 getter/setter 等样板代码
import lombok.Data;

// JDBC 时间戳类型，对应数据库 TIMESTAMP 列
import java.sql.Timestamp;

/**
 * 玩家邮件实体：对应表 {@code mail}，含标题、正文、附件与有效期等，
 * 由 {@link cn.itcast.demo.mylunarcore.hall.MailApplicationService} 负责发送、读取与过期清理。
 */
@Data // Lombok：生成 getter/setter 与 equals/hashCode 等
public class MailEntity {
    private long id;                 // 邮件主键，全表唯一
    private int playerId;            // 收件玩家 id
    private String title;            // 邮件标题
    private String content;          // 邮件正文
    private int status;              // 状态：未读/已读/已领奖/已删除等，具体枚举由业务定义
    private String attachmentsJson;  // 附件奖励 JSON 数组（[{itemId, count}, ...]），未领取时有效
    private Timestamp sendTime;      // 发送/入库时间
    private Timestamp expireTime;    // 过期时间，超过后邮件不可见或自动删除
    private Timestamp createdAt;     // 记录创建时间
    private Timestamp updatedAt;     // 记录最后更新时间
}
