// 战斗辅助策略所在包（方案 D）
package cn.itcast.demo.mylunarcore.battle.assist;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 方案 D 数据飞轮：战斗结束特征落盘（JSONL），供后续小模型/策略训练；
 * 不参与实时结算，失败只打 debug，不影响战斗主链路。
 */
@Component
public class BattleFeatureCollector {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, BattleFeatureCollector.class);
    /** 相对 dataDir 的 JSONL 文件名 */
    private static final String RELATIVE_PATH = "battle_features.jsonl";

    private final LunarCoreProperties properties; // battleFeatureCollectEnabled 开关
    private final ConfigFileService configFileService; // resolve 绝对路径
    private final ObjectMapper objectMapper = new ObjectMapper(); // 行 JSON 序列化
    private final AtomicLong collected = new AtomicLong(); // 成功追加行数

    public BattleFeatureCollector(LunarCoreProperties properties, ConfigFileService configFileService) {
        this.properties = properties;
        this.configFileService = configFileService;
    }

    /**
     * 监听战斗结束事件：开关开启时把 battleId/胜负等写成一行 JSONL 追加到磁盘。
     */
    @EventListener
    public void onBattleEnded(BattleEndedEvent event) {
        if (!properties.getAiAssist().isBattleFeatureCollectEnabled()) {
            return; // 运营关闭采集时静默跳过
        }
        try {
            Map<String, Object> row = new LinkedHashMap<>(); // 保持字段插入顺序便于阅读
            row.put("battleId", event.battleId());
            row.put("playerId", event.playerId());
            row.put("endStatus", event.endStatus()); // 数值状态码
            row.put("reason", event.reason()); // 结束原因字符串
            row.put("endTimeSeconds", event.endTimeSeconds());
            // 胜负启发式：status==1 或 reason 含 WIN
            row.put("won", event.endStatus() == 1 || "WIN".equalsIgnoreCase(String.valueOf(event.reason())));
            row.put("schemaVersion", 2); // 特征行 schema，训练侧可按版本解析
            row.put("ts", System.currentTimeMillis()); // 采集墙钟时间
            String line = objectMapper.writeValueAsString(row) + "\n";
            java.nio.file.Path path = configFileService.resolve(RELATIVE_PATH);
            java.nio.file.Files.createDirectories(path.getParent()); // 确保 dataDir 存在
            java.nio.file.Files.writeString(path, line,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND); // 追加写，不截断历史
            collected.incrementAndGet();
            log.debug("battle feature appended battleId={} total={}", event.battleId(), collected.get());
        } catch (Exception e) {
            // 采集失败不得影响结算；仅 debug
            log.debug("battle feature collect skipped: {}", e.toString());
        }
    }

    /** 监控：已成功落盘特征行数 */
    public long getCollectedCount() {
        return collected.get();
    }
}
