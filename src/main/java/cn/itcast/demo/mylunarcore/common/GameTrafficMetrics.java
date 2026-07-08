// 游戏协议包处理计数（原子增量）
package cn.itcast.demo.mylunarcore.common;

// 注册为 Spring 组件
import org.springframework.stereotype.Component;

// 原子长整型：无锁递增统计
import java.util.concurrent.atomic.AtomicLong;

/**
 * 游戏协议包处理计数，供 {@link ServerMetricsMonitor} 统计吞吐。
 */
@Component // 注册为 Spring Bean
public class GameTrafficMetrics {

    // 累计已处理的协议包数量
    private final AtomicLong packetsHandled = new AtomicLong();

    /**
     * Netty 业务线程处理完一包后调用。
     */
    public void recordPacketHandled() {
        packetsHandled.incrementAndGet(); // 原子递增，线程安全
    }

    /**
     * @return 累计处理包总数
     */
    public long getPacketsHandled() {
        return packetsHandled.get(); // 读取当前累计值
    }
}
