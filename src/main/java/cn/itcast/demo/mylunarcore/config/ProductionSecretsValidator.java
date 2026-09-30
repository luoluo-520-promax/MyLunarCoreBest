// 生产环境密钥安全校验类所在包
package cn.itcast.demo.mylunarcore.config;

// 统一日志工厂：按日志分类创建 Logger
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举：SYSTEM 表示系统/框架级日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志门面接口
import org.slf4j.Logger;
// 限定 Bean 生效的 profile：本组件仅在 prod profile 下注册
import org.springframework.context.annotation.Profile;
// 访问 Spring 运行时环境：读取系统属性、环境变量、配置文件值
import org.springframework.core.env.Environment;
// 声明为 Spring 管理的组件
import org.springframework.stereotype.Component;

// 可变列表：按顺序收集所有校验错误
import java.util.ArrayList;
// 列表接口
import java.util.List;
// 不可变集合：保存禁止使用的弱密码 / 弱令牌
import java.util.Set;

/**
 * prod profile 启动时 fail-fast：拒绝默认/缺失密钥进入生产。
 * <p>
 * 职责范围：数据库密码、TLS 密钥库密码、内部 API Token、KCP 会话加密、
 * 协议层 HMAC、IAP 模拟验签、管理后台 IP 白名单。任何一项不满足生产安全基线，
 * 应用立即启动失败，防止使用演示默认值上线导致数据泄露或被攻击。
 */
@Component // 注册为 Spring Bean（构造器在启动阶段执行）
@Profile("prod") // 仅在 prod profile 激活时注册，dev/test 环境不拦截本地调试
public class ProductionSecretsValidator {

