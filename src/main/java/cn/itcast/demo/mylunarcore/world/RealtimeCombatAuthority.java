// 世界实时战斗（realtime combat）权威模块所在包
package cn.itcast.demo.mylunarcore.world;

// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举
import cn.itcast.demo.mylunarcore.common.LogCategory;
// 全局配置属性对象，读取 world.realtime-* 系列开关与阈值
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring 业务服务注解
import org.springframework.stereotype.Service;

// 通用键值映射接口
import java.util.Map;
// 线程安全哈希表，保存技能最近施放时间
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界内实时战斗权威（实验骨架）：服务器对实时命中请求的最终裁决组件。
 * <p>
 * 核心安全模型：客户端只允许申报"技能意图 + 命中目标 + 期望伤害"，禁止直接决定最终伤害。
 * 本类在服务端对每次 hit 依次执行四重校验：
 * <ol>
 *     <li><b>功能开关</b>：未启用时直接拒绝，不影响正常回合制战斗；</li>
 *     <li><b>入参校验</b>：attackerUid / skillId 非法即拒；</li>
 *     <li><b>CD 校验</b>：以「玩家 UID + 技能 ID」为键记录上次施放时间，低于配置最小间隔即拒，防连点刷伤；</li>
 *     <li><b>距离粗检</b>：攻击者与目标 XZ 平面距离超过 25 世界单位视为非法命中（防跨屏/穿墙打击）。</li>
 * </ol>
 * 通过校验后，服务端用确定性占位公式重算伤害：基础伤由 skillId 派生（{@code 100 + skillId % 400}），
 * 客户端申报值仅用于压缩（封顶为基础伤的 2 倍），最终整体受配置上限 {@code realtime-max-declared-damage} 钳制，
 * 从根本上防止客户端伪造高额伤害。
 * <p>
 * 默认关闭（{@code lunarcore.world.realtime-combat-enabled=false}）：当前对局仍走回合制
 * {@code WorldEncounterService → FightStart} 链路，本类为开放世界即时战斗的占位实验。
 * 线程安全性：{@link #lastSkillAt} 使用 {@link ConcurrentHashMap}，可安全地被多个 Netty 线程并发调用。
 */
@Service // 注册为 Spring 单例服务，供协议适配层注入
public class RealtimeCombatAuthority {

    /** 本类日志记录器（战斗业务分类）。 */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, RealtimeCombatAuthority.class);

    /**
     * 一次命中意图（客户端申报，不可信输入）。
     *
     * @param attackerUid           攻击者玩家 UID
     * @param skillId               使用的技能配置 ID
     * @param targetEntityId        被命中实体 ID（怪物/玩家/物件）
     * @param attackerX             攻击者世界 X 坐标
     * @param attackerZ             攻击者世界 Z 坐标
     * @param targetX               目标世界 X 坐标
     * @param targetZ               目标世界 Z 坐标
     * @param clientDeclaredDamage  客户端申报的期望伤害（仅作参考与上限压缩）
     */
    public record HitIntent(long attackerUid, int skillId, long targetEntityId,
                            float attackerX, float attackerZ, float targetX, float targetZ,
                            int clientDeclaredDamage) {}

    /**
     * 一次命中的结算结果。
     *
     * @param accepted     是否被服务器接受
     * @param serverDamage 服务端重算后的真实伤害（拒绝时为 0）
     * @param rejectReason 拒绝原因码（如 realtime_disabled / bad_intent / skill_cd / out_of_range / zero_damage）
     */
    public record HitResult(boolean accepted, int serverDamage, String rejectReason) {

        /** 构造拒绝结果：不接受该次命中，服务端伤害置 0。 */
        public static HitResult reject(String reason) {
            return new HitResult(false, 0, reason);
        }

        /** 构造接受结果：返回服务端核定的真实伤害。 */
        public static HitResult ok(int damage) {
            return new HitResult(true, damage, "");
        }
    }

    /** 全局配置属性对象，读取 world.realtime-* 系列配置项。 */
    private final LunarCoreProperties properties;

    /**
     * 技能最近施放时间索引：键 = "{attackerUid}:{skillId}"，值 = 毫秒时间戳。
     * 用于跨请求的 CD 校验；使用 ConcurrentHashMap 保证多线程安全。
     */
    private final Map<String, Long> lastSkillAt = new ConcurrentHashMap<>();

    /**
     * 构造注入全局配置。
     *
     * @param properties 全局配置属性
     */
    public RealtimeCombatAuthority(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 实时战斗功能是否启用（读取配置开关 lunarcore.world.realtime-combat-enabled）。
     *
     * @return true 表示启用实时战斗结算
     */
    public boolean isEnabled() {
        return properties.getWorld().isRealtimeCombatEnabled();
    }

    /**
     * 结算一次命中：开关检查 → 入参校验 → CD 校验 → 距离粗检 → 服务端伤害公式 → 记录审计。
     * <p>
     * 服务端伤害公式（确定性占位）：{@code base = 100 + floorMod(skillId, 400)}；
     * 最终 {@code serverDamage = min(maxDmg, declared>0 ? min(declared, base*2) : base)}，
     * 即客户端申报值仅作上限压缩，服务端始终掌握最终伤害权。
     *
     * @param intent 客户端申报的命中意图（不可信）
     * @return 命中结算结果；accepted=true 时 serverDamage 为服务端真实伤害
     */
    public HitResult applyHit(HitIntent intent) {
        if (!isEnabled()) { // 功能开关关闭：实时战斗为实验特性，未启用时一律拒绝
            return HitResult.reject("realtime_disabled");
        }
        if (intent == null || intent.attackerUid() <= 0 || intent.skillId() <= 0) { // 空意图或关键字段非法
            return HitResult.reject("bad_intent");
        }
        long now = System.currentTimeMillis(); // 取当前毫秒时间作为 CD 判定基准
        String cdKey = intent.attackerUid() + ":" + intent.skillId(); // 按「玩家+技能」维度隔离 CD
        long minCd = Math.max(50L, properties.getWorld().getRealtimeSkillMinCdMs()); // 最小 CD 取配置值与 50ms 的较大者，防极端连点
        Long last = lastSkillAt.get(cdKey); // 查询该技能上次施放时间
        if (last != null && now - last < minCd) { // 距离上次施放不足最小间隔
            return HitResult.reject("skill_cd"); // 拒绝：技能冷却中
        }

        float dx = intent.attackerX() - intent.targetX(); // 攻击者与目标在 X 轴的位移
        float dz = intent.attackerZ() - intent.targetZ(); // 攻击者与目标在 Z 轴的位移
        float distSq = dx * dx + dz * dz; // 距离平方（比较平方值可避免开方，性能更优）
        // 粗检：超过约 25 单位视为非法命中（跨屏/穿墙）
        if (distSq > 25f * 25f) {
            return HitResult.reject("out_of_range");
        }

        int maxDmg = Math.max(1, properties.getWorld().getRealtimeMaxDeclaredDamage()); // 配置的伤害上限，非法配置回退为 1
        // 服务端公式占位：以 skillId 派生基础伤，再与客户端申报取 min（防刷）
        int base = 100 + Math.floorMod(intent.skillId(), 400); // 由技能 ID 确定性派生基础伤害
        int declared = Math.max(0, intent.clientDeclaredDamage()); // 客户端申报伤害，负数钳制为 0
        int serverDamage = Math.min(maxDmg, declared > 0 ? Math.min(declared, base * 2) : base); // 最终服务端核定伤害
        if (serverDamage <= 0) { // 核定伤害必须为正
            return HitResult.reject("zero_damage");
        }

        lastSkillAt.put(cdKey, now); // 记录本次施放时间，供下一次 CD 校验
        log.info("realtime hit ok uid={} skill={} target={} dmg={} declared={}",
                intent.attackerUid(), intent.skillId(), intent.targetEntityId(),
                serverDamage, declared); // 审计日志：记录核定伤害与客户端申报，便于反作弊追溯
        return HitResult.ok(serverDamage); // 返回服务端真实伤害
    }
}
