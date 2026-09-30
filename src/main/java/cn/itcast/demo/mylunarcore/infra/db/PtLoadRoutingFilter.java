package cn.itcast.demo.mylunarcore.infra.db;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 压测流量标记：请求头 {@code x-pt-load=true} 时路由到影子库，避免污染线上数据。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PtLoadRoutingFilter extends OncePerRequestFilter {

    public static final String HEADER = "x-pt-load";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean pt = "true".equalsIgnoreCase(request.getHeader(HEADER))
                || "1".equals(request.getHeader(HEADER));
        try {
            if (pt) {
                RoutingDataSourceContext.markPtLoad(true);
                RoutingDataSourceContext.set(DataSourceType.SHADOW);
            }
            filterChain.doFilter(request, response);
        } finally {
            RoutingDataSourceContext.clear();
        }
    }
}
