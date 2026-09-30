package cn.itcast.demo.mylunarcore.assist; // 教练提示配置所在包

import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 忽略未知字段以兼容热更

import java.util.Collections; // 空配置使用 Collections.emptyList
import java.util.List; // 用列表保存提示定义
/**
 * {@code data/CoachTips.json} 的根配置结构。 <p> 规则引擎会从这里读取每一条教练提示定义， 再按分类、优先级和命中条件生成最终的 CoachHint 列表。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoachTipsConfig(
        int version, // 配置版本号
        List<TipDef> tips // tip 定义列表
) { // 记录/配置对象的紧凑构造体定义开始
    public CoachTipsConfig { // 紧凑构造，冻结提示列表
        tips = tips == null ? List.of() : List.copyOf(tips); // 防止外部修改运行时规则
    } // 紧凑构造结束
    /**
     * 返回空占位配置
     */
    public static CoachTipsConfig empty() {
        return new CoachTipsConfig(0, Collections.emptyList()); // 用空列表构造安全默认值
    } // empty 结束
    /**
     * 教练提示配置定义
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TipDef(
            String id, // tip 的稳定 ID
            String category, // 命中的规则分类
            int priority, // 提示排序优先级
            String title, // 展示短标题
            String template, // 正文模板
            String action, // 客户端动作标识
            Boolean enabled, // 是否启用
            Integer endingWithinHours, // 活动结束小时阈值
            Integer requiredItemId, // 养成提示所需道具 ID
            Integer requiredItemCount, // 养成提示所需数量
            Integer maxAvatarLevel, // 角色提示的最高等级
            Integer maxPromotion, // 角色提示的最高突破
            Integer requiredTalentId, // 天赋提示目标 ID
            Integer maxTalentLevel, // 天赋提示的最高等级
            Boolean requireInactiveTalent, // 是否要求天赋未激活
            Integer metaTopN, // 热门榜取前 N
            Boolean metaRequireOwned // 是否仅推荐已拥有角色
    ) { // 记录/配置对象的紧凑构造体定义开始
        /**
         * 判断 tip 是否启用
         */
        public boolean isEnabled() {
            return enabled == null || enabled; // null 或 true 视为启用
        } // isEnabled 结束
    } // TipDef 结束
}
