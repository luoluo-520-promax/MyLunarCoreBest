// 统一日志分类枚举：把系统日志与各业务域日志分到不同 logger 命名空间
package cn.itcast.demo.mylunarcore.common;

/**
 * 日志分类：系统框架与业务域分离，对应 Logback 中不同滚动文件。
 * <p>通过 {@link #loggerName(Class)} 生成与 logback-spring.xml 一致的 logger 名称，
 * 便于在配置中按业务域分别设置输出路径、级别和滚动策略。</p>
 */
public enum LogCategory {

    /** 系统层：网络、编解码、主循环、资源、监控、热更新等 */
    SYSTEM, // 无 segment，logger 前缀为 cn.itcast.demo.mylunarcore.sys

    /** 会话与账号、在线会话 */
    BUSINESS_SESSION("session"), // 业务子域：session
    /** 场景与 NPC 等 */
    BUSINESS_SCENE("scene"), // 业务子域：scene
    /** 战斗 */
    BUSINESS_BATTLE("battle"), // 业务子域：battle
    /** 抽卡 */
    BUSINESS_GACHA("gacha"), // 业务子域：gacha
    /** 道具 */
    BUSINESS_ITEM("item"), // 业务子域：item
    /** 模拟宇宙 / Rogue */
    BUSINESS_ROGUE("rogue"), // 业务子域：rogue
    /** 挑战 */
    BUSINESS_CHALLENGE("challenge"), // 业务子域：challenge
    /** 活动排期 */
    BUSINESS_ACTIVITY("activity"), // 业务子域：activity
    /** 持久化与仓储 */
    BUSINESS_DATA("data"), // 业务子域：data
    /** 同步与货币等辅助 */
    BUSINESS_SYNC("sync"), // 业务子域：sync
    /** AI 辅助 / 规则教练 */
    BUSINESS_ASSIST("assist"); // 业务子域：assist

    // 系统日志 logger 名前缀：对应 logback 中的 system 文件/控制台规则
    private static final String ROOT_SYS = "cn.itcast.demo.mylunarcore.sys";
    // 业务日志 logger 名前缀：再拼接细分子域
    private static final String ROOT_BIZ = "cn.itcast.demo.mylunarcore.biz";

    // 业务子命名空间片段（SYSTEM 为 null）
    private final String segment;

    /** 系统分类构造：不绑定 segment */
    LogCategory() {
        this.segment = null;
    }

    /** 业务分类构造：绑定子域名称 */
    LogCategory(String segment) {
        this.segment = segment;
    }

    /**
     * 生成绑定到 SLF4J / Logback 的 logger 名称（与 logback-spring.xml 中 logger name 前缀一致）。
     *
     * @param clazz 打日志的类
     * @return 完整 logger 名称
     */
    public String loggerName(Class<?> clazz) {
        if (this == SYSTEM) {
            return ROOT_SYS + "." + clazz.getSimpleName();
        }
        if (segment == null) {
            throw new IllegalStateException("BUSINESS category must have segment");
        }
        return ROOT_BIZ + "." + segment + "." + clazz.getSimpleName();
    }
}
