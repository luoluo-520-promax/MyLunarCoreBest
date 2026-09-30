package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 统一账号密码哈希：Admin 与游戏账号共用 {@link PasswordEncoder}。
 * 明文无前缀比对默认关闭，仅迁移期可通过配置临时开启。
 */
@Service
public class AccountPasswordService {

    private final PasswordEncoder passwordEncoder;
    private final LunarCoreProperties properties;

    public AccountPasswordService(PasswordEncoder passwordEncoder, LunarCoreProperties properties) {
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String storedPassword) {
        if (rawPassword == null || storedPassword == null || storedPassword.isBlank()) {
            return false;
        }
        if (storedPassword.startsWith("{")) {
            return passwordEncoder.matches(rawPassword, storedPassword);
        }
        if (!properties.getSecurity().isAllowPlaintextPasswordMatch()) {
            return false;
        }
        return storedPassword.equals(rawPassword);
    }

    public boolean needsRehash(String storedPassword) {
        return storedPassword != null && !storedPassword.isBlank() && !storedPassword.startsWith("{");
    }
}
