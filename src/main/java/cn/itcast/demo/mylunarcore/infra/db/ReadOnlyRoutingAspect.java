package cn.itcast.demo.mylunarcore.infra.db;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 强制读写分离：{@code @Transactional(readOnly=true)} 自动路由到从库。
 */
@Aspect
@Component
@Order(0)
@ConditionalOnProperty(prefix = "lunarcore.datasource", name = "routing-enabled", havingValue = "true")
public class ReadOnlyRoutingAspect {

    @Around("@annotation(tx)")
    public Object routeReadOnly(ProceedingJoinPoint pjp, Transactional tx) throws Throwable {
        if (tx == null || !tx.readOnly()) {
            return pjp.proceed();
        }
        DataSourceType prev = RoutingDataSourceContext.get();
        try {
            if (!RoutingDataSourceContext.isPtLoad()) {
                RoutingDataSourceContext.set(DataSourceType.REPLICA);
            }
            return pjp.proceed();
        } finally {
            RoutingDataSourceContext.set(prev);
        }
    }

    @Around("@within(tx)")
    public Object routeClassReadOnly(ProceedingJoinPoint pjp, Transactional tx) throws Throwable {
        if (tx == null || !tx.readOnly()) {
            return pjp.proceed();
        }
        DataSourceType prev = RoutingDataSourceContext.get();
        try {
            if (!RoutingDataSourceContext.isPtLoad()) {
                RoutingDataSourceContext.set(DataSourceType.REPLICA);
            }
            return pjp.proceed();
        } finally {
            RoutingDataSourceContext.set(prev);
        }
    }
}
