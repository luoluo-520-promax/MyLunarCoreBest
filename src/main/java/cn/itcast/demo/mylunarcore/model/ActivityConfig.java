package cn.itcast.demo.mylunarcore.model;



import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;

import lombok.Setter;



import java.util.Collections;

import java.util.List;

import java.util.Map;



/**

 * 统一活动配置：排期、条件、玩法规则、关卡阶段、奖励、商店、界面资源等可热更参数。

 */

@Getter

@Setter

@JsonIgnoreProperties(ignoreUnknown = true)

public class ActivityConfig {



    /** 活动 ID */

    private int activityId;

    /** 活动名称 */

    private String name = "";

    /** 活动类型（如 gacha、challenge、shop、seasonal） */

    private String activityType = "";

    /** 关联玩法模块 ID */

    private long moduleId;

    /** 开始时间（Unix 秒） */

    private long beginTime;

    /** 结束时间（Unix 秒） */
    private long endTime;

    /** 客户端展示开始（Unix 秒）；0=回退 beginTime。未来版本可提前展示入口但不可玩。 */
    private long displayStart;

    /** 实际玩法生效开始（Unix 秒）；0=回退 beginTime。 */
    private long effectStart;

    /** 实际玩法生效结束（Unix 秒）；0=回退 endTime。 */
    private long effectEnd;

    /** 客户端展示结束（Unix 秒）；0=回退 endTime。 */
    private long displayEnd;

    /** 活动独有逻辑脚本 ID（对应 data/scripts/activity/{scriptId}.groovy） */
    private String scriptId = "";

    /** 解锁等级（兼容旧配置；conditions 为空时生效） */

    private int unlockLevel;

    /** 活动条件列表（与 unlockLevel 二选一或组合使用） */

    private List<ActivityCondition> conditions = Collections.emptyList();

    /** 活动描述 */

    private String description = "";

    /** 玩法说明 */

    private String gameplay = "";

    /** 活动规则 */

    private String rules = "";

    /** 关卡/阶段配置 */

    private List<ActivityStage> stages = Collections.emptyList();

    /** 消耗与限制 */

    private ActivityCostLimit costAndLimits = new ActivityCostLimit();

    /** 奖励列表 */

    private List<ActivityReward> rewards = Collections.emptyList();

    /** 奖励方式（auto / manual / mail / milestone） */

    private String rewardMethod = "auto";

    /** 积分/代币定义 */

    private List<ActivityPointToken> pointsTokens = Collections.emptyList();

    /** 关联商店 ID */

    private int shopId;

    /** 商店商品列表 */

    private List<ShopProduct> shopProducts = Collections.emptyList();

    /** 界面资源 */

    private ActivityUiResources uiResources = new ActivityUiResources();

    /** 显示文本 */

    private ActivityDisplayText displayText = new ActivityDisplayText();

    /** 关联卡池 */

    private List<ActivityBannerRef> banners = Collections.emptyList();

    /** 关联道具（代币等） */

    private List<ActivityItemRef> items = Collections.emptyList();

    /** 自由扩展字段 */

    private Map<String, Object> extra = Collections.emptyMap();



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityCondition {

        /** 条件类型：level / quest / story / item / custom */

        private String type = "level";

        /** 条件判定用的业务键：如 quest 类型时是任务 ID、item 类型时是道具 ID、story 类型时是剧情 ID */

        private String key = "";

        /** 数值型条件阈值：level 类型时是需要达到的等级、item 类型时是需要的道具数量 */

        private int intValue;

        /** 字符串型条件值：与 key 配套的文本判定，按 type 解释 */

        private String stringValue = "";

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityStage {

        /** 阶段 ID（活动内唯一） */

        private int stageId;

        /** 阶段名称 */

        private String name = "";

        /** 阶段描述 */

        private String description = "";

        /** 解锁该阶段所需的活动积分 */

        private int unlockScore;

        /** 该阶段通关后的奖励列表 */

        private List<ActivityReward> rewards = Collections.emptyList();

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityCostLimit {

        /** 单次消耗道具 ID */

        private int costItemId;

        /** 单次消耗数量 */

        private int costCount;

        /** 每日参与次数上限（0=不限） */

        private int dailyLimit;

        /** 活动总参与次数上限（0=不限） */

        private int totalLimit;

        /** 冷却时间（秒，0=无） */

        private int cooldownSeconds;

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityReward {

        /** 奖励道具 ID（type 为 currency/token 时指向对应货币/代币 ID） */

        private int itemId;

        /** 奖励数量 */

        private int count;

        /** 奖励类型：item（道具）/ currency（货币）/ token（活动代币） */

        private String type = "item";

        /** 发放时机：immediate / stage_clear / daily / milestone */

        private String grantTiming = "immediate";

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityPointToken {

        /** 活动积分/代币 ID */

        private int tokenId;

        /** 积分/代币名称 */

        private String name = "";

        /** 玩家可持有的最大堆叠数 */

        private int maxStack = 999999;

        /** 积分/代币图标资源路径 */

        private String iconPath = "";

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ShopProduct {

        /** 商品 ID（活动商店内唯一） */

        private int productId;

        /** 出售的道具 ID */

        private int itemId;

        /** 售价数量 */

        private int price;

        /** 支付代币/道具 ID */

        private int currencyId;

        /** 每日限购（0=不限） */

        private int dailyLimit;

        /** 活动总限购（0=不限） */

        private int totalLimit;

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityUiResources {

        /** 活动横幅图资源路径 */

        private String bannerImage = "";

        /** 活动背景图资源路径 */

        private String backgroundImage = "";

        /** 活动图标资源路径 */

        private String iconImage = "";

        /** 客户端预制体/界面资源路径 */

        private String prefabPath = "";

        /** 主题色（如 #RRGGBB，供客户端着色） */

        private String themeColor = "";

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityDisplayText {

        /** 活动主标题 */

        private String title = "";

        /** 活动副标题 */

        private String subtitle = "";

        /** 横幅上的宣传文案 */

        private String bannerText = "";

        /** 客户端 Tab 页签显示名 */

        private String tabLabel = "";

        /** 帮助/说明弹窗文本 */

        private String helpText = "";

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityBannerRef {

        /** 关联卡池 ID */

        private int id;

        /** 卡池类型（如 Normal、Character、Weapon 等，对应 GachaBannerType） */

        private String gachaType = "Normal";

        /** 卡池开放时间（Unix 秒） */

        private long beginTime;

        /** 卡池关闭时间（Unix 秒） */

        private long endTime;

        /** 5 星 UP 物品 ID 列表 */

        private List<Integer> rateUpItems5 = Collections.emptyList();

        /** 4 星 UP 物品 ID 列表 */

        private List<Integer> rateUpItems4 = Collections.emptyList();

    }



    @Getter

    @Setter

    @JsonIgnoreProperties(ignoreUnknown = true)

    public static class ActivityItemRef {

        /** 关联道具 ID（如活动代币） */

        private int id;

        /** 道具名称 */

        private String name = "";

        /** 道具单次堆叠数 */

        private int stack = 1;

    }

}

