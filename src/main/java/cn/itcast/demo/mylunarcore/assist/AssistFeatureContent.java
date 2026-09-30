package cn.itcast.demo.mylunarcore.assist; // 助手功能内容包所在包

import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 忽略未知字段以兼容热更

import java.util.List; // 使用列表承载多条配置
import java.util.Map; // 使用映射承载角色克制标签
/**
 * 助手功能内容包根结构。 <p> 这里集中保存探索路线、POI 解说、敌人弱点、角色克制标签、百科条目和任务提示梯子， 供攻略、战斗建议和世界观问答共同使用。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistFeatureContent(
        int version, // 内容包版本号，便于热更日志和回滚排查
        List<ExploreRoute> exploreRoutes, // 任务或场景绑定的探索路线
        List<PoiLore> pois, // 场景 POI 的环境解说资料
        List<EnemyWeakness> enemyWeaknesses, // 怪物弱点与战斗策略配置
        Map<String, List<String>> avatarCounterTags, // 角色 ID 到克制标签的映射
        List<LoreEntry> loreEntries, // 世界观百科条目列表
        List<QuestHintLadder> questHintLadders // 任务渐进提示梯子
) { // 记录/配置对象的紧凑构造体定义开始
    public AssistFeatureContent { // 紧凑构造，统一冻结集合
        exploreRoutes = exploreRoutes == null ? List.of() : List.copyOf(exploreRoutes); // 冻结探索路线集合
        pois = pois == null ? List.of() : List.copyOf(pois); // 冻结 POI 集合
        enemyWeaknesses = enemyWeaknesses == null ? List.of() : List.copyOf(enemyWeaknesses); // 冻结敌人弱点集合
        avatarCounterTags = avatarCounterTags == null ? Map.of() : Map.copyOf(avatarCounterTags); // 冻结克制标签映射
        loreEntries = loreEntries == null ? List.of() : List.copyOf(loreEntries); // 冻结百科条目集合
        questHintLadders = questHintLadders == null ? List.of() : List.copyOf(questHintLadders); // 冻结任务提示梯子
    } // 紧凑构造结束
    /**
     * 构造空白内容占位
     */
    public static AssistFeatureContent empty() {
        return new AssistFeatureContent(0, List.of(), List.of(), List.of(), Map.of(), List.of(), List.of()); // 返回全空快照
    } // 空内容构造结束
    /**
     * 探索路线配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExploreRoute( // 探索路线定义
            String routeId, // 路线稳定 ID
            int questId, // 绑定的任务 ID
            int sceneId, // 绑定的场景 ID
            String title, // 路线标题
            List<Waypoint> waypoints, // 路线航点
            List<ResourceHint> resourceHints // 路线资源提示
    ) { // 路线体结束
        public ExploreRoute { // 冻结内部集合
            waypoints = waypoints == null ? List.of() : List.copyOf(waypoints); // 冻结航点列表
            resourceHints = resourceHints == null ? List.of() : List.copyOf(resourceHints); // 冻结资源提示列表
        } // 探索路线紧凑构造结束
    } // ExploreRoute 结束
    /**
     * 路线途经点
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Waypoint(float x, float y, float z, String label, String markerType) { // 单个航点坐标
    } // Waypoint 结束
    /**
     * 资源点提示
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceHint(String itemHint, int nearWaypointIndex) { // 航点附近资源提示
    } // ResourceHint 结束
    /**
     * 地点世界观条目
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PoiLore( // 场景 POI 解说定义
            String poiId, // POI 稳定 ID
            int sceneId, // 绑定场景 ID
            List<String> tags, // POI 标签集合
            float centerX, float centerY, float centerZ, // POI 中心坐标
            float radius, // 触发半径
            String title, // POI 标题
            String loreShort, // POI 简短解说
            String puzzleHint, // 可选解谜提示
            String persona // 旁白口吻角色
    ) { // POI 体结束
        public PoiLore { // 冻结标签并修正半径
            tags = tags == null ? List.of() : List.copyOf(tags); // 冻结标签集合
            radius = radius <= 0 ? 5f : radius; // 半径缺省时给一个安全值
        } // POI 紧凑构造结束
    } // PoiLore 结束
    /**
     * 敌人弱点配置
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnemyWeakness( // 敌人弱点定义
            int monsterId, // 怪物配置 ID
            String name, // 怪物名称
            List<String> tags, // 怪物标签
            List<String> weakTags, // 克制标签
            List<String> resistTags, // 抗性标签
            String strategyHint, // 战斗策略提示
            double switchWhenHpBelow // 低血切换阈值
    ) { // 敌人弱点体结束
        public EnemyWeakness { // 冻结集合并补默认阈值
            tags = tags == null ? List.of() : List.copyOf(tags); // 冻结标签
            weakTags = weakTags == null ? List.of() : List.copyOf(weakTags); // 冻结弱点标签
            resistTags = resistTags == null ? List.of() : List.copyOf(resistTags); // 冻结抗性标签
            switchWhenHpBelow = switchWhenHpBelow <= 0 ? 0.35 : switchWhenHpBelow; // 默认切换阈值
        } // EnemyWeakness 紧凑构造结束
    } // EnemyWeakness 结束
    /**
     * 世界观词条
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoreEntry( // 百科词条定义
            String id, // 词条 ID
            String type, // 词条类型
            String name, // 正式名称
            List<String> aliases, // 别名列表
            String persona, // 口吻 persona
            String summary, // 简述
            String detail // 详情
    ) { // 百科体结束
        public LoreEntry { // 冻结别名集合
            aliases = aliases == null ? List.of() : List.copyOf(aliases); // 冻结别名
        } // LoreEntry 紧凑构造结束
    } // LoreEntry 结束
    /**
     * 任务提示阶梯
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuestHintLadder(int questId, List<HintStage> stages) { // 任务提示梯子
        public QuestHintLadder { // 冻结阶段集合
            stages = stages == null ? List.of() : List.copyOf(stages); // 冻结阶段列表
        } // QuestHintLadder 紧凑构造结束
    } // QuestHintLadder 结束
    /**
     * 任务提示阶段
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HintStage(int stage, String title, String message) { // 单阶段提示内容
    } // HintStage 结束
}
