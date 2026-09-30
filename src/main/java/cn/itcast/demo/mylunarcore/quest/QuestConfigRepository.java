// 任务静态配置仓储：从 data/QuestConfigs.json 加载任务定义、目标与奖励
package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.common.ConfigFileService; // 统一 JSON 配置文件读取服务
import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 反序列化时忽略 JSON 中未定义字段，增强配置向前兼容
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct; // Bean 初始化后自动加载 QuestConfigs.json
import org.springframework.stereotype.Repository; // 仓储层 Bean，与 quest_progress 持久化仓储区分

import java.io.IOException; // reload 读取文件时可能抛出
import java.util.Collections; // emptyMap、unmodifiableMap 工具
import java.util.HashMap; // 构建 questId 索引的临时可变 Map
import java.util.List; // 任务列表、目标列表、奖励列表
import java.util.Map; // questId → QuestConfig 内存索引

/**
 * 任务静态配置仓储。
 * 从 {@code data/QuestConfigs.json} 加载，构建 questId 索引；
 * reload 后替换为不可变 map，保证热更时并发读一致。
 */
@Repository
public class QuestConfigRepository {

    /** 配置文件读取服务：负责 readJsonList 反序列化。 */
    private final ConfigFileService configFileService;

    /** questId → 任务配置不可变映射；热更时整体替换，避免读方看到半更新状态。 */
    private Map<Integer, QuestConfig> configsById = Collections.emptyMap();

    /** 构造器注入 ConfigFileService。 */
    public QuestConfigRepository(ConfigFileService configFileService) {
        this.configFileService = configFileService; // 保存配置读取服务引用
    }

    /** 应用启动后自动加载 QuestConfigs.json，预热内存索引。 */
    @PostConstruct
    public void load() throws IOException {
        reload(); // 委托 reload 完成实际加载
    }

    /**
     * 从 QuestConfigs.json 重新加载并替换内存索引。
     * 使用 unmodifiableMap 防止外部代码误修改索引结构。
     */
    public void reload() throws IOException {
        List<QuestConfig> list = configFileService.readJsonList("QuestConfigs.json", new TypeReference<>() {}); // 反序列化为 QuestConfig 列表
        Map<Integer, QuestConfig> map = new HashMap<>(); // 构建新的可变索引
        for (QuestConfig cfg : list) { // 遍历配置文件中每条任务
            map.put(cfg.questId(), cfg); // 以 questId 为 key 建立 O(1) 查找
        }
        configsById = Collections.unmodifiableMap(map); // 原子替换为不可变副本
    }

    /**
     * 按任务 ID 查找静态配置。
     *
     * @return 配置不存在时 null
     */
    public QuestConfig find(int questId) {
        return configsById.get(questId); // O(1) 查内存索引
    }

    /**
     * 返回全部任务配置的不可变副本。
     * 供管理端批量校验、任务列表展示或热更对比使用。
     */
    public List<QuestConfig> listAll() {
        return List.copyOf(configsById.values()); // 防御性拷贝，调用方无法修改内部索引
    }

    /**
     * 单条任务配置 record：标题、描述、目标列表、奖励列表。
     * objectives 定义完成条件；rewards 定义提交后发放的货币/道具。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuestConfig(
            int questId,                    // 任务唯一 ID，与 quest_progress.quest_id 对应
            String title,                   // 任务标题，供客户端 UI 展示
            String description,             // 任务描述文本
            List<ObjectiveConfig> objectives, // 目标列表，每项含 targetType/targetId/required
            List<RewardConfig> rewards      // 奖励列表，提交成功后由 RewardDistributor 发放
    ) {}

    /**
     * 单项目标配置 record。
     * targetType：1=杀怪 2=NPC交互 3=场景事件 4=通用；
     * targetId 与 required 由 QuestProgressRepository.applyTrigger 解释并更新 objectivesJson。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectiveConfig(
            int objectiveId, // 目标在任务内的唯一编号，对应 objectivesJson 中的键
            int targetType,  // 触发类型，与 QuestTriggerEngine.onTrigger 的 triggerType 对齐
            int targetId,    // 主匹配参数（如 monsterId、npcId）
            int required     // 完成该目标所需的累计次数
    ) {}

    /**
     * 单条奖励 record：货币（currencyId+amount）或道具（itemId+count）。
     * 字段可为 null 表示该类型无奖励；同一条可同时含货币与道具。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RewardConfig(
            Integer currencyId, // 奖励货币类型 ID，null 表示本条无货币奖励
            Integer amount,     // 奖励货币数量
            Integer itemId,     // 奖励道具模板 ID，null 表示本条无道具奖励
            Integer count       // 奖励道具数量
    ) {}
}
