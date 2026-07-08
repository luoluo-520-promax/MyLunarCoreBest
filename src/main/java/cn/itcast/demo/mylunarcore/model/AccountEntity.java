// 玩家账号领域实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：自动生成 getter/setter
import lombok.Data;

/**
 * 账号表 {@code account} 的行映射：登录凭证、封禁状态等。
 * <p>
 * {@code id} 在库中为字符串，与 {@code player.account_id}（无符号整型）通过 CAST 对齐，详见仓储层 SQL。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class AccountEntity {
    /**
     * account.id: VARCHAR(64)
     * 注意：player.account_id 是 INT UNSIGNED，这里解析时会做范围与格式校验。
     */
    private String id;       // 账号主键（字符串形式）
    private String username; // 登录名
    private String password; // 密码（演示可能明文，生产应哈希）
    private String email;    // 邮箱
    private String phone;    // 手机号
    private int status;      // 账号状态：正常/封禁等，具体枚举由业务定义
}
