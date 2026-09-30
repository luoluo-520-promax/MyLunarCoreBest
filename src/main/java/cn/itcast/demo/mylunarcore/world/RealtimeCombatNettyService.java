package cn.itcast.demo.mylunarcore.world;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Service;

/**
 * 开放世界实时战斗的 Netty/协议薄适配层。
 * <p>
 * 仅当 {@code lunarcore.world.realtime-combat-enabled=true} 时处理命中；
 * 实际 CD、距离、伤害裁决在 {@link RealtimeCombatAuthority}。
 * 完整独立 CmdId 可后续挂接，当前可由场景旁路调用 {@link #handleWorldHit}。
 */
@Service
public class RealtimeCombatNettyService {

    // 服务端权威命中结算
    private final RealtimeCombatAuthority authority;
    // 读取 realtime-combat-enabled 开关
    private final LunarCoreProperties properties;

    public RealtimeCombatNettyService(RealtimeCombatAuthority authority, LunarCoreProperties properties) {
        this.authority = authority;
        this.properties = properties;
    }

    /**
     * 处理一次世界命中申报。
     *
     * @param attackerUid     攻击者 UID
     * @param skillId         技能配置 ID
     * @param targetEntityId  目标实体 ID
     * @param ax              攻击者世界 X
     * @param az              攻击者世界 Z
     * @param tx              目标世界 X
     * @param tz              目标世界 Z
     * @param clientDamage    客户端申报伤害（权威侧可裁剪/拒绝）
     * @return retcode：0=接受结算；1=功能关闭；2=权威拒绝（CD/距离/非法等）
     */
    public int handleWorldHit(long attackerUid, int skillId, long targetEntityId,
                              float ax, float az, float tx, float tz, int clientDamage) {
        // 总开关关闭：直接告诉调用方功能不可用，不进入权威逻辑
        if (!properties.getWorld().isRealtimeCombatEnabled()) {
            return 1;
        }
        // 组装意图并交权威；accepted=false 统一映射为 retcode 2
        RealtimeCombatAuthority.HitResult result = authority.applyHit(
                new RealtimeCombatAuthority.HitIntent(attackerUid, skillId, targetEntityId, ax, az, tx, tz, clientDamage));
        return result.accepted() ? 0 : 2;
    }
}
