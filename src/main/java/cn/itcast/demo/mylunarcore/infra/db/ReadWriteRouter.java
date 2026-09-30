package cn.itcast.demo.mylunarcore.infra.db;

import org.springframework.stereotype.Component;

/**
 * 读路径助手：在从库上下文中执行查询；写路径保持主库。
 */
@Component
public class ReadWriteRouter {

    public <T> T read(java.util.function.Supplier<T> action) {
        DataSourceType prev = RoutingDataSourceContext.get();
        try {
            if (!RoutingDataSourceContext.isPtLoad()) {
                RoutingDataSourceContext.set(DataSourceType.REPLICA);
            }
            return action.get();
        } finally {
            RoutingDataSourceContext.set(prev);
        }
    }

    public void write(Runnable action) {
        DataSourceType prev = RoutingDataSourceContext.get();
        try {
            if (!RoutingDataSourceContext.isPtLoad()) {
                RoutingDataSourceContext.set(DataSourceType.PRIMARY);
            }
            action.run();
        } finally {
            RoutingDataSourceContext.set(prev);
        }
    }

    public <T> T write(java.util.function.Supplier<T> action) {
        DataSourceType prev = RoutingDataSourceContext.get();
        try {
            if (!RoutingDataSourceContext.isPtLoad()) {
                RoutingDataSourceContext.set(DataSourceType.PRIMARY);
            }
            return action.get();
        } finally {
            RoutingDataSourceContext.set(prev);
        }
    }
}
