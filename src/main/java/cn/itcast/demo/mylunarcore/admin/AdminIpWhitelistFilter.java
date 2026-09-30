// 管理后台 IP 白名单过滤器所在包
package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 管理后台 {@code /api/admin/**} IP 白名单：未配置白名单时放行（兼容本地开发）；
 * 配置后仅允许列表内地址（支持单 IP 与 CIDR，如 10.0.0.0/8）。
 * <p>
 * 安全目的：即使账号密码泄露，也尽量把后台访问限制在 VPN、堡垒机或内网出口。
 */
public class AdminIpWhitelistFilter extends OncePerRequestFilter {

    // 精确允许的 IP 列表（如 127.0.0.1、10.0.0.5）
    private final Set<String> allowExact;
    // CIDR 网段白名单（如 10.0.0.0/8）
    private final Set<Cidr> allowCidrs;
    // 是否启用白名单：只要 exact 或 cidr 任意一个非空就视为启用
    private final boolean enabled;

    /**
     * 从 {@code lunarcore.admin.ip-whitelist} 解析 IP 白名单。
     * <p>
     * 解析规则：以英文逗号分隔；每项若包含 {@code /} 则按 CIDR 解析，否则按精确 IP。
     * 空值表示未启用白名单，此时为了兼容本地开发直接放行。
     *
     * @param properties 全局配置对象
     */
    public AdminIpWhitelistFilter(LunarCoreProperties properties) {
        // 读取后台 IP 白名单原始字符串（可能为空）
        String raw = properties.getAdmin().getIpWhitelist();
        // 用 LinkedHashSet 保持配置顺序，便于调试输出时稳定
        Set<String> exact = new LinkedHashSet<>();
        Set<Cidr> cidrs = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            // 以逗号分隔多个白名单项
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                if (p.contains("/")) {
                    // CIDR 格式：如 10.0.0.0/8
                    Cidr c = Cidr.parse(p);
                    if (c != null) {
                        cidrs.add(c);
                    }
                } else {
                    // 单 IP：如 127.0.0.1
                    exact.add(p);
                }
            }
        }
        // 转成不可变集合，避免运行时被误改
        this.allowExact = Set.copyOf(exact);
        this.allowCidrs = Set.copyOf(cidrs);
        // 只要有任一白名单项就启用过滤
        this.enabled = !allowExact.isEmpty() || !allowCidrs.isEmpty();
    }

    /**
     * 只拦截 /api/admin/** 请求，其余路径直接放行给后续过滤器/控制器。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/admin/");
    }

    /**
     * 白名单拦截逻辑：
     * <ul>
     *   <li>未启用白名单时直接放行；</li>
     *   <li>启用后，取请求来源 IP（优先 X-Forwarded-For 首段）；</li>
     *   <li>若命中精确 IP 或 CIDR 网段则放行，否则返回 403 JSON。</li>
     * </ul>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 本地开发/联调未配置白名单时直接放行，避免阻塞开发效率
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }
        // 从请求中提取客户端 IP（兼容代理）
        String ip = clientIp(request);
        if (isAllowed(ip)) {
            // 命中白名单：继续后续过滤链
            filterChain.doFilter(request, response);
            return;
        }
        // 未命中：返回 403，告诉前端是网络隔离而非账号错误
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"admin_ip_not_allowed\",\"ip\":\"" + ip + "\"}");
    }

    /**
     * 判断 IP 是否在白名单中。
     *
     * @param ip 请求来源 IP
     * @return true 表示允许访问
     */
    private boolean isAllowed(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        if (allowExact.contains(ip)) {
            return true;
        }
        for (Cidr c : allowCidrs) {
            if (c.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取请求来源 IP：优先 X-Forwarded-For 的第一个地址，否则回退 remoteAddr。
     * <p>
     * 说明：后台通常可能挂在反向代理后面，因此必须考虑转发头；只取首段是为了防止
     * 客户端伪造后续逗号分隔项混淆日志。
     */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * CIDR 网段描述对象。
     * <p>
     * 采用字节数组 + 前缀长度的方式表示网络段，支持 IPv4/IPv6；
     * 这里仅用于 IP 白名单，不涉及路由或子网计算的完整功能。
     */
    record Cidr(byte[] network, int prefix) {
        /**
         * 解析字符串 CIDR（如 10.0.0.0/8）。解析失败返回 null，不抛异常打断启动。
         */
        static Cidr parse(String cidr) {
            try {
                String[] parts = cidr.split("/");
                if (parts.length != 2) {
                    return null;
                }
                byte[] addr = InetAddress.getByName(parts[0].trim()).getAddress();
                int prefix = Integer.parseInt(parts[1].trim());
                if (prefix < 0 || prefix > addr.length * 8) {
                    return null;
                }
                return new Cidr(addr, prefix);
            } catch (UnknownHostException | NumberFormatException e) {
                return null;
            }
        }

        /**
         * 判断给定 IP 是否落入当前 CIDR 网段。
         *
         * @param ip 待判断的 IP
         * @return true 表示属于该网段
         */
        boolean contains(String ip) {
            try {
                byte[] addr = InetAddress.getByName(ip).getAddress();
                // IPv4/IPv6 长度不一致时直接判定不匹配
                if (addr.length != network.length) {
                    return false;
                }
                int fullBytes = prefix / 8;
                int remBits = prefix % 8;
                for (int i = 0; i < fullBytes; i++) {
                    if (addr[i] != network[i]) {
                        return false;
                    }
                }
                if (remBits == 0) {
                    return true;
                }
                // 计算剩余比特位的掩码，按位比较网络前缀
                int mask = 0xFF << (8 - remBits);
                return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
            } catch (UnknownHostException e) {
                return false;
            }
        }
    }
}
