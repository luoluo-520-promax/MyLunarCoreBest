// 声明当前包：AI 助手旁路微服务共用的「契约版本」常量
package cn.itcast.demo.mylunarcore.common.api.assist;

/**
 * 游戏服 ↔ ai-assist-service 契约 schema 版本。
 * <p>
 * 双方请求/响应均应带上该字段；不匹配时可记录告警并按兼容策略降级。
 * </p>
 *
 * <p>维护约定：新增/变更 AssistAskRequest / AssistAskResponse 的字段结构时，
 * 应将 {@link #CURRENT} 提升一个版本号，并保证旁路服务兼容旧版本请求
 * （旧调用方缺省版本时由紧凑构造器自动补齐）。</p>
 */
public final class AssistSchemaVersions {

    /**
     * 当前契约版本：含 schemaVersion 字段与统一 Prompt/Safety 约定。
     */
    public static final int CURRENT = 1;

    /**
     * 私有构造器：本类仅承载常量，禁止实例化。
     */
    private AssistSchemaVersions() {
    }
}