    // 本类专用日志对象：归类到 SYSTEM 分类
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ProductionSecretsValidator.class);

    /**
     * 密码类弱值黑名单：出现在 Spring 官方文档示例、常见脚手架默认值中的弱口令。
     * 命中即拒绝（忽略大小写比较，防 "ChangeIt" 之类绕过）。
     */
    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "changeit", "123456", "password", "root", "admin"
    );

    /**
     * 内部 Token 弱值黑名单：默认/演示用令牌，命中即拒绝。
     * 与 {@link #FORBIDDEN_PASSWORDS} 分开维护，因为两类字段的语义不同。
     */
    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
            "dev-internal-token", "change-me", "changeme", "secret"
    );

    /**
     * 构造器：执行全部校验，存在错误则抛异常终止应用启动。
     *
     * @param environment Spring 环境抽象（由容器注入），可读取配置文件/环境变量
     */
    public ProductionSecretsValidator(Environment environment) {
        // 汇总所有校验项的错误描述（可能多条，一次性全部报出便于一次性修复）
        List<String> errors = validate(environment);
        if (!errors.isEmpty()) {
            // 把多条错误用分号连接成一条信息，随异常抛出让启动流程中止
            String message = "Production secrets validation failed: " + String.join("; ", errors);
            // 输出 error 日志保留现场，便于后续追溯
            log.error(message);
            // fail-fast：抛 IllegalStateException，Spring 启动中断
            throw new IllegalStateException(message);
        }
        // 全部通过时输出确认日志
        log.info("Production secrets validation passed");
    }

    /**
     * 执行全部生产密钥校验项，返回错误描述列表（为空表示全部通过）。
     * <p>
     * 校验顺序与优先级：
     * <ol>
     *   <li>数据库密码必填且非弱口令；</li>
     *   <li>TLS 密钥库/私钥密码不得使用默认值；</li>
     *   <li>内部 API Token 必填、非弱值、具备足够强度、必须来自环境变量；</li>
     *   <li>生产必须启用 KCP 会话加密或 TCP+TLS；</li>
     *   <li>生产必须启用协议层 HMAC；</li>
     *   <li>IAP 禁止 mock 验签；</li>
     *   <li>管理后台必须配置 IP 白名单。</li>
     * </ol>
     *
     * @param environment Spring 环境抽象
     * @return 错误描述列表；空列表 = 校验全部通过
     */
    public static List<String> validate(Environment environment) {
        // 依次收集错误，保持报告顺序稳定
        List<String> errors = new ArrayList<>();
        // 数据库密码：优先取配置项，其次取 DB_PASSWORD 环境变量（配置项可能被占位符覆盖）
        String dbPassword = firstNonBlank(
                environment.getProperty("spring.datasource.password"),
                environment.getProperty("DB_PASSWORD"));
        if (dbPassword == null) {
            // 完全缺失：生产不允许无密码数据库
            errors.add("spring.datasource.password / DB_PASSWORD is required");
        } else if (FORBIDDEN_PASSWORDS.contains(dbPassword.trim().toLowerCase())) {
            // 命中弱口令黑名单（忽略大小写）
            errors.add("database password must not use a default/weak value");
        }
        // 三个 TLS 相关密码键逐一校验默认值（缺省时若生产启用 TLS 会在别处报错）
        rejectDefault(environment, "server.ssl.key-store-password", errors);
        rejectDefault(environment, "lunarcore.tls.key-store-password", errors);
        rejectDefault(environment, "lunarcore.tls.key-password", errors);
        // 内部 API Token 校验（必填 + 非弱值）
        rejectInternalToken(environment, errors);
        // 生产密钥必须来自环境变量（防止只在配置文件里写明文演示值）
        requireEnvBackedSecrets(environment, errors);
        // 密钥强度：长度与字符组成
        requireSecretStrength(environment, errors);
        // 生产强制 KCP 加密（或显式声明 TCP+TLS）
        requireKcpCryptoInProd(environment, errors);
        // 生产强制协议 HMAC
        requireProtocolHmacInProd(environment, errors);
        // 生产禁止 IAP mock 验签
        rejectIapMockInProd(environment, errors);
        // 生产必须配置管理后台 IP 白名单
        requireAdminIpWhitelistInProd(environment, errors);
        // allow-plaintext 逃生门与 TCP+TLS 的联动约束
        requireTcpTlsWhenAllowPlaintext(environment, errors);
        return errors;
    }

    /**
     * 密钥强度：长度 ≥32，且同时包含字母与数字（特殊字符推荐）。
     * 统一配合 Spring Cloud Config / K8s Secret / 环境变量注入，禁止配置文件明文弱默认。
     * <p>
     * 注意：本方法只校验"已被配置的值"；若两处配置都缺失，错误会在
     * {@link #requireEnvBackedSecrets} 中另行报告，避免重复报错。
     */
    private static void requireSecretStrength(Environment environment, List<String> errors) {
        // 取内部 API Token（环境变量优先于配置项，与运行时使用的取值顺序一致）
        String token = firstNonBlank(
                environment.getProperty("INTERNAL_API_TOKEN"),
                environment.getProperty("lunarcore.internal-api-token"));
        if (token != null && !token.isBlank()) {
            validateStrength("INTERNAL_API_TOKEN", token, errors);
        }
        // 取数据库密码（DB_PASSWORD 环境变量优先）
        String db = firstNonBlank(
                environment.getProperty("DB_PASSWORD"),
                environment.getProperty("spring.datasource.password"));
        if (db != null && !db.isBlank()) {
            validateStrength("DB_PASSWORD", db, errors);
        }
    }

    /**
     * 单条密钥的强度检查：长度≥32、必须同时含字母与数字、必须含特殊字符。
     *
     * @param name   密钥的展示名（用于错误信息定位）
     * @param value  密钥明文
     * @param errors 错误收集列表
     */
    private static void validateStrength(String name, String value, List<String> errors) {
        String v = value.trim();
        // 长度下限 32 字符：对高价值 Token/密码而言 32 位是常见安全基线
        if (v.length() < 32) {
            errors.add(name + " must be at least 32 characters");
        }
        // 逐字符检查是否含字母、数字
        boolean hasLetter = v.chars().anyMatch(Character::isLetter);
        boolean hasDigit = v.chars().anyMatch(Character::isDigit);
        // 非字母数字即视为特殊字符（空格也算，因此要求同时有字母与数字是有效的）
        boolean hasSpecial = v.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        if (!hasLetter || !hasDigit) {
            errors.add(name + " must contain letters and digits");
        }
        if (!hasSpecial) {
            errors.add(name + " must contain at least one special character");
        }
    }

    /** allow-plaintext 时必须开启游戏 TCP TLS，避免裸 UDP/TCP。 */
    private static void requireTcpTlsWhenAllowPlaintext(Environment environment, List<String> errors) {
        // 读取"允许明文"逃生门开关（默认 false）
        boolean allowPlain = Boolean.parseBoolean(
                firstNonBlank(environment.getProperty("lunarcore.kcp-crypto.allow-plaintext"), "false"));
        if (!allowPlain) {
            // 未开启逃生门则无约束，直接返回
            return;
        }
        // 允许明文时，强制要求 TCP 链路启用 TLS，否则游戏流量将以明文暴露
        boolean tcpTls = Boolean.parseBoolean(
                firstNonBlank(environment.getProperty("lunarcore.tls.game-tcp-enabled"), "false"));
        if (!tcpTls) {
            errors.add("lunarcore.tls.game-tcp-enabled must be true when kcp-crypto.allow-plaintext=true");
        }
    }

    /** 生产密钥必须来自环境变量，禁止仅依赖配置文件中的明文演示值。 */
    private static void requireEnvBackedSecrets(Environment environment, List<String> errors) {
        // 内部 Token：配置属性与进程环境变量都为空 → 缺失
        if (isBlank(environment.getProperty("INTERNAL_API_TOKEN"))
                && isBlank(System.getenv("INTERNAL_API_TOKEN"))) {
            errors.add("INTERNAL_API_TOKEN env var is required in prod (do not rely on application.properties alone)");
        }
        // 数据库密码同理：强制要求以环境变量注入（便于 K8s Secret/密钥管理系统挂载）
        if (isBlank(environment.getProperty("DB_PASSWORD"))
                && isBlank(System.getenv("DB_PASSWORD"))) {
            errors.add("DB_PASSWORD env var is required in prod");
        }
    }

    /** 生产强制开启 KCP 会话加密，或显式声明改用 TCP+TLS（lunarcore.kcp-crypto.allow-plaintext=true 禁止）。 */
    private static void requireKcpCryptoInProd(Environment environment, List<String> errors) {
        // KCP 会话加密开关（默认 false）
        boolean kcpEnabled = Boolean.parseBoolean(
                firstNonBlank(environment.getProperty("lunarcore.kcp-crypto.enabled"), "false"));
        // 允许明文逃生门（默认 false）
        boolean allowPlain = Boolean.parseBoolean(
                firstNonBlank(environment.getProperty("lunarcore.kcp-crypto.allow-plaintext"), "false"));
        // 两项都关 → 生产裸奔，必须报错；两项任一开启则通过
        if (!kcpEnabled && !allowPlain) {
            errors.add("lunarcore.kcp-crypto.enabled must be true in prod (or set allow-plaintext=true only for TCP+TLS-only deployments)");
        }
    }

    /** 生产强制协议 HMAC，防止帧篡改；与 KCP AES-GCM 正交。 */
    private static void requireProtocolHmacInProd(Environment environment, List<String> errors) {
        // 协议层命令号 HMAC 开关（默认 false）
        boolean hmac = Boolean.parseBoolean(
                firstNonBlank(environment.getProperty("lunarcore.protocol-hmac.enabled"), "false"));
        if (!hmac) {
            errors.add("lunarcore.protocol-hmac.enabled must be true in prod");
        }
    }

    /**
     * 双保险：即使 IapProductionGuard Bean 未装配，也在密钥校验阶段拒绝 mock 验签。
     */
    private static void rejectIapMockInProd(Environment environment, List<String> errors) {
        // 内购 mock 验签开关（默认 false）
        boolean mock = Boolean.parseBoolean(
                firstNonBlank(environment.getProperty("mylunarcore.iap.mock-verify"), "false"));
        if (mock) {
            errors.add("mylunarcore.iap.mock-verify must be false in prod");
        }
    }

    /** 生产必须配置管理后台 IP 白名单（VPN/内网），防止 8080 暴露公网。 */
    private static void requireAdminIpWhitelistInProd(Environment environment, List<String> errors) {
        // 管理后台 IP 白名单：配置项或 ADMIN_IP_WHITELIST 环境变量
        String wl = firstNonBlank(
                environment.getProperty("lunarcore.admin.ip-whitelist"),
                environment.getProperty("ADMIN_IP_WHITELIST"));
        // 空白 = 未配置 = 后台公网裸奔，生产必须拒绝
        if (wl == null || wl.isBlank()) {
            errors.add("lunarcore.admin.ip-whitelist / ADMIN_IP_WHITELIST is required in prod");
        }
    }

    /**
     * 判断字符串是否为空或全空白。
     *
     * @param value 待判断的字符串
     * @return true 表示 null 或全空白
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 内部 API Token 校验：必填 + 不得使用演示/弱值。
     * <p>
     * 同时检查四个可能的取值来源（其中两个是环境变量映射），
     * 只要任一来源给出有效值即认为已配置。
     */
    private static void rejectInternalToken(Environment environment, List<String> errors) {
        // 依次尝试：主配置项 → 环境变量 → AI 旁路 token 配置 → AI 旁路环境变量
        String token = firstNonBlank(
                environment.getProperty("lunarcore.internal-api-token"),
                environment.getProperty("INTERNAL_API_TOKEN"),
                environment.getProperty("lunarcore.ai-assist.remote-internal-token"),
                environment.getProperty("AI_ASSIST_INTERNAL_TOKEN"));
        if (token == null || token.isBlank()) {
            // 全部来源都为空 → 缺失
            errors.add("lunarcore.internal-api-token / INTERNAL_API_TOKEN is required");
            return;
        }
        // 命中弱值黑名单（忽略大小写）→ 拒绝
        if (FORBIDDEN_TOKENS.contains(token.trim().toLowerCase())) {
            errors.add("internal api token must not use a default/dev value");
        }
    }

    /**
     * 对单个配置键做弱口令黑名单检查。
     *
     * @param environment Spring 环境抽象
     * @param key         配置键名（用于取值与错误报告）
     * @param errors      错误收集列表
     */
    private static void rejectDefault(Environment environment, String key, List<String> errors) {
        // 读取配置键值（环境变量同名时会被环境变量覆盖）
        String value = environment.getProperty(key);
        // 命中弱口令黑名单（忽略大小写）→ 拒绝
        if (value != null && FORBIDDEN_PASSWORDS.contains(value.trim().toLowerCase())) {
            errors.add(key + " must not use default/weak value");
        }
    }

    /**
     * 返回首个非空白值（按传入顺序）；全部为空返回 null。
     * <p>
     * 作用：支持"配置项优先、环境变量兜底"或"环境变量优先、配置项兜底"
     * 的取值策略，由调用方的参数顺序决定优先级。
     *
     * @param values 候选值列表
     * @return 第一个非 null 且非空白的值；否则 null
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
