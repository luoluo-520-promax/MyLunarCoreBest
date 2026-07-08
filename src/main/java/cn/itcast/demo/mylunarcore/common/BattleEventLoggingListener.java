// 战斗事件监听器所在包
package cn.itcast.demo.mylunarcore.common;

// 战斗结束事件类型
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
// 战斗开始事件类型
import cn.itcast.demo.mylunarcore.common.BattleStartedEvent;
// 项目统一日志工厂
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举（战斗业务）
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// 标记方法为 Spring 事件监听器
import org.springframework.context.event.EventListener;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

/**
 * 示例：订阅战斗生命周期事件并写入日志。
 */
@Component // 随容器启动注册，自动扫描 @EventListener 方法
public class BattleEventLoggingListener {

    // 战斗分类专用 Logger，类名用于日志输出定位
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, BattleEventLoggingListener.class);

    // 收到 BattleStartedEvent 时调用（同步，默认在同一线程发布处执行）
    @EventListener
    public void onBattleStarted(BattleStartedEvent event) {
        log.info("event=battle_started battleId={} playerId={} stageId={} lineupId={} waveCount={} start={}",
                event.battleId(),        // record 访问器：战斗 id
                event.playerId(),
                event.battleStageId(),
                event.lineupId(),
                event.waveCount(),
                event.startTimeSeconds());
    }

    // 收到 BattleEndedEvent 时调用
    @EventListener
    public void onBattleEnded(BattleEndedEvent event) {
        log.info("event=battle_ended battleId={} playerId={} endStatus={} reason={} end={}",
                event.battleId(),
                event.playerId(),
                event.endStatus(),
                event.reason(),
                event.endTimeSeconds());
    }
}
