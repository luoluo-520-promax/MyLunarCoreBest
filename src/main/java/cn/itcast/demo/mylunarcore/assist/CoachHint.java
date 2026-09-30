package cn.itcast.demo.mylunarcore.assist;
/**
 * 规则教练引擎给前端的一条可展示提示。 <p> 只保存已通过规则筛选后的结果，不承载判断逻辑，方便列表展示、埋点和排序。
 */
public record CoachHint(
        /** 提示稳定标识，对应 {@link CoachTipsConfig.TipDef#id()}，用于去重与配置追踪。 */
        String tipId,
        /** 业务分类：quest / activity / growth / avatar / talent / meta 等。 */
        String category,
        /** 排序权重，数值越大越靠前，截断展示数量前使用。 */
        int priority,
        /** 卡片短标题，例如“继续主线”“活动将结束”。 */
        String title,
        /** 模板渲染后的完整提示正文，已含玩家当前上下文变量。 */
        String message,
        /** 推荐客户端动作：OPEN_QUEST、OPEN_ACTIVITY 等。 */
        String action,
        /** 关联业务实体 ID：questId / activityId / itemId / avatarId。 */
        int refId
) { // 记录/配置对象的紧凑构造体定义开始
}
