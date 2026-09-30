package cn.itcast.demo.mylunarcore.admin;

import org.springframework.security.core.AuthenticationException;

/**
 * 管理端登录失败次数过多导致的临时锁定。
 */
public class AccountLockedException extends AuthenticationException {

    public AccountLockedException(String msg) {
        super(msg);
    }
}
