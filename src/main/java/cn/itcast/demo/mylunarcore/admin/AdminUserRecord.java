// 后台 RBAC 领域模型与数据访问所在包
package cn.itcast.demo.mylunarcore.admin;

/**
 * 后台用户表 {@code admin_user} 行。
 * <p>不可变记录：id、用户名、密码哈希、账号状态。</p>
 */
public record AdminUserRecord( // 后台用户记录
        long id, // 用户主键
        String username, // 登录用户名
        String passwordHash, // 密码哈希
        int status // 账号状态
) {
    // record 自动生成构造器、getter（id()、username() 等）
}
