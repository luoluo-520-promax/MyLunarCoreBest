// 内部 API 鉴权过滤器所在包
package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.InternalTokenVerifier;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 校验 {@code /internal/**} 的 {@code X-Internal-Token}（支持 previous 轮换宽限期），
 * 并可叠加 IP/CIDR 白名单（{@code lunarcore.internal-api.ip-whitelist}）。
 * <p>
 * 用途：保护内部管理/节点间接口，避免外部流量直接调用这些不面向玩家暴露的路径。
 */
public class InternalApiAuthFilter extends OncePerRequestFilter {

    // 请求头名称：内部调用方必须携带该 header
    public static final String HEADER = "X-Internal-Token";

    // 全局配置对象：用于读取主 token、previous token 以及 IP 白名单
    private final LunarCoreProperties properties;
    // 精确允许的 IP 集合
    private final Set<String> allowExact;
    // 允许的 CIDR 网段集合
    private final Set<Cidr> allowCidrs;
    // 白名单功能是否启用（精确 IP 或 CIDR 任一非空即启用）
    private final boolean ipFilterEnabled;

    /**
     * 从配置初始化内部 API 安全策略。
     * <p>
     * 解析 {@code lunarcore.internal-api.ip-whitelist}：按逗号分隔，
     * 既支持精确 IP，也支持 CIDR（如 10.0.0.0/8）。
     *
     * @param properties 全局配置对象
     */
    public InternalApiAuthFilter(LunarCoreProperties properties) {
        this.properties = properties;
        // 读取内部 API 白名单原始字符串，可能为空
        String raw = properties.getInternalApi() == null ? "" : properties.getInternalApi().getIpWhitelist();
        Set<String> exact = new LinkedHashSet<>();
        Set<Cidr> cidrs = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                if (p.contains("/")) {
                    // CIDR 规则：例如 10.0.0.0/8
                    Cidr c = Cidr.parse(p);
                    if (c != null) {
                        cidrs.add(c);
                    }
                } else {
                    // 单 IP 精确匹配
                    exact.add(p);
                }
            }
        }
        this.allowExact = Set.copyOf(exact);
        this.allowCidrs = Set.copyOf(cidrs);
        this.ipFilterEnabled = !allowExact.isEmpty() || !allowCidrs.isEmpty();
    }

    /**
     * 仅拦截 /internal/** 路径，其余路径不在本过滤器职责范围内。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/internal/");
    }

    /**
     * 内部 API 主鉴权逻辑：
     * <ol>
     *   <li>若启用了 IP 白名单，先检查来源 IP 是否允许；</li>
     *   <li>检查主 token 是否配置；为空时直接拒绝；</li>
     *   <li>校验请求头 {@link #HEADER}，支持当前 token 与 previous token 双窗口；</li>
     *   <li>全部通过才放行。</li>
     * </ol>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 先做 IP 过滤：若白名单启用且来源 IP 不允许，则直接拒绝，减少后续 token 校验开销
        if (ipFilterEnabled && !isIpAllowed(clientIp(request))) {
            writeUnauthorized(response, "internal api ip not allowed");
            return;
        }
        // 主 token 为空说明配置错误或未初始化，内部接口不应继续放行
        String expected = properties.getInternalApiToken();
        if (expected == null || expected.isBlank()) {
            writeUnauthorized(response, "internal api token not configured");
            return;
        }
        // 读取调用方携带的内部 token
        String provided = request.getHeader(HEADER);
        // 支持当前 token 与 previous token 轮换宽限期的比对
        if (!InternalTokenVerifier.matchesAny(expected, properties.getInternalApiTokenPrevious(), provided)) {
            writeUnauthorized(response, "invalid internal token");
            return;
        }
        // 认证通过，继续执行后续过滤链与控制器
        filterChain.doFilter(request, response);
    }

    /**
     * 判断来源 IP 是否命中内部服务白名单。
     *
     * @param ip 来源 IP
     * @return true 表示允许
     */
    private boolean isIpAllowed(String ip) {
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
     * 获取客户端 IP：优先 X-Forwarded-For 首段，否则回退 remoteAddr。
     * <p>
     * 只取首段是为了防止客户端在代理头中塞入多个伪造 IP 混淆判定。
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
     * 返回统一的 401 JSON 响应，避免内部接口暴露 HTML 错误页。
     */
    private static void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /**
     * CIDR 网络段描述：用于判断单个 IP 是否属于白名单网段。
     */
    record Cidr(byte[] network, int prefix) {
        /**
         * 解析 CIDR 字符串（例如 10.0.0.0/8）。
         * 解析失败时返回 null，不抛异常中断启动。
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
         * 判断给定 IP 是否在该 CIDR 网段内。
         */
        boolean contains(String ip) {
            try {
                byte[] addr = InetAddress.getByName(ip).getAddress();
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
                int mask = 0xFF << (8 - remBits);
                return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
            } catch (UnknownHostException e) {
                return false;
            }
        }
    }
}
